package de.flexy.pendel.data.db

import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests 1 → 2 → 3 (run on a device/emulator: `./gradlew :app:connectedDebugAndroidTest`).
 *
 * Creates a real schema-v1 database from `schemas/.../1.json`, fills it with trips, raw GPS points,
 * stops, intersections and wait events, migrates it and checks
 *  - the migrated schema equals what Room expects for v2 (validateDroppedTables + table/index/FK check)
 *  - no raw data is lost, derived data is carried over with analysisVersion = 1
 *  - foreign-key integrity (PRAGMA foreign_key_check) and working cascades
 *  - the migrated file opens with the real Room database and DAOs.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PendelDatabase::class.java)

    @After
    fun cleanup() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DB)
    }

    private fun createV1WithData() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO places(id,name,lat,lon,radiusM,kind,userNamed) VALUES(1,'Zuhause',49.0,8.47,150,'HOME',0),(2,'Uni',49.015,8.39,150,'UNI',1)")
            execSQL("INSERT INTO routes(id,name,colorIndex,originPlaceId,destPlaceId,mode,signature,representativeTripId,userNamed,createdAt) VALUES(1,'Route A',0,1,2,'BICYCLE','_p~iF~ps|U',1,1,0)")
            execSQL(
                """INSERT INTO trips(id,startTime,endTime,zoneOffsetMin,dayOfWeek,startMinuteOfDay,endMinuteOfDay,mode,modeSource,trigger,state,
                   distanceM,durationS,movingS,stoppedS,stopCount,waitS,elevationGainM,elevationLossM,avgMovingSpeed,maxSpeed,startLat,startLon,endLat,endLon,
                   startPlaceId,endPlaceId,routeId,polyline,signature,bikeScore,via,pointCount,matchedBy,excluded,isDemo)
                   VALUES
                   (1,1000000,2800000,120,1,480,510,'BICYCLE','USER','MANUAL','ANALYZED',8000,1800,1700,100,3,45,12,10,4.7,8.1,49.0,8.47,49.015,8.39,1,2,1,'poly','sig',NULL,'Durlacher Allee',5,NULL,0,0),
                   (2,3000000,NULL,120,1,600,600,'UNKNOWN','SPEED','AUTO','PROCESSING',0,0,0,0,0,0,NULL,NULL,0,0,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,0,0),
                   (3,5000000,6000000,120,2,500,520,'BICYCLE','ACTIVITY','AUTO','ANALYZED',7000,1500,1400,100,1,0,NULL,NULL,4.6,7.9,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,5,NULL,1,1)""",
            )
            for ((tripId, base) in listOf(1L to 990_000L, 2L to 3_000_000L, 3L to 4_990_000L)) {
                for (k in 0 until 5) {
                    execSQL(
                        "INSERT INTO track_points(tripId,t,latE7,lonE7,accDm,speedCms,bearingDeg,altDm) VALUES(?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(tripId, base + k * 2000, 490_000_000 + k, 84_700_000 + k, 50, 500, 90, 1150),
                    )
                }
            }
            execSQL("INSERT INTO stops(id,tripId,startTime,endTime,lat,lon,durationS,alongM,kind,resumed) VALUES(1,1,1200000,1230000,49.005,8.45,30,2000,'STOP',1),(2,1,1500000,1520000,49.008,8.43,20,4000,'STOP',1)")
            execSQL("INSERT INTO intersections(id,lat,lon,name,userName,source,kind,osmNodeId,passCount) VALUES(1,49.005,8.45,'A × B',NULL,'OSM','TRAFFIC_SIGNALS',123,4),(2,49.008,8.43,NULL,'Meine Ampel','LEARNED','LEARNED',NULL,3)")
            execSQL("INSERT INTO wait_events(id,stopId,tripId,intersectionId,startTime,durationS,confidence,level,dayOfWeek,minuteOfDay) VALUES(1,1,1,1,1200000,30,0.8,'LIKELY',1,485),(2,2,1,2,1500000,20,0.5,'POSSIBLE',1,490)")
            close()
        }
    }

    private fun migrate(): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(DB, 3, true, Migration1To2("Europe/Berlin"), Migration2To3)

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.rows(sql: String): Int = query(sql).use { it.count }

    @Test
    fun migrate1To2_keepsAllRawData() {
        createV1WithData()
        val db = migrate()
        assertEquals(3, db.long("SELECT COUNT(*) FROM trips"))
        assertEquals(15, db.long("SELECT COUNT(*) FROM track_points"))
        // raw recording start restored from the raw points (v1 had overwritten it)
        assertEquals(990_000, db.long("SELECT recordedStart FROM trips WHERE id = 1"))
        assertEquals(998_000, db.long("SELECT recordedEnd FROM trips WHERE id = 1"))
        // user mode / activity mode separated
        db.query("SELECT userMode, activityMode, excluded, isDemo, zoneId FROM trips WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals("BICYCLE", c.getString(0)); assertTrue(c.isNull(1)); assertEquals(0, c.getInt(2)); assertEquals("Europe/Berlin", c.getString(4))
        }
        db.query("SELECT userMode, activityMode, excluded, isDemo FROM trips WHERE id = 3").use { c ->
            c.moveToFirst()
            assertTrue(c.isNull(0)); assertEquals("BICYCLE", c.getString(1)); assertEquals(1, c.getInt(2)); assertEquals(1, c.getInt(3))
        }
        // every trip got a unique uuid
        assertEquals(3, db.long("SELECT COUNT(DISTINCT uuid) FROM trips"))
        // new raw columns exist and are empty for old rows
        assertEquals(15, db.long("SELECT COUNT(*) FROM track_points WHERE baroAltDm IS NULL AND altDm = 1150"))
        db.close()
    }

    @Test
    fun migrate1To2_carriesDerivedDataWithVersion() {
        createV1WithData()
        val db = migrate()
        // complete analysis → trip_analysis; incomplete analysis → re-queued instead of guessed
        assertEquals(1, db.long("SELECT COUNT(*) FROM trip_analysis"))
        db.query("SELECT analysisVersion, movementStart, routeId, waitS, via, distanceM FROM trip_analysis WHERE tripId = 1").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0)); assertEquals(1_000_000, c.getLong(1)); assertEquals(1, c.getLong(2))
            assertEquals(45.0, c.getDouble(3), 1e-9); assertEquals("Durlacher Allee", c.getString(4)); assertEquals(8000.0, c.getDouble(5), 1e-9)
        }
        assertEquals("ANALYZED", string(db, "SELECT state FROM trips WHERE id = 1"))
        assertEquals("PROCESSING", string(db, "SELECT state FROM trips WHERE id = 3"))
        // stops keep their data and get a version
        assertEquals(2, db.long("SELECT COUNT(*) FROM stops WHERE analysisVersion = 1"))
        // wait events enriched: position, route, kind, detection method
        assertEquals(2, db.long("SELECT COUNT(*) FROM wait_events"))
        db.query("SELECT lat, lon, routeId, kind, detectionMethod, confidence, analysisVersion FROM wait_events WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(49.005, c.getDouble(0), 1e-9); assertEquals(8.45, c.getDouble(1), 1e-9); assertEquals(1, c.getLong(2))
            assertEquals("PROBABLE_INTERSECTION_WAIT", c.getString(3)); assertEquals("GPS_STOP_NEAR_OSM_FEATURE", c.getString(4))
            assertEquals(0.8, c.getDouble(5), 1e-9); assertEquals(1, c.getInt(6))
        }
        assertEquals("GPS_STOP_NEAR_LEARNED_HOTSPOT", string(db, "SELECT detectionMethod FROM wait_events WHERE id = 2"))
        // user data untouched
        assertEquals("Meine Ampel", string(db, "SELECT userName FROM intersections WHERE id = 2"))
        assertEquals(1, db.long("SELECT userNamed FROM routes WHERE id = 1"))
        assertEquals(1, db.long("SELECT analysisVersion FROM routes WHERE id = 1"))
        // v3: place groups – new column, existing places stay independent
        assertEquals(2, db.long("SELECT COUNT(*) FROM places WHERE parentPlaceId IS NULL"))
        // the app must refresh derived passes after the migration
        assertEquals("PENDING", string(db, "SELECT status FROM analysis_runs ORDER BY id DESC LIMIT 1"))
        db.close()
    }

    @Test
    fun migrate1To2_foreignKeysIntactAndCascading() {
        createV1WithData()
        val db = migrate()
        assertEquals(0, db.rows("PRAGMA foreign_key_check"))
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM trips WHERE id = 1")
        for (table in listOf("track_points", "stops", "wait_events", "trip_analysis", "intersection_passes")) {
            assertEquals("$table not cascaded", 0, db.long("SELECT COUNT(*) FROM $table WHERE tripId = 1"))
        }
        assertEquals(10, db.long("SELECT COUNT(*) FROM track_points"))
        // autoincrement continues after the copied ids
        db.execSQL("INSERT INTO trips(uuid,recordedStart,zoneId,trigger,state,excluded,isDemo,createdAt) VALUES('x',1,'Europe/Berlin','MANUAL','RECORDING',0,0,0)")
        assertEquals(4, db.long("SELECT MAX(id) FROM trips"))
        db.close()
    }

    @Test
    fun migrate1To2_emptyDatabase() {
        helper.createDatabase(DB, 1).close()
        val db = migrate()
        assertEquals(0, db.long("SELECT COUNT(*) FROM trips"))
        assertEquals(0, db.rows("PRAGMA foreign_key_check"))
        db.close()
    }

    @Test
    fun migratedDatabaseOpensWithRoom() {
        createV1WithData()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), PendelDatabase::class.java, DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        runBlocking {
            assertEquals(3, room.tripDao().all().size)
            val analyzed = room.tripDao().analyzed()
            assertEquals(1, analyzed.size)
            assertNotNull(analyzed.first().analysis)
            assertEquals(5, room.pointDao().forTrip(1).size)
            assertNull(room.pointDao().forTrip(1).first().baroAltDm)
            assertEquals(1, room.tripAnalysisDao().countOlderThan(2))
        }
        room.close()
    }

    private fun string(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { c: Cursor -> c.moveToFirst(); c.getString(0) }

    companion object {
        private const val DB = "migration-test.db"
    }
}
