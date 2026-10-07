package de.flexy.pendel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        // raw
        TripEntity::class,
        TrackPointEntity::class,
        ActivityEventEntity::class,
        // derived
        TripAnalysisEntity::class,
        StopEntity::class,
        WaitEventEntity::class,
        IntersectionPassEntity::class,
        RouteEntity::class,
        AnalysisRunEntity::class,
        // reference + user data
        IntersectionEntity::class,
        PlaceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PendelDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun pointDao(): TrackPointDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun tripAnalysisDao(): TripAnalysisDao
    abstract fun stopDao(): StopDao
    abstract fun waitEventDao(): WaitEventDao
    abstract fun intersectionDao(): IntersectionDao
    abstract fun intersectionPassDao(): IntersectionPassDao
    abstract fun routeDao(): RouteDao
    abstract fun placeDao(): PlaceDao
    abstract fun analysisRunDao(): AnalysisRunDao

    companion object {
        const val NAME = "pendel.db"

        fun build(context: Context): PendelDatabase =
            Room.databaseBuilder(context, PendelDatabase::class.java, NAME)
                // WAL: recorder writes and UI reads don't block each other
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // real migrations only – never silently wipe personal history
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
