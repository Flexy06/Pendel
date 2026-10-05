package de.flexy.pendel.core.model

import de.flexy.pendel.core.geo.LatLon

enum class TransportMode(val maxSpeedMs: Double) {
    BICYCLE(20.0),
    WALK(4.0),
    CAR(60.0),
    TRANSIT(45.0),
    UNKNOWN(60.0);
}

/** A single location fix. [speed]/[bearing]/[altitude] are null when the sensor did not provide them. */
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float? = null,
    val bearing: Float? = null,
    val altitude: Double? = null,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

enum class StopKind {
    /** Stationary phase at the very start or end of a trip (unlocking bike, parking). */
    TERMINAL,
    /** Stationary longer than a plausible wait (shop, coffee …). */
    PAUSE,
    /** Regular stop during the ride – candidate for a wait event. */
    STOP,
}

data class DetectedStop(
    val startIndex: Int,
    val endIndex: Int,
    val startTime: Long,
    val endTime: Long,
    val lat: Double,
    val lon: Double,
    /** Distance travelled since trip start when the stop began. */
    val alongM: Double,
    val kind: StopKind,
    /** True if the rider moved on after this stop (false if trip ended here). */
    val resumed: Boolean,
) {
    val durationS: Double get() = (endTime - startTime) / 1000.0
    val latLon: LatLon get() = LatLon(lat, lon)
}

enum class IntersectionKind(val prior: Double) {
    TRAFFIC_SIGNALS(0.95),
    CROSSING_SIGNALS(0.90),
    STOP_SIGN(0.80),
    GIVE_WAY(0.70),
    CROSSING(0.60),
    LEARNED(0.60),
    JUNCTION(0.50),
}

data class IntersectionCandidate(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val kind: IntersectionKind,
)

enum class WaitLevel { LIKELY, POSSIBLE, UNCLEAR }

data class WaitEstimate(
    val intersectionId: Long?,
    val distanceM: Double?,
    val confidence: Double,
    val level: WaitLevel,
)
