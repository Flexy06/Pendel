package de.flexy.pendel.core.insights

import de.flexy.pendel.core.stats.Bootstrap
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.core.stats.Interval
import de.flexy.pendel.core.stats.KernelTimeModel
import de.flexy.pendel.core.stats.Summary
import de.flexy.pendel.core.stats.TimedObs

/** Minimal per-trip facts needed for statistics. */
data class TripStat(
    val tripId: Long,
    val routeId: Long,
    val startTime: Long,
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val durationS: Double,
    val distanceM: Double,
    val waitS: Double,
    val stopCount: Int,
    val bikeScore: Double?,
)

data class RouteStats(
    val routeId: Long,
    val n: Int,
    val duration: Summary,
    val medianInterval: Interval?,
    val avgDistanceM: Double,
    val avgWaitS: Double,
    val medianWaitS: Double,
    val avgStops: Double,
    /** 1 − (P90 − P10)/median, clipped to [0,1]; higher = more predictable. */
    val reliability: Double,
    val bikeScore: Double?,
    val fastestTripId: Long,
    val slowestTripId: Long,
) {
    val basis: DataBasis get() = DataBasis.of(n)
}

object RouteStatsCalculator {
    fun compute(routeId: Long, trips: List<TripStat>): RouteStats? {
        val own = trips.filter { it.routeId == routeId }
        val summary = Descriptive.summarize(own.map { it.durationS }) ?: return null
        val spread = summary.p90 - summary.p10
        val bikes = own.mapNotNull { it.bikeScore }
        return RouteStats(
            routeId = routeId,
            n = own.size,
            duration = summary,
            medianInterval = Bootstrap.medianInterval(own.map { it.durationS }),
            avgDistanceM = own.map { it.distanceM }.average(),
            avgWaitS = own.map { it.waitS }.average(),
            medianWaitS = Descriptive.median(own.map { it.waitS }),
            avgStops = own.map { it.stopCount.toDouble() }.average(),
            reliability = if (own.size < 3 || summary.median <= 0) 0.0 else (1 - spread / summary.median).coerceIn(0.0, 1.0),
            bikeScore = if (bikes.isEmpty()) null else Descriptive.median(bikes),
            fastestTripId = own.minBy { it.durationS }.tripId,
            slowestTripId = own.maxBy { it.durationS }.tripId,
        )
    }

    fun computeAll(trips: List<TripStat>): List<RouteStats> =
        trips.map { it.routeId }.distinct().mapNotNull { compute(it, trips) }
}

// ---------------------------------------------------------------- time-dependent insights

enum class InsightType { ADVANTAGE, ADVANTAGE_FADES }

data class Insight(
    val type: InsightType,
    val dayGroup: DayGroup,
    val startMinute: Int,
    val endMinute: Int,
    val winnerRouteId: Long,
    val otherRouteId: Long,
    /** Average advantage of the winner in seconds over the window (≥ 0). */
    val deltaS: Double,
    val winnerTrips: Int,
    val otherTrips: Int,
    /** Bootstrap probability that the winner really is faster (0.5 … 1). */
    val probability: Double,
) {
    val basis: DataBasis get() = DataBasis.of(minOf(winnerTrips, otherTrips) * 2)
}

/**
 * Finds time windows in which one route is consistently faster than another,
 * without fixed hour buckets (Gaussian kernel over the time of day).
 */
object InsightEngine {
    var stepMin = 15
    var fromMin = 5 * 60
    var toMin = 23 * 60
    var minEss = 3.0
    var minProbability = 0.8
    var minDeltaS = 20.0

    private data class Slot(
        val minute: Int,
        val winner: Long?,
        val other: Long?,
        val delta: Double,
        val prob: Double,
        val significant: Boolean,
        val hasData: Boolean,
    )

    /**
     * Compares every pair of routes separately, so statements like
     * "B ist 3:25 schneller als A" appear even when a third route C is fastest overall.
     */
    fun find(
        durationsByRoute: Map<Long, List<TimedObs>>,
        groups: List<DayGroup> = listOf(DayGroup.Weekdays) + (1..7).map { DayGroup.Single(it) },
    ): List<Insight> {
        if (durationsByRoute.size < 2) return emptyList()
        val ids = durationsByRoute.keys.sorted()
        val out = ArrayList<Insight>()
        for (g in groups) for (i in ids.indices) for (j in i + 1 until ids.size) {
            val pair = mapOf(ids[i] to durationsByRoute.getValue(ids[i]), ids[j] to durationsByRoute.getValue(ids[j]))
            val slots = ArrayList<Slot>()
            var m = fromMin
            while (m <= toMin) {
                slots += evaluateSlot(pair, m, g)
                m += stepMin
            }
            out += mergeSlots(slots, g, pair)
        }
        return dedupe(out)
    }

    private fun evaluateSlot(data: Map<Long, List<TimedObs>>, minute: Int, g: DayGroup): Slot {
        val est = data.mapNotNull { (id, obs) ->
            KernelTimeModel.estimate(obs, minute, g)?.takeIf { it.ess >= minEss }?.let { id to it }
        }.sortedBy { it.second.median }
        if (est.size < 2) return Slot(minute, null, null, 0.0, 0.5, false, false)
        val (wId, w) = est[0]
        val (oId, o) = est[1]
        val delta = o.median - w.median
        // bootstrap is the expensive part – only needed if the advantage is large enough to matter
        val prob = if (delta < minDeltaS) 0.5
        else Bootstrap.probabilityALower(w.values, w.weights, o.values, o.weights, seed = minute)
        val sig = prob >= minProbability && delta >= minDeltaS
        return Slot(minute, wId, oId, delta, prob, sig, true)
    }

    private fun mergeSlots(slots: List<Slot>, g: DayGroup, data: Map<Long, List<TimedObs>>): List<Insight> {
        val res = ArrayList<Insight>()
        var i = 0
        while (i < slots.size) {
            val s = slots[i]
            if (!s.significant) { i++; continue }
            var j = i
            while (j + 1 < slots.size && slots[j + 1].significant &&
                slots[j + 1].winner == s.winner && slots[j + 1].other == s.other) j++
            val window = slots.subList(i, j + 1)
            val wObs = data[s.winner!!].orEmpty().filter { g.contains(it.dayOfWeek) }
            val oObs = data[s.other!!].orEmpty().filter { g.contains(it.dayOfWeek) }
            // never claim a window beyond the times we actually have rides for
            val dataFrom = maxOf(wObs.minOfOrNull { it.minuteOfDay } ?: 0, oObs.minOfOrNull { it.minuteOfDay } ?: 0)
            val dataTo = minOf(wObs.maxOfOrNull { it.minuteOfDay } ?: 0, oObs.maxOfOrNull { it.minuteOfDay } ?: 0)
            val start = roundQuarter(maxOf(window.first().minute, dataFrom))
            val end = roundQuarter(minOf(window.last().minute, dataTo))
            val nW = countIn(wObs, g, start - stepMin, end + stepMin)
            val nO = countIn(oObs, g, start - stepMin, end + stepMin)
            if (end > start && nW >= 3 && nO >= 3) {
                res += Insight(
                    InsightType.ADVANTAGE, g, start, end, s.winner, s.other!!,
                    window.map { it.delta }.average(), nW, nO, window.map { it.prob }.average(),
                )
                // does the advantage disappear right after the window (and do we have data there)?
                val next = slots.getOrNull(j + 1)
                if (next != null && next.hasData && !next.significant && next.winner != null && next.minute <= dataTo) {
                    res += Insight(
                        InsightType.ADVANTAGE_FADES, g, end, end, s.winner, s.other,
                        maxOf(0.0, if (next.winner == s.winner) next.delta else -next.delta), nW, nO, next.prob,
                    )
                }
            }
            i = j + 1
        }
        return res
    }

    private fun roundQuarter(m: Int) = ((m + 7) / 15) * 15

    private fun countIn(obs: List<TimedObs>, g: DayGroup, start: Int, end: Int) =
        obs.count { g.contains(it.dayOfWeek) && it.minuteOfDay in start..end }

    private fun dedupe(list: List<Insight>): List<Insight> {
        val weekdays = list.filter { it.dayGroup == DayGroup.Weekdays }
        return list.filter { ins ->
            if (ins.dayGroup !is DayGroup.Single || ins.dayGroup.dow > 5) return@filter true
            // drop a single-weekday statement that just repeats the Werktags one
            weekdays.none { w ->
                w.type == ins.type && w.winnerRouteId == ins.winnerRouteId && w.otherRouteId == ins.otherRouteId &&
                    kotlin.math.abs(w.startMinute - ins.startMinute) <= 30 && kotlin.math.abs(w.endMinute - ins.endMinute) <= 30 &&
                    kotlin.math.abs(w.deltaS - ins.deltaS) < 45
            }
        }.sortedWith(compareByDescending<Insight> { it.type == InsightType.ADVANTAGE }.thenByDescending { it.probability * it.deltaS })
    }
}

// ---------------------------------------------------------------- intersections

data class WaitObs(val tripId: Long, val durationS: Double, val confidence: Double, val dayOfWeek: Int, val minuteOfDay: Int)
data class PassObs(val tripId: Long, val dayOfWeek: Int, val minuteOfDay: Int)

data class CellStat(val avgWaitPerPassS: Double, val passes: Int)

data class IntersectionStats(
    val passes: Int,
    val stopsWithWait: Int,
    val stopProbability: Double,
    val avgWaitPerPassS: Double,
    val avgWaitWhenStoppedS: Double,
    val medianWaitWhenStoppedS: Double,
    val totalWaitS: Double,
    val avgConfidence: Double,
    /** [dow 1..7] → hour → stat. Hourly display only; n is always shown next to it. */
    val byDayHour: Map<Int, Map<Int, CellStat>>,
) {
    val basis: DataBasis get() = DataBasis.of(passes)
}

object IntersectionStatsCalculator {
    var minConfidence = 0.3

    /**
     * Waits are summed per trip (stop-and-go at one light = one pass). A pass without a wait
     * contributes 0 s – so "Ø Wartezeit pro Durchfahrt" honestly includes green-wave passes.
     */
    fun compute(passes: List<PassObs>, waits: List<WaitObs>): IntersectionStats {
        val valid = waits.filter { it.confidence >= minConfidence }
        val waitByTrip = valid.groupBy { it.tripId }.mapValues { e -> e.value.sumOf { it.durationS } }
        val passTrips = (passes.map { it.tripId } + waitByTrip.keys).toSet()
        val passMeta = passes.associateBy { it.tripId }.toMutableMap()
        for (w in valid) passMeta.putIfAbsent(w.tripId, PassObs(w.tripId, w.dayOfWeek, w.minuteOfDay))
        val n = passTrips.size
        val stopped = waitByTrip.values.toList()
        val total = stopped.sum()
        val cells = HashMap<Int, HashMap<Int, MutableList<Double>>>()
        for (t in passTrips) {
            val meta = passMeta[t] ?: continue
            val hour = meta.minuteOfDay / 60
            cells.getOrPut(meta.dayOfWeek) { HashMap() }.getOrPut(hour) { ArrayList() } += (waitByTrip[t] ?: 0.0)
        }
        return IntersectionStats(
            passes = n,
            stopsWithWait = stopped.size,
            stopProbability = if (n == 0) 0.0 else stopped.size.toDouble() / n,
            avgWaitPerPassS = if (n == 0) 0.0 else total / n,
            avgWaitWhenStoppedS = if (stopped.isEmpty()) 0.0 else stopped.average(),
            medianWaitWhenStoppedS = if (stopped.isEmpty()) 0.0 else Descriptive.median(stopped),
            totalWaitS = total,
            avgConfidence = if (valid.isEmpty()) 0.0 else valid.map { it.confidence }.average(),
            byDayHour = cells.mapValues { (_, hours) -> hours.mapValues { (_, v) -> CellStat(v.average(), v.size) } },
        )
    }

    /** Recurrence (stop probability) used by the wait estimator. */
    fun recurrence(passes: Int, stops: Int): Double = if (passes < 3) 0.0 else stops.toDouble() / passes
}
