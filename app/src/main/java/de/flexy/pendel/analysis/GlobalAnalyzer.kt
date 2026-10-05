package de.flexy.pendel.analysis

import android.util.Log
import androidx.room.withTransaction
import de.flexy.pendel.core.analysis.AnalysisVersion
import de.flexy.pendel.core.analysis.GlobalAnalysis
import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.model.IntersectionCandidate
import de.flexy.pendel.core.model.IntersectionKind
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.routes.PlaceInput
import de.flexy.pendel.core.routes.PlaceKind
import de.flexy.pendel.core.routes.RouteInput
import de.flexy.pendel.core.routes.RouteKey
import de.flexy.pendel.data.db.AnalysisRunEntity
import de.flexy.pendel.data.db.DetectionMethod
import de.flexy.pendel.data.db.IntersectionEntity
import de.flexy.pendel.data.db.IntersectionPassEntity
import de.flexy.pendel.data.db.IntersectionSource
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.PlaceEntity
import de.flexy.pendel.data.db.RouteEntity
import de.flexy.pendel.data.db.WaitEventEntity
import de.flexy.pendel.data.db.WaitEventKind
import de.flexy.pendel.data.localTimeInfo
import de.flexy.pendel.data.settings.SettingsRepository
import de.flexy.pendel.data.signaturePoints
import de.flexy.pendel.data.toView
import de.flexy.pendel.data.transportMode
import de.flexy.pendel.data.zone
import de.flexy.pendel.providers.OverpassIntersectionSource
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Whole-history analysis (places, routes, intersections, passes, probable waits) and persistence of
 * its results with stable ids. All results are derived and stamped with [AnalysisVersion.CURRENT];
 * user data (names, flags) is preserved. Heavy lifting happens in core's [GlobalAnalysis].
 */
class GlobalAnalyzer(
    private val db: PendelDatabase,
    private val settings: SettingsRepository,
) {
    suspend fun run() {
        val version = AnalysisVersion.CURRENT
        val started = System.currentTimeMillis()
        val runId = db.analysisRunDao().insert(AnalysisRunEntity(analysisVersion = version, startedAt = started, status = "RUNNING"))
        try {
            val count = runInternal(version)
            db.analysisRunDao().update(AnalysisRunEntity(runId, version, started, System.currentTimeMillis(), count, "OK", null))
        } catch (e: Exception) {
            db.analysisRunDao().update(AnalysisRunEntity(runId, version, started, System.currentTimeMillis(), 0, "FAILED", e.message))
            throw e
        } finally {
            db.analysisRunDao().prune()
        }
    }

    private suspend fun runInternal(version: Int): Int {
        val rows = db.tripDao().analyzed().filter { it.analysis != null }
        val trips = rows.map { it.toView() }
        val tripEntities = rows.associate { it.trip.id to it.trip }
        val stopsByTrip = db.stopDao().all().groupBy { it.tripId }
        val places = db.placeDao().all()
        val routes = db.routeDao().all()
        val intersections = db.intersectionDao().all()

        val input = GlobalAnalysis.Input(
            trips = trips.filter { it.signature != null && it.startLat != null && it.endLat != null }.map { t ->
                val zone = tripEntities.getValue(t.id).zone()
                GlobalAnalysis.TripInput(
                    id = t.id,
                    startTime = t.startTime,
                    dayOfWeek = t.dayOfWeek,
                    startMinuteOfDay = t.startMinuteOfDay,
                    endMinuteOfDay = t.endMinuteOfDay,
                    mode = t.transportMode(),
                    start = LatLon(t.startLat!!, t.startLon!!),
                    end = LatLon(t.endLat!!, t.endLon!!),
                    signature = t.signaturePoints(),
                    stops = stopsByTrip[t.id].orEmpty().map { s ->
                        val lt = localTimeInfo(s.startTime, zone)
                        GlobalAnalysis.StopInput(
                            s.id, s.lat, s.lon, s.startTime, s.durationS,
                            runCatching { StopKind.valueOf(s.kind) }.getOrDefault(StopKind.STOP),
                            s.resumed, lt.dayOfWeek, lt.minuteOfDay,
                        )
                    },
                    durationS = t.durationS,
                )
            },
            places = places.map {
                PlaceInput(it.id, it.lat, it.lon, it.radiusM, runCatching { PlaceKind.valueOf(it.kind) }.getOrDefault(PlaceKind.OTHER), it.userNamed)
            },
            routes = routes.map {
                RouteInput(it.id, RouteKey(it.originPlaceId, it.destPlaceId, runCatching { TransportMode.valueOf(it.mode) }.getOrDefault(TransportMode.UNKNOWN)), PolylineCodec.decode(it.signature))
            },
            osmIntersections = intersections.filter { it.source == IntersectionSource.OSM.name }.map { it.toCandidate() },
            learnedIntersections = intersections.filter { it.source == IntersectionSource.LEARNED.name }.map { it.toCandidate() },
        )
        val out = GlobalAnalysis.run(input)
        val sourceById = intersections.associate { it.id to it.source }

        db.withTransaction {
            // ---- places
            val placeId = HashMap<Long, Long>()
            var otherCounter = places.count { it.kind == PlaceKind.OTHER.name }
            for (p in out.places.places) {
                val existing = places.firstOrNull { it.id == p.id }
                if (existing != null) {
                    placeId[p.id] = existing.id
                    if (!existing.userNamed) {
                        db.placeDao().update(existing.copy(lat = p.lat, lon = p.lon, kind = p.kind.name, name = defaultPlaceName(p.kind, existing.name)))
                    }
                } else {
                    val name = when (p.kind) {
                        PlaceKind.HOME -> "Zuhause"
                        PlaceKind.UNI -> "Uni"
                        PlaceKind.OTHER -> "Ort ${++otherCounter}"
                    }
                    placeId[p.id] = db.placeDao().insert(PlaceEntity(name = name, lat = p.lat, lon = p.lon, kind = p.kind.name))
                }
            }
            val unusedPlaces = places.filter { pl -> !pl.userNamed && placeId.values.none { it == pl.id } }.map { it.id }
            if (unusedPlaces.isNotEmpty()) db.placeDao().delete(unusedPlaces)

            // ---- routes (ids and user names are stable)
            val routeId = HashMap<Long, Long>()
            val usedColors = routes.map { it.colorIndex }.toMutableSet()
            var letter = routes.size
            val viaOf = trips.associate { it.id to it.via }
            for (r in out.routes) {
                val origin = placeId[r.key.origin] ?: continue
                val dest = placeId[r.key.destination] ?: continue
                val existing = routes.firstOrNull { it.id == r.id }
                val via = viaOf[r.representativeTripId]?.substringBefore(",")
                if (existing != null) {
                    routeId[r.id] = existing.id
                    db.routeDao().update(
                        existing.copy(
                            originPlaceId = origin, destPlaceId = dest,
                            signature = PolylineCodec.encode(r.signature),
                            representativeTripId = r.representativeTripId,
                            name = if (existing.userNamed || via == null) existing.name else routeName(existing.name, via),
                            analysisVersion = version,
                        ),
                    )
                } else {
                    val color = (0..64).first { it !in usedColors }.also { usedColors += it }
                    val base = "Route ${letterFor(letter++)}"
                    routeId[r.id] = db.routeDao().insert(
                        RouteEntity(
                            name = if (via != null) routeName(base, via) else base,
                            colorIndex = color,
                            originPlaceId = origin, destPlaceId = dest, mode = r.key.mode.name,
                            signature = PolylineCodec.encode(r.signature),
                            representativeTripId = r.representativeTripId,
                            createdAt = System.currentTimeMillis(),
                            analysisVersion = version,
                        ),
                    )
                }
            }
            val goneRoutes = routes.map { it.id }.filter { id -> routeId.values.none { it == id } }
            if (goneRoutes.isNotEmpty()) db.routeDao().delete(goneRoutes)

            // ---- learned intersections (derived; user-named ones are kept)
            val interId = HashMap<Long, Long>()
            intersections.forEach { interId[it.id] = it.id }
            val keptLearned = HashSet<Long>()
            for (l in out.learned) {
                if (l.id > 0) {
                    keptLearned += l.id
                    intersections.firstOrNull { it.id == l.id }?.let { db.intersectionDao().update(it.copy(lat = l.lat, lon = l.lon)) }
                } else {
                    val nearestOsmName = intersections
                        .filter { it.source == IntersectionSource.OSM.name && it.name != null }
                        .minByOrNull { GeoMath.distance(it.lat, it.lon, l.lat, l.lon) }
                        ?.takeIf { GeoMath.distance(it.lat, it.lon, l.lat, l.lon) < 80 }
                        ?.name
                    val newId = db.intersectionDao().insert(
                        IntersectionEntity(
                            lat = l.lat, lon = l.lon,
                            name = nearestOsmName?.let { "Nahe $it" },
                            source = IntersectionSource.LEARNED.name,
                            kind = IntersectionKind.LEARNED.name,
                        ),
                    )
                    interId[l.id] = newId
                    keptLearned += newId
                }
            }
            val goneLearned = intersections
                .filter { it.source == IntersectionSource.LEARNED.name && it.id !in keptLearned && it.userName == null }
                .map { it.id }
            if (goneLearned.isNotEmpty()) db.intersectionDao().delete(goneLearned)

            // ---- passes (derived) + denormalized counts
            db.intersectionPassDao().deleteAll()
            db.intersectionPassDao().insertAll(out.passes.mapNotNull { p ->
                val iid = interId[p.intersectionId] ?: return@mapNotNull null
                IntersectionPassEntity(iid, p.tripId, p.passTime, p.dayOfWeek, p.minuteOfDay, version)
            })
            db.intersectionDao().resetPassCounts()
            for ((id, count) in out.passCounts) interId[id]?.let { db.intersectionDao().setPassCount(it, count) }

            // ---- trip assignments
            for (t in trips) {
                db.tripAnalysisDao().setAssignment(
                    t.id,
                    out.places.startPlace[t.id]?.let { placeId[it] },
                    out.places.endPlace[t.id]?.let { placeId[it] },
                    out.tripRoute[t.id]?.let { routeId[it] },
                    out.tripWait[t.id] ?: 0.0,
                )
            }

            // ---- probable intersection waits (fully re-derived)
            db.waitEventDao().deleteAll()
            db.waitEventDao().insertAll(out.waitEvents.mapNotNull { w ->
                val iid = interId[w.intersectionId] ?: return@mapNotNull null
                val method = if (w.intersectionId > 0 && sourceById[w.intersectionId] == IntersectionSource.OSM.name)
                    DetectionMethod.NEAR_OSM_FEATURE else DetectionMethod.NEAR_LEARNED_HOTSPOT
                WaitEventEntity(
                    stopId = w.stopId, tripId = w.tripId, intersectionId = iid,
                    routeId = out.tripRoute[w.tripId]?.let { routeId[it] },
                    startTime = w.startTime, durationS = w.durationS,
                    lat = w.lat, lon = w.lon, distanceToIntersectionM = w.distanceToIntersectionM,
                    confidence = w.confidence, level = w.level.name,
                    kind = WaitEventKind.PROBABLE_INTERSECTION_WAIT, detectionMethod = method,
                    dayOfWeek = w.dayOfWeek, minuteOfDay = w.minuteOfDay, analysisVersion = version,
                )
            })
        }
        Log.i(TAG, "Global analysis v$version: ${trips.size} trips, ${out.routes.size} routes, ${out.waitEvents.size} probable waits")
        return trips.size
    }

    /**
     * Downloads OSM intersections for the area of all trips if enabled and stale.
     * Transmits only a rounded bounding box. OSM rows are a reference cache, not personal data.
     */
    suspend fun refreshOsmIntersections(force: Boolean = false) {
        val s = settings.current()
        if (!s.overpassEnabled) return
        val pts = db.tripDao().analyzed().mapNotNull { it.analysis }.flatMap { PolylineCodec.decode(it.signature) }
        if (pts.isEmpty()) return
        // round outward to 0.01° (~1 km) – coarse on purpose
        val south = floor(pts.minOf { it.lat } * 100) / 100
        val west = floor(pts.minOf { it.lon } * 100) / 100
        val north = ceil(pts.maxOf { it.lat } * 100) / 100
        val east = ceil(pts.maxOf { it.lon } * 100) / 100
        val bboxKey = "%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.ROOT, south, west, north, east)
        val stale = System.currentTimeMillis() - s.overpassLastFetch > 30L * 24 * 3600 * 1000
        if (!force && bboxKey == s.overpassBbox && !stale) return

        val found = runCatching { OverpassIntersectionSource(s.overpassUrl).fetch(south, west, north, east) }
            .onFailure { Log.w(TAG, "Overpass failed", it) }
            .getOrNull() ?: return
        db.withTransaction {
            for (o in found) {
                val existing = db.intersectionDao().byOsmNode(o.osmNodeId)
                if (existing == null) {
                    db.intersectionDao().insert(
                        IntersectionEntity(
                            lat = o.lat, lon = o.lon, name = o.name, source = IntersectionSource.OSM.name,
                            kind = o.kind.name, osmNodeId = o.osmNodeId,
                        ),
                    )
                } else {
                    db.intersectionDao().update(existing.copy(lat = o.lat, lon = o.lon, name = o.name, kind = o.kind.name))
                }
            }
        }
        settings.setOverpassFetched(bboxKey, System.currentTimeMillis())
        Log.i(TAG, "Overpass: ${found.size} intersections in $bboxKey")
    }

    private fun IntersectionEntity.toCandidate() = IntersectionCandidate(
        id, lat, lon, runCatching { IntersectionKind.valueOf(kind) }.getOrDefault(IntersectionKind.JUNCTION),
    )

    private fun defaultPlaceName(kind: PlaceKind, current: String): String = when (kind) {
        PlaceKind.HOME -> "Zuhause"
        PlaceKind.UNI -> "Uni"
        PlaceKind.OTHER -> if (current == "Zuhause" || current == "Uni") "Ort" else current
    }

    private fun routeName(current: String, via: String): String = current.substringBefore(" · ") + " · " + via

    private fun letterFor(i: Int): String {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return if (i < 26) letters[i].toString() else letters[i / 26 - 1].toString() + letters[i % 26]
    }

    companion object {
        private const val TAG = "GlobalAnalyzer"
    }
}
