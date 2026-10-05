package de.flexy.pendel.data

import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.model.TrackPoint
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.data.db.TrackPointEntity
import de.flexy.pendel.data.db.TripEntity
import de.flexy.pendel.data.db.TripState
import de.flexy.pendel.data.db.TripWithAnalysis
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Raw point → analysis input. The *choice* of altitude source is an analysis decision:
 * barometric altitude (precise relative changes) wins over GNSS altitude. Both stay stored.
 */
fun TrackPointEntity.toCore(): TrackPoint = TrackPoint(
    t = t,
    lat = latE7 / 1e7,
    lon = lonE7 / 1e7,
    accuracy = accDm / 10f,
    speed = if (speedCms >= 0) speedCms / 100f else null,
    bearing = if (bearingDeg >= 0) bearingDeg.toFloat() else null,
    altitude = (baroAltDm ?: altDm)?.let { it / 10.0 },
)

/** Encodes a fix compactly. [baroAltitudeM] is stored separately from the GNSS altitude. */
fun rawPoint(
    tripId: Long,
    t: Long,
    lat: Double,
    lon: Double,
    accuracyM: Float,
    speedMs: Float?,
    bearingDeg: Float?,
    gpsAltitudeM: Double?,
    speedAccuracyMs: Float? = null,
    verticalAccuracyM: Float? = null,
    baroAltitudeM: Double? = null,
): TrackPointEntity = TrackPointEntity(
    tripId = tripId,
    t = t,
    latE7 = (lat * 1e7).roundToInt(),
    lonE7 = (lon * 1e7).roundToInt(),
    accDm = (accuracyM * 10).roundToInt().coerceIn(0, 65_000),
    speedCms = speedMs?.let { (it * 100).roundToInt().coerceAtLeast(0) } ?: -1,
    bearingDeg = bearingDeg?.let { it.roundToInt().mod(360) } ?: -1,
    altDm = gpsAltitudeM?.let { (it * 10).roundToInt() },
    speedAccCms = speedAccuracyMs?.let { (it * 100).roundToInt() },
    vAccDm = verticalAccuracyM?.let { (it * 10).roundToInt() },
    baroAltDm = baroAltitudeM?.let { (it * 10).roundToInt() },
)

/** Synthetic/demo points: their altitude behaves like barometric altitude. */
fun TrackPoint.toEntity(tripId: Long): TrackPointEntity =
    rawPoint(tripId, t, lat, lon, accuracy, speed, bearing, gpsAltitudeM = null, baroAltitudeM = altitude)

/**
 * UI/statistics read model: a raw trip combined with its current analysis. Field names match the
 * former single-table entity so screens stay simple; [startTime] is the movement start (analysis).
 */
data class TripView(
    val id: Long,
    val uuid: String,
    val recordedStart: Long,
    val startTime: Long,
    val endTime: Long?,
    val zoneId: String,
    val dayOfWeek: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** Effective mode: user override > detected > activity hint. */
    val mode: String,
    val userMode: String?,
    val trigger: String,
    val state: String,
    val distanceM: Double,
    val durationS: Double,
    val movingS: Double,
    val stoppedS: Double,
    val stopCount: Int,
    val waitS: Double,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    val avgMovingSpeed: Double,
    val maxSpeed: Double,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val startPlaceId: Long?,
    val endPlaceId: Long?,
    val routeId: Long?,
    val polyline: String?,
    val signature: String?,
    val bikeScore: Double?,
    val via: String?,
    val pointCount: Int,
    val matchedBy: String?,
    val excluded: Boolean,
    val isDemo: Boolean,
    val note: String?,
    val analysisVersion: Int?,
)

fun TripWithAnalysis.toView(): TripView {
    val a = analysis
    val t = trip
    val lt = localTimeInfo(t.recordedStart, runCatching { ZoneId.of(t.zoneId) }.getOrDefault(ZoneId.systemDefault()))
    return TripView(
        id = t.id, uuid = t.uuid, recordedStart = t.recordedStart,
        startTime = a?.movementStart ?: t.recordedStart,
        endTime = a?.movementEnd ?: t.recordedEnd,
        zoneId = t.zoneId,
        dayOfWeek = a?.dayOfWeek ?: lt.dayOfWeek,
        startMinuteOfDay = a?.startMinuteOfDay ?: lt.minuteOfDay,
        endMinuteOfDay = a?.endMinuteOfDay ?: lt.minuteOfDay,
        mode = t.userMode ?: a?.mode ?: t.activityMode ?: TransportMode.UNKNOWN.name,
        userMode = t.userMode,
        trigger = t.trigger,
        state = if (a == null && t.state == TripState.ANALYZED.name) TripState.PROCESSING.name else t.state,
        distanceM = a?.distanceM ?: 0.0,
        durationS = a?.durationS ?: 0.0,
        movingS = a?.movingS ?: 0.0,
        stoppedS = a?.stoppedS ?: 0.0,
        stopCount = a?.stopCount ?: 0,
        waitS = a?.waitS ?: 0.0,
        elevationGainM = a?.elevationGainM,
        elevationLossM = a?.elevationLossM,
        avgMovingSpeed = a?.avgMovingSpeed ?: 0.0,
        maxSpeed = a?.maxSpeed ?: 0.0,
        startLat = a?.startLat, startLon = a?.startLon, endLat = a?.endLat, endLon = a?.endLon,
        startPlaceId = a?.startPlaceId, endPlaceId = a?.endPlaceId, routeId = a?.routeId,
        polyline = a?.polyline, signature = a?.signature,
        bikeScore = a?.bikeScore, via = a?.via,
        pointCount = a?.pointCount ?: 0, matchedBy = a?.matchedBy,
        excluded = t.excluded, isDemo = t.isDemo, note = t.note,
        analysisVersion = a?.analysisVersion,
    )
}

fun TripView.transportMode(): TransportMode =
    runCatching { TransportMode.valueOf(mode) }.getOrDefault(TransportMode.UNKNOWN)

fun TripView.signaturePoints(): List<LatLon> = signature?.let { PolylineCodec.decode(it) } ?: emptyList()
fun TripView.polylinePoints(): List<LatLon> = polyline?.let { PolylineCodec.decode(it) } ?: emptyList()

/** Effective mode hint for the analysis (user override first, then activity recognition). */
fun TripEntity.modeHint(): TransportMode? =
    (userMode ?: activityMode)?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
        ?.takeIf { it != TransportMode.UNKNOWN }

/** Local calendar facts for an instant (DST-safe). */
data class LocalTimeInfo(val zoneOffsetMin: Int, val dayOfWeek: Int, val minuteOfDay: Int)

fun localTimeInfo(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTimeInfo {
    val z = Instant.ofEpochMilli(epochMs).atZone(zone)
    return LocalTimeInfo(z.offset.totalSeconds / 60, z.dayOfWeek.value, z.hour * 60 + z.minute)
}

fun TripEntity.zone(): ZoneId = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
