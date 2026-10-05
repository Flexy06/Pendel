package de.flexy.pendel.data.export

import android.content.ContentResolver
import android.net.Uri
import de.flexy.pendel.core.analysis.AnalysisVersion
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.TrackPointEntity
import de.flexy.pendel.data.toView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Data portability. The JSON export is the backup / device-transfer format: its `raw` and
 * `userData` sections are sufficient to rebuild everything else with [Importer] + re-analysis.
 * `derived` is informative only (for spreadsheets / own tools) and ignored on import.
 */
class Exporter(private val db: PendelDatabase) {

    suspend fun exportJson(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val trips = db.tripDao().all()
        val views = db.tripDao().analyzed().associate { it.trip.id to it.toView() }
        val routes = db.routeDao().all()
        val routeById = routes.associateBy { it.id }
        val places = db.placeDao().all()
        val intersections = db.intersectionDao().all()
        val uuidById = trips.associate { it.id to it.uuid }
        resolver.openOutputStream(uri)?.use { os ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                w.write("{\"format\":\"pendel-export\",\"version\":2,\"schemaVersion\":2,")
                w.write("\"analysisVersion\":${AnalysisVersion.CURRENT},\"exportedAt\":\"${Instant.now()}\",")
                w.write("\"pointFormat\":[\"epochMs\",\"lat\",\"lon\",\"accuracyM\",\"speedMs\",\"bearingDeg\",\"gpsAltitudeM\",\"speedAccuracyMs\",\"verticalAccuracyM\",\"baroAltitudeM\"],")

                // ---------------------------------------------------------------- raw
                w.write("\"raw\":{\"trips\":[")
                trips.forEachIndexed { i, t ->
                    if (i > 0) w.write(",")
                    val o = JSONObject()
                        .put("uuid", t.uuid).put("recordedStart", t.recordedStart).put("recordedEnd", t.recordedEnd)
                        .put("zoneId", t.zoneId).put("trigger", t.trigger).put("userMode", t.userMode)
                        .put("activityMode", t.activityMode).put("excluded", t.excluded).put("note", t.note)
                        .put("isDemo", t.isDemo).put("createdAt", t.createdAt)
                    val json = o.toString()
                    // stream points without building one huge object in memory
                    w.write(json.substring(0, json.length - 1))
                    w.write(",\"points\":[")
                    db.pointDao().forTrip(t.id).forEachIndexed { k, p ->
                        if (k > 0) w.write(",")
                        w.write(pointArray(p))
                    }
                    w.write("]}")
                }
                w.write("],\"activityEvents\":[")
                db.activityEventDao().all().forEachIndexed { i, e ->
                    if (i > 0) w.write(",")
                    w.write("[${e.time},${e.activityType},${e.transition}]")
                }
                w.write("]},")

                // ---------------------------------------------------------------- user data
                w.write("\"userData\":{\"places\":[")
                places.forEachIndexed { i, p ->
                    if (i > 0) w.write(",")
                    w.write(
                        JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("radiusM", p.radiusM)
                            .put("kind", p.kind).put("userNamed", p.userNamed).toString(),
                    )
                }
                w.write("],\"routeNames\":[")
                routes.filter { it.userNamed }.forEachIndexed { i, r ->
                    if (i > 0) w.write(",")
                    w.write(JSONObject().put("name", r.name).put("representativeTripUuid", r.representativeTripId?.let { uuidById[it] }).toString())
                }
                w.write("],\"intersectionNames\":[")
                intersections.filter { it.userName != null }.forEachIndexed { i, x ->
                    if (i > 0) w.write(",")
                    w.write(JSONObject().put("userName", x.userName).put("lat", x.lat).put("lon", x.lon).put("osmNodeId", x.osmNodeId).toString())
                }
                w.write("]},")

                // ---------------------------------------------------------------- derived (informative)
                w.write("\"derived\":{\"note\":\"Abgeleitete Daten – werden beim Import neu berechnet\",\"trips\":[")
                var first = true
                for (t in trips) {
                    val v = views[t.id] ?: continue
                    if (!first) w.write(",")
                    first = false
                    w.write(
                        JSONObject().put("uuid", t.uuid).put("analysisVersion", v.analysisVersion)
                            .put("movementStart", Instant.ofEpochMilli(v.startTime).toString())
                            .put("mode", v.mode).put("route", v.routeId?.let { routeById[it]?.name })
                            .put("distanceM", v.distanceM).put("durationS", v.durationS).put("movingS", v.movingS)
                            .put("waitS", v.waitS).put("stops", v.stopCount).toString(),
                    )
                }
                w.write("]}}")
            }
        } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** One row per trip – for spreadsheets. Local times in the trip's own time zone. */
    suspend fun exportTripsCsv(resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val rows = db.tripDao().analyzed().map { it.toView() }
        val routes = db.routeDao().all().associateBy { it.id }
        val places = db.placeDao().all().associateBy { it.id }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        resolver.openOutputStream(uri)?.use { os ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                w.write("uuid;start_local;weekday;from;to;route;mode;distance_km;duration_s;moving_s;probable_wait_s;stops;elevation_gain_m;excluded;demo;analysis_version\n")
                for (t in rows) {
                    val zone = runCatching { ZoneId.of(t.zoneId) }.getOrDefault(ZoneId.systemDefault())
                    val cols = listOf(
                        t.uuid,
                        fmt.format(Instant.ofEpochMilli(t.startTime).atZone(zone)),
                        t.dayOfWeek.toString(),
                        t.startPlaceId?.let { places[it]?.name }.orEmpty(),
                        t.endPlaceId?.let { places[it]?.name }.orEmpty(),
                        t.routeId?.let { routes[it]?.name }.orEmpty(),
                        t.mode,
                        String.format(Locale.ROOT, "%.3f", t.distanceM / 1000),
                        String.format(Locale.ROOT, "%.0f", t.durationS),
                        String.format(Locale.ROOT, "%.0f", t.movingS),
                        String.format(Locale.ROOT, "%.1f", t.waitS),
                        t.stopCount.toString(),
                        t.elevationGainM?.let { String.format(Locale.ROOT, "%.0f", it) }.orEmpty(),
                        if (t.excluded) "1" else "0",
                        if (t.isDemo) "1" else "0",
                        t.analysisVersion?.toString().orEmpty(),
                    )
                    w.write(cols.joinToString(";") { csv(it) })
                    w.write("\n")
                }
            }
        } ?: error("Datei konnte nicht geöffnet werden")
    }

    /** One trip as GPX 1.1 (Strava, Komoot, GPXSee …) – raw points, unmodified. */
    suspend fun exportGpx(resolver: ContentResolver, uri: Uri, tripId: Long) = withContext(Dispatchers.IO) {
        val trip = db.tripDao().get(tripId) ?: error("Fahrt nicht gefunden")
        val pts = db.pointDao().forTrip(tripId)
        val fmt = DateTimeFormatter.ISO_INSTANT
        resolver.openOutputStream(uri)?.use { os ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                w.write("<gpx version=\"1.1\" creator=\"Pendel\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                w.write("<trk><name>Fahrt ${fmt.format(Instant.ofEpochMilli(trip.recordedStart))}</name><trkseg>\n")
                for (p in pts) {
                    w.write("<trkpt lat=\"${p.latE7 / 1e7}\" lon=\"${p.lonE7 / 1e7}\">")
                    (p.altDm ?: p.baroAltDm)?.let { w.write("<ele>${it / 10.0}</ele>") }
                    w.write("<time>${fmt.format(Instant.ofEpochMilli(p.t))}</time></trkpt>\n")
                }
                w.write("</trkseg></trk>\n</gpx>\n")
            }
        } ?: error("Datei konnte nicht geöffnet werden")
    }

    private fun pointArray(p: TrackPointEntity): String {
        fun n(v: Int?, div: Double) = v?.let { (it / div).toString() } ?: "null"
        return "[${p.t},${p.latE7 / 1e7},${p.lonE7 / 1e7},${p.accDm / 10.0}," +
            "${if (p.speedCms >= 0) p.speedCms / 100.0 else "null"},${if (p.bearingDeg >= 0) p.bearingDeg else "null"}," +
            "${n(p.altDm, 10.0)},${n(p.speedAccCms, 100.0)},${n(p.vAccDm, 10.0)},${n(p.baroAltDm, 10.0)}]"
    }

    private fun csv(v: String): String =
        if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
