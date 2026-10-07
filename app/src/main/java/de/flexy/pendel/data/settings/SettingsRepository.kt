package de.flexy.pendel.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.flexy.pendel.core.optimize.Objective
import de.flexy.pendel.core.optimize.Weights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class MatcherChoice(val label: String) { NONE("Aus (offline)"), VALHALLA("Valhalla") }

data class Settings(
    val autoDetect: Boolean = false,
    val objective: Objective = Objective.FASTEST,
    val customWeights: Weights = Weights.DEFAULT_CUSTOM,
    val matcher: MatcherChoice = MatcherChoice.NONE,
    val valhallaUrl: String = "",
    val overpassEnabled: Boolean = false,
    val overpassUrl: String = DEFAULT_OVERPASS,
    val overpassLastFetch: Long = 0L,
    val overpassBbox: String = "",
    val mapStyleUrl: String = DEFAULT_MAP_STYLE,
    val mapStyleDarkUrl: String = DEFAULT_MAP_STYLE_DARK,
    val onboardingDone: Boolean = false,
    /** Key of the main corridor ("Hauptstrecke"), see corridorKey(); null = automatic (Uni). */
    val primaryCorridor: String? = null,
    /** Place-group suggestions the user declined ("childId-parentId"). */
    val dismissedPlaceGroups: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_OVERPASS = "https://overpass-api.de/api/interpreter"
        const val DEFAULT_MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
        const val DEFAULT_MAP_STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val autoDetect = booleanPreferencesKey("auto_detect")
        val objective = stringPreferencesKey("objective")
        val wDuration = doublePreferencesKey("w_duration")
        val wWait = doublePreferencesKey("w_wait")
        val wDistance = doublePreferencesKey("w_distance")
        val wStops = doublePreferencesKey("w_stops")
        val wSpread = doublePreferencesKey("w_spread")
        val wBike = doublePreferencesKey("w_bike")
        val matcher = stringPreferencesKey("matcher")
        val valhallaUrl = stringPreferencesKey("valhalla_url")
        val overpassEnabled = booleanPreferencesKey("overpass_enabled")
        val overpassUrl = stringPreferencesKey("overpass_url")
        val overpassLast = longPreferencesKey("overpass_last")
        val overpassBbox = stringPreferencesKey("overpass_bbox")
        val mapStyle = stringPreferencesKey("map_style")
        val mapStyleDark = stringPreferencesKey("map_style_dark")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val primaryCorridor = stringPreferencesKey("primary_corridor")
        val dismissedGroups = stringSetPreferencesKey("dismissed_place_groups")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Weights.DEFAULT_CUSTOM
        Settings(
            autoDetect = p[K.autoDetect] ?: false,
            objective = p[K.objective]?.let { runCatching { Objective.valueOf(it) }.getOrNull() } ?: Objective.FASTEST,
            customWeights = Weights(
                duration = p[K.wDuration] ?: d.duration,
                wait = p[K.wWait] ?: d.wait,
                distance = p[K.wDistance] ?: d.distance,
                stops = p[K.wStops] ?: d.stops,
                spread = p[K.wSpread] ?: d.spread,
                bike = p[K.wBike] ?: d.bike,
            ),
            matcher = p[K.matcher]?.let { runCatching { MatcherChoice.valueOf(it) }.getOrNull() } ?: MatcherChoice.NONE,
            valhallaUrl = p[K.valhallaUrl] ?: "",
            overpassEnabled = p[K.overpassEnabled] ?: false,
            overpassUrl = p[K.overpassUrl] ?: Settings.DEFAULT_OVERPASS,
            overpassLastFetch = p[K.overpassLast] ?: 0L,
            overpassBbox = p[K.overpassBbox] ?: "",
            mapStyleUrl = p[K.mapStyle] ?: Settings.DEFAULT_MAP_STYLE,
            mapStyleDarkUrl = p[K.mapStyleDark] ?: Settings.DEFAULT_MAP_STYLE_DARK,
            onboardingDone = p[K.onboarding] ?: false,
            primaryCorridor = p[K.primaryCorridor],
            dismissedPlaceGroups = p[K.dismissedGroups] ?: emptySet(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setAutoDetect(v: Boolean) = context.dataStore.edit { it[K.autoDetect] = v }
    suspend fun setObjective(o: Objective) = context.dataStore.edit { it[K.objective] = o.name }
    suspend fun setCustomWeights(w: Weights) = context.dataStore.edit {
        it[K.wDuration] = w.duration; it[K.wWait] = w.wait; it[K.wDistance] = w.distance
        it[K.wStops] = w.stops; it[K.wSpread] = w.spread; it[K.wBike] = w.bike
    }
    suspend fun setMatcher(m: MatcherChoice, url: String) = context.dataStore.edit {
        it[K.matcher] = m.name; it[K.valhallaUrl] = url.trim()
    }
    suspend fun setOverpass(enabled: Boolean, url: String) = context.dataStore.edit {
        it[K.overpassEnabled] = enabled; it[K.overpassUrl] = url.trim().ifEmpty { Settings.DEFAULT_OVERPASS }
    }
    suspend fun setOverpassFetched(bbox: String, time: Long) = context.dataStore.edit {
        it[K.overpassBbox] = bbox; it[K.overpassLast] = time
    }
    suspend fun resetOverpassCache() = context.dataStore.edit { it.remove(K.overpassBbox); it.remove(K.overpassLast) }
    suspend fun setMapStyles(light: String, dark: String) = context.dataStore.edit {
        it[K.mapStyle] = light.trim().ifEmpty { Settings.DEFAULT_MAP_STYLE }
        it[K.mapStyleDark] = dark.trim().ifEmpty { Settings.DEFAULT_MAP_STYLE_DARK }
    }
    suspend fun dismissPlaceGroup(key: String) = context.dataStore.edit { it[K.dismissedGroups] = (it[K.dismissedGroups] ?: emptySet()) + key }
    suspend fun setPrimaryCorridor(key: String) = context.dataStore.edit { it[K.primaryCorridor] = key }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
}
