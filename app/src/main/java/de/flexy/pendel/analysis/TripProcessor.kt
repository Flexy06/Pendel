package de.flexy.pendel.analysis

import android.util.Log
import androidx.room.withTransaction
import de.flexy.pendel.core.analysis.AnalysisVersion
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.track.TripAnalyzer
import de.flexy.pendel.core.track.TripSplitter
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.StopEntity
import de.flexy.pendel.data.db.TripAnalysisEntity
import de.flexy.pendel.data.db.TripState
import de.flexy.pendel.data.db.TripTrigger
import de.flexy.pendel.data.localTimeInfo
import de.flexy.pendel.data.splitTripAt
import de.flexy.pendel.data.modeHint
import de.flexy.pendel.data.settings.SettingsRepository
import de.flexy.pendel.data.toCore
import de.flexy.pendel.data.zone
import de.flexy.pendel.providers.ProviderRegistry

/**
 * Per-trip derivation from raw points: cleaning, metrics, stops, geometry, optional map matching.
 * Writes only derived tables (trip_analysis, stops) – the raw trip row is never modified except
 * for its processing state.
 */
class TripProcessor(
    private val db: PendelDatabase,
    private val settings: SettingsRepository,
) {
    /** @return true if the trip was kept. */
    suspend fun process(tripId: Long, allowNetwork: Boolean): Boolean {
        var trip = db.tripDao().get(tripId) ?: return false
        var raw = db.pointDao().forTrip(tripId).map { it.toCore() }

        // v3: one recording may contain two rides with a long stay in between (Uni → Mensa → home,
        // when indoor GPS kept the recorder from stopping). Split it into separate trips first.
        if (trip.state != TripState.RECORDING.name) {
            val dwells = TripSplitter.findDwells(raw)
            if (dwells.isNotEmpty()) {
                val newIds = splitTripAt(db, trip, dwells.map { it.splitT })
                if (newIds.isNotEmpty()) {
                    Log.i(TAG, "Split trip $tripId at ${dwells.size} stay(s) into ${newIds.size + 1} trips")
                    for (id in newIds) process(id, allowNetwork)
                    trip = db.tripDao().get(tripId) ?: return false
                    raw = db.pointDao().forTrip(tripId).map { it.toCore() }
                }
            }
        }
        // a mode the user set is authoritative; an activity-recognition hint is only a prior
        val analysis = TripAnalyzer.analyze(raw, trip.modeHint(), hintIsAuthoritative = trip.userMode != null)

        if (analysis == null) {
            if (raw.size < 3) {
                Log.i(TAG, "Dropping trip $tripId without usable points")
                db.tripDao().delete(tripId)
            } else {
                db.tripDao().setState(tripId, TripState.FAILED.name)
            }
            return false
        }
        if (trip.trigger == TripTrigger.AUTO.name && !TripAnalyzer.isValidTrip(analysis.metrics)) {
            // noise from automatic detection (walking to the bike rack …) – raw data is worthless
            Log.i(TAG, "Discarding auto trip $tripId (too short)")
            db.tripDao().delete(tripId)
            return false
        }

        val m = analysis.metrics
        var polyline = analysis.polyline
        var bikeScore: Double? = null
        var via: String? = null
        var matchedBy: String? = null
        val matcher = ProviderRegistry.matcher(settings.current())
        if (!matcher.requiresNetwork || allowNetwork) {
            runCatching { matcher.match(analysis.cleanedPoints, analysis.mode) }
                .onSuccess { r ->
                    r.geometry?.takeIf { it.size >= 2 }?.let { polyline = PolylineCodec.encode(it) }
                    bikeScore = r.bikeScore
                    r.mainStreets.takeIf { it.isNotEmpty() }?.let { via = it.joinToString(", ") }
                    if (matcher.requiresNetwork) matchedBy = matcher.id
                }
                .onFailure { Log.w(TAG, "Map matching failed for $tripId", it) }
        }
        // keep previously derived street names if matching is not available right now
        val previous = db.tripAnalysisDao().get(tripId)
        if (via == null) via = previous?.via
        if (via == null && trip.isDemo) via = trip.note // demo rides carry their street name as note
        if (bikeScore == null && matchedBy == null) bikeScore = previous?.bikeScore

        val zone = trip.zone()
        val start = localTimeInfo(m.startTime, zone)
        val end = localTimeInfo(m.endTime, zone)
        val first = analysis.cleanedPoints.first()
        val last = analysis.cleanedPoints.last()
        val version = AnalysisVersion.CURRENT
        val stops = analysis.stops.map {
            StopEntity(
                tripId = tripId, startTime = it.startTime, endTime = it.endTime, lat = it.lat, lon = it.lon,
                durationS = it.durationS, alongM = it.alongM, kind = it.kind.name, resumed = it.resumed,
                analysisVersion = version,
            )
        }
        db.withTransaction {
            db.stopDao().deleteForTrip(tripId) // cascades old wait events of this trip
            db.stopDao().insertAll(stops)
            db.tripAnalysisDao().upsert(
                TripAnalysisEntity(
                    tripId = tripId,
                    analysisVersion = version,
                    analyzedAt = System.currentTimeMillis(),
                    movementStart = m.startTime,
                    movementEnd = m.endTime,
                    zoneOffsetMin = start.zoneOffsetMin,
                    dayOfWeek = start.dayOfWeek,
                    startMinuteOfDay = start.minuteOfDay,
                    endMinuteOfDay = end.minuteOfDay,
                    mode = analysis.mode.name,
                    distanceM = m.distanceM,
                    durationS = m.durationS,
                    movingS = m.movingS,
                    stoppedS = m.stoppedS,
                    stopCount = analysis.stops.count { it.kind == StopKind.STOP },
                    waitS = previous?.waitS ?: 0.0, // replaced by the global analysis
                    elevationGainM = m.elevationGainM,
                    elevationLossM = m.elevationLossM,
                    avgMovingSpeed = m.avgMovingSpeedMs,
                    maxSpeed = m.maxSpeedMs,
                    startLat = first.lat, startLon = first.lon,
                    endLat = last.lat, endLon = last.lon,
                    startPlaceId = previous?.startPlaceId,
                    endPlaceId = previous?.endPlaceId,
                    routeId = previous?.routeId,
                    polyline = polyline,
                    signature = analysis.signature,
                    bikeScore = bikeScore,
                    via = via,
                    pointCount = raw.size,
                    matchedBy = matchedBy ?: previous?.matchedBy?.takeIf { matcher.requiresNetwork },
                ),
            )
            db.tripDao().setState(tripId, TripState.ANALYZED.name)
        }
        return true
    }

    companion object {
        private const val TAG = "TripProcessor"
    }
}
