package app.seamlessupdate.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemProperties
import android.util.Log

class PeriodicJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(TAG, "onStartJob id: ${params.jobId}")
        val network = params.network
        if (network == null) {
            Log.e(TAG, "JobParameters have a null Network")
            return false
        }
        val intent = Intent(this, Service::class.java).apply {
            putExtra(Service.INTENT_EXTRA_NETWORK, network)
        }
        startForegroundService(intent)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "PeriodicJob"
        private const val JOB_ID_PERIODIC = 1
        private const val JOB_ID_RETRY = 2
        private const val INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        private const val MIN_LATENCY_MILLIS = 4 * 60 * 1000L
        private const val EXTRA_JOB_CHANNEL = "extra_job_channel"

        fun schedule(context: Context) {
            val channel = SystemProperties.get("sys.update.channel", Settings.getChannel(context))
            val networkRequest = Settings.getNetworkRequest(context)
            val batteryNotLow = Settings.getBatteryNotLow(context)
            val requiresCharging = Settings.getRequiresCharging(context)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val jobInfo = scheduler.getPendingJob(JOB_ID_PERIODIC)
            if (jobInfo != null &&
                jobInfo.requiredNetwork == networkRequest &&
                jobInfo.isRequireBatteryNotLow == batteryNotLow &&
                jobInfo.isRequireCharging == requiresCharging &&
                jobInfo.isPersisted &&
                jobInfo.intervalMillis == INTERVAL_MILLIS &&
                jobInfo.extras.getString(EXTRA_JOB_CHANNEL) == channel
            ) {
                Log.d(TAG, "Periodic job already registered")
                return
            }
            val extras = PersistableBundle().apply {
                putString(EXTRA_JOB_CHANNEL, channel)
            }
            val serviceName = ComponentName(context, PeriodicJob::class.java)
            val result = scheduler.schedule(
                JobInfo.Builder(JOB_ID_PERIODIC, serviceName)
                    .setRequiredNetwork(networkRequest)
                    .setRequiresBatteryNotLow(batteryNotLow)
                    .setRequiresCharging(requiresCharging)
                    .setPersisted(true)
                    .setPeriodic(INTERVAL_MILLIS)
                    .setExtras(extras)
                    .build()
            )
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "Periodic job schedule failed")
            }
        }

        fun scheduleRetry(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val serviceName = ComponentName(context, PeriodicJob::class.java)
            val result = scheduler.schedule(
                JobInfo.Builder(JOB_ID_RETRY, serviceName)
                    .setRequiredNetworkType(Settings.getNetworkType(context))
                    .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
                    .setRequiresCharging(Settings.getRequiresCharging(context))
                    .setMinimumLatency(MIN_LATENCY_MILLIS)
                    .build()
            )
            if (result == JobScheduler.RESULT_FAILURE) {
                Log.d(TAG, "Retry job schedule failed")
            }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(JOB_ID_PERIODIC)
            scheduler.cancel(JOB_ID_RETRY)
        }
    }
}