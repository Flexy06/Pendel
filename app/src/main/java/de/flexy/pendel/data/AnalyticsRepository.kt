package de.flexy.pendel.data

import de.flexy.pendel.core.insights.Insight
import de.flexy.pendel.core.insights.InsightEngine
import de.flexy.pendel.core.insights.IntersectionStats
import de.flexy.pendel.core.insights.IntersectionStatsCalculator
import de.flexy.pendel.core.insights.PassObs
import de.flexy.pendel.core.insights.RouteStats
import de.flexy.pendel.core.insights.RouteStatsCalculator
import de.flexy.pendel.core.insights.TripStat
import de.flexy.pendel.core.insights.WaitObs
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.optimize.Objective
import de.flexy.pendel.core.optimize.RouteMetrics
import de.flexy.pendel.core.optimize.RouteScorer
import de.flexy.pendel.core.optimize.ScoredRoute
import de.flexy.pendel.core.optimize.Weights
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.core.stats.KernelTimeModel
import de.flexy.pendel.core.stats.TimedObs
import de.flexy.pendel.data.db.IntersectionEntity
import de.flexy.pendel.data.db.IntersectionPassEntity
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.PlaceEntity
import de.flexy.pendel.data.db.RouteEntity
import de.flexy.pendel.data.db.WaitEventEntity
import de.flexy.pendel.data.settings.Settings
import de.flexy.pendel.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class OdPair(val originId: Long, val destId: Long, val mode: TransportMode) {
    val key: String get() = "$originId-$destId-${mode.name}"
}

data class OdInfo(
    val od: OdPair,
    val origin: PlaceEntity?,
    val dest: PlaceEntity?,
    val routes: List<RouteEntity>,
    val trips: List<TripView>,
) {
    val label: String get() = "${origin?.name ?: "?"} → ${dest?.name ?: "?"}"
}

/**
 * A "Strecke": two places connected in both directions (e.g. Zuhause ↔ Uni) for one transport mode.
 * Different destinations are different corridors – their routes are never compared with each other.
 */
data class CorridorInfo(
    val key: String,
    val placeA: PlaceEntity?,
    val placeB: PlaceEntity?,
    val mode: TransportMode,
    /** Both directions, outbound (away from home) first. */
    val directions: List<OdInfo>,
    val isPrimary: Boolean,
    val label: String,
) {
    val tripCount: Int get() = directions.sumOf { it.trips.size }
    val trips: List<TripView> get() = directions.flatMap { it.trips }
    val outbound: OdInfo? get() = directions.firstOrNull()
}

fun corridorKey(a: Long, b: Long, mode: TransportMode): String = "${minOf(a, b)}-${maxOf(a, b)}-${mode.name}"

data class IntersectionInfo(val entity: IntersectionEntity, val stats: IntersectionStats)

data class Kpis(
    val primary: CorridorInfo?,
    val mainOd: OdInfo?,
    val medianMainS: Double?,
    val mainN: Int,
    val fastestMain: TripView?,
    val weekDistanceM: Double,
    val weekWaitS: Double,
    val weekTrips: Int,
    val todayTrips: List<TripView>,
)

data class Recommendation(
    val od: OdInfo,
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val objective: Objective,
    val ranking: List<ScoredRoute>,
    /** Kernel-weighted effective sample size per route at this time. */
    val ess: Map<Long, Double>,
)

data class ProfilePoint(val minute: Int, val medianS: Double, val ess: Double)

data class AnalyticsSnapshot(
    val allTrips: List<TripView> = emptyList(),
    val statTrips: List<TripView> = emptyList(),
    val places: Map<Long, PlaceEntity> = emptyMap(),
    val routes: Map<Long, RouteEntity> = emptyMap(),
    val routeStats: Map<Long, RouteStats> = emptyMap(),
    val ods: List<OdInfo> = emptyList(),
    /** Destinations ("Strecken"), primary first. */
    val corridors: List<CorridorInfo> = emptyList(),
    /** Nearby places that probably belong together (child → parent), e.g. Mensa → Uni. */
    val placeGroupSuggestions: List<Pair<PlaceEntity, PlaceEntity>> = emptyList(),
    val intersections: List<IntersectionInfo> = emptyList(),
    val waitEvents: List<WaitEventEntity> = emptyList(),
    val kpis: Kpis? = null,
    val settings: Settings = Settings(),
    val loaded: Boolean = false,
) {
    fun routeColorIndex(routeId: Long?): Int = routeId?.let { routes[it]?.colorIndex } ?: -1
    val primary: CorridorInfo? get() = corridors.firstOrNull { it.isPrimary }
    fun corridorOf(od: OdPair): CorridorInfo? = corridors.firstOrNull { c -> c.directions.any { it.od == od } }
}

/**
 * Read side: derives all statistics from Room flows. Data volume is small (hundreds of trips),
 * so everything is recomputed on change on a background dispatcher – no stale caches.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AnalyticsRepository(
    private val db: PendelDatabase,
    private val settingsRepo: SettingsRepository,
    scope: CoroutineScope,
) {
    val snapshot: StateFlow<AnalyticsSnapshot> = combine(
        db.tripDao().observeAnalyzed(),
        db.routeDao().observeAll(),
        db.placeDao().observeAll(),
        db.intersectionDao().observeRelevant(),
        db.waitEventDao().observeAll(),
    ) { trips, routes, places, inters, waits ->
        Raw(trips.filter { it.analysis != null }.map { it.toView() }, routes, places, inters, waits, emptyList())
    }
        .combine(db.intersectionPassDao().observeAll()) { raw, passes -> raw.copy(passes = passes) }
        .combine(settingsRepo.settings) { raw, s -> raw to s }
        .debounce(150)
        .map { (raw, s) -> build(raw, s) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, AnalyticsSnapshot())

    /** Time-dependent insights per OD pair – the expensive part, computed separately. */
    val insights: StateFlow<Map<String, List<Insight>>> = snapshot
        .map { it.ods }
        .distinctUntilChanged()
        .mapLatest { ods ->
            ods.associate { od ->
                val obs = od.routes.associate { r ->
                    r.id to od.trips.filter { it.routeId == r.id }.map { TimedObs(it.startMinuteOfDay, it.dayOfWeek, it.durationS, it.id) }
                }.filterValues { it.size >= 3 }
                od.od.key to InsightEngine.find(obs)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private data class Raw(
        val trips: List<TripView>,
        val routes: List<RouteEntity>,
        val places: List<PlaceEntity>,
        val intersections: List<IntersectionEntity>,
        val waits: List<WaitEventEntity>,
        val passes: List<IntersectionPassEntity>,
    )

    private fun build(raw: Raw, s: Settings): AnalyticsSnapshot {
        val statTrips = raw.trips.filter { !it.excluded }
        val tripStats = statTrips.filter { it.routeId != null }.map { it.toStat() }
        val routeStats = RouteStatsCalculator.computeAll(tripStats).associateBy { it.routeId }
        val places = raw.places.associateBy { it.id }
        val routes = raw.routes.associateBy { it.id }

        val ods = raw.routes.groupBy { OdPair(it.originPlaceId, it.destPlaceId, runCatching { TransportMode.valueOf(it.mode) }.getOrDefault(TransportMode.UNKNOWN)) }
            .map { (od, rs) ->
                val ids = rs.map { it.id }.toSet()
                OdInfo(od, places[od.originId], places[od.destId], rs.sortedBy { it.id }, statTrips.filter { it.routeId in ids })
            }
            .sortedByDescending { it.trips.size }
        val corridors = buildCorridors(ods, s.primaryCorridor)

        val intersections = computeIntersections(raw.intersections, raw.waits, raw.passes, statTrips)
        return AnalyticsSnapshot(
            allTrips = raw.trips,
            statTrips = statTrips,
            places = places,
            routes = routes,
            routeStats = routeStats,
            ods = ods,
            corridors = corridors,
            placeGroupSuggestions = groupSuggestions(raw.places, s.dismissedPlaceGroups),
            intersections = intersections,
            waitEvents = raw.waits,
            kpis = kpis(statTrips, corridors.firstOrNull { it.isPrimary }),
            settings = s,
            loaded = true,
        )
    }

    /** Statistics per intersection from stored (derived) passes and probable waits – no geometry here. */
    private fun computeIntersections(
        inters: List<IntersectionEntity>,
        waits: List<WaitEventEntity>,
        passes: List<IntersectionPassEntity>,
        trips: List<TripView>,
    ): List<IntersectionInfo> {
        val included = trips.map { it.id }.toHashSet()
        val waitsBy = waits.filter { it.tripId in included }.groupBy { it.intersectionId }
        val passesBy = passes.filter { it.tripId in included }.groupBy { it.intersectionId }
        return inters.map { x ->
            val p = passesBy[x.id].orEmpty().map { PassObs(it.tripId, it.dayOfWeek, it.minuteOfDay) }
            val w = waitsBy[x.id].orEmpty().map { WaitObs(it.tripId, it.durationS, it.confidence, it.dayOfWeek, it.minuteOfDay) }
            IntersectionInfo(x, IntersectionStatsCalculator.compute(p, w))
        }.filter { it.stats.passes > 0 }.sortedByDescending { it.stats.totalWaitS }
    }

    /**
     * A non-home/uni place within 400 m of the Uni or Zuhause place is very likely part of it
     * (Mensa, Bibliothek, Fahrradkeller). Suggested once; the user confirms or dismisses.
     */
    private fun groupSuggestions(places: List<PlaceEntity>, dismissed: Set<String>): List<Pair<PlaceEntity, PlaceEntity>> {
        val anchors = places.filter { (it.kind == "UNI" || it.kind == "HOME") && it.parentPlaceId == null }
        return places.filter { it.kind == "OTHER" && it.parentPlaceId == null }.mapNotNull { p ->
            anchors.filter { it.id != p.id }
                .minByOrNull { de.flexy.pendel.core.geo.GeoMath.distance(it.lat, it.lon, p.lat, p.lon) }
                ?.takeIf { de.flexy.pendel.core.geo.GeoMath.distance(it.lat, it.lon, p.lat, p.lon) <= 400.0 && "${p.id}-${it.id}" !in dismissed }
                ?.let { p to it }
        }
    }

    /** Groups both directions between two places into one corridor; resolves the primary one. */
    private fun buildCorridors(ods: List<OdInfo>, primaryKey: String?): List<CorridorInfo> {
        val groups = ods.groupBy { corridorKey(it.od.originId, it.od.destId, it.od.mode) }
        val modesPerPair = ods.groupBy { "${minOf(it.od.originId, it.od.destId)}-${maxOf(it.od.originId, it.od.destId)}" }
            .mapValues { e -> e.value.map { it.od.mode }.toSet().size }
        val list = groups.map { (key, dirs) ->
            val any = dirs.first()
            val places = listOfNotNull(any.origin, any.dest)
            val home = places.firstOrNull { it.kind == "HOME" }
            val a = home ?: places.minByOrNull { it.id }
            val b = places.firstOrNull { it != a }
            // outbound = leaving home (or the more frequent direction)
            val sorted = dirs.sortedWith(compareByDescending<OdInfo> { it.od.originId == a?.id }.thenByDescending { it.trips.size })
            val pairKey = "${minOf(any.od.originId, any.od.destId)}-${maxOf(any.od.originId, any.od.destId)}"
            val modeSuffix = if ((modesPerPair[pairKey] ?: 1) > 1) " (${modeName(any.od.mode)})" else ""
            val label = (if (home != null) (b?.name ?: "?") else "${a?.name ?: "?"} ↔ ${b?.name ?: "?"}") + modeSuffix
            CorridorInfo(key, a, b, any.od.mode, sorted, false, label)
        }
        val primary = list.firstOrNull { it.key == primaryKey }
            ?: list.filter { c -> c.placeA?.kind == "UNI" || c.placeB?.kind == "UNI" }.maxByOrNull { it.tripCount }
            ?: list.maxByOrNull { it.tripCount }
        return list.map { it.copy(isPrimary = it.key == primary?.key) }
            .sortedWith(compareByDescending<CorridorInfo> { it.isPrimary }.thenByDescending { it.tripCount })
    }

    private fun modeName(m: TransportMode) = when (m) {
        TransportMode.BICYCLE -> "Rad"
        TransportMode.WALK -> "zu Fuß"
        TransportMode.CAR -> "Auto"
        TransportMode.TRANSIT -> "ÖPNV"
        TransportMode.UNKNOWN -> "?"
    }

    private fun kpis(trips: List<TripView>, primary: CorridorInfo?): Kpis {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val week = trips.filter { it.startTime >= weekStart }
        // the main commute: outbound direction of the primary corridor ("Hauptstrecke")
        val main = primary?.directions?.firstOrNull { it.trips.isNotEmpty() }
        val mainDurations = main?.trips?.map { it.durationS }.orEmpty()
        return Kpis(
            primary = primary,
            mainOd = main,
            medianMainS = if (mainDurations.isEmpty()) null else Descriptive.median(mainDurations),
            mainN = mainDurations.size,
            fastestMain = main?.trips?.minByOrNull { it.durationS },
            weekDistanceM = week.sumOf { it.distanceM },
            weekWaitS = week.sumOf { it.waitS },
            weekTrips = week.size,
            todayTrips = trips.filter { it.startTime >= todayStart }.sortedByDescending { it.startTime },
        )
    }

    // ------------------------------------------------------------------ on-demand computations

    fun recommend(od: OdInfo, stats: Map<Long, RouteStats>, dow: Int, minute: Int, objective: Objective, custom: Weights): Recommendation? {
        if (od.routes.isEmpty()) return null
        val ess = HashMap<Long, Double>()
        val metrics = od.routes.mapNotNull { r ->
            val st = stats[r.id] ?: return@mapNotNull null
            val own = od.trips.filter { it.routeId == r.id }
            val obs = own.map { TimedObs(it.startMinuteOfDay, it.dayOfWeek, it.durationS, it.id) }
            val est = KernelTimeModel.estimateShrunk(obs, minute, dow)
            val waitObs = own.map { TimedObs(it.startMinuteOfDay, it.dayOfWeek, it.waitS, it.id) }
            val waitEst = KernelTimeModel.estimateShrunk(waitObs, minute, dow)
            ess[r.id] = est?.ess ?: 0.0
            RouteMetrics(
                routeId = r.id,
                // fall back to the overall median when no ride exists near this time
                expectedDurationS = if (est != null && est.ess >= 1.0) est.median else st.duration.median,
                distanceM = st.avgDistanceM,
                waitS = if (waitEst != null && waitEst.ess >= 1.0) waitEst.median else st.medianWaitS,
                stops = st.avgStops,
                spreadS = st.duration.p90 - st.duration.p10,
                bikeScore = st.bikeScore,
                sampleSize = st.n.toDouble(),
            )
        }
        if (metrics.isEmpty()) return null
        val ranking = RouteScorer.score(metrics, Weights.forObjective(objective, custom))
        return Recommendation(od, dow, minute, objective, ranking, ess)
    }

    fun timeProfile(od: OdInfo, group: DayGroup, from: Int = 6 * 60, to: Int = 21 * 60, step: Int = 15): Map<Long, List<ProfilePoint>> =
        od.routes.associate { r ->
            val obs = od.trips.filter { it.routeId == r.id }.map { TimedObs(it.startMinuteOfDay, it.dayOfWeek, it.durationS, it.id) }
            r.id to (from..to step step).mapNotNull { m ->
                KernelTimeModel.estimate(obs, m, group)?.takeIf { it.ess >= 1.0 }?.let { ProfilePoint(m, it.median, it.ess) }
            }
        }

    companion object {
        fun TripView.toStat() = TripStat(
            tripId = id, routeId = routeId ?: -1, startTime = startTime, dayOfWeek = dayOfWeek,
            minuteOfDay = startMinuteOfDay, durationS = durationS, distanceM = distanceM, waitS = waitS,
            stopCount = stopCount, bikeScore = bikeScore,
        )
    }
}
