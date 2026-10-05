package de.flexy.pendel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import de.flexy.pendel.analysis.AnalysisWorker
import de.flexy.pendel.core.analysis.AnalysisVersion
import de.flexy.pendel.di.AppContainer
import de.flexy.pendel.tracking.ActivityRecognitionManager
import de.flexy.pendel.tracking.TrackingState
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class PendelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val processStart = System.currentTimeMillis()
        container = AppContainer(this)
        MapLibre.getInstance(this)
        createChannels()
        container.appScope.launch {
            // a recording that was interrupted by process death is finalized and analyzed
            if (TrackingState.live.value == null) container.trips.finalizeOrphanedRecordings(before = processStart)
            if (container.settings.current().autoDetect) ActivityRecognitionManager.register(this@PendelApp)
            // derived data from an older algorithm version, after a migration or an aborted run → refresh
            val db = container.db
            val stale = db.tripAnalysisDao().countOlderThan(AnalysisVersion.CURRENT) > 0 ||
                db.analysisRunDao().latestStatus().let { it != null && it != "OK" }
            if (stale) AnalysisWorker.enqueue(this@PendelApp)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Wird angezeigt, solange eine Fahrt aufgezeichnet wird"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
    }
}
