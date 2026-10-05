package de.flexy.pendel.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate in decimal degrees. */
data class LatLon(val lat: Double, val lon: Double)

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0

    /** Great-circle distance in meters. */
    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * DEG
        val dLon = (bLon - aLon) * DEG
        val s = sin(dLat / 2).let { it * it } +
            cos(aLat * DEG) * cos(bLat * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(s.coerceIn(0.0, 1.0)))
    }

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Initial bearing in degrees [0, 360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin((b.lon - a.lon) * DEG) * cos(b.lat * DEG)
        val x = cos(a.lat * DEG) * sin(b.lat * DEG) -
            sin(a.lat * DEG) * cos(b.lat * DEG) * cos((b.lon - a.lon) * DEG)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    fun centroid(points: List<LatLon>): LatLon {
        require(points.isNotEmpty())
        return LatLon(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
    }
}

/** Planar point in meters (local tangent plane). */
data class XY(val x: Double, val y: Double)

/**
 * Equirectangular projection around an origin. Error < 0.1 % within ~50 km, which is far below
 * GPS noise – good enough for city-scale geometry (distances to segments, clustering).
 */
class LocalProjection(origin: LatLon) {
    private val lat0 = origin.lat
    private val lon0 = origin.lon
    private val kx = cos(lat0 * PI / 180.0) * GeoMath.EARTH_RADIUS_M * PI / 180.0
    private val ky = GeoMath.EARTH_RADIUS_M * PI / 180.0

    fun project(p: LatLon): XY = XY((p.lon - lon0) * kx, (p.lat - lat0) * ky)
    fun project(lat: Double, lon: Double): XY = XY((lon - lon0) * kx, (lat - lat0) * ky)
    fun unproject(p: XY): LatLon = LatLon(p.y / ky + lat0, p.x / kx + lon0)
}

object Planar {
    fun dist(a: XY, b: XY): Double = hypot(a.x - b.x, a.y - b.y)

    /** Distance from p to segment ab. */
    fun distToSegment(p: XY, a: XY, b: XY): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Minimum distance from p to a polyline. Early exit once below [stopBelow]. */
    fun distToPolyline(p: XY, line: List<XY>, stopBelow: Double = 0.0): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return dist(p, line[0])
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            // cheap bounding-box rejection
            val minX = minOf(a.x, b.x) - best
            val maxX = maxOf(a.x, b.x) + best
            if (p.x < minX || p.x > maxX) continue
            val minY = minOf(a.y, b.y) - best
            val maxY = maxOf(a.y, b.y) + best
            if (p.y < minY || p.y > maxY) continue
            val d = distToSegment(p, a, b)
            if (d < best) {
                best = d
                if (best <= stopBelow) return best
            }
        }
        return best
    }
}

object PolylineOps {
    fun length(points: List<LatLon>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += GeoMath.distance(points[i - 1], points[i])
        return sum
    }

    /** Resample a polyline to (approximately) equal spacing in meters. Keeps first and last point. */
    fun resample(points: List<LatLon>, spacingM: Double): List<LatLon> {
        if (points.size < 2) return points
        val out = ArrayList<LatLon>()
        out += points.first()
        var carry = 0.0 // distance already travelled since last emitted point
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segLen = GeoMath.distance(a, b)
            if (segLen <= 0.0) continue
            var pos = spacingM - carry
            while (pos <= segLen) {
                val f = pos / segLen
                out += LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                pos += spacingM
            }
            carry = segLen - (pos - spacingM)
        }
        val last = points.last()
        if (GeoMath.distance(out.last(), last) > 0.5) out += last
        return out
    }

    /** Douglas–Peucker simplification with tolerance in meters (iterative, no recursion depth issues). */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val proj = LocalProjection(points.first())
        val xy = points.map { proj.project(it) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            if (e <= s + 1) continue
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = Planar.distToSegment(xy[i], xy[s], xy[e])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > toleranceM && idx > 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Signed smallest difference between two angles in degrees. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return abs(d)
    }
}

/** Google Encoded Polyline Algorithm (precision 5 by default; Valhalla uses 6). */
object PolylineCodec {
    fun encode(points: List<LatLon>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lon = Math.round(p.lon * factor)
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }

    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, i1) = decodeValue(encoded, index)
            val (dLon, i2) = decodeValue(encoded, i1)
            index = i2
            lat += dLat
            lon += dLon
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= s.length) return 0L to s.length
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val v = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return v to i
    }
}
