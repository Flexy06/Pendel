package de.flexy.pendel.core.analysis

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.model.DetectedStop
import de.flexy.pendel.core.model.IntersectionCandidate
import de.flexy.pendel.core.model.IntersectionKind
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.model.WaitLevel
import de.flexy.pendel.core.routes.Endpoint
import de.flexy.pendel.core.routes.PassCounter
import de.flexy.pendel.core.routes.PlaceAssignment
import de.flexy.pendel.core.routes.PlaceClusterer
import de.flexy.pendel.core.routes.PlaceInput
import de.flexy.pendel.core.routes.RouteInput
import de.flexy.pendel.core.routes.RouteKey
import de.flexy.pendel.core.routes.RouteMatcher
import de.flexy.pendel.core.routes.RouteResult
import de.flexy.pendel.core.routes.TripSignature
import de.flexy.pendel.core.stops.HotspotLearner
import de.flexy.pendel.core.stops.StopRef
import de.flexy.pendel.core.stops.WaitEstimator

/**
 * Whole-history analysis: places → routes → learned intersections → wait events.
 * Pure function of its input; cheap enough (hundreds of trips) to run after every trip,
 * which keeps results consistent instead of drifting through incremental updates.
 */
object GlobalAnalysis {

    data class StopInput(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val startTime: Long,
        val durationS: Double,
        val kind: StopKind,
        val resumed: Boolean,
        val dayOfWeek: Int,
        val minuteOfDay: Int,
    )

    data class TripInput(
        val id: Long,
        val startTime: Long,
        val dayOfWeek: Int,
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val mode: TransportMode,
        val start: LatLon,
        val end: LatLon,
        val signature: List<LatLon>,
        val stops: List<StopInput>,
        /** Trip duration – used to estimate when an intersection was passed. */
        val durationS: Double = 0.0,
    )

    data class Input(
        val trips: List<TripInput>,
        val places: List<PlaceInput>,
        val routes: List<RouteInput>,
        /** Intersections from OSM (cached). */
        val osmIntersections: List<IntersectionCandidate>,
        /** Previously learned intersections – their ids are kept if still supported by data. */
        val learnedIntersections: List<IntersectionCandidate>,
    )

    data class LearnedIntersection(val id: Long, val lat: Double, val lon: Double, val tripCount: Int)

    data class WaitEventOut(
        val stopId: Long,
        val tripId: Long,
        val intersectionId: Long,
        val startTime: Long,
        val durationS: Double,
        val confidence: Double,
        val level: WaitLevel,
        val dayOfWeek: Int,
        val minuteOfDay: Int,
        val distanceToIntersectionM: Double? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
    )

    /** A trip passing an intersection (within the pass radius), with estimated local time. */
    data class PassOut(
        val intersectionId: Long,
        val tripId: Long,
        val passTime: Long,
        val dayOfWeek: Int,
        val minuteOfDay: Int,
    )

    data class Output(
        val places: PlaceAssignment,
        val routes: List<RouteResult>,
        val tripRoute: Map<Long, Long>,
        /** Learned intersections (negative id = new). */
        val learned: List<LearnedIntersection>,
        val waitEvents: List<WaitEventOut>,
        /** Every intersection (osm + learned) that at least one trip passes, with pass count. */
        val passCounts: Map<Long, Int>,
        /** Trip → total wait seconds (confidence ≥ 0.3). */
        val tripWait: Map<Long, Double>,
        /** Every (intersection, trip) pass – stored so the UI never recomputes geometry. */
        val passes: List<PassOut> = emptyList(),
    )

    private class BBox(var minLat: Double, var maxLat: Double, var minLon: Double, var maxLon: Double) {
        fun contains(lat: Double, lon: Double, marginDeg: Double) =
            lat >= minLat - marginDeg && lat <= maxLat + marginDeg && lon >= minLon - marginDeg * 1.5 && lon <= maxLon + marginDeg * 1.5
    }

    fun run(input: Input): Output {
        val trips = input.trips
        // 1) places
        val endpoints = trips.flatMap {
            listOf(
                Endpoint(it.id, it.start.lat, it.start.lon, true, it.startMinuteOfDay, it.dayOfWeek),
                Endpoint(it.id, it.end.lat, it.end.lon, false, it.endMinuteOfDay, it.dayOfWeek),
            )
        }
        val places = PlaceClusterer.cluster(endpoints, input.places)

        // 2) routes (per origin/destination/mode)
        // place groups (v2): routes are keyed by the group root, so "Mensa → Zuhause" and
        // "Uni → Zuhause" are compared with each other when Mensa belongs to Uni
        val parentOf = input.places.filter { it.parentId != null }.associate { it.id to it.parentId!! }
        fun root(id: Long): Long {
            var cur = id
            repeat(5) { cur = parentOf[cur] ?: return cur }
            return cur
        }
        val sigs = trips.mapNotNull { t ->
            val o = places.startPlace[t.id]?.let(::root) ?: return@mapNotNull null
            val d = places.endPlace[t.id]?.let(::root) ?: return@mapNotNull null
            if (o == d) return@mapNotNull null // round trip – no meaningful A→B route
            TripSignature(t.id, RouteKey(o, d, t.mode), t.startTime, t.signature)
        }
        val routes = RouteMatcher.cluster(sigs, input.routes)
        val tripRoute = HashMap<Long, Long>()
        routes.forEach { r -> r.tripIds.forEach { tripRoute[it] = r.id } }

        // 3) learned intersections from repeated stops
        val stopRefs = trips.flatMap { t -> t.stops.filter { it.kind == StopKind.STOP }.map { StopRef(t.id, it.lat, it.lon) } }
        val hotspots = HotspotLearner.learn(stopRefs, exclude = input.osmIntersections.map { LatLon(it.lat, it.lon) })
        var tmp = -1L
        val usedOld = HashSet<Long>()
        val learned = hotspots.map { h ->
            val old = input.learnedIntersections
                .filter { it.id !in usedOld }
                .minByOrNull { GeoMath.distance(it.lat, it.lon, h.lat, h.lon) }
                ?.takeIf { GeoMath.distance(it.lat, it.lon, h.lat, h.lon) <= 25.0 }
            val id = old?.id?.also { usedOld += it } ?: tmp--
            LearnedIntersection(id, h.lat, h.lon, h.tripCount)
        }
        val candidates = input.osmIntersections + learned.map { IntersectionCandidate(it.id, it.lat, it.lon, IntersectionKind.LEARNED) }

        // 4) passes per candidate (bbox pre-filter per trip)
        val boxes = trips.associate { t ->
            t.id to BBox(
                t.signature.minOfOrNull { it.lat } ?: 0.0, t.signature.maxOfOrNull { it.lat } ?: 0.0,
                t.signature.minOfOrNull { it.lon } ?: 0.0, t.signature.maxOfOrNull { it.lon } ?: 0.0,
            )
        }
        val margin = 0.0004 // ≈ 45 m latitude
        val passCounts = HashMap<Long, Int>()
        val stopCounts = HashMap<Long, Int>()
        val passList = ArrayList<PassOut>()
        for (c in candidates) {
            var passes = 0
            var stopsNear = 0
            val p = LatLon(c.lat, c.lon)
            for (t in trips) {
                if (boxes[t.id]?.contains(c.lat, c.lon, margin) != true) continue
                if (!PassCounter.passes(p, t.signature)) continue
                passes++
                val idx = t.signature.indices.minBy { GeoMath.distance(t.signature[it], p) }
                val offsetS = if (t.signature.size > 1) t.durationS * idx / (t.signature.size - 1) else 0.0
                passList += PassOut(
                    c.id, t.id, t.startTime + (offsetS * 1000).toLong(), t.dayOfWeek,
                    (t.startMinuteOfDay + (offsetS / 60).toInt()).coerceAtMost(1439),
                )
                if (t.stops.any { it.kind == StopKind.STOP && GeoMath.distance(it.lat, it.lon, c.lat, c.lon) <= 25.0 }) stopsNear++
            }
            if (passes > 0) {
                passCounts[c.id] = passes
                stopCounts[c.id] = stopsNear
            }
        }
        val relevant = candidates.filter { passCounts.containsKey(it.id) }

        // 5) wait events
        val waitEvents = ArrayList<WaitEventOut>()
        for (t in trips) for (s in t.stops) {
            if (s.kind != StopKind.STOP) continue
            val ds = DetectedStop(0, 0, s.startTime, s.startTime + (s.durationS * 1000).toLong(), s.lat, s.lon, 0.0, s.kind, s.resumed)
            val est = WaitEstimator.estimate(ds, relevant) { id ->
                val pc = passCounts[id] ?: 0
                if (pc < 3) 0.0 else (stopCounts[id] ?: 0).toDouble() / pc
            }
            val iid = est.intersectionId ?: continue
            waitEvents += WaitEventOut(
                s.id, t.id, iid, s.startTime, s.durationS, est.confidence, est.level, s.dayOfWeek, s.minuteOfDay,
                est.distanceM, s.lat, s.lon,
            )
        }
        val tripWait = waitEvents.filter { it.confidence >= 0.3 }.groupBy { it.tripId }
            .mapValues { e -> e.value.sumOf { it.durationS } }

        val keptIds = relevant.map { it.id }.toSet()
        return Output(
            places, routes, tripRoute, learned.filter { passCounts.containsKey(it.id) }, waitEvents, passCounts, tripWait,
            passList.filter { it.intersectionId in keptIds },
        )
    }
}
