package de.flexy.pendel.core.track

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.model.TrackPoint

/**
 * Finds long stays *inside* one recording (ride to Uni → 50 min in the Mensa → ride home) so the
 * recording can be split into separate trips.
 *
 * Why this is needed: the live recorder decides "arrived" from momentary speed, and indoor GPS
 * keeps reporting phantom speeds (1–14 m/s at 10–130 m accuracy). The recorder then never sees
 * enough stillness to stop. Post-processing has the whole track and can judge by *position*:
 * a stay is a period of at least [minDwellS] during which every reasonably accurate fix lies
 * within [radiusM] of where the stay began. Inaccurate fixes cannot break a stay.
 *
 * A stay only splits the recording if there was real travel on both sides ([minTravelM] straight
 * line) – otherwise it is just the start/end wait that trip trimming already handles.
 */
object TripSplitter {

    data class Dwell(val startT: Long, val endT: Long, val lat: Double, val lon: Double) {
        val durationS: Double get() = (endT - startT) / 1000.0
        /** Raw points with t < splitT belong to the first part, the rest to the second. */
        val splitT: Long get() = startT + (endT - startT) / 2
    }

    const val DEFAULT_MIN_DWELL_S = 8 * 60.0
    const val DEFAULT_RADIUS_M = 150.0
    const val DEFAULT_MIN_TRAVEL_M = 300.0
    /** Fixes worse than this neither define nor break a stay. */
    const val USABLE_ACCURACY_M = 50f

    fun findDwells(
        points: List<TrackPoint>,
        minDwellS: Double = DEFAULT_MIN_DWELL_S,
        radiusM: Double = DEFAULT_RADIUS_M,
        minTravelM: Double = DEFAULT_MIN_TRAVEL_M,
    ): List<Dwell> {
        val pts = points.sortedBy { it.t }
        val good = pts.filter { it.accuracy <= USABLE_ACCURACY_M }
        if (good.size < 3) return emptyList()
        val first = good.first()
        val last = good.last()
        val out = ArrayList<Dwell>()
        var i = 0
        while (i < good.size) {
            val a = good[i]
            var j = i
            // running centre of the stay: the first fix may still be on the approach
            var sLat = a.lat
            var sLon = a.lon
            while (j + 1 < good.size) {
                val n = (j - i + 1).toDouble()
                val q = good[j + 1]
                if (GeoMath.distance(sLat / n, sLon / n, q.lat, q.lon) > radiusM) break
                sLat += q.lat
                sLon += q.lon
                j++
            }
            val dur = (good[j].t - a.t) / 1000.0
            if (dur >= minDwellS) {
                // centre of the stay = median of its fixes (robust against drifting indoor fixes)
                val lats = (i..j).map { good[it].lat }.sorted()
                val lons = (i..j).map { good[it].lon }.sorted()
                val lat = lats[lats.size / 2]
                val lon = lons[lons.size / 2]
                val before = GeoMath.distance(first.lat, first.lon, lat, lon)
                val after = GeoMath.distance(lat, lon, last.lat, last.lon)
                if (before >= minTravelM && after >= minTravelM) {
                    val prev = out.lastOrNull()
                    // one stay interrupted by a single far-off fix → still one stay
                    if (prev != null && a.t - prev.endT < 3 * 60_000 && GeoMath.distance(prev.lat, prev.lon, lat, lon) <= radiusM) {
                        out[out.size - 1] = prev.copy(endT = good[j].t)
                    } else {
                        out += Dwell(a.t, good[j].t, lat, lon)
                    }
                }
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    /** Splits [points] at the given stays; each part keeps all its raw points (nothing dropped). */
    fun split(points: List<TrackPoint>, dwells: List<Dwell>): List<List<TrackPoint>> {
        if (dwells.isEmpty()) return listOf(points)
        val cuts = dwells.map { it.splitT }.sorted()
        val parts = List(cuts.size + 1) { ArrayList<TrackPoint>() }
        for (p in points.sortedBy { it.t }) {
            val k = cuts.count { p.t >= it }
            parts[k].add(p)
        }
        return parts
    }
}
