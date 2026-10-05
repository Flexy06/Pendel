package de.flexy.pendel.ui.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.Dest
import de.flexy.pendel.core.geo.PolylineCodec
import de.flexy.pendel.core.model.WaitLevel
import de.flexy.pendel.data.polylinePoints
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.ChartLegend
import de.flexy.pendel.ui.components.LegendItem

@Composable
fun MapScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val stops by vm.allStops.collectAsStateWithLifecycle(emptyList())
    var showRoutes by rememberSaveable { mutableStateOf(true) }
    var showTrips by rememberSaveable { mutableStateOf(false) }
    var showStops by rememberSaveable { mutableStateOf(false) }
    var showInter by rememberSaveable { mutableStateOf(true) }
    var showHeat by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val selectedKey by vm.selectedCorridor.collectAsStateWithLifecycle()
    val corridor = if (showAll) null else (snap.corridors.firstOrNull { it.key == selectedKey } ?: snap.primary)
    val routes = corridor?.directions?.flatMap { it.routes } ?: snap.routes.values.toList()
    val routeIds = routes.map { it.id }.toSet()
    val trips = snap.statTrips.filter { corridor == null || it.routeId in routeIds }
    val tripIds = trips.map { it.id }.toSet()

    val layers = remember(snap, stops, showRoutes, showTrips, showStops, showInter, showHeat, corridor?.key) {
        val stopById = stops.associateBy { it.id }
        MapLayers(
            lines = buildList {
                if (showTrips) trips.forEach { t -> add(MapLine("t${t.id}", t.polylinePoints(), snap.routeColorIndex(t.routeId), 0.25f)) }
                if (showRoutes) routes.forEach { r -> add(MapLine("r${r.id}", PolylineCodec.decode(r.signature), r.colorIndex, 0.95f)) }
            },
            markers = stops.filter { it.kind == "STOP" && it.tripId in tripIds }.map { MapMarker(it.id, it.lat, it.lon, it.durationS, "stop") },
            intersections = snap.intersections.filter { it.stats.stopsWithWait > 0 }.map {
                MapMarker(it.entity.id, it.entity.lat, it.entity.lon, it.stats.totalWaitS, "intersection")
            },
            heat = snap.waitEvents.filter { it.tripId in tripIds && it.level != WaitLevel.UNCLEAR.name }.mapNotNull { w ->
                stopById[w.stopId]?.let { s -> MapMarker(w.id, s.lat, s.lon, w.durationS * w.confidence, "heat") }
            },
            showLines = showRoutes || showTrips,
            showMarkers = showStops,
            showIntersections = showInter,
            showHeat = showHeat,
        )
    }

    Box(Modifier.fillMaxSize()) {
        PendelMap(
            layers = layers,
            styleUrlLight = snap.settings.mapStyleUrl,
            styleUrlDark = snap.settings.mapStyleDarkUrl,
            modifier = Modifier.fillMaxSize(),
            onIntersectionClick = { id -> nav.navigate(Dest.intersection(id)) },
        )
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                snap.corridors.forEach { c ->
                    MapChip((if (c.isPrimary) "★ " else "") + c.label, corridor?.key == c.key) { showAll = false; vm.selectCorridor(c.key) }
                }
                MapChip("Alle Ziele", showAll) { showAll = true }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapChip("Routen", showRoutes) { showRoutes = !showRoutes }
                MapChip("Alle Fahrten", showTrips) { showTrips = !showTrips }
                MapChip("Kreuzungen", showInter) { showInter = !showInter }
                MapChip("Stopps", showStops) { showStops = !showStops }
                MapChip("Wartezeit-Heatmap", showHeat) { showHeat = !showHeat }
            }
        }
        if (routes.size >= 2 && (showRoutes || showTrips)) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(12.dp).width(220.dp)) {
                    ChartLegend(routes.map { LegendItem(it.name, it.colorIndex) })
                    if (showInter) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Kreise: Kreuzungen – größer/dunkler = mehr Wartezeit. Antippen für Details.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    )
}
