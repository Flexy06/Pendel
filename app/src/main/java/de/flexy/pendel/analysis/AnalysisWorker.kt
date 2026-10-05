package de.flexy.pendel.analysis

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.flexy.pendel.PendelApp
import de.flexy.pendel.core.analysis.AnalysisVersion
import de.flexy.pendel.data.db.TripState

/**
 * Runs after every recorded trip (and on demand): processes pending trips, optionally refreshes
 * OSM intersections, then recomputes the global analysis. Network is only used if the user enabled
 * a network provider – recording and analysis work fully offline.
 */
class AnalysisWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as PendelApp).container
        val online = isOnline(applicationContext)
        val reprocessAll = inputData.getBoolean(KEY_REPROCESS_ALL, false)
        return try {
            // Re-derive everything from raw data when asked to, or when the stored derived data was
            // produced by an older algorithm version.
            val outdated = c.db.tripAnalysisDao().countOlderThan(AnalysisVersion.CURRENT) > 0
            if (reprocessAll || outdated) {
                val n = c.db.tripDao().markAllForReanalysis()
                Log.i("AnalysisWorker", "Re-analysing $n trips with algorithm v${AnalysisVersion.CURRENT}")
            }
            val pending = c.db.tripDao().byState(TripState.PROCESSING.name)
            for (t in pending) c.tripProcessor.process(t.id, allowNetwork = online)
            if (online) c.globalAnalyzer.refreshOsmIntersections(force = inputData.getBoolean(KEY_FORCE_OSM, false))
            c.globalAnalyzer.run()
            Result.success()
        } catch (e: Exception) {
            Log.e("AnalysisWorker", "analysis failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE = "pendel-analysis"
        private const val KEY_REPROCESS_ALL = "reprocess_all"
        private const val KEY_FORCE_OSM = "force_osm"

        fun enqueue(context: Context, reprocessAll: Boolean = false, forceOsm: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf(KEY_REPROCESS_ALL to reprocessAll, KEY_FORCE_OSM to forceOsm))
                .build()
            // APPEND_OR_REPLACE: a trip finishing during a running analysis still gets processed
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
