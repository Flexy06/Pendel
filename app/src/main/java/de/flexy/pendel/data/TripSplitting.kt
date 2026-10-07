package de.flexy.pendel.data

import androidx.room.withTransaction
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.db.TripEntity
import de.flexy.pendel.data.db.TripState
import java.util.UUID

/**
 * Splits one recorded trip into several at the given times. Raw GPS fixes are only re-grouped
 * (moved to the new trip rows) – nothing is deleted. All parts are marked for (re-)analysis.
 *
 * @param cuts epoch-ms split points; fixes with t >= cut belong to the next part
 * @return ids of the newly created trips (the original keeps the first part)
 */
suspend fun splitTripAt(db: PendelDatabase, trip: TripEntity, cuts: List<Long>): List<Long> {
    val points = db.pointDao().forTrip(trip.id)
    if (points.size < 6) return emptyList()
    val bounds = (listOf(Long.MIN_VALUE) + cuts.sorted() + listOf(Long.MAX_VALUE)).zipWithNext()
    val parts = bounds.map { (from, to) -> points.filter { it.t >= from && it.t < to } }
    // a part without real data makes no sense – then the cut is wrong
    if (parts.any { it.size < 3 }) return emptyList()
    val newIds = ArrayList<Long>()
    db.withTransaction {
        for (part in parts.drop(1)) {
            val from = part.first().t
            val id = db.tripDao().insert(
                trip.copy(
                    id = 0, uuid = UUID.randomUUID().toString(), recordedStart = from, recordedEnd = part.last().t,
                    state = TripState.PROCESSING.name, createdAt = System.currentTimeMillis(),
                ),
            )
            db.pointDao().moveToTrip(trip.id, id, from, part.last().t + 1)
            newIds += id
        }
        db.tripDao().update(trip.copy(recordedEnd = parts.first().last().t, state = TripState.PROCESSING.name))
        // derived rows of the old, merged trip are stale now
        db.stopDao().deleteForTrip(trip.id)
        db.tripAnalysisDao().delete(trip.id)
    }
    return newIds
}
