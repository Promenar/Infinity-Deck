package com.promenar.infinity.recording

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingRetryService : JobService() {
    private var retry: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        retry = CoroutineScope(Dispatchers.Main).launch {
            val unfinished = try {
                withContext(Dispatchers.IO) { recordingController().retryCloudQueue() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                true
            }
            jobFinished(params, unfinished)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        retry?.cancel()
        retry = null
        return true
    }

    override fun onDestroy() {
        retry?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x524543
        internal fun ensureScheduled(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, RecordingRetryService::class.java))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(15_000)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
    }
}
