package de.flexy.pendel.data

import android.content.Context
import androidx.room.withTransaction
import de.flexy.pendel.analysis.AnalysisWorker
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.data.db.ActivityEventEntity
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.TrackPointEntity
import de.flexy.pendel.data.db.TripEntity
import de.flexy.pendel.data.db.TripState
import de.flexy.pendel.data.db.TripTrigger
import java.time.ZoneId
import java.util.UUID

/** Write side for raw trips (recording lifecycle, user edits, deletion). */
class TripRepository(
    private val context: Context,
    private val db: PendelDatabase,
) {
    suspend fun startTrip(trigger: TripTrigger, activityMode: TransportMode?): Long {
        val now = System.currentTimeMillis()
        return db.tripDao().insert(
            TripEntity(
                uuid = UUID.randomUUID().toString(),
                recordedStart = now,
                zoneId = ZoneId.systemDefault().id,
                trigger = trigger.name,
                state = TripState.RECORDING.name,
                activityMode = activityMode?.name,
                createdAt = now,
            ),
        )
    }

    suspend fun appendPoints(points: List<TrackPointEntity>) {
        if (points.isNotEmpty()) db.pointDao().insertAll(points)
    }

    suspend fun recordActivityEvents(events: List<ActivityEventEntity>) {
        if (events.isNotEmpty()) db.activityEventDao().insertAll(events)
    }

    /** Ends a recording: raw start/end come from the raw fixes, then analysis is queued. */
    suspend fun finishTrip(tripId: Long) {
        val trip = db.tripDao().get(tripId) ?: return
        if (db.pointDao().count(tripId) < 3) {
            db.tripDao().delete(tripId)
            return
        }
        db.tripDao().update(
            trip.copy(
                recordedStart = db.pointDao().firstTime(tripId) ?: trip.recordedStart,
                recordedEnd = db.pointDao().lastTime(tripId) ?: System.currentTimeMillis(),
                state = TripState.PROCESSING.name,
            ),
        )
        AnalysisWorker.enqueue(context)
    }

    /** Trips left in RECORDING by a killed process (only those started before [before]). */
    suspend fun finalizeOrphanedRecordings(before: Long) {
        db.tripDao().byState(TripState.RECORDING.name).filter { it.recordedStart < before }.forEach { finishTrip(it.id) }
    }

    // ---------------------------------------------------------------- user edits

    suspend fun setExcluded(tripId: Long, excluded: Boolean) = db.tripDao().setExcluded(tripId, excluded)

    /** User override of the transport mode – re-derives the trip (mode influences analysis). */
    suspend fun setMode(tripId: Long, mode: TransportMode) {
        db.tripDao().setUserMode(tripId, mode.name)
        db.tripDao().setState(tripId, TripState.PROCESSING.name)
        AnalysisWorker.enqueue(context)
    }

    suspend fun renameRoute(id: Long, name: String) = db.routeDao().rename(id, name.trim())
    suspend fun renamePlace(id: Long, name: String, kind: String) = db.placeDao().rename(id, name.trim(), kind)
    suspend fun renameIntersection(id: Long, name: String?) = db.intersectionDao().rename(id, name?.trim()?.ifEmpty { null })

    // ---------------------------------------------------------------- deletion (privacy)

    /** Deletes one trip incl. raw points; derived rows cascade, global analysis is refreshed. */
    suspend fun delete(tripId: Long) {
        db.tripDao().delete(tripId)
        AnalysisWorker.enqueue(context)
    }

    /** Deletes all trips recorded in [from, to) plus raw activity events of that period. */
    suspend fun deleteBetween(from: Long, to: Long): Int {
        val n = db.withTransaction {
            db.activityEventDao().deleteBetween(from, to)
            db.tripDao().deleteBetween(from, to)
        }
        AnalysisWorker.enqueue(context)
        return n
    }

    suspend fun deleteDemoData() {
        db.tripDao().deleteDemo()
        AnalysisWorker.enqueue(context)
    }

    /** Removes every tracking-derived record. Places/route names go too – nothing personal remains. */
    suspend fun deleteEverything() {
        db.withTransaction {
            db.tripDao().deleteAll() // cascades: points, analysis, stops, waits, passes
            db.activityEventDao().deleteAll()
            db.routeDao().deleteAll()
            db.placeDao().deleteAll()
            db.intersectionDao().deleteAll()
        }
    }
}
