package de.flexy.pendel.ui.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.Dest
import de.flexy.pendel.core.optimize.Objective
import de.flexy.pendel.core.optimize.ScoredRoute
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.data.AnalyticsSnapshot
import de.flexy.pendel.data.localTimeInfo
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.BarChart
import de.flexy.pendel.ui.components.BarDatum
import de.flexy.pendel.ui.components.BasisBadge
import de.flexy.pendel.ui.components.ChartLegend
import de.flexy.pendel.ui.components.CorridorTabs
import de.flexy.pendel.ui.components.DistributionChart
import de.flexy.pendel.ui.components.DistributionGroup
import de.flexy.pendel.ui.components.EmptyHint
import de.flexy.pendel.ui.components.Fmt
import de.flexy.pendel.ui.components.InsightCard
import de.flexy.pendel.ui.components.LegendItem
import de.flexy.pendel.ui.components.ProfileSeries
import de.flexy.pendel.ui.components.RouteDot
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.components.StatTile
import de.flexy.pendel.ui.components.TimeProfileChart
import de.flexy.pendel.ui.dashboard.ChartCard
import de.flexy.pendel.ui.theme.LocalPendelColors
import de.flexy.pendel.ui.theme.NumberStyle
import de.flexy.pendel.ui.trips.TripRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val now = remember { localTimeInfo(System.currentTimeMillis()) }
    val selectedKey by vm.selectedCorridor.collectAsStateWithLifecycle()
    var dirIndex by rememberSaveable(selectedKey) { mutableIntStateOf(0) }
    var dow by rememberSaveable { mutableIntStateOf(now.dayOfWeek) }
    var minute by rememberSaveable { mutableFloatStateOf(now.minuteOfDay.toFloat()) }
    var profileGroup by rememberSaveable { mutableIntStateOf(0) } // 0 = Werktags, 1 = selected day, 2 = Wochenende

    val corridor = snap.corridors.firstOrNull { it.key == selectedKey } ?: snap.primary ?: snap.corridors.firstOrNull()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Routen") })
                CorridorTabs(snap.corridors, corridor?.key) { vm.selectCorridor(it) }
            }
        },
    ) { padding ->
        val od = corridor?.directions?.getOrNull(dirIndex) ?: corridor?.directions?.firstOrNull()
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (od == null) {
                item {
                    EmptyHint(
                        "Noch keine Routen erkannt",
                        "Routen entstehen automatisch, sobald du mehrere Fahrten zwischen denselben Orten aufgezeichnet hast.",
                    )
                }
                return@LazyColumn
            }
            // ---------------------------------------------------------------- direction + main corridor
            if (corridor != null) item {
                if (corridor.directions.size > 1) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        corridor.directions.forEachIndexed { i, d ->
                            SegmentedButton(
                                selected = d == od,
                                onClick = { dirIndex = i },
                                shape = SegmentedButtonDefaults.itemShape(i, corridor.directions.size),
                            ) { Text("${d.label} (${d.trips.size})", maxLines = 1) }
                        }
                    }
                } else {
                    Text(od.label, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                if (corridor.isPrimary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Hauptstrecke – bestimmt die Übersicht", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    AssistChip(
                        onClick = { vm.setPrimaryCorridor(corridor.key) },
                        label = { Text("Als Hauptstrecke festlegen") },
                        leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) },
                    )
                }
            }
            // ---------------------------------------------------------------- objective
            item {
                Text("Optimieren nach", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Objective.entries.forEach { o ->
                        FilterChip(selected = snap.settings.objective == o, onClick = { vm.setObjective(o) }, label = { Text(o.label) })
                    }
                    IconButton(onClick = { nav.navigate(Dest.SETTINGS) }) { Icon(Icons.Outlined.Tune, "Gewichtung") }
                }
            }
            // ---------------------------------------------------------------- when?
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Abfahrt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                val sel = d == dow
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { dow = d }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        DayGroup.DAY_SHORT[d - 1], style = MaterialTheme.typography.labelLarge,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.minuteOfDay(minute.roundToInt()) + " Uhr", style = MaterialTheme.typography.titleMedium.merge(NumberStyle), modifier = Modifier.width(92.dp))
                            Slider(
                                value = minute,
                                onValueChange = { minute = (it / 15f).roundToInt() * 15f },
                                valueRange = 300f..1380f,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            val rec = vm.recommend(od, dow, minute.roundToInt())
            if (rec != null) {
                item { SectionHeader("Ranking: ${rec.objective.label}", "${DayGroup.DAY_NAMES[dow - 1]}, ${Fmt.minuteOfDay(minute.roundToInt())} Uhr · zeitlich gewichtete Mediane") }
                items(rec.ranking, key = { "rank-${it.metrics.routeId}" }) { sr ->
                    RankingCard(sr, snap, rec.ess[sr.metrics.routeId] ?: 0.0, best = sr.rank == 1, onClick = { nav.navigate(Dest.route(sr.metrics.routeId)) })
                }
            }
            // ---------------------------------------------------------------- time profile
            item {
                val group = when (profileGroup) {
                    1 -> DayGroup.Single(dow)
                    2 -> DayGroup.Weekend
                    else -> DayGroup.Weekdays
                }
                val profile = vm.timeProfile(od, group)
                ChartCard("Fahrzeit nach Uhrzeit", "Geglätteter Median · gestrichelt = dünne Datenbasis · ziehen für Werte") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(profileGroup == 0, onClick = { profileGroup = 0 }, label = { Text("Werktags") })
                        FilterChip(profileGroup == 1, onClick = { profileGroup = 1 }, label = { Text(DayGroup.DAY_SHORT[dow - 1]) })
                        FilterChip(profileGroup == 2, onClick = { profileGroup = 2 }, label = { Text("Wochenende") })
                    }
                    Spacer(Modifier.height(8.dp))
                    val series = od.routes.map { r ->
                        ProfileSeries(r.name, r.colorIndex, profile[r.id].orEmpty().map { Triple(it.minute, it.medianS, it.ess) })
                    }
                    if (series.all { it.points.size < 2 }) {
                        Text("Für diese Auswahl gibt es noch zu wenige Fahrten.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TimeProfileChart(series, markerMinute = minute.roundToInt())
                        Spacer(Modifier.height(8.dp))
                        ChartLegend(od.routes.map { LegendItem(it.name, it.colorIndex) })
                    }
                }
            }
            // ---------------------------------------------------------------- insights
            val list = insights[od.od.key].orEmpty()
            item {
                SectionHeader(
                    "Zeitabhängige Muster",
                    if (list.isEmpty()) "Noch keine belastbaren Unterschiede – Aussagen erscheinen erst bei ausreichender Datenbasis."
                    else "Nur Unterschiede mit mindestens 80 % Sicherheit (Bootstrap)",
                )
            }
            items(list.size) { i ->
                InsightCard(list[i], routeName = { snap.routes[it]?.name ?: "Route" }, colorOf = { snap.routeColorIndex(it) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RankingCard(sr: ScoredRoute, snap: AnalyticsSnapshot, ess: Double, best: Boolean, onClick: () -> Unit) {
    val route = snap.routes[sr.metrics.routeId] ?: return
    val stats = snap.routeStats[route.id]
    val color = LocalPendelColors.current.route(route.colorIndex)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (best) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Box(Modifier.width(4.dp).height(64.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${sr.rank}.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(route.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(Fmt.duration(sr.metrics.expectedDurationS), style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Fmt.distance(sr.metrics.distanceM)} · Wartezeit ${Fmt.wait(sr.metrics.waitS)} · ${"%.1f".format(sr.metrics.stops)} Stopps · Streuung ±${Fmt.mmss(sr.metrics.spreadS / 2)}" +
                        (sr.metrics.bikeScore?.let { " · Rad-Infrastruktur ${Fmt.percent(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasisBadge(DataBasis.of(stats?.n ?: 0), stats?.n)
                    Spacer(Modifier.width(8.dp))
                    Text("≈${ess.roundToInt()} zu dieser Zeit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sr.bikeUnknown && snap.settings.objective == Objective.BIKE_FRIENDLY) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Radfreundlichkeit unbekannt – benötigt Map Matching (Einstellungen).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(vm: AppViewModel, nav: NavHostController, routeId: Long) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val route = snap.routes[routeId]
    val stats = snap.routeStats[routeId]
    var rename by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route?.name ?: "Route", maxLines = 1) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = { IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Umbenennen") } },
            )
        },
    ) { padding ->
        if (route == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Route nicht gefunden") }
            return@Scaffold
        }
        val trips = snap.statTrips.filter { it.routeId == routeId }
        val od = snap.ods.firstOrNull { o -> o.routes.any { it.id == routeId } }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouteDot(route.colorIndex, 14)
                    Spacer(Modifier.width(8.dp))
                    Text(od?.label ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (stats != null) BasisBadge(stats.basis, stats.n)
                }
            }
            if (stats == null) {
                item { EmptyHint("Keine Statistik", "Für diese Route gibt es noch keine auswertbaren Fahrten.") }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Median", Fmt.duration(stats.duration.median), Modifier.weight(1f),
                        sub = stats.medianInterval?.let { "80 %-Intervall ${Fmt.mmss(it.low)}–${Fmt.mmss(it.high)}" } ?: "Intervall ab 3 Fahrten",
                    )
                    StatTile("Mittelwert", Fmt.duration(stats.duration.mean), Modifier.weight(1f), sub = "σ ${Fmt.mmss(stats.duration.std)} min" + if (stats.duration.outliers > 0) " · ${stats.duration.outliers} Ausreißer ignoriert" else "")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Schnellste", Fmt.duration(stats.duration.min), Modifier.weight(1f).clickable { nav.navigate(Dest.trip(stats.fastestTripId)) })
                    StatTile("Langsamste", Fmt.duration(stats.duration.max), Modifier.weight(1f).clickable { nav.navigate(Dest.trip(stats.slowestTripId)) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Ø Distanz", Fmt.distance(stats.avgDistanceM), Modifier.weight(1f))
                    StatTile("Ø Wartezeit", Fmt.wait(stats.avgWaitS), Modifier.weight(1f), sub = "Ø ${"%.1f".format(stats.avgStops)} Stopps")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Zuverlässigkeit", if (stats.n >= 3) Fmt.percent(stats.reliability) else "–", Modifier.weight(1f),
                        sub = "80 % der Fahrten: ${Fmt.mmss(stats.duration.p10)}–${Fmt.mmss(stats.duration.p90)}",
                    )
                    StatTile(
                        "Radfreundlichkeit", stats.bikeScore?.let { Fmt.percent(it) } ?: "–", Modifier.weight(1f),
                        sub = if (stats.bikeScore == null) "benötigt Map Matching" else "Anteil Radinfrastruktur",
                    )
                }
            }
            // comparison with other routes of the same OD pair
            val others = od?.routes?.filter { it.id != routeId }.orEmpty().mapNotNull { o -> snap.routeStats[o.id]?.let { o to it } }
            if (others.isNotEmpty()) {
                item { SectionHeader("Zeitersparnis gegenüber anderen Routen", "Differenz der Mediane über alle Uhrzeiten") }
                items(others, key = { "cmp-${it.first.id}" }) { (o, os) ->
                    val d = os.duration.median - stats.duration.median
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteDot(o.colorIndex)
                        Spacer(Modifier.width(8.dp))
                        Text(o.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (d >= 0) "${Fmt.mmss(d)} min schneller" else "${Fmt.mmss(-d)} min langsamer",
                            style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        BasisBadge(DataBasis.of(minOf(stats.n, os.n)))
                    }
                }
            }
            item {
                ChartCard("Verteilung", "Jede Fahrt ein Punkt") {
                    DistributionChart(listOf(DistributionGroup(route.name, route.colorIndex, trips.map { it.durationS })))
                }
            }
            item {
                val data = (1..7).map { d ->
                    val ds = trips.filter { it.dayOfWeek == d }.map { it.durationS }
                    BarDatum(
                        DayGroup.DAY_SHORT[d - 1], if (ds.isEmpty()) null else Descriptive.median(ds), ds.size,
                        "${DayGroup.DAY_NAMES[d - 1]}: " + if (ds.isEmpty()) "keine Fahrten" else "Median ${Fmt.mmss(Descriptive.median(ds))} min · ${Fmt.trips(ds.size)}",
                    )
                }
                ChartCard("Je Wochentag", "Median · gestrichelt = weniger als 5 Fahrten") { BarChart(data) }
            }
            item { SectionHeader("Fahrten auf dieser Route") }
            items(trips.sortedByDescending { it.startTime }, key = { it.id }) { t -> TripRow(t, snap) { nav.navigate(Dest.trip(t.id)) } }
        }
    }
    if (rename && route != null) {
        var text by remember { mutableStateOf(route.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Route umbenennen") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameRoute(route.id, text); rename = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Abbrechen") } },
        )
    }
}
