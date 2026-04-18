package app.seamlessupdate.client

import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.Build.DEVICE
import android.os.Build.FINGERPRINT
import android.os.Build.VERSION.INCREMENTAL
import android.os.IBinder
import android.os.PowerManager
import android.os.RecoverySystem
import android.os.ServiceSpecificException
import android.os.SystemProperties
import android.os.UpdateEngine
import android.os.UpdateEngine.ErrorCodeConstants
import android.os.UpdateEngine.UpdateStatusConstants
import android.os.UpdateEngineCallback
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libcore.io.IoUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.URL
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

class Service : Service() {

    private val tlsSocketFactory = ModernTLSSocketFactory()
    private lateinit var notificationHandler: NotificationHandler
    private var mUpdating = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        notificationHandler = NotificationHandler(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val network = intent?.getParcelableExtra(INTENT_EXTRA_NETWORK, Network::class.java)
        val serviceIsUserInitiated = intent?.getBooleanExtra(INTENT_EXTRA_IS_USER_INITIATED, false) ?: false

        serviceScope.launch {
            try {
                handleUpdate(intent, network, serviceIsUserInitiated)
            } finally {
                // Stop service when work is done, matching IntentService behaviour
                stopSelf(startId)
            }
        }

        // START_NOT_STICKY: don't restart if killed — PeriodicJob will reschedule
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun fetchData(network: Network, path: String): HttpURLConnection {
        val url = URL(getString(R.string.url) + path)
        val urlConnection = network.openConnection(url) as HttpsURLConnection
        urlConnection.sslSocketFactory = tlsSocketFactory
        urlConnection.connectTimeout = CONNECT_TIMEOUT
        urlConnection.readTimeout = READ_TIMEOUT
        return urlConnection
    }

    private fun applyUpdate(streaming: Boolean, payloadOffset: Long, headerKeyValuePairs: Array<String>) {
        notificationHandler.showInstallNotification(0)

        val monitor = CountDownLatch(1)
        val engine = UpdateEngine()
        engine.bind(object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) {
                Log.d(TAG, "onStatusUpdate: $status, ${percent * 100}%")
                when (status) {
                    UpdateStatusConstants.DOWNLOADING -> notificationHandler.showInstallNotification(Math.round(percent * 100))
                    UpdateStatusConstants.VERIFYING   -> notificationHandler.showValidateNotification(Math.round(percent * 100))
                    UpdateStatusConstants.FINALIZING  -> notificationHandler.showFinalizeNotification(Math.round(percent * 100))
                }
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "onPayloadApplicationComplete success")
                    annoyUser()
                } else {
                    Log.d(TAG, "onPayloadApplicationComplete: $errorCode")
                    notificationHandler.showFailureNotification("update_engine error code: $errorCode")
                    mUpdating = false
                }
                UPDATE_PATH.delete()
                monitor.countDown()
            }
        })

        if (streaming) {
            val preferences = Settings.getPreferences(this)
            val downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE.replace("-streaming", ""), null)
            engine.applyPayload(getString(R.string.url) + downloadFile, payloadOffset, 0, headerKeyValuePairs)
        } else {
            UPDATE_PATH.setReadable(true, false)
            engine.applyPayload("file://$UPDATE_PATH", payloadOffset, 0, headerKeyValuePairs)
        }

        try {
            monitor.await()
        } catch (e: InterruptedException) {}

        if (!engine.unbind()) {
            Log.e(TAG, "unable to unbind update_engine")
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun getEntry(zipFile: ZipFile, name: String) =
        zipFile.getEntry(name) ?: throw GeneralSecurityException("missing zip entry: $name")

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun onDownloadFinished(streaming: Boolean, targetBuildDate: Long, channel: String) {
        try {
            notificationHandler.showVerifyNotification(0)
            RecoverySystem.verifyPackage(UPDATE_PATH, { progress ->
                Log.d(TAG, "verifyPackage: $progress%")
                notificationHandler.showVerifyNotification(progress)
            }, null)

            val zipFile = ZipFile(UPDATE_PATH)
            val metadata = getEntry(zipFile, "META-INF/com/android/metadata")
            val reader = BufferedReader(InputStreamReader(zipFile.getInputStream(metadata)))

            var device: String? = null
            var serialno: String? = null
            var type: String? = null
            var sourceIncremental: String? = null
            var sourceFingerprint: String? = null
            var streamingPropertyFiles: Array<String>? = null
            var timestamp = 0L

            for (line in reader.lineSequence()) {
                val pair = line.split("=")
                when (pair[0]) {
                    "post-timestamp"               -> timestamp = pair[1].toLong()
                    "serialno"                     -> serialno = pair[1]
                    "pre-device"                   -> device = pair[1]
                    "ota-type"                     -> type = pair[1]
                    "ota-streaming-property-files" -> streamingPropertyFiles = pair[1].trim().split(",").toTypedArray()
                    "pre-build-incremental"        -> sourceIncremental = pair[1]
                    "pre-build"                    -> sourceFingerprint = pair[1]
                }
            }

            if (timestamp != targetBuildDate)                                  throw GeneralSecurityException("timestamp does not match server metadata")
            if (DEVICE != device)                                              throw GeneralSecurityException("device mismatch")
            if (serialno != null)                                              throw GeneralSecurityException("serialno constraint not permitted")
            if (type != "AB")                                                  throw GeneralSecurityException("package is not an A/B update")
            if (sourceIncremental != null && sourceIncremental != INCREMENTAL) throw GeneralSecurityException("source incremental mismatch")
            if (sourceFingerprint != null && sourceFingerprint != FINGERPRINT) throw GeneralSecurityException("source fingerprint mismatch")

            var payloadOffset = 0L
            for (streamingPropertyFile in streamingPropertyFiles!!) {
                val properties = streamingPropertyFile.split(":")
                if (properties[0] == "payload.bin") {
                    payloadOffset = properties[1].toLong()
                }
            }

            Files.deleteIfExists(CARE_MAP_PATH.toPath())
            val careMapEntry = zipFile.getEntry("care_map.pb")
            if (careMapEntry == null) {
                Log.w(TAG, "care_map.pb missing")
            } else {
                Files.copy(zipFile.getInputStream(careMapEntry), CARE_MAP_PATH.toPath())
                CARE_MAP_PATH.setReadable(true, false)
            }

            val payloadProperties = getEntry(zipFile, "payload_properties.txt")
            val propertiesReader = BufferedReader(InputStreamReader(zipFile.getInputStream(payloadProperties)))
            applyUpdate(streaming, payloadOffset, propertiesReader.lines().toArray { arrayOfNulls(it) })
        } catch (e: GeneralSecurityException) {
            UPDATE_PATH.delete()
            throw e
        }
    }

    private fun annoyUser() {
        PeriodicJob.cancel(this)
        Settings.getPreferences(this).edit().putBoolean(Settings.KEY_WAITING_FOR_REBOOT, true).apply()
        if (Settings.getIdleReboot(this)) {
            IdleReboot.schedule(this)
        }
        notificationHandler.showRebootNotification()
    }

    private fun handleUpdate(intent: Intent?, network: Network?, serviceIsUserInitiated: Boolean) {
        Log.d(TAG, "handleUpdate")
        if (serviceIsUserInitiated) Log.d(TAG, "handleUpdate() – service is user-initiated")

        val pm = getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:$TAG")
        var connection: HttpURLConnection? = null
        var input: InputStream? = null

        try {
            wakeLock.acquire()

            if (mUpdating) {
                Log.d(TAG, "updating already, returning early")
                return
            }
            val preferences = Settings.getPreferences(this)
            if (preferences.getBoolean(Settings.KEY_WAITING_FOR_REBOOT, false)) {
                Log.d(TAG, "updated already, waiting for reboot")
                return
            }
            mUpdating = true
            notificationHandler.start()

            if (network == null) throw IOException("Network is unavailable")

            val channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this))

            Log.d(TAG, "fetching metadata for $DEVICE-$channel")
            connection = fetchData(network, "$DEVICE-$channel")
            val metadata = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readLine().split(" ")
            }

            val targetIncremental = metadata[0]
            val targetBuildDate = metadata[1].toLong()
            val sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0)
            if (targetBuildDate <= sourceBuildDate) {
                notificationHandler.showUpdatedNotification(channel)
                Log.d(TAG, "targetBuildDate: $targetBuildDate not higher than sourceBuildDate: $sourceBuildDate")
                mUpdating = false
                return
            }
            val targetDevice = metadata[2]
            if (targetDevice != DEVICE) throw GeneralSecurityException("targetDevice: $targetDevice does not match device: $DEVICE")

            val targetChannel = metadata[3]
            if (targetChannel != channel) throw GeneralSecurityException("targetChannel: $targetChannel does not match channel: $channel")

            notificationHandler.showDownloadNotification(0, 100)

            var downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null)
            var downloaded: Long
            var contentLength: Long

            val streaming = SystemProperties.getBoolean("sys.update.streaming_test", false)
            val streamingPrefix = if (streaming) "-streaming" else ""
            val incrementalUpdate = "$DEVICE${streamingPrefix}-incremental-$INCREMENTAL-$targetIncremental.zip"
            val fullUpdate = "$DEVICE${streamingPrefix}-ota_update-$targetIncremental.zip"

            downloaded = if (incrementalUpdate == downloadFile || fullUpdate == downloadFile) {
                UPDATE_PATH.length()
            } else {
                0L
            }

            if (downloaded > 0) {
                Log.d(TAG, "resume fetch of $downloadFile from $downloaded bytes")
                connection = fetchData(network, downloadFile!!)
                connection.setRequestProperty("Range", "bytes=$downloaded-")
                val responseCode = connection.responseCode
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously")
                    onDownloadFinished(streaming, targetBuildDate, channel)
                    return
                }
                if (responseCode == HTTP_NOT_FOUND && incrementalUpdate == downloadFile) {
                    connection.errorStream?.close()
                    downloaded = 0
                    UPDATE_PATH.delete()
                    Log.d(TAG, "previous incremental not found, fetch full update $fullUpdate")
                    downloadFile = fullUpdate
                    connection = fetchData(network, downloadFile)
                }
                contentLength = connection.contentLengthLong + downloaded
                input = connection.inputStream
            } else {
                Files.deleteIfExists(UPDATE_PATH.toPath())
                Log.d(TAG, "fetch incremental $incrementalUpdate")
                downloadFile = incrementalUpdate
                connection = fetchData(network, downloadFile)
                if (connection.responseCode == HTTP_NOT_FOUND) {
                    connection.errorStream?.close()
                    Log.d(TAG, "incremental not found, fetch full update $fullUpdate")
                    downloadFile = fullUpdate
                    connection = fetchData(network, downloadFile)
                }
                contentLength = connection.contentLengthLong
                input = connection.inputStream
            }

            notificationHandler.showDownloadNotification(downloaded, contentLength)

            val requiredBytes = contentLength - downloaded
            try {
                val sm = getSystemService(StorageManager::class.java)
                sm.allocateBytes(sm.getUuidForPath(UPDATE_PATH), requiredBytes, StorageManager.FLAG_ALLOCATE_AGGRESSIVE)
            } catch (e: IOException) {
                Log.d(TAG, "unable to allocate $requiredBytes bytes, proceeding anyway", e)
            }

            FileOutputStream(UPDATE_PATH, downloaded != 0L).use { output ->
                preferences.edit().putString(PREFERENCE_DOWNLOAD_FILE, downloadFile).commit()
                var bytesRead: Int
                var last = System.nanoTime()
                val buffer = ByteArray(8192)
                while (input!!.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    val now = System.nanoTime()
                    if (now - last > 1_000_000_000L) {
                        Log.d(TAG, "downloaded $downloaded from $contentLength bytes")
                        notificationHandler.showDownloadNotification(downloaded, contentLength)
                        last = now
                    }
                }
            }

            Log.d(TAG, "download completed")
            onDownloadFinished(streaming, targetBuildDate, channel)

        } catch (e: Exception) {
            when (e) {
                is GeneralSecurityException, is IOException, is ServiceSpecificException -> {
                    Log.e(TAG, "failed to download and install update", e)
                    notificationHandler.showFailureNotification(e.message ?: "unknown error")
                    mUpdating = false
                    if (serviceIsUserInitiated) {
                        Log.w(TAG, "handleUpdate() – service failed but failure is ignored because it was user-initiated")
                    } else {
                        PeriodicJob.scheduleRetry(this)
                        Log.w(TAG, "handleUpdate() – service failed but has been scheduled for retry")
                    }
                }
                else -> throw e
            }
        } finally {
            IoUtils.closeQuietly(input)
            connection?.disconnect()
            notificationHandler.cancelProgressNotification()
            Log.d(TAG, "release wake lock")
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "Service"
        const val INTENT_EXTRA_NETWORK = "network"
        const val INTENT_EXTRA_IS_USER_INITIATED = "is_user_initiated"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val PREFERENCE_DOWNLOAD_FILE = "download_file"
        private val CARE_MAP_PATH = File("/data/ota_package/care_map.pb")
        private val UPDATE_PATH = File("/data/ota_package/update.zip")
    }
}