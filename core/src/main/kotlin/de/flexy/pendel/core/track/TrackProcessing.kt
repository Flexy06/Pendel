package de.flexy.pendel.core.track

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.geo.PolylineOps
import de.flexy.pendel.core.model.DetectedStop
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.model.TrackPoint
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.core.stops.StopDetector

/** Central, documented tuning constants. */
object CoreConfig {
    const val MAX_ACCURACY_M = 35f
    const val RELAXED_ACCURACY_M = 60f
    const val POLYLINE_TOLERANCE_M = 5.0
    const val SIGNATURE_SPACING_M = 25.0
    const val ELEVATION_HYSTERESIS_M = 3.0
    const val MIN_TRIP_DISTANCE_M = 300.0
    const val MIN_TRIP_DURATION_S = 90.0
}

object TrackCleaner {
    /**
     * Sorts, de-duplicates and removes implausible fixes.
     * - accuracy filter (relaxed automatically if it would remove almost everything)
     * - jump filter: implied speed above the mode maximum. If several consecutive fixes are
     *   rejected, the *previous* accepted fix was probably the outlier → re-anchor.
     */
    fun clean(raw: List<TrackPoint>, mode: TransportMode = TransportMode.UNKNOWN): List<TrackPoint> {
        if (raw.isEmpty()) return raw
        val sorted = raw.sortedBy { it.t }.distinctBy { it.t }
        var acc = sorted.filter { it.accuracy <= CoreConfig.MAX_ACCURACY_M }
        if (acc.size < sorted.size / 3) acc = sorted.filter { it.accuracy <= CoreConfig.RELAXED_ACCURACY_M }
        if (acc.size < 2) return acc

        val maxSpeed = mode.maxSpeedMs * 1.5
        val out = ArrayList<TrackPoint>(acc.size)
        out += acc[0]
        var rejectedInRow = 0
        for (i in 1 until acc.size) {
            val prev = out.last()
            val p = acc[i]
            val dt = (p.t - prev.t) / 1000.0
            if (dt <= 0) continue
            val d = GeoMath.distance(prev.lat, prev.lon, p.lat, p.lon)
            // tolerate the combined accuracy radius before judging speed
            val effective = (d - (prev.accuracy + p.accuracy) * 0.5).coerceAtLeast(0.0)
            if (effective / dt <= maxSpeed) {
                out += p
                rejectedInRow = 0
            } else {
                rejectedInRow++
                if (rejectedInRow >= 3) {
                    out[out.size - 1] = p
                    rejectedInRow = 0
                }
            }
        }
        return out
    }

    /**
     * Per-point speed in m/s. Prefers the GNSS Doppler speed (much less noisy than position
     * derivatives) and falls back to a centered difference.
     */
    fun speeds(points: List<TrackPoint>): DoubleArray {
        val n = points.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val p = points[i]
            val doppler = p.speed
            if (doppler != null && !doppler.isNaN() && p.accuracy <= 25f) {
                out[i] = doppler.toDouble()
                continue
            }
            val a = points[maxOf(0, i - 1)]
            val b = points[minOf(n - 1, i + 1)]
            val dt = (b.t - a.t) / 1000.0
            out[i] = if (dt > 0) GeoMath.distance(a.lat, a.lon, b.lat, b.lon) / dt else 0.0
        }
        return out
    }
}

object ModeClassifier {
    /** Speed-based guess; an activity-recognition hint wins when available. */
    fun classify(movingSpeeds: List<Double>, hint: TransportMode?): TransportMode {
        if (hint != null && hint != TransportMode.UNKNOWN) return hint
        if (movingSpeeds.size < 10) return TransportMode.UNKNOWN
        val sorted = movingSpeeds.sorted()
        val p85 = Descriptive.quantileSorted(sorted, 0.85)
        return when {
            p85 < 2.6 -> TransportMode.WALK
            p85 < 11.5 -> TransportMode.BICYCLE
            else -> TransportMode.CAR
        }
    }
}

data class TripMetrics(
    val startTime: Long,
    val endTime: Long,
    val distanceM: Double,
    val durationS: Double,
    val movingS: Double,
    val stoppedS: Double,
    val avgMovingSpeedMs: Double,
    val maxSpeedMs: Double,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
)

data class TripAnalysis(
    val cleanedPoints: List<TrackPoint>,
    val metrics: TripMetrics,
    val stops: List<DetectedStop>,
    val mode: TransportMode,
    /** Display geometry (Douglas-Peucker 5 m), encoded polyline precision 5. */
    val polyline: String,
    /** Clustering signature (resampled every 25 m), encoded polyline precision 5. */
    val signature: String,
)

object TripAnalyzer {
    fun analyze(raw: List<TrackPoint>, modeHint: TransportMode? = null): TripAnalysis? {
        val full = TrackCleaner.clean(raw, modeHint ?: TransportMode.UNKNOWN)
        if (full.size < 3) return null
        // Trim terminal stationary phases (unlocking/locking the bike, auto-stop wait time):
        // trip duration is measured from first to last movement so trips stay comparable.
        val fullStops = StopDetector.detect(full, TrackCleaner.speeds(full))
        var from = 0
        var to = full.size - 1
        fullStops.firstOrNull()?.let { if (it.kind == StopKind.TERMINAL && it.resumed) from = it.endIndex }
        fullStops.lastOrNull()?.let { if (it.kind == StopKind.TERMINAL && !it.resumed) to = it.startIndex }
        if (to - from < 2) { from = 0; to = full.size - 1 }
        val clean = full.subList(from, to + 1).toList()
        val speeds = TrackCleaner.speeds(clean)
        val stops = StopDetector.detect(clean, speeds)

        // Distance: ignore jitter inside stops.
        val inStop = BooleanArray(clean.size)
        for (s in stops) for (i in s.startIndex..s.endIndex) inStop[i] = true
        var distance = 0.0
        for (i in 1 until clean.size) {
            if (inStop[i] && inStop[i - 1]) continue
            distance += GeoMath.distance(clean[i - 1].lat, clean[i - 1].lon, clean[i].lat, clean[i].lon)
        }

        val start = clean.first().t
        val end = clean.last().t
        val duration = (end - start) / 1000.0
        val stopped = stops.sumOf { it.durationS }
        val moving = (duration - stopped).coerceAtLeast(1.0)
        val movingSpeeds = speeds.filterIndexed { i, _ -> !inStop[i] }.filter { it > 0.5 }
        val maxSpeed = if (movingSpeeds.isEmpty()) 0.0
        else Descriptive.quantileSorted(movingSpeeds.sorted(), 0.95)
        val (gain, loss) = elevation(clean)

        val mode = ModeClassifier.classify(movingSpeeds, modeHint)
        val latLons = clean.map { LatLon(it.lat, it.lon) }
        val simplified = PolylineOps.simplify(latLons, CoreConfig.POLYLINE_TOLERANCE_M)
        val signature = PolylineOps.resample(simplified, CoreConfig.SIGNATURE_SPACING_M)

        return TripAnalysis(
            cleanedPoints = clean,
            metrics = TripMetrics(
                startTime = start,
                endTime = end,
                distanceM = distance,
                durationS = duration,
                movingS = moving,
                stoppedS = stopped,
                avgMovingSpeedMs = distance / moving,
                maxSpeedMs = maxSpeed,
                elevationGainM = gain,
                elevationLossM = loss,
            ),
            stops = stops,
            mode = mode,
            polyline = PolylineCodec.encode(simplified),
            signature = PolylineCodec.encode(signature),
        )
    }

    /** Cumulative gain/loss with hysteresis so that sensor noise does not add up. */
    fun elevation(points: List<TrackPoint>): Pair<Double?, Double?> {
        val alts = points.mapNotNull { it.altitude }
        if (alts.size < points.size / 2 || alts.size < 5) return null to null
        // light moving-average smoothing (window 5)
        val smooth = alts.indices.map { i ->
            val from = maxOf(0, i - 2)
            val to = minOf(alts.size - 1, i + 2)
            (from..to).sumOf { alts[it] } / (to - from + 1)
        }
        var gain = 0.0
        var loss = 0.0
        var ref = smooth.first()
        for (a in smooth) {
            val d = a - ref
            if (d >= CoreConfig.ELEVATION_HYSTERESIS_M) {
                gain += d; ref = a
            } else if (d <= -CoreConfig.ELEVATION_HYSTERESIS_M) {
                loss -= d; ref = a
            }
        }
        return gain to loss
    }

    fun isValidTrip(m: TripMetrics): Boolean =
        m.distanceM >= CoreConfig.MIN_TRIP_DISTANCE_M && m.durationS >= CoreConfig.MIN_TRIP_DURATION_S

    /** Only stops that can be waits. */
    fun waitCandidates(stops: List<DetectedStop>) = stops.filter { it.kind == StopKind.STOP }
}
