package app.seamlessupdate.client

import android.app.job.JobInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.view.MenuItem
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

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

    class SettingsFragment : PreferenceFragmentCompat(),
            SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.setStorageDeviceProtected()
            setPreferencesFromResource(R.xml.settings, rootKey)

            val clickListener = Preference.OnPreferenceClickListener { _ ->
                val context = requireContext()
                if (!getPreferences(context).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                    val network = connectivityManager.activeNetwork
                    if (network == null) {
                        Log.w(TAG, "checkForUpdates.onClickListener – network will be unavailable")
                    }
                    val intent = Intent(context, Service::class.java).apply {
                        putExtra(Service.INTENT_EXTRA_IS_USER_INITIATED, true)
                        putExtra(Service.INTENT_EXTRA_NETWORK, network)
                    }
                    context.startForegroundService(intent)
                }
                true
            }
            findPreference<Preference>(KEY_CHECK_FOR_UPDATES)?.setOnPreferenceClickListener(clickListener)

            val changeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toInt()
                getPreferences(requireContext()).edit().putInt(KEY_NETWORK_TYPE, value).apply()
                if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    PeriodicJob.schedule(requireContext())
                }
                true
            }
            findPreference<Preference>(KEY_NETWORK_TYPE)?.setOnPreferenceChangeListener(changeListener)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            when (key) {
                KEY_CHANNEL, KEY_BATTERY_NOT_LOW, KEY_REQUIRES_CHARGING -> {
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext())
                    }
                }
                KEY_IDLE_REBOOT -> {
                    if (!getIdleReboot(requireContext())) {
                        IdleReboot.cancel(requireContext())
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            val networkType = findPreference<ListPreference>(KEY_NETWORK_TYPE)
            networkType?.value = getNetworkType(requireContext()).toString()
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        companion object {
            private const val TAG = "SettingsFragment"
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

        fun getPreferences(context: Context): SharedPreferences {
            val deviceContext = context.createDeviceProtectedStorageContext()
            return PreferenceManager.getDefaultSharedPreferences(deviceContext)
        }

        fun getChannel(context: Context): String =
            getPreferences(context).getString(KEY_CHANNEL, context.getString(R.string.channel_default))!!

        fun getNetworkType(context: Context): Int =
            getPreferences(context).getInt(KEY_NETWORK_TYPE,
                context.getString(R.string.network_type_default).toInt())

        fun getNetworkRequest(context: Context): NetworkRequest {
            val networkType = getNetworkType(context)
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (networkType == JobInfo.NETWORK_TYPE_UNMETERED) {
                builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            return builder.build()
        }

        fun getBatteryNotLow(context: Context): Boolean =
            getPreferences(context).getBoolean(KEY_BATTERY_NOT_LOW,
                context.getString(R.string.battery_not_low_default).toBoolean())

        fun getRequiresCharging(context: Context): Boolean =
            getPreferences(context).getBoolean(KEY_REQUIRES_CHARGING,
                context.getString(R.string.requires_charging_default).toBoolean())

        fun getIdleReboot(context: Context): Boolean =
            getPreferences(context).getBoolean(KEY_IDLE_REBOOT,
                context.getString(R.string.idle_reboot_default).toBoolean())
    }
}