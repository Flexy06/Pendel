package de.flexy.pendel.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.ZoneId

/**
 * v1 → v2: split raw trip data from derived analysis, version all derived data, enrich wait events,
 * add raw sensor columns and new tables.
 *
 * Strategy:
 *  - `trips` and `wait_events` are rebuilt (create-new → copy → drop → rename), as recommended for
 *    structural changes in SQLite. Foreign keys are not enforced during Room migrations.
 *  - `track_points` – the largest table – only gets ADD COLUMN (no copy, fast even with millions of rows).
 *  - The raw recording start/end are restored from the raw points (v1 had overwritten them with
 *    the trimmed analysis times).
 *  - Analyzed trips with incomplete data are re-queued for analysis instead of being guessed.
 *  - `intersection_passes` cannot be derived in SQL; a PENDING analysis run makes the app refresh the
 *    global analysis on the next start.
 */
class Migration1To2(private val zoneId: String = ZoneId.systemDefault().id) : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        for (sql in statements(zoneId)) db.execSQL(sql)
    }

    companion object {
        fun statements(zoneId: String): List<String> = SQL.map { it.replace("{ZONE}", zoneId.replace("'", "''")) }

        private val SQL = listOf(
        """
CREATE TABLE IF NOT EXISTS `trips_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, `recordedStart` INTEGER NOT NULL, `recordedEnd` INTEGER, `zoneId` TEXT NOT NULL, `trigger` TEXT NOT NULL, `state` TEXT NOT NULL, `userMode` TEXT, `activityMode` TEXT, `excluded` INTEGER NOT NULL, `note` TEXT, `isDemo` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)
        """.trimIndent(),
        """
INSERT INTO `trips_new` (`id`, `uuid`, `recordedStart`, `recordedEnd`, `zoneId`, `trigger`, `state`, `userMode`, `activityMode`, `excluded`, `note`, `isDemo`, `createdAt`)
SELECT t.`id`,
       lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || hex(randomblob(6))),
       COALESCE((SELECT MIN(p.`t`) FROM `track_points` p WHERE p.`tripId` = t.`id`), t.`startTime`),
       COALESCE((SELECT MAX(p.`t`) FROM `track_points` p WHERE p.`tripId` = t.`id`), t.`endTime`),
       '{ZONE}',
       t.`trigger`,
       t.`state`,
       CASE WHEN t.`modeSource` = 'USER' THEN t.`mode` END,
       CASE WHEN t.`modeSource` = 'ACTIVITY' THEN t.`mode` END,
       t.`excluded`, NULL, t.`isDemo`, t.`startTime`
FROM `trips` t
        """.trimIndent(),
        """
CREATE TABLE IF NOT EXISTS `trip_analysis` (`tripId` INTEGER NOT NULL, `analysisVersion` INTEGER NOT NULL, `analyzedAt` INTEGER NOT NULL, `movementStart` INTEGER NOT NULL, `movementEnd` INTEGER NOT NULL, `zoneOffsetMin` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinuteOfDay` INTEGER NOT NULL, `endMinuteOfDay` INTEGER NOT NULL, `mode` TEXT NOT NULL, `distanceM` REAL NOT NULL, `durationS` REAL NOT NULL, `movingS` REAL NOT NULL, `stoppedS` REAL NOT NULL, `stopCount` INTEGER NOT NULL, `waitS` REAL NOT NULL, `elevationGainM` REAL, `elevationLossM` REAL, `avgMovingSpeed` REAL NOT NULL, `maxSpeed` REAL NOT NULL, `startLat` REAL NOT NULL, `startLon` REAL NOT NULL, `endLat` REAL NOT NULL, `endLon` REAL NOT NULL, `startPlaceId` INTEGER, `endPlaceId` INTEGER, `routeId` INTEGER, `polyline` TEXT NOT NULL, `signature` TEXT NOT NULL, `bikeScore` REAL, `via` TEXT, `pointCount` INTEGER NOT NULL, `matchedBy` TEXT, PRIMARY KEY(`tripId`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent(),
        """
INSERT INTO `trip_analysis` (`tripId`, `analysisVersion`, `analyzedAt`, `movementStart`, `movementEnd`, `zoneOffsetMin`, `dayOfWeek`, `startMinuteOfDay`, `endMinuteOfDay`, `mode`, `distanceM`, `durationS`, `movingS`, `stoppedS`, `stopCount`, `waitS`, `elevationGainM`, `elevationLossM`, `avgMovingSpeed`, `maxSpeed`, `startLat`, `startLon`, `endLat`, `endLon`, `startPlaceId`, `endPlaceId`, `routeId`, `polyline`, `signature`, `bikeScore`, `via`, `pointCount`, `matchedBy`)
SELECT `id`, 1, CAST(strftime('%s', 'now') AS INTEGER) * 1000, `startTime`, COALESCE(`endTime`, `startTime`), `zoneOffsetMin`, `dayOfWeek`, `startMinuteOfDay`, `endMinuteOfDay`, `mode`, `distanceM`, `durationS`, `movingS`, `stoppedS`, `stopCount`, `waitS`, `elevationGainM`, `elevationLossM`, `avgMovingSpeed`, `maxSpeed`, `startLat`, `startLon`, `endLat`, `endLon`, `startPlaceId`, `endPlaceId`, `routeId`, `polyline`, `signature`, `bikeScore`, `via`, `pointCount`, `matchedBy`
FROM `trips`
WHERE `state` = 'ANALYZED' AND `startLat` IS NOT NULL AND `startLon` IS NOT NULL AND `endLat` IS NOT NULL AND `endLon` IS NOT NULL AND `polyline` IS NOT NULL AND `signature` IS NOT NULL
        """.trimIndent(),
        """
UPDATE `trips_new` SET `state` = 'PROCESSING' WHERE `state` = 'ANALYZED' AND `id` NOT IN (SELECT `tripId` FROM `trip_analysis`)
        """.trimIndent(),
        """
CREATE TABLE IF NOT EXISTS `wait_events_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stopId` INTEGER NOT NULL, `tripId` INTEGER NOT NULL, `intersectionId` INTEGER NOT NULL, `routeId` INTEGER, `startTime` INTEGER NOT NULL, `durationS` REAL NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `distanceToIntersectionM` REAL, `confidence` REAL NOT NULL, `level` TEXT NOT NULL, `kind` TEXT NOT NULL, `detectionMethod` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, `analysisVersion` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`intersectionId`) REFERENCES `intersections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`stopId`) REFERENCES `stops`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent(),
        """
INSERT INTO `wait_events_new` (`id`, `stopId`, `tripId`, `intersectionId`, `routeId`, `startTime`, `durationS`, `lat`, `lon`, `distanceToIntersectionM`, `confidence`, `level`, `kind`, `detectionMethod`, `dayOfWeek`, `minuteOfDay`, `analysisVersion`)
SELECT w.`id`, w.`stopId`, w.`tripId`, w.`intersectionId`, t.`routeId`, w.`startTime`, w.`durationS`, s.`lat`, s.`lon`, NULL, w.`confidence`, w.`level`,
       'PROBABLE_INTERSECTION_WAIT',
       CASE i.`source` WHEN 'OSM' THEN 'GPS_STOP_NEAR_OSM_FEATURE' ELSE 'GPS_STOP_NEAR_LEARNED_HOTSPOT' END,
       w.`dayOfWeek`, w.`minuteOfDay`, 1
FROM `wait_events` w
JOIN `stops` s ON s.`id` = w.`stopId`
JOIN `intersections` i ON i.`id` = w.`intersectionId`
LEFT JOIN `trips` t ON t.`id` = w.`tripId`
        """.trimIndent(),
        """
DROP TABLE `wait_events`
        """.trimIndent(),
        """
ALTER TABLE `wait_events_new` RENAME TO `wait_events`
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_wait_events_tripId` ON `wait_events` (`tripId`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_wait_events_intersectionId` ON `wait_events` (`intersectionId`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_wait_events_stopId` ON `wait_events` (`stopId`)
        """.trimIndent(),
        """
DROP TABLE `trips`
        """.trimIndent(),
        """
ALTER TABLE `trips_new` RENAME TO `trips`
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_trips_recordedStart` ON `trips` (`recordedStart`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_trips_state` ON `trips` (`state`)
        """.trimIndent(),
        """
CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_uuid` ON `trips` (`uuid`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_trip_analysis_routeId` ON `trip_analysis` (`routeId`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_trip_analysis_movementStart` ON `trip_analysis` (`movementStart`)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_trip_analysis_startPlaceId_endPlaceId` ON `trip_analysis` (`startPlaceId`, `endPlaceId`)
        """.trimIndent(),
        """
ALTER TABLE `track_points` ADD COLUMN `speedAccCms` INTEGER
        """.trimIndent(),
        """
ALTER TABLE `track_points` ADD COLUMN `vAccDm` INTEGER
        """.trimIndent(),
        """
ALTER TABLE `track_points` ADD COLUMN `baroAltDm` INTEGER
        """.trimIndent(),
        """
ALTER TABLE `stops` ADD COLUMN `analysisVersion` INTEGER NOT NULL DEFAULT 1
        """.trimIndent(),
        """
ALTER TABLE `routes` ADD COLUMN `analysisVersion` INTEGER NOT NULL DEFAULT 1
        """.trimIndent(),
        """
CREATE TABLE IF NOT EXISTS `activity_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `activityType` INTEGER NOT NULL, `transition` INTEGER NOT NULL)
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_activity_events_time` ON `activity_events` (`time`)
        """.trimIndent(),
        """
CREATE TABLE IF NOT EXISTS `intersection_passes` (`intersectionId` INTEGER NOT NULL, `tripId` INTEGER NOT NULL, `passTime` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, `analysisVersion` INTEGER NOT NULL, PRIMARY KEY(`intersectionId`, `tripId`), FOREIGN KEY(`intersectionId`) REFERENCES `intersections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        """.trimIndent(),
        """
CREATE INDEX IF NOT EXISTS `index_intersection_passes_tripId` ON `intersection_passes` (`tripId`)
        """.trimIndent(),
        """
CREATE TABLE IF NOT EXISTS `analysis_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `analysisVersion` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `tripCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `message` TEXT)
        """.trimIndent(),
        """
INSERT INTO `analysis_runs` (`analysisVersion`, `startedAt`, `finishedAt`, `tripCount`, `status`, `message`)
VALUES (1, CAST(strftime('%s', 'now') AS INTEGER) * 1000, NULL, 0, 'PENDING', 'Migration 1→2: global analysis must be refreshed')
        """.trimIndent()
        )
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(Migration1To2())
