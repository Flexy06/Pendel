package de.flexy.pendel.data.export

import android.content.ContentResolver
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import de.flexy.pendel.core.geo.GeoMath
import de.flexy.pendel.data.db.ActivityEventEntity
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.PlaceEntity
import de.flexy.pendel.data.db.TrackPointEntity
import de.flexy.pendel.data.db.TripEntity
import de.flexy.pendel.data.db.TripState
import de.flexy.pendel.data.rawPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

data class ImportResult(val tripsImported: Int, val tripsSkipped: Int, val points: Int, val places: Int)

/**
 * Restores a JSON export (format version 1 or 2) – the path for moving to a new phone.
 *
 * Only raw data and user data are imported; everything derived is rebuilt by the normal analysis.
 * Trips are de-duplicated by UUID, so importing the same file twice (or merging two devices) is safe.
 * Streams the file (JsonReader) so multi-year exports don't need to fit into memory.
 */
class Importer(private val db: PendelDatabase) {

    suspend fun import(resolver: ContentResolver, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        var points = 0
        var placesAdded = 0
        resolver.openInputStream(uri)?.use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "raw" -> {
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "trips" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        val res = readTrip(r, legacy = false)
                                        if (res < 0) skipped++ else { imported++; points += res }
                                    }
                                    r.endArray()
                                }
                                "activityEvents" -> readActivityEvents(r)
                                else -> r.skipValue()
                            }
                            r.endObject()
                        }
                        // format version 1 had trips at the top level (derived + raw mixed)
                        "trips" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                val res = readTrip(r, legacy = true)
                                if (res < 0) skipped++ else { imported++; points += res }
                            }
                            r.endArray()
                        }
                        "userData" -> {
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "places" -> placesAdded += readPlaces(r)
                                else -> r.skipValue() // route/intersection names: re-attached in a later version
                            }
                            r.endObject()
                        }
                        "places" -> placesAdded += readPlaces(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
        } ?: error("Datei konnte nicht geöffnet werden")
        ImportResult(imported, skipped, points, placesAdded)
    }

    /** @return number of points, or -1 if the trip already exists. */
    private suspend fun readTrip(r: JsonReader, legacy: Boolean): Int {
        var uuid: String? = null
        var start: Long? = null
        var end: Long? = null
        var zoneId = java.time.ZoneId.systemDefault().id
        var trigger = "IMPORT"
        var userMode: String? = null
        var activityMode: String? = null
        var excluded = false
        var note: String? = null
        var isDemo = false
        var createdAt = System.currentTimeMillis()
        val pts = ArrayList<DoubleArray?>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            when (name) {
                "uuid" -> uuid = r.nextString()
                "recordedStart" -> start = r.nextLong()
                "recordedEnd" -> end = r.nextLong()
                "start" -> if (legacy) start = java.time.Instant.parse(r.nextString()).toEpochMilli() else r.skipValue()
                "zoneId" -> zoneId = r.nextString()
                "trigger" -> trigger = r.nextString()
                "userMode" -> userMode = r.nextString()
                "activityMode" -> activityMode = r.nextString()
                "excluded" -> excluded = r.nextBoolean()
                "note" -> note = r.nextString()
                "isDemo", "demo" -> isDemo = r.nextBoolean()
                "createdAt" -> createdAt = r.nextLong()
                "points" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val row = DoubleArray(10) { Double.NaN }
                        var i = 0
                        while (r.hasNext()) {
                            if (r.peek() == JsonToken.NULL) r.nextNull() else if (i < 10) row[i] = r.nextDouble() else r.skipValue()
                            i++
                        }
                        r.endArray()
                        pts += row
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val id = uuid ?: "legacy-${start ?: 0}-${pts.size}"
        if (db.tripDao().byUuid(id) != null) return -1
        val rows = pts.filterNotNull().filter { !it[0].isNaN() && !it[1].isNaN() && !it[2].isNaN() }
        if (rows.size < 3) return -1
        // legacy (v1) rows: [t, lat, lon, acc, speed, alt]; v2 rows: see Exporter.pointFormat
        db.withTransaction {
            val tripId = db.tripDao().insert(
                TripEntity(
                    uuid = id,
                    recordedStart = start ?: rows.first()[0].toLong(),
                    recordedEnd = end ?: rows.last()[0].toLong(),
                    zoneId = zoneId,
                    trigger = trigger,
                    state = TripState.PROCESSING.name,
                    userMode = userMode,
                    activityMode = activityMode,
                    excluded = excluded,
                    note = note,
                    isDemo = isDemo,
                    createdAt = createdAt,
                ),
            )
            db.pointDao().insertAll(rows.map { toPoint(tripId, it, legacy) })
        }
        return rows.size
    }

    private fun toPoint(tripId: Long, a: DoubleArray, legacy: Boolean): TrackPointEntity {
        fun f(i: Int) = a[i].takeIf { !it.isNaN() }
        return if (legacy) {
            rawPoint(tripId, a[0].toLong(), a[1], a[2], (f(3) ?: 99.0).toFloat(), f(4)?.toFloat(), null, gpsAltitudeM = f(5))
        } else {
            rawPoint(
                tripId, a[0].toLong(), a[1], a[2], (f(3) ?: 99.0).toFloat(), f(4)?.toFloat(), f(5)?.toFloat(),
                gpsAltitudeM = f(6), speedAccuracyMs = f(7)?.toFloat(), verticalAccuracyM = f(8)?.toFloat(), baroAltitudeM = f(9),
            )
        }
    }

    private suspend fun readActivityEvents(r: JsonReader) {
        val events = ArrayList<ActivityEventEntity>()
        r.beginArray()
        while (r.hasNext()) {
            r.beginArray()
            val time = r.nextLong()
            val type = r.nextInt()
            val transition = r.nextInt()
            while (r.hasNext()) r.skipValue()
            r.endArray()
            events += ActivityEventEntity(time = time, activityType = type, transition = transition)
        }
        r.endArray()
        // de-duplicate against what is already there
        val existing = db.activityEventDao().all().map { Triple(it.time, it.activityType, it.transition) }.toHashSet()
        db.activityEventDao().insertAll(events.filter { Triple(it.time, it.activityType, it.transition) !in existing })
    }

    /** Places the user named are user data → restored (skipped if one already exists nearby). */
    private suspend fun readPlaces(r: JsonReader): Int {
        var added = 0
        val existing = db.placeDao().all().toMutableList()
        r.beginArray()
        while (r.hasNext()) {
            var name = ""
            var lat = Double.NaN
            var lon = Double.NaN
            var radius = 150.0
            var kind = "OTHER"
            var userNamed = false
            r.beginObject()
            while (r.hasNext()) {
                val n = r.nextName()
                if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                when (n) {
                    "name" -> name = r.nextString()
                    "lat" -> lat = r.nextDouble()
                    "lon" -> lon = r.nextDouble()
                    "radiusM" -> radius = r.nextDouble()
                    "kind" -> kind = r.nextString()
                    "userNamed" -> userNamed = r.nextBoolean()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!userNamed || lat.isNaN() || lon.isNaN()) continue
            val near = existing.firstOrNull { GeoMath.distance(it.lat, it.lon, lat, lon) < maxOf(radius, it.radiusM) }
            if (near != null) {
                if (!near.userNamed) db.placeDao().rename(near.id, name, kind)
                continue
            }
            val p = PlaceEntity(name = name, lat = lat, lon = lon, radiusM = radius, kind = kind, userNamed = true)
            existing += p.copy(id = db.placeDao().insert(p))
            added++
        }
        r.endArray()
        return added
    }
}
