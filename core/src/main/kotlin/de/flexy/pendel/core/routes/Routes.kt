package de.flexy.pendel.core.routes

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.LocalProjection
import de.flexy.pendel.core.geo.Planar
import de.flexy.pendel.core.geo.PolylineOps
import de.flexy.pendel.core.geo.XY
import de.flexy.pendel.core.model.TransportMode

// ---------------------------------------------------------------- places

enum class PlaceKind { HOME, UNI, OTHER }

data class PlaceInput(val id: Long, val lat: Double, val lon: Double, val radiusM: Double, val kind: PlaceKind, val userNamed: Boolean)

data class Endpoint(val tripId: Long, val lat: Double, val lon: Double, val isStart: Boolean, val minuteOfDay: Int, val dayOfWeek: Int)

data class PlaceResult(
    /** Existing id, or a negative temporary id for new places. */
    val id: Long,
    val lat: Double,
    val lon: Double,
    val kind: PlaceKind,
    val count: Int,
)

data class PlaceAssignment(val places: List<PlaceResult>, val startPlace: Map<Long, Long>, val endPlace: Map<Long, Long>)

object PlaceClusterer {
    var radiusM = 150.0

    fun cluster(endpoints: List<Endpoint>, existing: List<PlaceInput>): PlaceAssignment {
        class Acc(val id: Long, var lat: Double, var lon: Double, val fixed: Boolean, var kind: PlaceKind, val radius: Double) {
            val members = ArrayList<Endpoint>()
        }
        val accs = existing.map { Acc(it.id, it.lat, it.lon, it.userNamed, it.kind, maxOf(it.radiusM, radiusM)) }.toMutableList()
        var tmpId = -1L
        val start = HashMap<Long, Long>()
        val end = HashMap<Long, Long>()
        for (e in endpoints) {
            var best: Acc? = null
            var bestD = Double.MAX_VALUE
            for (a in accs) {
                val d = GeoMath.distance(a.lat, a.lon, e.lat, e.lon)
                if (d <= a.radius && d < bestD) { best = a; bestD = d }
            }
            val target = best ?: Acc(tmpId--, e.lat, e.lon, false, PlaceKind.OTHER, radiusM).also { accs += it }
            target.members += e
            if (e.isStart) start[e.tripId] = target.id else end[e.tripId] = target.id
        }
        // recompute centroids of automatic places
        for (a in accs) if (!a.fixed && a.members.isNotEmpty()) {
            a.lat = a.members.sumOf { it.lat } / a.members.size
            a.lon = a.members.sumOf { it.lon } / a.members.size
        }
        // suggest HOME / UNI for places that are not user-defined
        val used = accs.filter { it.members.isNotEmpty() || it.fixed }
        if (used.none { it.kind == PlaceKind.HOME }) {
            used.filter { !it.fixed }.maxByOrNull { a -> a.members.count { it.isStart && it.minuteOfDay in 300..600 } }
                ?.takeIf { a -> a.members.count { it.isStart && it.minuteOfDay in 300..600 } >= 2 }
                ?.kind = PlaceKind.HOME
        }
        if (used.none { it.kind == PlaceKind.UNI }) {
            used.filter { !it.fixed && it.kind != PlaceKind.HOME }
                .maxByOrNull { a -> a.members.count { !it.isStart && it.minuteOfDay in 360..660 && it.dayOfWeek <= 5 } }
                ?.takeIf { a -> a.members.count { !it.isStart && it.minuteOfDay in 360..660 && it.dayOfWeek <= 5 } >= 2 }
                ?.kind = PlaceKind.UNI
        }
        return PlaceAssignment(
            places = used.map { PlaceResult(it.id, it.lat, it.lon, it.kind, it.members.size) },
            startPlace = start, endPlace = end,
        )
    }
}

// ---------------------------------------------------------------- route clustering

data class RouteKey(val origin: Long, val destination: Long, val mode: TransportMode)

data class TripSignature(
    val tripId: Long,
    val key: RouteKey,
    val startTime: Long,
    val signature: List<LatLon>,
)

data class RouteInput(val id: Long, val key: RouteKey, val signature: List<LatLon>)

data class RouteResult(
    /** Existing id or negative temporary id for a newly discovered route. */
    val id: Long,
    val key: RouteKey,
    val representativeTripId: Long,
    val signature: List<LatLon>,
    val tripIds: List<Long>,
)

object RouteSimilarity {
    var toleranceM = 35.0

    /** Share of A's points lying within [toleranceM] of polyline B. */
    fun coverage(a: List<XY>, b: List<XY>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var hit = 0
        for (p in a) if (Planar.distToPolyline(p, b, stopBelow = toleranceM) <= toleranceM) hit++
        return hit.toDouble() / a.size
    }

    /** Symmetric similarity in [0, 1]. A real detour on either side lowers it. */
    fun similarity(a: List<LatLon>, b: List<LatLon>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val proj = LocalProjection(a.first())
        val ax = a.map { proj.project(it) }
        val bx = b.map { proj.project(it) }
        return minOf(coverage(ax, bx), coverage(bx, ax))
    }
}

/**
 * Incremental, ID-stable route clustering.
 * Trips are processed chronologically; each one joins the most similar existing route of the same
 * origin/destination/mode if similarity ≥ [threshold], otherwise it founds a new route.
 * Afterwards every route's representative becomes its medoid.
 */
object RouteMatcher {
    var threshold = 0.85
    var medoidSample = 30

    fun cluster(trips: List<TripSignature>, existing: List<RouteInput>): List<RouteResult> {
        class R(val id: Long, val key: RouteKey, var sig: List<LatLon>, var repTrip: Long) {
            val members = ArrayList<TripSignature>()
        }
        val routes = existing.map { R(it.id, it.key, it.signature, -1) }.toMutableList()
        var tmp = -1L
        for (t in trips.sortedBy { it.startTime }) {
            if (t.signature.size < 2) continue
            var best: R? = null
            var bestSim = 0.0
            for (r in routes) {
                if (r.key != t.key) continue
                // quick length gate: > 35 % length difference can never reach the threshold
                val la = PolylineOps.length(t.signature)
                val lb = PolylineOps.length(r.sig)
                if (minOf(la, lb) / maxOf(la, lb) < 0.65) continue
                val s = RouteSimilarity.similarity(t.signature, r.sig)
                if (s > bestSim) { bestSim = s; best = r }
            }
            if (best != null && bestSim >= threshold) best.members += t
            else routes += R(tmp--, t.key, t.signature, t.tripId).also { it.members += t }
        }
        // medoid update (on the most recent trips to bound cost)
        for (r in routes) {
            if (r.members.isEmpty()) continue
            val sample = r.members.sortedByDescending { it.startTime }.take(medoidSample)
            if (sample.size <= 2) {
                if (r.repTrip < 0) r.repTrip = sample.first().tripId
                if (r.id < 0) r.sig = sample.first().signature
                continue
            }
            var bestScore = -1.0
            var bestT = sample.first()
            for (cand in sample) {
                var sum = 0.0
                for (o in sample) if (o !== cand) sum += RouteSimilarity.similarity(cand.signature, o.signature)
                if (sum > bestScore) { bestScore = sum; bestT = cand }
            }
            r.sig = bestT.signature
            r.repTrip = bestT.tripId
        }
        return routes.filter { it.members.isNotEmpty() }.map {
            RouteResult(it.id, it.key, it.repTrip, it.sig, it.members.map { m -> m.tripId })
        }
    }
}

/** Does a trip pass a point (e.g. an intersection)? */
object PassCounter {
    var passRadiusM = 25.0

    fun passes(point: LatLon, signature: List<LatLon>): Boolean {
        if (signature.isEmpty()) return false
        val proj = LocalProjection(point)
        val line = signature.map { proj.project(it) }
        return Planar.distToPolyline(XY(0.0, 0.0), line, stopBelow = passRadiusM) <= passRadiusM
    }
}
