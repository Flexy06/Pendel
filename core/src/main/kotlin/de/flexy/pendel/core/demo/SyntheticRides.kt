package de.flexy.pendel.core.demo

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineOps
import de.flexy.pendel.core.model.TrackPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates physically plausible synthetic rides (GNSS noise, Doppler speed, traffic lights with
 * signal cycles, time-dependent congestion). Used for the in-app demo mode and for tests –
 * the generated data goes through exactly the same pipeline as real recordings.
 */
object SyntheticRides {

    data class Light(val alongFraction: Double, val cycleS: Int, val redS: Int, val offsetS: Int)

    data class DemoRoute(
        val name: String,
        val waypoints: List<LatLon>,
        val lights: List<Light>,
        /** Slow-down factor (0..1) as function of (dayOfWeek, minuteOfDay). */
        val congestion: (Int, Int) -> Double,
        /** Extra red time at rush hour (dayOfWeek, minuteOfDay) → seconds. */
        val rushRedExtra: (Int, Int) -> Int,
    )

    private fun rush(dow: Int, minute: Int, from: Int, to: Int): Double =
        if (dow <= 5 && minute in from..to) 1.0 else 0.0

    /** Three demo routes in Karlsruhe (Durlach → Hochschule Karlsruhe, Moltkestraße). */
    val karlsruheRoutes: List<DemoRoute> = listOf(
        DemoRoute(
            name = "Durlacher Allee",
            waypoints = listOf(
                LatLon(48.99950, 8.47400), LatLon(49.00350, 8.46000), LatLon(49.00700, 8.43000),
                LatLon(49.00900, 8.41500), LatLon(49.00950, 8.40400), LatLon(49.01050, 8.39500),
                LatLon(49.01500, 8.39050),
            ),
            lights = listOf(
                Light(0.12, 80, 35, 5), Light(0.30, 90, 45, 20), Light(0.52, 90, 40, 60),
                Light(0.66, 90, 50, 10), Light(0.80, 75, 35, 40), Light(0.93, 60, 25, 0),
            ),
            congestion = { d, m -> 0.22 * rush(d, m, 450, 525) + 0.10 * rush(d, m, 990, 1080) },
            rushRedExtra = { d, m -> if (rush(d, m, 450, 525) > 0) 20 else 0 },
        ),
        DemoRoute(
            name = "Haid-und-Neu-Straße",
            waypoints = listOf(
                LatLon(48.99950, 8.47400), LatLon(49.00600, 8.46800), LatLon(49.01500, 8.45000),
                LatLon(49.01800, 8.42000), LatLon(49.01700, 8.40500), LatLon(49.01550, 8.39600),
                LatLon(49.01500, 8.39050),
            ),
            lights = listOf(Light(0.25, 90, 40, 30), Light(0.55, 80, 30, 0), Light(0.85, 70, 30, 15)),
            congestion = { d, m -> 0.03 * rush(d, m, 450, 525) },
            rushRedExtra = { _, _ -> 0 },
        ),
        DemoRoute(
            name = "Ostendstraße",
            waypoints = listOf(
                LatLon(48.99950, 8.47400), LatLon(48.99800, 8.45500), LatLon(49.00000, 8.43000),
                LatLon(49.00400, 8.41000), LatLon(49.00800, 8.39800), LatLon(49.01500, 8.39050),
            ),
            lights = listOf(
                Light(0.20, 80, 35, 10), Light(0.45, 90, 45, 50), Light(0.70, 70, 35, 0),
                Light(0.88, 80, 40, 25),
            ),
            congestion = { d, m -> 0.10 * rush(d, m, 460, 540) },
            rushRedExtra = { _, _ -> 0 },
        ),
    )

    /** Second destination for the demo: Durlach → sports pool (Fächerbad), two alternatives. */
    val sportDestination = LatLon(49.02150, 8.43300)
    val karlsruheSportRoutes: List<DemoRoute> = listOf(
        DemoRoute(
            name = "Ostring",
            waypoints = listOf(
                LatLon(48.99950, 8.47400), LatLon(49.00600, 8.46800), LatLon(49.01300, 8.45300),
                LatLon(49.01900, 8.44200), sportDestination,
            ),
            lights = listOf(Light(0.30, 80, 35, 15), Light(0.65, 90, 40, 40)),
            congestion = { _, _ -> 0.0 },
            rushRedExtra = { _, _ -> 0 },
        ),
        DemoRoute(
            name = "Hagsfeld",
            waypoints = listOf(
                LatLon(48.99950, 8.47400), LatLon(49.00900, 8.47100), LatLon(49.02100, 8.46300),
                LatLon(49.02500, 8.44800), sportDestination,
            ),
            lights = listOf(Light(0.50, 70, 25, 5)),
            congestion = { _, _ -> 0.0 },
            rushRedExtra = { _, _ -> 0 },
        ),
    )

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    private fun offset(p: LatLon, northM: Double, eastM: Double): LatLon =
        LatLon(
            p.lat + northM / GeoMath.EARTH_RADIUS_M * 180 / PI,
            p.lon + eastM / (GeoMath.EARTH_RADIUS_M * cos(p.lat * PI / 180)) * 180 / PI,
        )

    /**
     * Simulate one ride.
     * @param startEpochMs departure time (UTC epoch)
     * @param dayOfWeek ISO weekday of the departure in local time
     * @param minuteOfDay local minute of departure
     */
    fun ride(
        route: DemoRoute,
        startEpochMs: Long,
        dayOfWeek: Int,
        minuteOfDay: Int,
        seed: Int,
        intervalS: Int = 2,
        reverse: Boolean = false,
    ): List<TrackPoint> {
        val rnd = Random(seed)
        val wps = if (reverse) route.waypoints.reversed() else route.waypoints
        val path = PolylineOps.resample(wps, 1.0)
        val total = PolylineOps.length(path)
        val lights = route.lights.map { if (reverse) it.copy(alongFraction = 1 - it.alongFraction) else it }
            .sortedBy { it.alongFraction }
        val riderFactor = 1.0 + gauss(rnd) * 0.05
        val congestion = route.congestion(dayOfWeek, minuteOfDay)
        val baseSpeed = 5.4 * riderFactor * (1 - congestion)
        val extraRed = route.rushRedExtra(dayOfWeek, minuteOfDay)

        val points = ArrayList<TrackPoint>()
        var t = 0.0 // seconds since start
        var along = 0.0
        var lightIdx = 0
        var nextSample = 0.0
        var speed = 0.0
        val startJitter = rnd.nextInt(0, 90)
        // unlock bike: ~20 s standing at start
        val standStart = 15.0 + rnd.nextDouble() * 10
        fun posAt(a: Double): LatLon {
            val idx = (a / total * (path.size - 1)).toInt().coerceIn(0, path.size - 1)
            return path[idx]
        }
        fun emit(speedNow: Double, standing: Boolean) {
            val p = posAt(along)
            val noise = if (standing) 2.5 else 3.0
            val q = offset(p, gauss(rnd) * noise, gauss(rnd) * noise)
            val dop = if (standing) (rnd.nextDouble() * 0.25) else (speedNow + gauss(rnd) * 0.25).coerceAtLeast(0.0)
            points += TrackPoint(
                t = startEpochMs + (t * 1000).toLong(),
                lat = q.lat, lon = q.lon,
                accuracy = (4.0 + rnd.nextDouble() * 6.0).toFloat(),
                speed = dop.toFloat(),
                bearing = null,
                altitude = 115.0 + 6 * sin(along / 900.0) + gauss(rnd) * 0.3,
            )
        }
        while (t < standStart) {
            if (t >= nextSample) { emit(0.0, true); nextSample += intervalS }
            t += 1.0
        }
        while (along < total) {
            val nextLight = lights.getOrNull(lightIdx)
            if (nextLight != null && along >= nextLight.alongFraction * total) {
                // arrived at the light: is it red?
                val cycle = nextLight.cycleS
                val red = (nextLight.redS + extraRed).coerceAtMost(cycle - 10)
                val phase = ((startEpochMs / 1000 + startJitter + t.toLong() + nextLight.offsetS) % cycle).toInt()
                if (phase < red) {
                    val wait = red - phase + 1 + rnd.nextInt(0, 3)
                    val until = t + wait
                    speed = 0.0
                    while (t < until) {
                        if (t >= nextSample) { emit(0.0, true); nextSample += intervalS }
                        t += 1.0
                    }
                }
                lightIdx++
            }
            // accelerate towards cruising speed
            val target = baseSpeed * (1 + gauss(rnd) * 0.03)
            speed += (target - speed) * 0.25
            along += speed
            t += 1.0
            if (t >= nextSample) { emit(speed, false); nextSample += intervalS }
        }
        // lock bike at destination
        val end = t + 10 + rnd.nextInt(0, 10)
        along = total
        while (t < end) {
            if (t >= nextSample) { emit(0.0, true); nextSample += intervalS }
            t += 1.0
        }
        return points
    }
}
