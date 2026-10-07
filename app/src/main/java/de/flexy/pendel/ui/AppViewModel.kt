package de.flexy.pendel.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.flexy.pendel.analysis.AnalysisWorker
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.optimize.Objective
import de.flexy.pendel.core.optimize.Weights
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.data.OdInfo
import de.flexy.pendel.data.ProfilePoint
import de.flexy.pendel.data.Recommendation
import de.flexy.pendel.data.db.StopEntity
import de.flexy.pendel.data.db.WaitEventEntity
import de.flexy.pendel.data.settings.MatcherChoice
import de.flexy.pendel.di.AppContainer
import de.flexy.pendel.tracking.ActivityRecognitionManager
import de.flexy.pendel.tracking.TrackingService
import de.flexy.pendel.tracking.TrackingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(private val c: AppContainer) : ViewModel() {
    val snapshot = c.analytics.snapshot
    val insights = c.analytics.insights
    val live = TrackingState.live
    val demoCount: Flow<Int> = c.db.tripDao().observeDemoCount()
    val pointCount: Flow<Int> = c.db.pointDao().observeTotalCount()
    val allStops: Flow<List<StopEntity>> = c.db.stopDao().observeRegular()

    /** Corridor ("Strecke") selected in Routen/Karte – shared so the dashboard can jump to one. */
    private val _selectedCorridor = MutableStateFlow<String?>(null)
    val selectedCorridor: StateFlow<String?> = _selectedCorridor.asStateFlow()
    fun selectCorridor(key: String?) { _selectedCorridor.value = key }

    private val _busy = MutableStateFlow<String?>(null)
    /** Short description of a running long operation (demo generation, export …). */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    // ---------------------------------------------------------------- recording
    fun startRecording(context: Context) = TrackingService.startManual(context)
    fun stopRecording(context: Context) = TrackingService.stop(context)

    // ---------------------------------------------------------------- trip detail
    fun stops(tripId: Long): Flow<List<StopEntity>> = c.db.stopDao().observeForTrip(tripId)
    fun waits(tripId: Long): Flow<List<WaitEventEntity>> = c.db.waitEventDao().observeForTrip(tripId)

    fun deleteTrip(id: Long) = viewModelScope.launch { c.trips.delete(id) }
    fun setExcluded(id: Long, excluded: Boolean) = viewModelScope.launch { c.trips.setExcluded(id, excluded) }
    fun setMode(id: Long, mode: TransportMode) = viewModelScope.launch { c.trips.setMode(id, mode) }
    fun renameRoute(id: Long, name: String) = viewModelScope.launch { c.trips.renameRoute(id, name) }
    /** Naming a place makes it user data; [kind] HOME/UNI/OTHER drives labels and the default main corridor. */
    fun setPlaceParent(id: Long, parentId: Long?) = viewModelScope.launch {
        c.trips.setPlaceParent(id, parentId)
        _message.value = if (parentId == null) "Ort ist wieder eigenständig" else "Orte zusammengefasst – Routen werden neu berechnet"
    }

    fun dismissPlaceGroup(childId: Long, parentId: Long) = viewModelScope.launch { c.settings.dismissPlaceGroup("$childId-$parentId") }

    fun renamePlace(id: Long, name: String, kind: String) = viewModelScope.launch {
        c.trips.renamePlace(id, name, kind)
        AnalysisWorker.enqueue(c.appContext)
    }

    fun setPrimaryCorridor(key: String) = viewModelScope.launch {
        c.settings.setPrimaryCorridor(key)
        _message.value = "Als Hauptstrecke festgelegt"
    }

    val latestAnalysisRun = c.db.analysisRunDao().observeLatest()
    fun renameIntersection(id: Long, name: String?) = viewModelScope.launch { c.trips.renameIntersection(id, name) }

    // ---------------------------------------------------------------- analysis
    fun recommend(od: OdInfo, dow: Int, minute: Int): Recommendation? {
        val snap = snapshot.value
        return c.analytics.recommend(od, snap.routeStats, dow, minute, snap.settings.objective, snap.settings.customWeights)
    }

    fun timeProfile(od: OdInfo, group: DayGroup): Map<Long, List<ProfilePoint>> = c.analytics.timeProfile(od, group)

    fun reanalyze(context: Context) {
        AnalysisWorker.enqueue(context, reprocessAll = true, forceOsm = true)
        _message.value = "Analyse wurde gestartet"
    }

    // ---------------------------------------------------------------- settings
    fun setObjective(o: Objective) = viewModelScope.launch { c.settings.setObjective(o) }
    fun setWeights(w: Weights) = viewModelScope.launch { c.settings.setCustomWeights(w) }
    fun setMatcher(m: MatcherChoice, url: String) = viewModelScope.launch { c.settings.setMatcher(m, url) }
    fun setOverpass(enabled: Boolean, url: String, context: Context) = viewModelScope.launch {
        c.settings.setOverpass(enabled, url)
        if (enabled) {
            c.settings.resetOverpassCache()
            AnalysisWorker.enqueue(context, forceOsm = true)
        }
    }
    fun setMapStyles(light: String, dark: String) = viewModelScope.launch { c.settings.setMapStyles(light, dark) }
    fun completeOnboarding() = viewModelScope.launch { c.settings.setOnboardingDone() }

    fun setAutoDetect(enabled: Boolean, context: Context) = viewModelScope.launch {
        c.settings.setAutoDetect(enabled)
        if (enabled) ActivityRecognitionManager.register(context) else ActivityRecognitionManager.unregister(context)
    }

    // ---------------------------------------------------------------- data
    fun generateDemo() = viewModelScope.launch {
        _busy.value = "Demo-Fahrten werden erzeugt …"
        try {
            c.demo.generate { p -> _busy.value = "Demo-Fahrten werden erzeugt … ${(p * 100).toInt()} %" }
            _message.value = "Demo-Daten erzeugt – die Analyse läuft im Hintergrund"
        } finally {
            _busy.value = null
        }
    }

    fun deleteDemo() = viewModelScope.launch {
        c.trips.deleteDemoData()
        _message.value = "Demo-Daten gelöscht"
    }

    fun deleteRange(from: Long, to: Long) = viewModelScope.launch {
        val n = c.trips.deleteBetween(from, to)
        _message.value = if (n == 1) "1 Fahrt gelöscht" else "$n Fahrten gelöscht"
    }

    fun deleteEverything() = viewModelScope.launch {
        c.trips.deleteEverything()
        _message.value = "Alle Daten gelöscht"
    }

    fun exportJson(context: Context, uri: Uri) = viewModelScope.launch {
        _busy.value = "Export läuft …"
        runCatching { c.exporter.exportJson(context.contentResolver, uri) }
            .onSuccess { _message.value = "Export gespeichert" }
            .onFailure { _message.value = "Export fehlgeschlagen: ${it.message}" }
        _busy.value = null
    }

    fun exportCsv(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching { c.exporter.exportTripsCsv(context.contentResolver, uri) }
            .onSuccess { _message.value = "CSV gespeichert" }
            .onFailure { _message.value = "Export fehlgeschlagen: ${it.message}" }
    }

    /** Restores a JSON export (e.g. from the old phone). Derived data is rebuilt afterwards. */
    fun importJson(context: Context, uri: Uri) = viewModelScope.launch {
        _busy.value = "Import läuft …"
        runCatching { c.importer.import(context.contentResolver, uri) }
            .onSuccess {
                _message.value = "Importiert: ${it.tripsImported} Fahrten (${it.tripsSkipped} schon vorhanden), ${it.places} Orte"
                AnalysisWorker.enqueue(context)
            }
            .onFailure { _message.value = "Import fehlgeschlagen: ${it.message}" }
        _busy.value = null
    }

    fun exportGpx(context: Context, uri: Uri, tripId: Long) = viewModelScope.launch {
        runCatching { c.exporter.exportGpx(context.contentResolver, uri, tripId) }
            .onSuccess { _message.value = "GPX gespeichert" }
            .onFailure { _message.value = "Export fehlgeschlagen: ${it.message}" }
    }
}
