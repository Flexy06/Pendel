package de.flexy.pendel.data.demo

import android.content.Context
import androidx.room.withTransaction
import de.flexy.pendel.analysis.AnalysisWorker
import de.flexy.pendel.core.demo.SyntheticRides
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.PlaceEntity
import de.flexy.pendel.data.db.TripEntity
import de.flexy.pendel.data.db.TripState
import de.flexy.pendel.data.db.TripTrigger
import de.flexy.pendel.data.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * Demo mode: generates realistic rides in Karlsruhe and stores them as *raw GPS points*.
 * They run through the identical analysis pipeline as real recordings – nothing is faked
 * downstream. Demo trips are flagged and can be deleted with one tap.
 */
class DemoDataGenerator(
    private val context: Context,
    private val db: PendelDatabase,
) {
    suspend fun generate(weeks: Int = 10, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.Default) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusWeeks(weeks.toLong())
        val routes = SyntheticRides.karlsruheRoutes
        val now = System.currentTimeMillis()
        // name the demo sport place so the second destination tab is recognisable; it is not
        // user-named, so it disappears together with the demo trips
        if (db.placeDao().all().none { it.name == "Sport" }) {
            db.placeDao().insert(
                PlaceEntity(name = "Sport", lat = SyntheticRides.sportDestination.lat, lon = SyntheticRides.sportDestination.lon, kind = "OTHER"),
            )
        }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        days.forEachIndexed { index, date ->
            onProgress(index / days.size.toFloat())
            val dow = date.dayOfWeek.value
            val rnd = Random(date.toEpochDay().toInt())
            val rides = ArrayList<Pair<Int, Boolean>>() // minuteOfDay, reverse
            if (dow == 2 || dow == 4) {
                // second destination: sport on Tuesday/Thursday evenings (separate corridor, never
                // compared with the uni routes)
                if (rnd.nextDouble() < 0.8) {
                    rides += (1080 + rnd.nextInt(0, 30)) to false // 18:00 → Sport
                    rides += (1200 + rnd.nextInt(0, 30)) to true // 20:00 → home
                }
            }
            if (dow <= 5) {
                if (rnd.nextDouble() < 0.9) rides += (415 + rnd.nextInt(0, 200)) to false // 06:55 … 10:15
                if (rnd.nextDouble() < 0.85) rides += (930 + rnd.nextInt(0, 180)) to true // 15:30 … 18:30
            } else if (rnd.nextDouble() < 0.25) {
                rides += (600 + rnd.nextInt(0, 240)) to false
            }
            for ((minute, reverse) in rides) {
                val startMs = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
                if (startMs > now - 3_600_000) continue
                val r = rnd.nextDouble()
                val sport = (dow == 2 || dow == 4) && ((minute in 1080..1110 && !reverse) || (minute in 1200..1230 && reverse))
                val route = when {
                    sport -> SyntheticRides.karlsruheSportRoutes[if (r < 0.6) 0 else 1]
                    r < 0.40 -> routes[0]
                    r < 0.75 -> routes[1]
                    else -> routes[2]
                }
                val seed = (date.toEpochDay() * 31 + minute).toInt()
                val points = SyntheticRides.ride(route, startMs, dow, minute, seed, reverse = reverse)
                db.withTransaction {
                    val id = db.tripDao().insert(
                        TripEntity(
                            uuid = UUID.randomUUID().toString(),
                            recordedStart = points.first().t,
                            recordedEnd = points.last().t,
                            zoneId = zone.id,
                            trigger = TripTrigger.DEMO.name,
                            state = TripState.PROCESSING.name,
                            activityMode = "BICYCLE",
                            isDemo = true,
                            // demo rides have no map matching – the street name stands in for it
                            note = route.name,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    db.pointDao().insertAll(points.map { it.toEntity(id) })
                }
            }
        }
        onProgress(1f)
        AnalysisWorker.enqueue(context)
    }
}
