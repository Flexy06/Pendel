package de.flexy.pendel.core.stops

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.LocalProjection
import de.flexy.pendel.core.geo.Planar
import de.flexy.pendel.core.model.DetectedStop
import de.flexy.pendel.core.model.IntersectionCandidate
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.model.TrackPoint
import de.flexy.pendel.core.model.WaitEstimate
import de.flexy.pendel.core.model.WaitLevel
import kotlin.math.exp

/**
 * Anchor-based stop detection with hysteresis.
 *
 * GNSS positions drift a few meters while standing still, which makes naive
 * "speed == 0" detection flicker. We therefore open a stop when speed drops below
 * [enterSpeed], remember the position as anchor and keep the stop open while the rider is
 * either still slow ([exitSpeed]) or has not left the anchor radius.
 */
object StopDetector {
    var enterSpeed = 0.8      // m/s ≈ 2.9 km/h
    var exitSpeed = 1.6       // m/s ≈ 5.8 km/h
    var anchorRadius = 10.0   // m, enlarged by reported accuracy
    var maxRadius = 25.0      // m – beyond that it's movement, no matter how slow
    var minDurationS = 4.0
    var mergeGapS = 8.0
    var mergeDistM = 15.0
    var terminalDistM = 60.0
    var pauseThresholdS = 300.0

    fun detect(points: List<TrackPoint>, speeds: DoubleArray): List<DetectedStop> {
        val n = points.size
        if (n < 3) return emptyList()

        // cumulative distance along the track
        val along = DoubleArray(n)
        for (i in 1 until n) along[i] = along[i - 1] +
            GeoMath.distance(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)

        data class Raw(val s: Int, val e: Int)
        val raws = ArrayList<Raw>()
        var i = 0
        while (i < n) {
            if (speeds[i] < enterSpeed) {
                val anchor = points[i]
                var j = i + 1
                var last = i
                while (j < n) {
                    val p = points[j]
                    val d = GeoMath.distance(anchor.lat, anchor.lon, p.lat, p.lon)
                    if (d > maxRadius) break
                    val radius = maxOf(anchorRadius, p.accuracy.toDouble())
                    if (speeds[j] < exitSpeed || d < radius) {
                        last = j; j++
                    } else break
                }
                raws += Raw(i, last)
                i = last + 1
            } else i++
        }

        // merge stop-and-go fragments (creeping forward at a red light)
        val merged = ArrayList<Raw>()
        for (r in raws) {
            val prev = merged.lastOrNull()
            if (prev != null) {
                val gap = (points[r.s].t - points[prev.e].t) / 1000.0
                val dist = GeoMath.distance(points[prev.s].lat, points[prev.s].lon, points[r.s].lat, points[r.s].lon)
                if (gap <= mergeGapS && dist <= mergeDistM) {
                    merged[merged.size - 1] = Raw(prev.s, r.e)
                    continue
                }
            }
            merged += r
        }

        val totalDist = along[n - 1]
        val result = ArrayList<DetectedStop>()
        for (r in merged) {
            val startT = points[r.s].t
            val endT = points[r.e].t
            val dur = (endT - startT) / 1000.0
            if (dur < minDurationS) continue
            val seg = points.subList(r.s, r.e + 1)
            val lat = seg.sumOf { it.lat } / seg.size
            val lon = seg.sumOf { it.lon } / seg.size
            val resumed = r.e < n - 1 && (totalDist - along[r.e]) > terminalDistM
            val kind = when {
                along[r.s] <= terminalDistM || !resumed -> StopKind.TERMINAL
                dur > pauseThresholdS -> StopKind.PAUSE
                else -> StopKind.STOP
            }
            result += DetectedStop(r.s, r.e, startT, endT, lat, lon, along[r.s], kind, resumed)
        }
        return result
    }
}

/**
 * Turns a physical stop into a probabilistic "wait at intersection" estimate.
 * GPS cannot tell *why* we stopped – the confidence reflects how plausible a wait is.
 */
object WaitEstimator {
    var searchRadiusM = 40.0
    var sigmaM = 15.0

    fun durationScore(s: Double): Double = when {
        s < 3.0 -> 0.2
        s < 6.0 -> 0.2 + 0.8 * (s - 3.0) / 3.0
        s <= 150.0 -> 1.0
        s <= 300.0 -> 1.0 - 0.7 * (s - 150.0) / 150.0
        else -> 0.05
    }

    /**
     * @param recurrence stop probability observed at a candidate (0..1), from history.
     */
    fun estimate(
        stop: DetectedStop,
        candidates: List<IntersectionCandidate>,
        recurrence: (Long) -> Double = { 0.0 },
    ): WaitEstimate {
        val dScore = durationScore(stop.durationS)
        val resume = if (stop.resumed) 1.0 else 0.3
        var bestConf = 0.0
        var bestId: Long? = null
        var bestDist: Double? = null
        for (c in candidates) {
            val d = GeoMath.distance(stop.lat, stop.lon, c.lat, c.lon)
            if (d > searchRadiusM) continue
            val pDist = exp(-(d * d) / (2 * sigmaM * sigmaM))
            val prior = c.kind.prior
            val rec = recurrence(c.id).coerceIn(0.0, 1.0)
            val conf = pDist * (0.6 * prior + 0.4 * maxOf(prior, rec)) * dScore * resume
            if (conf > bestConf) {
                bestConf = conf; bestId = c.id; bestDist = d
            }
        }
        val level = when {
            bestConf >= 0.6 -> WaitLevel.LIKELY
            bestConf >= 0.3 -> WaitLevel.POSSIBLE
            else -> WaitLevel.UNCLEAR
        }
        return WaitEstimate(bestId, bestDist, bestConf.coerceIn(0.0, 1.0), level)
    }
}

data class StopRef(val tripId: Long, val lat: Double, val lon: Double)

data class Hotspot(val lat: Double, val lon: Double, val tripCount: Int, val stopCount: Int)

/**
 * Learns intersections from the user's own stops (DBSCAN). Works fully offline:
 * places where you repeatedly stop on different trips are almost always lights/junctions.
 */
object HotspotLearner {
    fun learn(
        stops: List<StopRef>,
        epsM: Double = 20.0,
        minTrips: Int = 3,
        exclude: List<LatLon> = emptyList(),
        excludeRadiusM: Double = 25.0,
    ): List<Hotspot> {
        if (stops.isEmpty()) return emptyList()
        val proj = LocalProjection(LatLon(stops[0].lat, stops[0].lon))
        val xy = stops.map { proj.project(it.lat, it.lon) }
        val n = stops.size
        // simple spatial grid for neighbor queries
        val cell = epsM
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        for (i in 0 until n) {
            val cx = Math.floorDiv(xy[i].x.toInt(), cell.toInt())
            val cy = Math.floorDiv(xy[i].y.toInt(), cell.toInt())
            grid.getOrPut(key(cx, cy)) { ArrayList() }.add(i)
        }
        fun neighbors(i: Int): List<Int> {
            val cx = Math.floorDiv(xy[i].x.toInt(), cell.toInt())
            val cy = Math.floorDiv(xy[i].y.toInt(), cell.toInt())
            val out = ArrayList<Int>()
            for (dx in -1..1) for (dy in -1..1) {
                grid[key(cx + dx, cy + dy)]?.forEach { j -> if (Planar.dist(xy[i], xy[j]) <= epsM) out += j }
            }
            return out
        }
        fun tripCount(idx: Collection<Int>) = idx.map { stops[it].tripId }.toSet().size

        val label = IntArray(n) { -1 } // -1 unvisited, -2 noise, >=0 cluster
        var clusterId = 0
        for (i in 0 until n) {
            if (label[i] != -1) continue
            val nb = neighbors(i)
            if (tripCount(nb) < minTrips) {
                label[i] = -2; continue
            }
            val cid = clusterId++
            label[i] = cid
            val queue = ArrayDeque(nb)
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (label[j] == -2) label[j] = cid
                if (label[j] != -1) continue
                label[j] = cid
                val nb2 = neighbors(j)
                if (tripCount(nb2) >= minTrips) queue.addAll(nb2)
            }
        }
        val result = ArrayList<Hotspot>()
        for (c in 0 until clusterId) {
            val members = (0 until n).filter { label[it] == c }
            if (members.isEmpty()) continue
            val lat = members.sumOf { stops[it].lat } / members.size
            val lon = members.sumOf { stops[it].lon } / members.size
            if (exclude.any { GeoMath.distance(it.lat, it.lon, lat, lon) <= excludeRadiusM }) continue
            result += Hotspot(lat, lon, tripCount(members), members.size)
        }
        return result
    }
}
