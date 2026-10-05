package de.flexy.pendel.providers

import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.core.geo.LatLon
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.geo.PolylineOps
import de.flexy.pendel.core.model.IntersectionKind
import de.flexy.pendel.core.model.TrackPoint
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.data.settings.MatcherChoice
import de.flexy.pendel.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ------------------------------------------------------------------ map matching

data class MatchedEdge(val name: String?, val lengthM: Double, val use: String?, val cycleLane: String?)

data class MatchResult(
    /** Road-snapped geometry, null if the provider does not snap. */
    val geometry: List<LatLon>?,
    val edges: List<MatchedEdge>,
) {
    /** Share of distance on cycling infrastructure, weighted (0..1); null without edge info. */
    val bikeScore: Double?
        get() {
            val total = edges.sumOf { it.lengthM }
            if (edges.isEmpty() || total <= 0) return null
            val score = edges.sumOf { e ->
                val w = when {
                    e.use == "cycleway" || e.cycleLane == "separated" -> 1.0
                    e.cycleLane == "dedicated" -> 0.8
                    e.use == "path" || e.use == "living_street" -> 0.7
                    e.cycleLane == "shared" -> 0.5
                    e.use == "footway" || e.use == "pedestrian" -> 0.4
                    else -> 0.3
                }
                w * e.lengthM
            }
            return score / total
        }

    /** The two streets with the longest share, e.g. "Durlacher Allee, Kaiserstraße". */
    val mainStreets: List<String>
        get() = edges.filter { !it.name.isNullOrBlank() }
            .groupBy { it.name!! }
            .mapValues { e -> e.value.sumOf { it.lengthM } }
            .entries.sortedByDescending { it.value }
            .take(2).map { it.key }
}

/**
 * Exchangeable map-matching backend. Implementations must describe what they transmit,
 * this text is shown verbatim in the privacy settings.
 */
interface MapMatcher {
    val id: String
    val requiresNetwork: Boolean
    val transmits: String
    suspend fun match(points: List<TrackPoint>, mode: TransportMode): MatchResult
}

/** Offline default: no road snapping, nothing leaves the device. */
object PassthroughMatcher : MapMatcher {
    override val id = "offline"
    override val requiresNetwork = false
    override val transmits = "Nichts – die Fahrt wird nur lokal geglättet."
    override suspend fun match(points: List<TrackPoint>, mode: TransportMode) = MatchResult(null, emptyList())
}

/**
 * Valhalla `trace_attributes` (open source, OSM based, has a real bicycle profile).
 * Recommended: self-host it on your home server so the track never leaves your network.
 */
class ValhallaMatcher(private val baseUrl: String) : MapMatcher {
    override val id = "valhalla"
    override val requiresNetwork = true
    override val transmits =
        "Die Koordinaten und Zeitstempel der Fahrt (ausgedünnt auf ca. alle 10 m) an $baseUrl."

    override suspend fun match(points: List<TrackPoint>, mode: TransportMode): MatchResult {
        // thin the track: Valhalla needs ~every 10 m, not every 2 s
        val thinned = ArrayList<TrackPoint>()
        for (p in points) {
            val last = thinned.lastOrNull()
            if (last == null || GeoMath.distance(last.lat, last.lon, p.lat, p.lon) >= 10.0) thinned += p
        }
        if (points.isNotEmpty() && thinned.lastOrNull() !== points.last()) thinned += points.last()
        val shape = JSONArray()
        thinned.forEach {
            shape.put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("time", it.t / 1000))
        }
        val costing = when (mode) {
            TransportMode.WALK -> "pedestrian"
            TransportMode.CAR -> "auto"
            else -> "bicycle"
        }
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", costing)
            .put("shape_match", "map_snap")
            .put("filters", JSONObject()
                .put("attributes", JSONArray(listOf("edge.names", "edge.length", "edge.use", "edge.cycle_lane", "shape")))
                .put("action", "include"))
        val json = Http.postJson("${baseUrl.trimEnd('/')}/trace_attributes", body.toString())
        val res = JSONObject(json)
        val edges = res.optJSONArray("edges") ?: JSONArray()
        val list = (0 until edges.length()).map { i ->
            val e = edges.getJSONObject(i)
            val names = e.optJSONArray("names")
            MatchedEdge(
                name = if (names != null && names.length() > 0) names.getString(0) else null,
                lengthM = e.optDouble("length", 0.0) * 1000.0, // km → m
                use = e.optString("use").ifEmpty { null },
                cycleLane = e.optString("cycle_lane").ifEmpty { null },
            )
        }
        val geometry = res.optString("shape").takeIf { it.isNotEmpty() }?.let { PolylineCodec.decode(it, 6) }
        return MatchResult(geometry?.let { PolylineOps.simplify(it, 3.0) }, list)
    }
}

object ProviderRegistry {
    fun matcher(s: Settings): MapMatcher = when (s.matcher) {
        MatcherChoice.VALHALLA -> if (s.valhallaUrl.isNotBlank()) ValhallaMatcher(s.valhallaUrl) else PassthroughMatcher
        MatcherChoice.NONE -> PassthroughMatcher
    }
}

// ------------------------------------------------------------------ intersections

data class OsmIntersection(
    val osmNodeId: Long,
    val lat: Double,
    val lon: Double,
    val kind: IntersectionKind,
    val name: String?,
)

/** Exchangeable source of known intersections / traffic lights. */
interface IntersectionSource {
    val transmits: String
    suspend fun fetch(south: Double, west: Double, north: Double, east: Double): List<OsmIntersection>
}

/**
 * Overpass API: downloads traffic lights, signalised crossings, stop and give-way signs inside
 * a rectangle plus the names of the streets meeting there. Only the rectangle is transmitted.
 * Nodes of the same junction (OSM maps one signal per approach) are merged within 35 m.
 */
class OverpassIntersectionSource(private val url: String) : IntersectionSource {
    override val transmits = "Nur ein auf ~1 km gerundetes Rechteck um alle Fahrten – kein Track, keine Zeiten."

    override suspend fun fetch(south: Double, west: Double, north: Double, east: Double): List<OsmIntersection> {
        val bbox = "$south,$west,$north,$east"
        val query = """
            [out:json][timeout:60];
            (
              node["highway"="traffic_signals"]($bbox);
              node["crossing"="traffic_signals"]($bbox);
              node["highway"="stop"]($bbox);
              node["highway"="give_way"]($bbox);
            )->.n;
            .n out body;
            way(bn.n)["highway"]["name"];
            out body;
        """.trimIndent()
        val json = Http.postForm(url, "data=" + URLEncoder.encode(query, "UTF-8"))
        val elements = JSONObject(json).optJSONArray("elements") ?: JSONArray()

        data class N(val id: Long, val lat: Double, val lon: Double, val kind: IntersectionKind)
        val nodes = HashMap<Long, N>()
        val namesOfNode = HashMap<Long, MutableSet<String>>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags")
            when (e.optString("type")) {
                "node" -> {
                    val kind = when {
                        tags?.optString("highway") == "traffic_signals" -> IntersectionKind.TRAFFIC_SIGNALS
                        tags?.optString("crossing") == "traffic_signals" -> IntersectionKind.CROSSING_SIGNALS
                        tags?.optString("highway") == "stop" -> IntersectionKind.STOP_SIGN
                        tags?.optString("highway") == "give_way" -> IntersectionKind.GIVE_WAY
                        else -> null
                    } ?: continue
                    val id = e.getLong("id")
                    nodes[id] = N(id, e.getDouble("lat"), e.getDouble("lon"), kind)
                }
                "way" -> {
                    val name = tags?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
                    val nds = e.optJSONArray("nodes") ?: continue
                    for (k in 0 until nds.length()) namesOfNode.getOrPut(nds.getLong(k)) { HashSet() } += name
                }
            }
        }
        // greedy merge of nodes belonging to the same junction
        val remaining = nodes.values.sortedBy { it.kind.ordinal }.toMutableList()
        val out = ArrayList<OsmIntersection>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val group = mutableListOf(seed)
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val n = iter.next()
                if (GeoMath.distance(seed.lat, seed.lon, n.lat, n.lon) <= 35.0) { group += n; iter.remove() }
            }
            val names = group.flatMap { n -> namesOfNode[n.id].orEmpty() }.toSortedSet().toList()
            val kind = group.minBy { it.kind.ordinal }.kind
            val label = when {
                names.size >= 2 -> names.take(2).joinToString(" × ")
                names.size == 1 -> when (kind) {
                    IntersectionKind.CROSSING_SIGNALS -> "Fußgängerampel ${names[0]}"
                    else -> names[0]
                }
                else -> null
            }
            out += OsmIntersection(
                osmNodeId = group.minOf { it.id },
                lat = group.sumOf { it.lat } / group.size,
                lon = group.sumOf { it.lon } / group.size,
                kind = kind,
                name = label,
            )
        }
        return out
    }
}

// ------------------------------------------------------------------ tiny HTTP helper

object Http {
    private const val USER_AGENT = "Pendel/0.1 (personal route analytics; Android)"

    suspend fun postJson(url: String, body: String): String = post(url, body, "application/json")
    suspend fun postForm(url: String, body: String): String = post(url, body, "application/x-www-form-urlencoded")

    private suspend fun post(url: String, body: String, contentType: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "$contentType; charset=utf-8")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
