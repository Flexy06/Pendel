package de.flexy.pendel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * DAO design: one DAO per table/aggregate. Flow only where the UI observes data; the worker and
 * repositories use plain suspend functions. There is deliberately no StatisticsDao – statistics are
 * computed from derived rows and never stored.
 */

// ============================================================================ raw

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): TripEntity?

    @Query("SELECT * FROM trips WHERE state = :state")
    suspend fun byState(state: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE state != 'RECORDING' ORDER BY recordedStart")
    suspend fun allFinished(): List<TripEntity>

    @Query("SELECT * FROM trips ORDER BY recordedStart")
    suspend fun all(): List<TripEntity>

    /**
     * UI read model: trips with their analysis. PROCESSING is included so trips don't disappear while
     * a re-analysis is running (they keep showing their previous analysis until it is replaced).
     */
    @Transaction
    @Query("SELECT * FROM trips WHERE state IN ('ANALYZED', 'PROCESSING') ORDER BY recordedStart DESC")
    fun observeAnalyzed(): Flow<List<TripWithAnalysis>>

    /** For the global analysis (worker). */
    @Transaction
    @Query("SELECT * FROM trips WHERE state = 'ANALYZED' ORDER BY recordedStart")
    suspend fun analyzed(): List<TripWithAnalysis>

    @Query("UPDATE trips SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    /** Mark everything for re-derivation from raw points. */
    @Query("UPDATE trips SET state = 'PROCESSING' WHERE state IN ('ANALYZED', 'FAILED')")
    suspend fun markAllForReanalysis(): Int

    @Query("UPDATE trips SET excluded = :excluded WHERE id = :id")
    suspend fun setExcluded(id: Long, excluded: Boolean)

    @Query("UPDATE trips SET userMode = :mode WHERE id = :id")
    suspend fun setUserMode(id: Long, mode: String?)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    /** Range delete on the raw recording time; dependent rows cascade. */
    @Query("DELETE FROM trips WHERE recordedStart >= :from AND recordedStart < :to AND state != 'RECORDING'")
    suspend fun deleteBetween(from: Long, to: Long): Int

    @Query("DELETE FROM trips WHERE isDemo = 1")
    suspend fun deleteDemo(): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM trips WHERE isDemo = 1")
    fun observeDemoCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trips")
    fun observeCount(): Flow<Int>
}

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY t")
    suspend fun forTrip(tripId: Long): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_points WHERE tripId = :tripId")
    suspend fun count(tripId: Long): Int

    @Query("SELECT MIN(t) FROM track_points WHERE tripId = :tripId")
    suspend fun firstTime(tripId: Long): Long?

    @Query("SELECT MAX(t) FROM track_points WHERE tripId = :tripId")
    suspend fun lastTime(tripId: Long): Long?

    @Query("SELECT COUNT(*) FROM track_points")
    fun observeTotalCount(): Flow<Int>

    /** Re-groups raw fixes into another trip (used when one recording contains two rides). */
    @Query("UPDATE track_points SET tripId = :toTrip WHERE tripId = :fromTrip AND t >= :from AND t < :to")
    suspend fun moveToTrip(fromTrip: Long, toTrip: Long, from: Long, to: Long): Int
}

@Dao
interface ActivityEventDao {
    @Insert
    suspend fun insertAll(events: List<ActivityEventEntity>)

    @Query("SELECT * FROM activity_events WHERE time BETWEEN :from AND :to ORDER BY time")
    suspend fun between(from: Long, to: Long): List<ActivityEventEntity>

    @Query("SELECT * FROM activity_events ORDER BY time")
    suspend fun all(): List<ActivityEventEntity>

    @Query("DELETE FROM activity_events WHERE time >= :from AND time < :to")
    suspend fun deleteBetween(from: Long, to: Long): Int

    @Query("DELETE FROM activity_events")
    suspend fun deleteAll()
}

// ============================================================================ derived

@Dao
interface TripAnalysisDao {
    @Upsert
    suspend fun upsert(a: TripAnalysisEntity)

    @Query("SELECT * FROM trip_analysis WHERE tripId = :tripId")
    suspend fun get(tripId: Long): TripAnalysisEntity?

    @Query("UPDATE trip_analysis SET startPlaceId = :start, endPlaceId = :end, routeId = :route, waitS = :waitS WHERE tripId = :tripId")
    suspend fun setAssignment(tripId: Long, start: Long?, end: Long?, route: Long?, waitS: Double)

    @Query("SELECT COUNT(*) FROM trip_analysis WHERE analysisVersion < :version")
    suspend fun countOlderThan(version: Int): Int

    @Query("DELETE FROM trip_analysis WHERE tripId = :tripId")
    suspend fun delete(tripId: Long)
}

@Dao
interface StopDao {
    @Insert
    suspend fun insertAll(stops: List<StopEntity>): List<Long>

    @Query("DELETE FROM stops WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)

    @Query("SELECT * FROM stops WHERE tripId = :tripId ORDER BY startTime")
    fun observeForTrip(tripId: Long): Flow<List<StopEntity>>

    @Query("SELECT * FROM stops")
    suspend fun all(): List<StopEntity>

    @Query("SELECT * FROM stops WHERE kind = 'STOP'")
    fun observeRegular(): Flow<List<StopEntity>>
}

@Dao
interface WaitEventDao {
    @Insert
    suspend fun insertAll(events: List<WaitEventEntity>)

    @Query("DELETE FROM wait_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM wait_events")
    fun observeAll(): Flow<List<WaitEventEntity>>

    @Query("SELECT * FROM wait_events WHERE tripId = :tripId")
    fun observeForTrip(tripId: Long): Flow<List<WaitEventEntity>>
}

@Dao
interface IntersectionDao {
    @Insert
    suspend fun insert(i: IntersectionEntity): Long

    @Update
    suspend fun update(i: IntersectionEntity)

    @Query("SELECT * FROM intersections")
    suspend fun all(): List<IntersectionEntity>

    @Query("SELECT * FROM intersections WHERE passCount > 0")
    fun observeRelevant(): Flow<List<IntersectionEntity>>

    @Query("SELECT * FROM intersections WHERE osmNodeId = :nodeId")
    suspend fun byOsmNode(nodeId: Long): IntersectionEntity?

    @Query("UPDATE intersections SET passCount = 0")
    suspend fun resetPassCounts()

    @Query("UPDATE intersections SET passCount = :count WHERE id = :id")
    suspend fun setPassCount(id: Long, count: Int)

    @Query("UPDATE intersections SET userName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("DELETE FROM intersections WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM intersections WHERE source = 'LEARNED'")
    suspend fun deleteLearned()

    @Query("DELETE FROM intersections")
    suspend fun deleteAll()
}

@Dao
interface IntersectionPassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(passes: List<IntersectionPassEntity>)

    @Query("DELETE FROM intersection_passes")
    suspend fun deleteAll()

    @Query("SELECT * FROM intersection_passes")
    fun observeAll(): Flow<List<IntersectionPassEntity>>
}

@Dao
interface RouteDao {
    @Insert
    suspend fun insert(r: RouteEntity): Long

    @Update
    suspend fun update(r: RouteEntity)

    @Query("SELECT * FROM routes ORDER BY id")
    suspend fun all(): List<RouteEntity>

    @Query("SELECT * FROM routes ORDER BY id")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("UPDATE routes SET name = :name, userNamed = 1 WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM routes WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}

@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(p: PlaceEntity): Long

    @Update
    suspend fun update(p: PlaceEntity)

    @Query("SELECT * FROM places")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("UPDATE places SET name = :name, kind = :kind, userNamed = 1 WHERE id = :id")
    suspend fun rename(id: Long, name: String, kind: String)

    /** Place group: [parentId] = null removes the place from its group. */
    @Query("UPDATE places SET parentPlaceId = :parentId, userNamed = 1 WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?)

    @Query("DELETE FROM places WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM places")
    suspend fun deleteAll()
}

@Dao
interface AnalysisRunDao {
    @Insert
    suspend fun insert(r: AnalysisRunEntity): Long

    @Update
    suspend fun update(r: AnalysisRunEntity)

    @Query("SELECT * FROM analysis_runs ORDER BY id DESC LIMIT 1")
    fun observeLatest(): Flow<AnalysisRunEntity?>

    @Query("SELECT status FROM analysis_runs ORDER BY id DESC LIMIT 1")
    suspend fun latestStatus(): String?

    @Query("DELETE FROM analysis_runs WHERE id NOT IN (SELECT id FROM analysis_runs ORDER BY id DESC LIMIT 50)")
    suspend fun prune()
}
