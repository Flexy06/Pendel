package de.flexy.pendel.core.optimize

import de.flexy.pendel.core.stats.DataBasis

enum class Objective(val label: String) {
    FASTEST("Schnellste"),
    SHORTEST("Kürzeste"),
    LEAST_WAIT("Wenigste Wartezeit"),
    FEWEST_STOPS("Wenigste Stopps"),
    MOST_RELIABLE("Zuverlässigste"),
    BIKE_FRIENDLY("Fahrradfreundlichste"),
    CUSTOM("Eigene Gewichtung"),
}

/** Relative weights; they are normalised to sum 1 before scoring. */
data class Weights(
    val duration: Double = 0.0,
    val wait: Double = 0.0,
    val distance: Double = 0.0,
    val stops: Double = 0.0,
    val spread: Double = 0.0,
    val bike: Double = 0.0,
) {
    val sum: Double get() = duration + wait + distance + stops + spread + bike

    companion object {
        val DEFAULT_CUSTOM = Weights(duration = 0.70, wait = 0.15, distance = 0.10, stops = 0.05)

        fun forObjective(o: Objective, custom: Weights = DEFAULT_CUSTOM): Weights = when (o) {
            // small tie-breakers keep the ranking sensible when the main metric is equal
            Objective.FASTEST -> Weights(duration = 1.0, spread = 0.05)
            Objective.SHORTEST -> Weights(distance = 1.0, duration = 0.05)
            Objective.LEAST_WAIT -> Weights(wait = 1.0, duration = 0.05)
            Objective.FEWEST_STOPS -> Weights(stops = 1.0, duration = 0.05)
            Objective.MOST_RELIABLE -> Weights(spread = 1.0, duration = 0.1)
            Objective.BIKE_FRIENDLY -> Weights(bike = 1.0, duration = 0.05)
            Objective.CUSTOM -> custom
        }
    }
}

data class RouteMetrics(
    val routeId: Long,
    val expectedDurationS: Double,
    val distanceM: Double,
    val waitS: Double,
    val stops: Double,
    /** P90 − P10 of duration. */
    val spreadS: Double,
    /** 0..1, higher = more bike infrastructure; null when unknown. */
    val bikeScore: Double?,
    val sampleSize: Double,
)

data class ScoredRoute(
    val metrics: RouteMetrics,
    /** 0 = best possible on every weighted metric, 1 = worst. */
    val score: Double,
    val rank: Int,
    val components: Map<String, Double>,
    val basis: DataBasis,
    val bikeUnknown: Boolean,
)

object RouteScorer {
    fun score(candidates: List<RouteMetrics>, weights: Weights): List<ScoredRoute> {
        if (candidates.isEmpty()) return emptyList()
        val total = weights.sum.takeIf { it > 0 } ?: 1.0

        fun norm(sel: (RouteMetrics) -> Double): (RouteMetrics) -> Double {
            val values = candidates.map(sel)
            val min = values.min()
            val max = values.max()
            return { m -> if (max - min < 1e-9) 0.0 else (sel(m) - min) / (max - min) }
        }
        val nDur = norm { it.expectedDurationS }
        val nWait = norm { it.waitS }
        val nDist = norm { it.distanceM }
        val nStops = norm { it.stops }
        val nSpread = norm { it.spreadS }
        // bike: higher is better → invert; unknown → neutral 0.5
        val bikes = candidates.mapNotNull { it.bikeScore }
        val bMin = bikes.minOrNull() ?: 0.0
        val bMax = bikes.maxOrNull() ?: 0.0
        val nBike: (RouteMetrics) -> Double = { m ->
            val b = m.bikeScore
            when {
                b == null -> 0.5
                bMax - bMin < 1e-9 -> 0.0
                else -> 1.0 - (b - bMin) / (bMax - bMin)
            }
        }

        val scored = candidates.map { m ->
            val comp = linkedMapOf(
                "Fahrzeit" to nDur(m) * weights.duration / total,
                "Wartezeit" to nWait(m) * weights.wait / total,
                "Distanz" to nDist(m) * weights.distance / total,
                "Stopps" to nStops(m) * weights.stops / total,
                "Streuung" to nSpread(m) * weights.spread / total,
                "Radfreundlichkeit" to nBike(m) * weights.bike / total,
            )
            Triple(m, comp.values.sum(), comp)
        }.sortedWith(compareBy<Triple<RouteMetrics, Double, Map<String, Double>>> { it.second }
            .thenBy { it.first.expectedDurationS })

        return scored.mapIndexed { i, (m, s, comp) ->
            ScoredRoute(m, s, i + 1, comp, DataBasis.of(m.sampleSize), m.bikeScore == null)
        }
    }
}
