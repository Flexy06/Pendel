package de.flexy.pendel.core

import de.flexy.pendel.core.analysis.GlobalAnalysis
import de.flexy.pendel.core.demo.SyntheticRides
import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.geo.PolylineOps
import de.flexy.pendel.core.insights.InsightEngine
import de.flexy.pendel.core.insights.InsightType
import de.flexy.pendel.core.insights.IntersectionStatsCalculator
import de.flexy.pendel.core.insights.PassObs
import de.flexy.pendel.core.insights.WaitObs
import de.flexy.pendel.core.model.DetectedStop
import de.flexy.pendel.core.model.IntersectionCandidate
import de.flexy.pendel.core.model.IntersectionKind
import de.flexy.pendel.core.model.StopKind
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.model.WaitLevel
import de.flexy.pendel.core.optimize.Objective
import de.flexy.pendel.core.optimize.RouteMetrics
import de.flexy.pendel.core.optimize.RouteScorer
import de.flexy.pendel.core.optimize.Weights
import de.flexy.pendel.core.routes.RouteSimilarity
import de.flexy.pendel.core.stats.Bootstrap
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.core.stats.TimedObs
import de.flexy.pendel.core.stops.WaitEstimator
import de.flexy.pendel.core.track.TripAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoreAlgorithmsTest {

    private val routes = SyntheticRides.karlsruheRoutes
    private val monday0745 = 1_759_729_500_000L // arbitrary epoch base

    @Test
    fun polylineRoundTrip() {
        val pts = listOf(LatLon(49.0, 8.4), LatLon(49.001, 8.4012), LatLon(48.9995, 8.3999))
        val back = PolylineCodec.decode(PolylineCodec.encode(pts))
        assertEquals(3, back.size)
        pts.zip(back).forEach { (a, b) -> assertTrue(GeoMath.distance(a, b) < 1.5) }
        // known reference from Google's documentation
        val ref = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(38.5, ref[0].lat, 1e-5)
        assertEquals(-120.2, ref[0].lon, 1e-5)
        assertEquals(43.252, ref[2].lat, 1e-5)
    }

    @Test
    fun resampleKeepsSpacing() {
        val line = listOf(LatLon(49.0, 8.40), LatLon(49.0, 8.41)) // ≈ 730 m
        val r = PolylineOps.resample(line, 25.0)
        for (i in 1 until r.size - 1) assertEquals(25.0, GeoMath.distance(r[i - 1], r[i]), 0.5)
        assertEquals(PolylineOps.length(line), PolylineOps.length(r), 1.0)
    }

    @Test
    fun stopsAreDetectedAtRedLights() {
        val route = routes[0]
        val pts = SyntheticRides.ride(route, monday0745, 1, 465, seed = 3)
        val a = TripAnalyzer.analyze(pts, TransportMode.BICYCLE)!!
        val stops = a.stops.filter { it.kind == StopKind.STOP }
        assertTrue("expected some stops, got ${a.stops}", stops.isNotEmpty())
        // every regular stop must be near one of the configured lights
        val path = PolylineOps.resample(route.waypoints, 1.0)
        val total = PolylineOps.length(path)
        val lightPos = route.lights.map { path[((it.alongFraction * total).toInt()).coerceAtMost(path.size - 1)] }
        for (s in stops) {
            val d = lightPos.minOf { GeoMath.distance(it, s.latLon) }
            assertTrue("stop ${s.durationS}s at distance $d from nearest light", d < 30)
        }
        // standing phases at start/end (unlocking/locking) are trimmed from the duration
        val rawDuration = (pts.last().t - pts.first().t) / 1000.0
        assertTrue("trimmed ${a.metrics.durationS} vs raw $rawDuration", rawDuration - a.metrics.durationS >= 20)
        assertEquals(TransportMode.BICYCLE, a.mode)
        assertTrue(a.metrics.distanceM in 6000.0..9000.0)
        assertTrue("elevation computed", a.metrics.elevationGainM != null)
    }

    @Test
    fun similarityDistinguishesRoutes() {
        val sa1 = sig(routes[0], 1)
        val sa2 = sig(routes[0], 2)
        val sb = sig(routes[1], 3)
        val same = RouteSimilarity.similarity(sa1, sa2)
        val diff = RouteSimilarity.similarity(sa1, sb)
        assertTrue("same route similarity $same", same > 0.9)
        assertTrue("different route similarity $diff", diff < 0.6)
    }

    private fun sig(r: SyntheticRides.DemoRoute, seed: Int): List<LatLon> {
        val a = TripAnalyzer.analyze(SyntheticRides.ride(r, monday0745 + seed * 86_400_000L, 1, 480, seed), TransportMode.BICYCLE)!!
        return PolylineCodec.decode(a.signature)
    }

    /** Builds a realistic history: 8 weeks, weekdays, morning departures between 07:00 and 10:30. */
    private fun history(): List<GlobalAnalysis.TripInput> {
        val out = ArrayList<GlobalAnalysis.TripInput>()
        var id = 1L
        var stopId = 1L
        for (week in 0 until 8) for (dow in 1..5) for (k in 0 until 2) {
            val seed = (week * 100 + dow * 10 + k)
            val routeIdx = (seed * 7 + week) % 3
            val minute = 420 + ((seed * 37) % 210) // 07:00 .. 10:30
            val start = monday0745 + ((week * 7 + dow - 1) * 86_400_000L) + minute * 60_000L
            val pts = SyntheticRides.ride(routes[routeIdx], start, dow, minute, seed)
            val a = TripAnalyzer.analyze(pts, TransportMode.BICYCLE)!!
            val stops = a.stops.map {
                GlobalAnalysis.StopInput(stopId++, it.lat, it.lon, it.startTime, it.durationS, it.kind, it.resumed, dow, minute)
            }
            out += GlobalAnalysis.TripInput(
                id++, start, dow, minute, minute + 30, a.mode,
                a.cleanedPoints.first().latLon, a.cleanedPoints.last().latLon,
                PolylineCodec.decode(a.signature), stops,
            ).also { durations[it.id] = Triple(routeIdx, a.metrics.durationS, minute) }
        }
        return out
    }

    private val durations = HashMap<Long, Triple<Int, Double, Int>>()

    @Test
    fun globalAnalysisClustersRoutesAndLearnsIntersections() {
        val trips = history()
        val out = GlobalAnalysis.run(GlobalAnalysis.Input(trips, emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals("home + uni", 2, out.places.places.size)
        assertEquals("three distinct routes", 3, out.routes.size)
        // each discovered route contains exactly one synthetic route
        for (r in out.routes) {
            val origins = r.tripIds.map { durations[it]!!.first }.toSet()
            assertEquals("route ${r.id} mixes $origins", 1, origins.size)
        }
        assertTrue("learned intersections expected", out.learned.size >= 6)
        assertTrue("wait events expected", out.waitEvents.count { it.level != WaitLevel.UNCLEAR } > 20)
        // derived passes are emitted for persistence, consistent with the pass counts
        assertEquals(out.passCounts.values.sum(), out.passes.size)
        assertTrue(out.waitEvents.all { it.lat != 0.0 && it.distanceToIntersectionM != null })
    }

    @Test
    fun insightFindsRushHourAdvantage() {
        val trips = history()
        val out = GlobalAnalysis.run(GlobalAnalysis.Input(trips, emptyList(), emptyList(), emptyList(), emptyList()))
        val routeOfSynthetic = out.routes.associate { r -> durations[r.tripIds.first()]!!.first to r.id }
        val obs = out.routes.associate { r ->
            r.id to r.tripIds.map { tid ->
                val t = trips.first { it.id == tid }
                TimedObs(t.startMinuteOfDay, t.dayOfWeek, durations[tid]!!.second, tid)
            }
        }
        val insights = InsightEngine.find(obs, listOf(DayGroup.Weekdays))
        println("mapping synthetic->route: $routeOfSynthetic")
        val a = routeOfSynthetic[0]!!
        val b = routeOfSynthetic[1]!!
        // Route B (Haid-und-Neu) should beat Route A (Durlacher Allee) in the morning rush
        val rush = insights.firstOrNull {
            it.type == InsightType.ADVANTAGE && it.winnerRouteId == b && it.otherRouteId == a &&
                it.startMinute <= 500 && it.endMinute >= 480
        }
        val printable = insights.joinToString("\n") { "${it.type} ${it.startMinute / 60}:${it.startMinute % 60}-${it.endMinute / 60}:${it.endMinute % 60} ${it.winnerRouteId}>${it.otherRouteId} Δ${it.deltaS.toInt()}s p=${"%.2f".format(it.probability)} n=${it.winnerTrips}/${it.otherTrips}" }
        assertTrue("rush-hour advantage of B over A expected, got:\n$printable", rush != null)
        println(printable)
    }

    @Test
    fun waitEstimatorPrefersSignalsAndPlausibleDurations() {
        val c = listOf(
            IntersectionCandidate(1, 49.0, 8.4, IntersectionKind.TRAFFIC_SIGNALS),
            IntersectionCandidate(2, 49.00005, 8.4, IntersectionKind.JUNCTION),
        )
        fun stop(d: Double) = DetectedStop(0, 1, 0, (d * 1000).toLong(), 49.00004, 8.4, 100.0, StopKind.STOP, true)
        val e = WaitEstimator.estimate(stop(30.0), c)
        assertEquals(1L, e.intersectionId)
        assertEquals(WaitLevel.LIKELY, e.level)
        assertTrue(WaitEstimator.estimate(stop(2.0), c).confidence < e.confidence)
        assertTrue(WaitEstimator.estimate(stop(600.0), c).level == WaitLevel.UNCLEAR)
        val far = DetectedStop(0, 1, 0, 30_000, 49.002, 8.4, 100.0, StopKind.STOP, true)
        assertEquals(null, WaitEstimator.estimate(far, c).intersectionId)
    }

    @Test
    fun intersectionStatsIncludeGreenPasses() {
        val passes = (1L..10L).map { PassObs(it, 1, 480) }
        val waits = listOf(WaitObs(1, 30.0, 0.9, 1, 480), WaitObs(1, 5.0, 0.8, 1, 480), WaitObs(2, 20.0, 0.7, 1, 480), WaitObs(3, 60.0, 0.1, 1, 480))
        val s = IntersectionStatsCalculator.compute(passes, waits)
        assertEquals(10, s.passes)
        assertEquals(2, s.stopsWithWait)
        assertEquals(5.5, s.avgWaitPerPassS, 1e-9)       // (35 + 20) / 10
        assertEquals(27.5, s.avgWaitWhenStoppedS, 1e-9)
        assertEquals(55.0, s.totalWaitS, 1e-9)
    }

    @Test
    fun statisticsAreRobust() {
        val v = listOf(1700.0, 1720.0, 1690.0, 1710.0, 1705.0, 1698.0, 5000.0)
        val s = Descriptive.summarize(v)!!
        assertEquals(1705.0, s.median, 1e-9)
        assertEquals(1, s.outliers)
        assertTrue(abs(s.mean - 1703.8) < 1.0)
        assertEquals(DataBasis.PRELIMINARY, DataBasis.of(2))
        assertEquals(DataBasis.GOOD, DataBasis.of(20))
        assertEquals(DataBasis.HIGH, DataBasis.of(100))
        assertEquals(DataBasis.TENDENCY, DataBasis.of(7.5))
        val ci = Bootstrap.medianInterval(v)!!
        assertTrue(ci.low <= 1705.0 && ci.high >= 1705.0)
        assertEquals(1.0, Bootstrap.probabilityALower(listOf(1.0, 2.0, 3.0), listOf(1.0, 1.0, 1.0), listOf(10.0, 11.0, 12.0), listOf(1.0, 1.0, 1.0)), 1e-9)
    }

    @Test
    fun scorerRespectsObjectives() {
        val m = listOf(
            RouteMetrics(1, 1900.0, 8100.0, 120.0, 6.0, 300.0, null, 20.0),
            RouteMetrics(2, 1700.0, 8700.0, 60.0, 3.0, 120.0, null, 20.0),
            RouteMetrics(3, 1800.0, 7600.0, 90.0, 5.0, 200.0, null, 20.0),
        )
        assertEquals(2L, RouteScorer.score(m, Weights.forObjective(Objective.FASTEST)).first().metrics.routeId)
        assertEquals(3L, RouteScorer.score(m, Weights.forObjective(Objective.SHORTEST)).first().metrics.routeId)
        assertEquals(2L, RouteScorer.score(m, Weights.forObjective(Objective.LEAST_WAIT)).first().metrics.routeId)
        assertEquals(2L, RouteScorer.score(m, Weights.DEFAULT_CUSTOM).first().metrics.routeId)
        assertTrue(RouteScorer.score(m, Weights.forObjective(Objective.BIKE_FRIENDLY)).all { it.bikeUnknown })
    }

    // ------------------------------------------------------------------ v2 regressions (from real data)

    @Test
    fun indoorNoiseAfterArrivalIsTrimmed() {
        val ride = SyntheticRides.ride(routes[0], monday0745, 1, 465, seed = 11)
        val clean = TripAnalyzer.analyze(ride, TransportMode.BICYCLE)!!
        // 8 minutes of indoor GNSS noise at the destination: poor accuracy, random jumps, Doppler spikes
        val end = ride.last()
        val rnd = kotlin.random.Random(5)
        val noise = (1..240).map { k ->
            de.flexy.pendel.core.model.TrackPoint(
                t = end.t + k * 2000L,
                lat = end.lat + (rnd.nextDouble() - 0.5) * 0.0006,
                lon = end.lon + (rnd.nextDouble() - 0.5) * 0.0008,
                accuracy = (18 + rnd.nextDouble() * 30).toFloat(),
                speed = if (k % 17 == 0) (8 + rnd.nextDouble() * 3).toFloat() else (rnd.nextDouble() * 1.2).toFloat(),
            )
        }
        val noisy = TripAnalyzer.analyze(ride + noise, TransportMode.BICYCLE)!!
        assertTrue("duration ${noisy.metrics.durationS} vs ${clean.metrics.durationS}", abs(noisy.metrics.durationS - clean.metrics.durationS) < 30)
        assertTrue("distance ${noisy.metrics.distanceM} vs ${clean.metrics.distanceM}", abs(noisy.metrics.distanceM - clean.metrics.distanceM) < 150)
    }

    @Test
    fun walkingPaceOverridesCyclingHint() {
        val start = LatLon(49.0150, 8.3900)
        val pts = (0 until 300).map { k ->
            val walking = (k / 30) % 3 != 2 // walk, walk, stand …
            val along = (0..k).count { (it / 30) % 3 != 2 } * 2 * 1.3
            de.flexy.pendel.core.model.TrackPoint(
                t = 1_000_000L + k * 2000L, lat = start.lat + along / 111_320.0, lon = start.lon,
                accuracy = 6f, speed = if (walking) 1.3f else 0.1f,
            )
        }
        assertEquals(TransportMode.WALK, TripAnalyzer.analyze(pts, TransportMode.BICYCLE)!!.mode)
        // a mode the user set explicitly still wins
        assertEquals(TransportMode.BICYCLE, TripAnalyzer.analyze(pts, TransportMode.BICYCLE, hintIsAuthoritative = true)!!.mode)
    }

    @Test
    fun implausibleAltitudeGivesNoElevation() {
        val ride = SyntheticRides.ride(routes[1], monday0745, 2, 500, seed = 4)
        val broken = ride.mapIndexed { i, p -> p.copy(altitude = 115.0 + if ((i / 6) % 2 == 0) 0.0 else 40.0) }
        val a = TripAnalyzer.analyze(broken, TransportMode.BICYCLE)!!
        assertEquals(null, a.metrics.elevationGainM)
        val ok = TripAnalyzer.analyze(ride, TransportMode.BICYCLE)!!
        assertTrue("plausible gain ${ok.metrics.elevationGainM}", (ok.metrics.elevationGainM ?: 999.0) < 60)
    }

    @Test
    fun placeGroupsShareRoutes() {
        val home = de.flexy.pendel.core.routes.PlaceInput(1, 48.99950, 8.47400, 150.0, de.flexy.pendel.core.routes.PlaceKind.HOME, true)
        val uni = de.flexy.pendel.core.routes.PlaceInput(2, 49.01500, 8.39050, 150.0, de.flexy.pendel.core.routes.PlaceKind.UNI, true)
        // "Mensa" ~250 m from the uni point, belongs to Uni
        val mensa = de.flexy.pendel.core.routes.PlaceInput(3, 49.01720, 8.39050, 120.0, de.flexy.pendel.core.routes.PlaceKind.OTHER, true, parentId = 2)
        val trips = (0 until 4).map { k ->
            val rev = true
            val pts = SyntheticRides.ride(routes[1], monday0745 + k * 86_400_000L, 1, 960, seed = 30 + k, reverse = rev)
            // half of the rides start at the Mensa: prepend a short leg from there
            val leg = if (k % 2 == 0) (0 until 20).map { j ->
                de.flexy.pendel.core.model.TrackPoint(pts.first().t - (20 - j) * 2000L, 49.01720 - j * 0.0001, 8.39050, 5f, 5f)
            } else emptyList()
            val a = TripAnalyzer.analyze(leg + pts, TransportMode.BICYCLE)!!
            GlobalAnalysis.TripInput(
                k + 1L, a.metrics.startTime, 1, 960, 990, a.mode,
                a.cleanedPoints.first().latLon, a.cleanedPoints.last().latLon, PolylineCodec.decode(a.signature), emptyList(), a.metrics.durationS,
            )
        }
        val out = GlobalAnalysis.run(GlobalAnalysis.Input(trips, listOf(home, uni, mensa), emptyList(), emptyList(), emptyList()))
        assertEquals(setOf(3L, 2L), trips.map { out.places.startPlace[it.id] }.toSet()) // places themselves stay distinct
        assertEquals("one route Uni-group → Home", 1, out.routes.size)
        assertEquals(2L, out.routes.first().key.origin)
        assertEquals(4, out.routes.first().tripIds.size)
    }

    @Test
    fun longStayInsideRecordingSplitsTrip() {
        // ride there, 50 min indoors (phantom speeds, 10–130 m accuracy, drifting fixes), ride back
        val there = SyntheticRides.ride(routes[0], monday0745, 1, 465, seed = 21)
        val end = there.last()
        val rnd = kotlin.random.Random(9)
        val stay = (1..600).map { k ->
            de.flexy.pendel.core.model.TrackPoint(
                t = end.t + k * 5000L,
                lat = end.lat + (rnd.nextDouble() - 0.5) * 0.0008,
                lon = end.lon + (rnd.nextDouble() - 0.5) * 0.0012,
                accuracy = (10 + rnd.nextDouble() * 120).toFloat(),
                speed = (rnd.nextDouble() * if (k % 9 == 0) 14 else 2).toFloat(),
            )
        }
        val backStart = stay.last().t + 5000L
        val back = SyntheticRides.ride(routes[0], monday0745, 1, 465, seed = 22).reversed()
            .let { r -> val tMax = r.first().t; r.map { p -> p.copy(t = backStart + (tMax - p.t)) } }
            .sortedBy { it.t }
        val all = there + stay + back
        val dwells = de.flexy.pendel.core.track.TripSplitter.findDwells(all)
        assertEquals(1, dwells.size)
        assertTrue("stay ${dwells[0].durationS}", dwells[0].durationS > 45 * 60)
        val parts = de.flexy.pendel.core.track.TripSplitter.split(all, dwells)
        assertEquals(2, parts.size)
        assertEquals(all.size, parts.sumOf { it.size }) // no raw point lost
        val a = TripAnalyzer.analyze(parts[0], TransportMode.BICYCLE)!!
        val clean = TripAnalyzer.analyze(there, TransportMode.BICYCLE)!!
        assertTrue("first part ${a.metrics.durationS} vs ${clean.metrics.durationS}", abs(a.metrics.durationS - clean.metrics.durationS) < 60)
        assertTrue(TripAnalyzer.analyze(parts[1], TransportMode.BICYCLE)!!.metrics.distanceM > 3000)
        // red lights and short stops never split a ride
        assertEquals(0, de.flexy.pendel.core.track.TripSplitter.findDwells(there).size)
    }
}
