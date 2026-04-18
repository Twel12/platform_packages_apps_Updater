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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection

class UpdaterService : Service() {

    private val tlsSocketFactory = ModernTLSSocketFactory()
    private lateinit var notificationHandler: NotificationHandler
    private val updating = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        notificationHandler = NotificationHandler(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val network = intent?.getParcelableExtra(INTENT_EXTRA_NETWORK, Network::class.java)
        val serviceIsUserInitiated =
            intent?.getBooleanExtra(INTENT_EXTRA_IS_USER_INITIATED, false) ?: false

        serviceScope.launch {
            try {
                handleUpdate(network, serviceIsUserInitiated)
            } finally {
                stopSelf(startId)
            }
        }

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
                    UpdateStatusConstants.DOWNLOADING -> notificationHandler.showInstallNotification(
                        Math.round(percent * 100)
                    )
                    UpdateStatusConstants.VERIFYING -> notificationHandler.showValidateNotification(
                        Math.round(percent * 100)
                    )
                    UpdateStatusConstants.FINALIZING -> notificationHandler.showFinalizeNotification(
                        Math.round(percent * 100)
                    )
                }
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "onPayloadApplicationComplete success")
                    annoyUser()
                } else {
                    Log.d(TAG, "onPayloadApplicationComplete: $errorCode")
                    notificationHandler.showFailureNotification("update_engine error code: $errorCode")
                    updating.set(false)
                }
                UPDATE_PATH.delete()
                monitor.countDown()
            }
        })

        if (streaming) {
            val downloadFile = Settings.getDownloadFile(this, streaming)
                ?: throw IOException("missing download file for streaming update")
            engine.applyPayload(getString(R.string.url) + downloadFile, payloadOffset, 0, headerKeyValuePairs)
        } else {
            UPDATE_PATH.setReadable(true, false)
            engine.applyPayload("file://$UPDATE_PATH", payloadOffset, 0, headerKeyValuePairs)
        }

        try {
            monitor.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("update was interrupted", e)
        }

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
            RecoverySystem.verifyPackage(
                UPDATE_PATH,
                { progress ->
                    Log.d(TAG, "verifyPackage: $progress%")
                    notificationHandler.showVerifyNotification(progress)
                },
                null
            )

            ZipFile(UPDATE_PATH).use { zipFile ->
                val metadataEntry = getEntry(zipFile, "META-INF/com/android/metadata")
                val packageMetadata = BufferedReader(
                    InputStreamReader(zipFile.getInputStream(metadataEntry))
                ).use { reader ->
                    UpdateMetadataParser.parsePackageMetadata(reader.lineSequence())
                }

                if (packageMetadata.timestamp != targetBuildDate) {
                    throw GeneralSecurityException("timestamp does not match server metadata")
                }
                if (DEVICE != packageMetadata.device) {
                    throw GeneralSecurityException("device mismatch")
                }
                if (packageMetadata.serialno != null) {
                    throw GeneralSecurityException("serialno constraint not permitted")
                }
                if (packageMetadata.type != "AB") {
                    throw GeneralSecurityException("package is not an A/B update")
                }
                if (packageMetadata.sourceIncremental != null &&
                    packageMetadata.sourceIncremental != INCREMENTAL
                ) {
                    throw GeneralSecurityException("source incremental mismatch")
                }
                if (packageMetadata.sourceFingerprint != null &&
                    packageMetadata.sourceFingerprint != FINGERPRINT
                ) {
                    throw GeneralSecurityException("source fingerprint mismatch")
                }

                val payloadOffset = UpdateMetadataParser.payloadOffsetFromStreamingProperties(
                    packageMetadata.streamingPropertyFiles
                )

                Files.deleteIfExists(CARE_MAP_PATH.toPath())
                val careMapEntry = zipFile.getEntry("care_map.pb")
                if (careMapEntry == null) {
                    Log.w(TAG, "care_map.pb missing")
                } else {
                    zipFile.getInputStream(careMapEntry).use { careMapInput ->
                        Files.copy(careMapInput, CARE_MAP_PATH.toPath())
                    }
                    CARE_MAP_PATH.setReadable(true, false)
                }

                val payloadPropertiesEntry = getEntry(zipFile, "payload_properties.txt")
                val properties = BufferedReader(
                    InputStreamReader(zipFile.getInputStream(payloadPropertiesEntry))
                ).use { propertiesReader ->
                    propertiesReader.lineSequence().toList().toTypedArray()
                }
                applyUpdate(streaming, payloadOffset, properties)
            }
        } catch (e: GeneralSecurityException) {
            UPDATE_PATH.delete()
            throw e
        }
    }

    private fun annoyUser() {
        PeriodicJob.cancel(this)
        Settings.setWaitingForReboot(this, true)
        if (Settings.getIdleReboot(this)) {
            IdleReboot.schedule(this)
        }
        notificationHandler.showRebootNotification()
    }

    private suspend fun handleUpdate(network: Network?, serviceIsUserInitiated: Boolean) {
        Log.d(TAG, "handleUpdate")
        if (serviceIsUserInitiated) {
            Log.d(TAG, "handleUpdate() - service is user-initiated")
        }

        val pm = getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:$TAG")
        var connection: HttpURLConnection? = null
        var input: InputStream? = null

        try {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)

            if (Settings.getWaitingForReboot(this)) {
                Log.d(TAG, "updated already, waiting for reboot")
                return
            }
            if (!updating.compareAndSet(false, true)) {
                Log.d(TAG, "updating already, returning early")
                return
            }
            notificationHandler.start()

            if (network == null) {
                throw IOException("network is unavailable")
            }

            val channel = SystemProperties.get("sys.update.channel", Settings.getChannel(this))

            Log.d(TAG, "fetching metadata for $DEVICE-$channel")
            connection = fetchData(network, "$DEVICE-$channel")
            val metadataLine = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readLine()
            } ?: throw IOException("server metadata is empty")
            val metadata = UpdateMetadataParser.parseServerMetadata(metadataLine)

            val sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0)
            if (metadata.targetBuildDate <= sourceBuildDate) {
                notificationHandler.showUpdatedNotification(channel)
                Log.d(
                    TAG,
                    "targetBuildDate: ${metadata.targetBuildDate} not higher than sourceBuildDate: $sourceBuildDate"
                )
                updating.set(false)
                return
            }
            if (metadata.targetDevice != DEVICE) {
                throw GeneralSecurityException(
                    "targetDevice: ${metadata.targetDevice} does not match device: $DEVICE"
                )
            }
            if (metadata.targetChannel != channel) {
                throw GeneralSecurityException(
                    "targetChannel: ${metadata.targetChannel} does not match channel: $channel"
                )
            }

            notificationHandler.showDownloadNotification(0, 100)

            val streaming = SystemProperties.getBoolean("sys.update.streaming_test", false)
            val streamingPrefix = if (streaming) "-streaming" else ""
            val incrementalUpdate =
                "$DEVICE${streamingPrefix}-incremental-$INCREMENTAL-${metadata.targetIncremental}.zip"
            val fullUpdate = "$DEVICE${streamingPrefix}-ota_update-${metadata.targetIncremental}.zip"
            var downloadFile = Settings.getDownloadFile(this, streaming)
            var downloaded: Long
            val contentLength: Long

            downloaded = if (incrementalUpdate == downloadFile || fullUpdate == downloadFile) {
                UPDATE_PATH.length()
            } else {
                0L
            }

            if (downloaded > 0) {
                Log.d(TAG, "resume fetch of $downloadFile from $downloaded bytes")
                val existingDownloadFile = downloadFile
                    ?: throw IOException("missing cached download file name")
                connection = fetchData(network, existingDownloadFile)
                connection.setRequestProperty("Range", "bytes=$downloaded-")
                val responseCode = connection.responseCode
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    Log.d(TAG, "download completed previously")
                    onDownloadFinished(streaming, metadata.targetBuildDate, channel)
                    return
                }
                if (responseCode == HTTP_NOT_FOUND && incrementalUpdate == existingDownloadFile) {
                    connection.errorStream?.close()
                    downloaded = 0
                    UPDATE_PATH.delete()
                    Log.d(TAG, "previous incremental not found, fetch full update $fullUpdate")
                    downloadFile = fullUpdate
                    connection = fetchData(network, fullUpdate)
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
                sm.allocateBytes(
                    sm.getUuidForPath(UPDATE_PATH),
                    requiredBytes,
                    StorageManager.FLAG_ALLOCATE_AGGRESSIVE
                )
            } catch (e: IOException) {
                Log.d(TAG, "unable to allocate $requiredBytes bytes, proceeding anyway", e)
            }

            val downloadInput = input ?: throw IOException("download input stream unavailable")
            FileOutputStream(UPDATE_PATH, downloaded != 0L).use { output ->
                Settings.setDownloadFile(this, downloadFile, streaming)
                var bytesRead: Int
                var last = System.nanoTime()
                val buffer = ByteArray(8192)
                while (downloadInput.read(buffer).also { bytesRead = it } != -1) {
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
            onDownloadFinished(streaming, metadata.targetBuildDate, channel)
        } catch (e: Exception) {
            when (e) {
                is GeneralSecurityException, is IOException, is ServiceSpecificException -> {
                    Log.e(TAG, "failed to download and install update", e)
                    notificationHandler.showFailureNotification(e.message ?: "unknown error")
                    updating.set(false)
                    if (serviceIsUserInitiated) {
                        Log.w(
                            TAG,
                            "handleUpdate() - service failed but failure is ignored because it was user-initiated"
                        )
                    } else {
                        PeriodicJob.scheduleRetry(this)
                        Log.w(TAG, "handleUpdate() - service failed but has been scheduled for retry")
                    }
                }
                else -> throw e
            }
        } finally {
            IoUtils.closeQuietly(input)
            connection?.disconnect()
            notificationHandler.cancelProgressNotification()
            if (wakeLock.isHeld) {
                Log.d(TAG, "release wake lock")
                wakeLock.release()
            }
        }
    }

    companion object {
        private const val TAG = "UpdaterService"
        const val INTENT_EXTRA_NETWORK = "network"
        const val INTENT_EXTRA_IS_USER_INITIATED = "is_user_initiated"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private val CARE_MAP_PATH = File("/data/ota_package/care_map.pb")
        private val UPDATE_PATH = File("/data/ota_package/update.zip")
    }
}
