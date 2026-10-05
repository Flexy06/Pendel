package de.flexy.pendel.core.stats

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** How much data backs a statement. Thresholds follow the product spec. */
enum class DataBasis(val label: String, val minSamples: Double) {
    NONE("Keine Daten", 0.0),
    PRELIMINARY("Vorläufige Daten", 1.0),
    TENDENCY("Erste Tendenz", 5.0),
    GOOD("Gute Datenbasis", 20.0),
    HIGH("Hohe Datenbasis", 100.0);

    companion object {
        fun of(n: Double): DataBasis = entries.last { n >= it.minSamples - 1e-9 }
        fun of(n: Int): DataBasis = of(n.toDouble())
    }
}

data class Summary(
    val n: Int,
    val mean: Double,
    val median: Double,
    val std: Double,
    val min: Double,
    val max: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val mad: Double,
    /** Number of robust outliers (excluded from mean/std, median is robust anyway). */
    val outliers: Int,
) {
    val iqr: Double get() = p75 - p25
    val basis: DataBasis get() = DataBasis.of(n)
}

object Descriptive {
    fun quantileSorted(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        val f = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * f
    }

    fun median(values: List<Double>): Double = quantileSorted(values.sorted(), 0.5)

    fun summarize(values: List<Double>): Summary? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val med = quantileSorted(s, 0.5)
        val mad = quantileSorted(s.map { abs(it - med) }.sorted(), 0.5)
        val robustSigma = 1.4826 * mad
        val inliers = if (s.size >= 5 && robustSigma > 0) s.filter { abs(it - med) <= 3.5 * robustSigma } else s
        val mean = inliers.average()
        val std = if (inliers.size > 1) sqrt(inliers.sumOf { (it - mean) * (it - mean) } / (inliers.size - 1)) else 0.0
        return Summary(
            n = s.size, mean = mean, median = med, std = std, min = s.first(), max = s.last(),
            p10 = quantileSorted(s, 0.10), p25 = quantileSorted(s, 0.25),
            p75 = quantileSorted(s, 0.75), p90 = quantileSorted(s, 0.90),
            mad = mad, outliers = s.size - inliers.size,
        )
    }

    /** Weighted quantile (weights >= 0). */
    fun weightedQuantile(values: List<Double>, weights: List<Double>, q: Double): Double {
        require(values.size == weights.size)
        val pairs = values.indices.filter { weights[it] > 0 }.map { values[it] to weights[it] }.sortedBy { it.first }
        if (pairs.isEmpty()) return Double.NaN
        val total = pairs.sumOf { it.second }
        val target = q * total
        var cum = 0.0
        for ((v, w) in pairs) {
            cum += w
            if (cum >= target - 1e-12) return v
        }
        return pairs.last().first
    }

    /** Kish effective sample size. */
    fun effectiveSampleSize(weights: List<Double>): Double {
        val s = weights.sum()
        val s2 = weights.sumOf { it * it }
        return if (s2 <= 0) 0.0 else s * s / s2
    }
}

data class Interval(val low: Double, val high: Double)

/** Deterministic bootstrap (seeded) – identical inputs always yield identical UI statements. */
object Bootstrap {
    fun medianInterval(values: List<Double>, level: Double = 0.8, b: Int = 400, seed: Int = 42): Interval? {
        if (values.size < 3) return null
        val rnd = Random(seed)
        val meds = DoubleArray(b)
        val buf = DoubleArray(values.size)
        for (k in 0 until b) {
            for (i in buf.indices) buf[i] = values[rnd.nextInt(values.size)]
            buf.sort()
            meds[k] = quantileArr(buf, 0.5)
        }
        meds.sort()
        val a = (1 - level) / 2
        return Interval(quantileArr(meds, a), quantileArr(meds, 1 - a))
    }

    /**
     * Probability that the (weighted) median of A is smaller than that of B,
     * estimated by resampling each group proportionally to its weights with size ≈ ESS.
     */
    fun probabilityALower(
        a: List<Double>, wa: List<Double>,
        b: List<Double>, wb: List<Double>,
        iterations: Int = 300, seed: Int = 7,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.5
        val rnd = Random(seed)
        val ca = cumulative(wa)
        val cb = cumulative(wb)
        val na = Descriptive.effectiveSampleSize(wa).roundToInt().coerceAtLeast(2)
        val nb = Descriptive.effectiveSampleSize(wb).roundToInt().coerceAtLeast(2)
        val bufA = DoubleArray(na)
        val bufB = DoubleArray(nb)
        var wins = 0.0
        repeat(iterations) {
            for (i in 0 until na) bufA[i] = a[pick(ca, rnd)]
            for (i in 0 until nb) bufB[i] = b[pick(cb, rnd)]
            bufA.sort(); bufB.sort()
            val ma = quantileArr(bufA, 0.5)
            val mb = quantileArr(bufB, 0.5)
            wins += when {
                ma < mb -> 1.0
                ma == mb -> 0.5
                else -> 0.0
            }
        }
        return wins / iterations
    }

    private fun cumulative(w: List<Double>): DoubleArray {
        val c = DoubleArray(w.size)
        var s = 0.0
        for (i in w.indices) { s += maxOf(0.0, w[i]); c[i] = s }
        return c
    }

    private fun pick(cum: DoubleArray, rnd: Random): Int {
        val total = cum.last()
        if (total <= 0) return rnd.nextInt(cum.size)
        val r = rnd.nextDouble() * total
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < r) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun quantileArr(sorted: DoubleArray, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}

/** A value observed at a local time of day / weekday (ISO: 1 = Monday … 7 = Sunday). */
data class TimedObs(val minuteOfDay: Int, val dayOfWeek: Int, val value: Double, val id: Long = 0)

sealed class DayGroup(val label: String) {
    abstract fun contains(dow: Int): Boolean

    data class Single(val dow: Int) : DayGroup(DAY_NAMES[dow - 1]) {
        override fun contains(dow: Int) = dow == this.dow
    }
    data object Weekdays : DayGroup("Werktags") {
        override fun contains(dow: Int) = dow in 1..5
    }
    data object Weekend : DayGroup("Wochenende") {
        override fun contains(dow: Int) = dow >= 6
    }
    data object All : DayGroup("Alle Tage") {
        override fun contains(dow: Int) = true
    }

    companion object {
        val DAY_NAMES = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
        val DAY_SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        fun parentOf(dow: Int): DayGroup = if (dow in 1..5) Weekdays else Weekend
    }
}

data class WeightedEstimate(
    val median: Double,
    val ess: Double,
    /** Observations with a meaningful weight (> 0.1). */
    val n: Int,
    val values: List<Double>,
    val weights: List<Double>,
) {
    val basis: DataBasis get() = DataBasis.of(ess)
}

/**
 * Kernel-smoothed statistics over the time of day. Instead of hard hour buckets, every
 * observation contributes with a Gaussian weight depending on its distance in minutes.
 */
object KernelTimeModel {
    var sigmaMin = 35.0
    var shrinkK = 4.0

    fun weight(m1: Int, m2: Int, sigma: Double = sigmaMin): Double {
        var d = abs(m1 - m2).toDouble()
        if (d > 720) d = 1440 - d
        if (d > 3 * sigma) return 0.0
        return exp(-(d * d) / (2 * sigma * sigma))
    }

    fun estimate(obs: List<TimedObs>, minute: Int, group: DayGroup, sigma: Double = sigmaMin): WeightedEstimate? {
        val vals = ArrayList<Double>()
        val ws = ArrayList<Double>()
        for (o in obs) {
            if (!group.contains(o.dayOfWeek)) continue
            val w = weight(o.minuteOfDay, minute, sigma)
            if (w <= 0.01) continue
            vals += o.value; ws += w
        }
        if (vals.isEmpty()) return null
        return WeightedEstimate(
            median = Descriptive.weightedQuantile(vals, ws, 0.5),
            ess = Descriptive.effectiveSampleSize(ws),
            n = ws.count { it > 0.1 },
            values = vals, weights = ws,
        )
    }

    /**
     * Estimate for a specific weekday, shrunk towards its parent group (Werktags/Wochenende)
     * while the weekday itself has little data.
     */
    fun estimateShrunk(obs: List<TimedObs>, minute: Int, dow: Int): WeightedEstimate? {
        val day = estimate(obs, minute, DayGroup.Single(dow))
        val parent = estimate(obs, minute, DayGroup.parentOf(dow)) ?: return day
        if (day == null) return parent
        val m = (day.ess * day.median + shrinkK * parent.median) / (day.ess + shrinkK)
        return parent.copy(median = m)
    }
}
