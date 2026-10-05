package de.flexy.pendel.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Schema v2 – strict separation:
 *
 *  RAW (never modified by analysis, sufficient to re-derive everything):
 *    trips, track_points, activity_events
 *
 *  USER DATA (survives re-analysis): names/flags on places, routes, intersections; trip flags
 *    (excluded, userMode, note) live on the raw trip row.
 *
 *  DERIVED (re-computable, stamped with analysisVersion):
 *    trip_analysis, stops, wait_events, intersection_passes, routes, learned intersections
 *
 *  REFERENCE CACHE (external, re-downloadable): OSM intersections
 *
 *  Statistics (averages, medians, insights) are never stored – always computed from derived rows.
 */

enum class TripState { RECORDING, PROCESSING, ANALYZED, FAILED }
enum class TripTrigger { MANUAL, AUTO, DEMO, IMPORT }
enum class IntersectionSource { OSM, LEARNED }

object WaitEventKind {
    /** GPS cannot prove a red light – only that a stop near an intersection was likely a wait. */
    const val PROBABLE_INTERSECTION_WAIT = "PROBABLE_INTERSECTION_WAIT"
}

object DetectionMethod {
    const val NEAR_OSM_FEATURE = "GPS_STOP_NEAR_OSM_FEATURE"
    const val NEAR_LEARNED_HOTSPOT = "GPS_STOP_NEAR_LEARNED_HOTSPOT"
}

// ============================================================================ RAW

/** Raw trip: what was recorded and what the user said about it. Analysis never writes here. */
@Entity(
    tableName = "trips",
    indices = [Index("recordedStart"), Index("state"), Index(value = ["uuid"], unique = true)],
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Globally unique id – stable across export/import and device changes. */
    val uuid: String,
    /** First and last raw fix (epoch ms, UTC). */
    val recordedStart: Long,
    val recordedEnd: Long? = null,
    /** IANA zone at recording time (e.g. Europe/Berlin) – local weekday/time are derived from it. */
    val zoneId: String,
    val trigger: String,
    val state: String,
    /** Transport mode set by the user (wins over everything). */
    val userMode: String? = null,
    /** Mode reported by activity recognition when an automatic recording started. */
    val activityMode: String? = null,
    /** Excluded from statistics by the user (detour, flat tyre …). */
    val excluded: Boolean = false,
    val note: String? = null,
    val isDemo: Boolean = false,
    val createdAt: Long,
)

/**
 * Raw GPS fix. Compact integer encoding (coordinates ×1e7 ≈ 1 cm). Accuracy values are kept so
 * that a later algorithm can apply different quality filters.
 */
@Entity(
    tableName = "track_points",
    primaryKeys = ["tripId", "t"],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackPointEntity(
    val tripId: Long,
    /** Fix time, epoch ms. */
    val t: Long,
    val latE7: Int,
    val lonE7: Int,
    /** Horizontal accuracy (68 %) in decimeters. */
    val accDm: Int,
    /** Doppler speed in cm/s, -1 = unknown. */
    val speedCms: Int,
    /** Bearing in degrees, -1 = unknown. */
    val bearingDeg: Int,
    /** GNSS altitude in decimeters (schema v1 rows may contain barometric altitude). */
    val altDm: Int?,
    /** Speed accuracy in cm/s (v2+). */
    val speedAccCms: Int? = null,
    /** Vertical accuracy in decimeters (v2+). */
    val vAccDm: Int? = null,
    /** Barometric altitude (standard atmosphere) in decimeters – relative changes are precise (v2+). */
    val baroAltDm: Int? = null,
)

/** Raw activity-recognition transitions (for later mode/segmentation algorithms). */
@Entity(tableName = "activity_events", indices = [Index("time")])
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** DetectedActivity type constant (e.g. 1 = ON_BICYCLE, 3 = STILL). */
    val activityType: Int,
    /** 0 = enter, 1 = exit. */
    val transition: Int,
)

// ============================================================================ DERIVED

/** Per-trip analysis result (1:1). Deleted and rebuilt on re-analysis. */
@Entity(
    tableName = "trip_analysis",
    indices = [Index("routeId"), Index("movementStart"), Index(value = ["startPlaceId", "endPlaceId"])],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TripAnalysisEntity(
    @PrimaryKey val tripId: Long,
    val analysisVersion: Int,
    val analyzedAt: Long,
    /** First/last movement after trimming lock/unlock phases (epoch ms). */
    val movementStart: Long,
    val movementEnd: Long,
    val zoneOffsetMin: Int,
    val dayOfWeek: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** Detected transport mode (speed profile / activity hint). */
    val mode: String,
    val distanceM: Double,
    val durationS: Double,
    val movingS: Double,
    val stoppedS: Double,
    val stopCount: Int,
    /** Sum of probable intersection waits (confidence ≥ 0.3). */
    val waitS: Double,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    val avgMovingSpeed: Double,
    val maxSpeed: Double,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val startPlaceId: Long?,
    val endPlaceId: Long?,
    val routeId: Long?,
    /** Display geometry, encoded polyline (precision 5). */
    val polyline: String,
    /** Clustering signature (25 m resampled), encoded polyline (precision 5). */
    val signature: String,
    val bikeScore: Double?,
    /** Main streets (from map matching), comma separated. */
    val via: String?,
    val pointCount: Int,
    val matchedBy: String?,
)

@Entity(
    tableName = "stops",
    indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class StopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startTime: Long,
    val endTime: Long,
    val lat: Double,
    val lon: Double,
    val durationS: Double,
    val alongM: Double,
    /** TERMINAL / PAUSE / STOP */
    val kind: String,
    val resumed: Boolean,
    @ColumnInfo(defaultValue = "1") val analysisVersion: Int,
)

/** Intersection candidates: OSM reference data (cache) or learned from repeated stops (derived). */
@Entity(
    tableName = "intersections",
    indices = [Index(value = ["osmNodeId"], unique = true)],
)
data class IntersectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val name: String?,
    /** User-given name (user data, kept across re-analysis). */
    val userName: String? = null,
    val source: String,
    val kind: String,
    val osmNodeId: Long? = null,
    /** Denormalized count of intersection_passes (derived). */
    val passCount: Int = 0,
)

fun IntersectionEntity.displayName(): String = userName ?: name ?: "Kreuzung #$id"

/**
 * A *probable* wait at an intersection, estimated from GPS. It is never a fact that a light was
 * red – [confidence] and [detectionMethod] say how the estimate was made.
 */
@Entity(
    tableName = "wait_events",
    indices = [Index("tripId"), Index("intersectionId"), Index("stopId")],
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = IntersectionEntity::class, parentColumns = ["id"], childColumns = ["intersectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StopEntity::class, parentColumns = ["id"], childColumns = ["stopId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WaitEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stopId: Long,
    val tripId: Long,
    val intersectionId: Long,
    /** Route of the trip at analysis time (no FK: routes are re-clustered). */
    val routeId: Long?,
    val startTime: Long,
    val durationS: Double,
    val lat: Double,
    val lon: Double,
    val distanceToIntersectionM: Double?,
    /** 0..1 – plausibility that this stop was a wait at the intersection. */
    val confidence: Double,
    /** LIKELY / POSSIBLE / UNCLEAR (derived from confidence). */
    val level: String,
    /** Always [WaitEventKind.PROBABLE_INTERSECTION_WAIT] for now. */
    val kind: String,
    /** How it was detected, see [DetectionMethod]. */
    val detectionMethod: String,
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val analysisVersion: Int,
)

/** Trip passed an intersection (derived, so the UI never recomputes geometry). */
@Entity(
    tableName = "intersection_passes",
    primaryKeys = ["intersectionId", "tripId"],
    indices = [Index("tripId")],
    foreignKeys = [
        ForeignKey(entity = IntersectionEntity::class, parentColumns = ["id"], childColumns = ["intersectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class IntersectionPassEntity(
    val intersectionId: Long,
    val tripId: Long,
    /** Estimated pass time (epoch ms). */
    val passTime: Long,
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val analysisVersion: Int,
)

@Entity(tableName = "routes", indices = [Index("originPlaceId"), Index("destPlaceId")])
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val originPlaceId: Long,
    val destPlaceId: Long,
    val mode: String,
    /** Representative (medoid) signature, encoded polyline. */
    val signature: String,
    val representativeTripId: Long?,
    /** User renamed the route – name is user data and kept. */
    val userNamed: Boolean = false,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val analysisVersion: Int = 1,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 150.0,
    /** HOME / UNI / OTHER */
    val kind: String,
    /** User named/confirmed the place – it is kept even without trips. */
    val userNamed: Boolean = false,
)

/** Book-keeping of analysis runs (which algorithm version produced the current derived data). */
@Entity(tableName = "analysis_runs")
data class AnalysisRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisVersion: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val tripCount: Int = 0,
    /** RUNNING / OK / FAILED */
    val status: String,
    val message: String? = null,
)

// ============================================================================ READ MODELS

/** Raw trip with its (optional) analysis. */
data class TripWithAnalysis(
    @Embedded val trip: TripEntity,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val analysis: TripAnalysisEntity?,
)
