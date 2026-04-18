package app.seamlessupdate.client

import android.app.job.JobInfo
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.MenuItem
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.updaterDataStore by preferencesDataStore(
    name = "updater_preferences",
    produceMigrations = { context ->
        val deviceContext = context.createDeviceProtectedStorageContext()
        listOf(SharedPreferencesMigration(deviceContext, "${context.packageName}_preferences"))
    }
)

class Settings : CollapsingToolbarBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userManager = getSystemService(USER_SERVICE) as UserManager
        if (!userManager.isSystemUser) {
            throw SecurityException("system user only")
        }
        setContentView(R.layout.settings_activity)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = UpdaterPreferenceDataStore(requireContext())
            setPreferencesFromResource(R.xml.settings, rootKey)

            val clickListener = Preference.OnPreferenceClickListener { _ ->
                val context = requireContext()
                if (!getWaitingForReboot(context)) {
                    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                    val network = connectivityManager.activeNetwork
                    if (network == null) {
                        Log.w(TAG, "checkForUpdates.onClickListener - network will be unavailable")
                    }
                    val intent = Intent(context, UpdaterService::class.java).apply {
                        putExtra(UpdaterService.INTENT_EXTRA_IS_USER_INITIATED, true)
                        putExtra(UpdaterService.INTENT_EXTRA_NETWORK, network)
                    }
                    context.startForegroundService(intent)
                }
                true
            }
            findPreference<Preference>(KEY_CHECK_FOR_UPDATES)?.setOnPreferenceClickListener(clickListener)

            val changeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toInt()
                setNetworkType(requireContext(), value)
                if (!getWaitingForReboot(requireContext())) {
                    PeriodicJob.schedule(requireContext())
                }
                true
            }
            findPreference<Preference>(KEY_NETWORK_TYPE)?.setOnPreferenceChangeListener(changeListener)

            val rescheduleListener = Preference.OnPreferenceChangeListener { _, _ ->
                if (!getWaitingForReboot(requireContext())) {
                    PeriodicJob.schedule(requireContext())
                }
                true
            }
            findPreference<Preference>(KEY_CHANNEL)?.setOnPreferenceChangeListener(rescheduleListener)
            findPreference<Preference>(KEY_BATTERY_NOT_LOW)?.setOnPreferenceChangeListener(rescheduleListener)
            findPreference<Preference>(KEY_REQUIRES_CHARGING)?.setOnPreferenceChangeListener(rescheduleListener)

            val idleRebootListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (!enabled) {
                    IdleReboot.cancel(requireContext())
                }
                if (!getWaitingForReboot(requireContext())) {
                    PeriodicJob.schedule(requireContext())
                }
                true
            }
            findPreference<Preference>(KEY_IDLE_REBOOT)?.setOnPreferenceChangeListener(idleRebootListener)
        }

        override fun onResume() {
            super.onResume()
            val networkType = findPreference<ListPreference>(KEY_NETWORK_TYPE)
            networkType?.value = getNetworkType(requireContext()).toString()
        }

        companion object {
            private const val TAG = "SettingsFragment"
        }
    }

    private class UpdaterPreferenceDataStore(context: Context) : PreferenceDataStore() {
        private val appContext = context.applicationContext.createDeviceProtectedStorageContext()

        override fun putString(key: String, value: String?) {
            val valueToStore = value ?: return
            runBlocking {
                appContext.updaterDataStore.edit { prefs ->
                    prefs[stringPreferencesKey(key)] = valueToStore
                }
            }
        }

        override fun putInt(key: String, value: Int) {
            runBlocking {
                appContext.updaterDataStore.edit { prefs ->
                    prefs[intPreferencesKey(key)] = value
                }
            }
        }

        override fun putBoolean(key: String, value: Boolean) {
            runBlocking {
                appContext.updaterDataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(key)] = value
                }
            }
        }

        override fun getString(key: String, defValue: String?): String? {
            return runBlocking {
                appContext.updaterDataStore.data.first()[stringPreferencesKey(key)] ?: defValue
            }
        }

        override fun getInt(key: String, defValue: Int): Int {
            return runBlocking {
                appContext.updaterDataStore.data.first()[intPreferencesKey(key)] ?: defValue
            }
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            return runBlocking {
                appContext.updaterDataStore.data.first()[booleanPreferencesKey(key)] ?: defValue
            }
        }
    }

    companion object {
        private const val KEY_CHANNEL = "channel"
        private const val KEY_NETWORK_TYPE = "network_type"
        private const val KEY_BATTERY_NOT_LOW = "battery_not_low"
        private const val KEY_REQUIRES_CHARGING = "requires_charging"
        private const val KEY_IDLE_REBOOT = "idle_reboot"
        private const val KEY_CHECK_FOR_UPDATES = "check_for_updates"
        const val KEY_WAITING_FOR_REBOOT = "waiting_for_reboot"
        private const val KEY_DOWNLOAD_FILE = "download_file"

        private fun appContext(context: Context): Context =
            context.applicationContext.createDeviceProtectedStorageContext()

        private fun defaults(context: Context): PreferenceDefaults = PreferenceDefaults(
            channel = context.getString(R.string.channel_default),
            networkType = context.getString(R.string.network_type_default).toInt(),
            batteryNotLow = context.getString(R.string.battery_not_low_default).toBoolean(),
            requiresCharging = context.getString(R.string.requires_charging_default).toBoolean(),
            idleReboot = context.getString(R.string.idle_reboot_default).toBoolean()
        )

        private fun readPrefs(context: Context): Preferences =
            runBlocking { appContext(context).updaterDataStore.data.first() }

        private fun writePrefs(
            context: Context,
            update: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
        ) {
            runBlocking {
                appContext(context).updaterDataStore.edit { prefs ->
                    update(prefs)
                }
            }
        }

        fun getChannel(context: Context): String {
            val prefs = readPrefs(context)
            val defaultValue = defaults(context).channel
            return prefs[stringPreferencesKey(KEY_CHANNEL)] ?: defaultValue
        }

        fun getNetworkType(context: Context): Int {
            val prefs = readPrefs(context)
            val defaultValue = defaults(context).networkType
            return prefs[intPreferencesKey(KEY_NETWORK_TYPE)] ?: defaultValue
        }

        fun setNetworkType(context: Context, value: Int) {
            writePrefs(context) { prefs ->
                prefs[intPreferencesKey(KEY_NETWORK_TYPE)] = value
            }
        }

        fun getNetworkRequest(context: Context): NetworkRequest {
            val networkType = getNetworkType(context)
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (networkType == JobInfo.NETWORK_TYPE_UNMETERED) {
                builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            return builder.build()
        }

        fun getBatteryNotLow(context: Context): Boolean {
            val prefs = readPrefs(context)
            val defaultValue = defaults(context).batteryNotLow
            return prefs[booleanPreferencesKey(KEY_BATTERY_NOT_LOW)] ?: defaultValue
        }

        fun getRequiresCharging(context: Context): Boolean {
            val prefs = readPrefs(context)
            val defaultValue = defaults(context).requiresCharging
            return prefs[booleanPreferencesKey(KEY_REQUIRES_CHARGING)] ?: defaultValue
        }

        fun getIdleReboot(context: Context): Boolean {
            val prefs = readPrefs(context)
            val defaultValue = defaults(context).idleReboot
            return prefs[booleanPreferencesKey(KEY_IDLE_REBOOT)] ?: defaultValue
        }

        fun getWaitingForReboot(context: Context): Boolean {
            val prefs = readPrefs(context)
            return prefs[booleanPreferencesKey(KEY_WAITING_FOR_REBOOT)] ?: false
        }

        fun setWaitingForReboot(context: Context, waitingForReboot: Boolean) {
            writePrefs(context) { prefs ->
                prefs[booleanPreferencesKey(KEY_WAITING_FOR_REBOOT)] = waitingForReboot
            }
        }

        fun clearWaitingForReboot(context: Context) {
            writePrefs(context) { prefs ->
                prefs[booleanPreferencesKey(KEY_WAITING_FOR_REBOOT)] = false
            }
        }

        fun getDownloadFile(context: Context, streaming: Boolean = false): String? {
            val key = if (streaming) "$KEY_DOWNLOAD_FILE-streaming" else KEY_DOWNLOAD_FILE
            return readPrefs(context)[stringPreferencesKey(key)]
        }

        fun setDownloadFile(context: Context, downloadFile: String, streaming: Boolean) {
            val key = if (streaming) "$KEY_DOWNLOAD_FILE-streaming" else KEY_DOWNLOAD_FILE
            writePrefs(context) { prefs ->
                prefs[stringPreferencesKey(key)] = downloadFile
            }
        }
    }

    private data class PreferenceDefaults(
        val channel: String,
        val networkType: Int,
        val batteryNotLow: Boolean,
        val requiresCharging: Boolean,
        val idleReboot: Boolean
    )

}