package de.flexy.pendel.ui.trips

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.Dest
import de.flexy.pendel.core.model.TransportMode
import de.flexy.pendel.core.model.WaitLevel
import de.flexy.pendel.data.AnalyticsSnapshot
import de.flexy.pendel.data.TripView
import de.flexy.pendel.data.db.displayName
import de.flexy.pendel.data.polylinePoints
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.EmptyHint
import de.flexy.pendel.ui.components.Fmt
import de.flexy.pendel.ui.components.RouteDot
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.components.StatTile
import de.flexy.pendel.ui.map.MapLayers
import de.flexy.pendel.ui.map.MapLine
import de.flexy.pendel.ui.map.MapMarker
import de.flexy.pendel.ui.map.PendelMap
import de.flexy.pendel.ui.theme.NumberStyle
import java.time.Instant
import java.time.ZoneId

private val modeLabels = mapOf(
    TransportMode.BICYCLE to "Fahrrad",
    TransportMode.WALK to "Zu Fuß",
    TransportMode.CAR to "Auto",
    TransportMode.TRANSIT to "ÖPNV",
    TransportMode.UNKNOWN to "Unbekannt",
)

fun modeLabel(mode: String) = modeLabels[runCatching { TransportMode.valueOf(mode) }.getOrDefault(TransportMode.UNKNOWN)] ?: mode

@Composable
fun TripRow(t: TripView, snap: AnalyticsSnapshot, onClick: () -> Unit) {
    val route = t.routeId?.let { snap.routes[it] }
    val from = t.startPlaceId?.let { snap.places[it]?.name }
    val to = t.endPlaceId?.let { snap.places[it]?.name }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteDot(route?.colorIndex ?: -1, 12)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (from != null && to != null) "$from → $to" else modeLabel(t.mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOfNotNull(Fmt.time(t.startTime), route?.name, if (t.isDemo) "Demo" else null, if (t.excluded) "ausgeschlossen" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.duration(t.durationS), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                Text(
                    "${Fmt.distance(t.distanceM)} · ${t.stopCount} Stopps",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val grouped = remember(snap.allTrips) {
        snap.allTrips.groupBy { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate() }.toSortedMap(compareByDescending { it })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Fahrten") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (snap.loaded && snap.allTrips.isEmpty()) {
                item { EmptyHint("Keine Fahrten", "Aufgezeichnete Fahrten erscheinen hier, sobald sie analysiert sind.") }
            }
            grouped.forEach { (date, trips) ->
                item(key = "h-$date") {
                    Text(
                        Fmt.date(trips.first().startTime) + " · " + Fmt.km(trips.sumOf { it.distanceM }) + " km",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(trips, key = { it.id }) { t -> TripRow(t, snap) { nav.navigate(Dest.trip(t.id)) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(vm: AppViewModel, nav: NavHostController, tripId: Long) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val trip = snap.allTrips.firstOrNull { it.id == tripId }
    val stops by remember(tripId) { vm.stops(tripId) }.collectAsStateWithLifecycle(emptyList())
    val waits by remember(tripId) { vm.waits(tripId) }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) vm.exportGpx(context, uri, tripId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.let { Fmt.dateTime(it.startTime) } ?: "Fahrt") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mehr") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Als GPX exportieren") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                            onClick = { menu = false; gpx.launch("pendel-fahrt-$tripId.gpx") },
                        )
                        DropdownMenuItem(
                            text = { Text(if (trip?.excluded == true) "Wieder in Statistik aufnehmen" else "Aus Statistik ausschließen") },
                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                            onClick = { menu = false; trip?.let { vm.setExcluded(it.id, !it.excluded) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Fahrt löschen") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (trip == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Fahrt nicht gefunden") }
            return@Scaffold
        }
        val route = trip.routeId?.let { snap.routes[it] }
        val stats = trip.routeId?.let { snap.routeStats[it] }
        val interById = snap.intersections.associateBy { it.entity.id }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val line = trip.polylinePoints()
                PendelMap(
                    layers = MapLayers(
                        lines = listOf(MapLine("trip", line, route?.colorIndex ?: 0, 1f)),
                        markers = stops.filter { it.kind == "STOP" }.map { s ->
                            MapMarker(s.id, s.lat, s.lon, s.durationS, "stop")
                        },
                    ),
                    styleUrlLight = snap.settings.mapStyleUrl,
                    styleUrlDark = snap.settings.mapStyleDarkUrl,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    interactive = false,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouteDot(route?.colorIndex ?: -1, 12)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        route?.name ?: "Noch keiner Route zugeordnet",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).clickable(enabled = route != null) { route?.let { nav.navigate(Dest.route(it.id)) } },
                    )
                    Box {
                        AssistChip(onClick = { modeMenu = true }, label = { Text(modeLabel(trip.mode)) })
                        DropdownMenu(modeMenu, onDismissRequest = { modeMenu = false }) {
                            modeLabels.forEach { (m, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { modeMenu = false; vm.setMode(trip.id, m) })
                            }
                        }
                    }
                }
                if (stats != null && stats.n >= 3 && !trip.excluded) {
                    val delta = trip.durationS - stats.duration.median
                    Text(
                        if (delta <= 0) "${Fmt.mmss(-delta)} min schneller als dein Median auf dieser Route (${Fmt.trips(stats.n)})"
                        else "${Fmt.mmss(delta)} min langsamer als dein Median auf dieser Route (${Fmt.trips(stats.n)})",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Dauer", Fmt.duration(trip.durationS), Modifier.weight(1f), sub = "${Fmt.time(trip.startTime)}–${trip.endTime?.let { Fmt.time(it) } ?: ""}")
                    StatTile("Distanz", Fmt.distance(trip.distanceM), Modifier.weight(1f), sub = trip.via)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Ø in Bewegung", Fmt.speedKmh(trip.avgMovingSpeed), Modifier.weight(1f), sub = "max. ${Fmt.speedKmh(trip.maxSpeed)}")
                    StatTile("Wartezeit", Fmt.wait(trip.waitS), Modifier.weight(1f), sub = "Stillstand gesamt ${Fmt.wait(trip.stoppedS)}")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Stopps", "${trip.stopCount}", Modifier.weight(1f), sub = "${waits.count { it.level != WaitLevel.UNCLEAR.name }} an Kreuzungen")
                    StatTile(
                        "Höhenmeter",
                        trip.elevationGainM?.let { "↑ ${it.toInt()} m" } ?: "–",
                        Modifier.weight(1f), sub = trip.elevationLossM?.let { "↓ ${it.toInt()} m" },
                    )
                }
            }
            val regular = stops.filter { it.kind == "STOP" || it.kind == "PAUSE" }
            if (regular.isNotEmpty()) {
                item { SectionHeader("Stopps", "Wartezeiten sind Schätzungen – GPS weiß nicht, ob die Ampel rot war") }
                items(regular, key = { it.id }) { s ->
                    val w = waits.firstOrNull { it.stopId == s.id }
                    val inter = w?.let { interById[it.intersectionId]?.entity }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                inter?.displayName() ?: if (s.kind == "PAUSE") "Pause" else "Stopp ohne bekannte Kreuzung",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.clickable(enabled = inter != null) { inter?.let { nav.navigate(Dest.intersection(it.id)) } },
                            )
                            Text(
                                "${Fmt.time(s.startTime)} · nach ${Fmt.distance(s.alongM)}" + (w?.let { " · " + levelLabel(it.level, it.confidence) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Fmt.wait(s.durationS), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                    }
                }
            }
            item {
                Text(
                    "${trip.pointCount} GPS-Punkte · " + (if (trip.matchedBy != null) "Map Matching: ${trip.matchedBy}" else "offline analysiert") +
                        (if (trip.isDemo) " · Demo-Fahrt" else "") +
                        (trip.analysisVersion?.let { " · Analyse v$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Fahrt löschen?") },
            text = { Text("Die Fahrt und alle zugehörigen GPS-Punkte werden endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteTrip(tripId)
                    nav.popBackStack()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

fun levelLabel(level: String, confidence: Double): String = when (level) {
    WaitLevel.LIKELY.name -> "wahrscheinlich Wartezeit (${(confidence * 100).toInt()} %)"
    WaitLevel.POSSIBLE.name -> "mögliche Wartezeit (${(confidence * 100).toInt()} %)"
    else -> "unklar (${(confidence * 100).toInt()} %)"
}
