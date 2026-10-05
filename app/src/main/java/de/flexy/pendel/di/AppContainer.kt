package de.flexy.pendel.di

import android.content.Context
import de.flexy.pendel.analysis.GlobalAnalyzer
import de.flexy.pendel.analysis.TripProcessor
import de.flexy.pendel.data.AnalyticsRepository
import de.flexy.pendel.data.TripRepository
import de.flexy.pendel.data.db.PendelDatabase
import de.flexy.pendel.data.demo.DemoDataGenerator
import de.flexy.pendel.data.export.Exporter
import de.flexy.pendel.data.export.Importer
import de.flexy.pendel.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection – one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: PendelDatabase by lazy { PendelDatabase.build(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val trips: TripRepository by lazy { TripRepository(appContext, db) }
    val tripProcessor: TripProcessor by lazy { TripProcessor(db, settings) }
    val globalAnalyzer: GlobalAnalyzer by lazy { GlobalAnalyzer(db, settings) }
    val analytics: AnalyticsRepository by lazy { AnalyticsRepository(db, settings, appScope) }
    val exporter: Exporter by lazy { Exporter(db) }
    val importer: Importer by lazy { Importer(db) }
    val demo: DemoDataGenerator by lazy { DemoDataGenerator(appContext, db) }
}
