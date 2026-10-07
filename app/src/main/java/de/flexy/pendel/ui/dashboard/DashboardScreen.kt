package de.flexy.pendel.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.Dest
import de.flexy.pendel.data.CorridorInfo
import de.flexy.pendel.navigateTab
import androidx.compose.material.icons.rounded.Star
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.core.stats.Descriptive
import de.flexy.pendel.data.AnalyticsSnapshot
import de.flexy.pendel.data.Recommendation
import de.flexy.pendel.data.localTimeInfo
import de.flexy.pendel.tracking.LiveTrip
import de.flexy.pendel.tracking.TrackingState
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.BarChart
import de.flexy.pendel.ui.components.BarDatum
import de.flexy.pendel.ui.components.BasisBadge
import de.flexy.pendel.ui.components.ChartLegend
import de.flexy.pendel.ui.components.DistributionChart
import de.flexy.pendel.ui.components.DistributionGroup
import de.flexy.pendel.ui.components.DurationTimelineChart
import de.flexy.pendel.ui.components.EmptyHint
import de.flexy.pendel.ui.components.Fmt
import de.flexy.pendel.ui.components.HBarDatum
import de.flexy.pendel.ui.components.HorizontalBars
import de.flexy.pendel.ui.components.InsightCard
import de.flexy.pendel.ui.components.LegendItem
import de.flexy.pendel.ui.components.RouteDot
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.components.StatTile
import de.flexy.pendel.ui.components.TimePoint
import de.flexy.pendel.ui.rememberRecordingPermission
import de.flexy.pendel.ui.theme.NumberStyle
import de.flexy.pendel.ui.trips.TripRow
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // live screen visible → service delivers fixes without batching
    DisposableEffect(Unit) {
        TrackingState.setUiVisible(true)
        onDispose { TrackingState.setUiVisible(false) }
    }

    val startRecording = rememberRecordingPermission(
        context,
        onReady = { vm.startRecording(context) },
        onDenied = { },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pendel", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        Fmt.date(System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { nav.navigate(Dest.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Einstellungen") }
            }
        }
        item {
            RecordingCard(
                live = live,
                autoDetect = snap.settings.autoDetect,
                onStart = startRecording,
                onStop = { vm.stopRecording(context) },
            )
        }
        snap.placeGroupSuggestions.firstOrNull()?.let { (child, parent) ->
            item(key = "group-${child.id}") {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("„${child.name}“ zu „${parent.name}“ zählen?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Die Orte liegen nah beieinander. Zusammengefasst werden Fahrten ab ${child.name} mit Fahrten ab ${parent.name} verglichen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { vm.setPlaceParent(child.id, parent.id) }) { Text("Zusammenfassen") }
                            androidx.compose.material3.TextButton(onClick = { vm.dismissPlaceGroup(child.id, parent.id) }) { Text("Nein") }
                        }
                    }
                }
            }
        }
        busy?.let { b ->
            item {
                Column {
                    Text(b, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (snap.loaded && snap.allTrips.isEmpty()) {
            item {
                EmptyHint(
                    "Noch keine Fahrten",
                    "Starte eine Aufzeichnung oder aktiviere die automatische Erkennung in den Einstellungen. " +
                        "Zum Ausprobieren kannst du realistische Demo-Fahrten erzeugen – sie durchlaufen dieselbe Analyse wie echte Fahrten.",
                )
            }
            item {
                FilledTonalButton(onClick = { vm.generateDemo() }, enabled = busy == null) { Text("Demo-Daten erzeugen") }
            }
        }

        val kpis = snap.kpis
        val main = kpis?.mainOd
        if (main != null) {
            val now = localTimeInfo(System.currentTimeMillis())
            val rec = vm.recommend(main, now.dayOfWeek, now.minuteOfDay)
            if (rec != null) item { RecommendationCard(rec, snap) { nav.navigate(Dest.ROUTES) } }
        }

        kpis?.primary?.let { p ->
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selectCorridor(p.key); nav.navigateTab(Dest.ROUTES) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Hauptstrecke: ${p.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("ändern", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (kpis != null && snap.allTrips.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Median ${main?.label ?: "Fahrt"}", Fmt.duration(kpis.medianMainS),
                        Modifier.weight(1f), sub = Fmt.trips(kpis.mainN), icon = Icons.Outlined.AvTimer,
                    )
                    StatTile(
                        "Schnellste Fahrt", Fmt.duration(kpis.fastestMain?.durationS),
                        Modifier.weight(1f).clickable(enabled = kpis.fastestMain != null) { kpis.fastestMain?.let { nav.navigate(Dest.trip(it.id)) } },
                        sub = kpis.fastestMain?.let { Fmt.date(it.startTime) }, icon = Icons.Outlined.Bolt,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Diese Woche", "${Fmt.km(kpis.weekDistanceM)} km", Modifier.weight(1f),
                        sub = Fmt.trips(kpis.weekTrips), icon = Icons.Outlined.Straighten,
                    )
                    StatTile(
                        "Verlorene Wartezeit", Fmt.duration(kpis.weekWaitS), Modifier.weight(1f),
                        sub = "diese Woche, geschätzt", icon = Icons.Outlined.Traffic,
                    )
                }
            }
        }

        // other destinations ("Sport", "Arbeit" …) – each analysed separately
        val others = snap.corridors.filter { !it.isPrimary && it.tripCount > 0 }
        if (others.isNotEmpty()) {
            item { SectionHeader("Weitere Ziele", "Eigene Routen und Statistiken – nicht mit der Hauptstrecke vermischt") }
            items(others, key = { "c-${it.key}" }) { c ->
                CorridorCard(c) {
                    vm.selectCorridor(c.key)
                    nav.navigateTab(Dest.ROUTES)
                }
            }
        }

        if (kpis != null && kpis.todayTrips.isNotEmpty()) {
            item { SectionHeader("Deine heutigen Fahrten") }
            items(kpis.todayTrips, key = { "today-${it.id}" }) { t ->
                TripRow(t, snap) { nav.navigate(Dest.trip(t.id)) }
            }
        }

        if (main != null) {
            val list = insights[main.od.key].orEmpty().take(3)
            if (list.isNotEmpty()) {
                item { SectionHeader("Erkannte Muster", main.label) }
                items(list.size) { i ->
                    InsightCard(list[i], routeName = { snap.routes[it]?.name ?: "Route" }, colorOf = { snap.routeColorIndex(it) })
                }
            }

            val legend = main.routes.map { LegendItem(it.name, it.colorIndex) }
            if (main.trips.size >= 2) {
                item {
                    ChartCard("Fahrzeit über Zeit", "${main.label} · Linie = gleitender Median") {
                        DurationTimelineChart(
                            main.trips.map { t ->
                                TimePoint(
                                    t.startTime, t.durationS, snap.routeColorIndex(t.routeId),
                                    "${Fmt.date(t.startTime)} ${Fmt.time(t.startTime)} · ${Fmt.mmss(t.durationS)} min · ${snap.routes[t.routeId]?.name ?: ""}",
                                )
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        ChartLegend(legend)
                    }
                }
                item {
                    val data = (1..7).map { d ->
                        val ds = main.trips.filter { it.dayOfWeek == d }.map { it.durationS }
                        BarDatum(
                            DayGroup.DAY_SHORT[d - 1],
                            if (ds.isEmpty()) null else Descriptive.median(ds),
                            ds.size,
                            if (ds.isEmpty()) "${DayGroup.DAY_NAMES[d - 1]}: keine Fahrten"
                            else "${DayGroup.DAY_NAMES[d - 1]}: Median ${Fmt.mmss(Descriptive.median(ds))} min · ${Fmt.trips(ds.size)}",
                        )
                    }
                    ChartCard("Fahrzeit je Wochentag", "Median · gestrichelt = weniger als 5 Fahrten") { BarChart(data) }
                }
                item {
                    ChartCard("Verteilung der Fahrzeiten", "Jeder Punkt eine Fahrt · Box = mittlere 50 % · Strich = Median") {
                        DistributionChart(
                            main.routes.map { r ->
                                DistributionGroup(r.name, r.colorIndex, main.trips.filter { it.routeId == r.id }.map { it.durationS })
                            }.filter { it.values.isNotEmpty() },
                        )
                        Spacer(Modifier.height(8.dp))
                        ChartLegend(legend.filter { l -> main.trips.any { t -> snap.routes[t.routeId]?.name == l.label } })
                    }
                }
            }
        }

        val topInter = snap.intersections.filter { it.stats.stopsWithWait > 0 }.take(5)
        if (topInter.isNotEmpty()) {
            item {
                ChartCard("Wo du am meisten wartest", "Gesamte geschätzte Wartezeit je Kreuzung") {
                    HorizontalBars(
                        topInter.map {
                            HBarDatum(
                                it.entity.userName ?: it.entity.name ?: "Kreuzung #${it.entity.id}",
                                it.stats.totalWaitS,
                                Fmt.wait(it.stats.totalWaitS),
                                "Ø ${Fmt.wait(it.stats.avgWaitPerPassS)} pro Durchfahrt · ${it.stats.passes} Durchfahrten",
                            )
                        },
                        onClick = { i -> nav.navigate(Dest.intersection(topInter[i].entity.id)) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ChartCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun RecordingCard(live: LiveTrip?, autoDetect: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val recording = live != null
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (recording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (live == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.DirectionsBike, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bereit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (autoDetect) "Automatische Erkennung aktiv – Fahrten starten von selbst" else "Automatische Erkennung aus",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onStart) {
                        Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start")
                    }
                }
            } else {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(live.tripId) {
                    while (true) { now = System.currentTimeMillis(); delay(1000) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (live.auto) "Fahrt automatisch erkannt" else "Aufzeichnung läuft",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            Fmt.duration((now - live.startTime) / 1000.0),
                            style = MaterialTheme.typography.displaySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Button(onClick = onStop) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stopp")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    LiveValue("Distanz", Fmt.distance(live.distanceM))
                    LiveValue("Tempo", Fmt.speedKmh(live.speedMs))
                    LiveValue("GPS", live.accuracyM?.let { "±${it.toInt()} m" } ?: "sucht …")
                    LiveValue("Takt", live.sampling.label)
                }
                AnimatedVisibility(live.stationarySince != null) {
                    Text(
                        "Stillstand – wird nach längerem Stehen automatisch beendet",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleSmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun RecommendationCard(rec: Recommendation, snap: AnalyticsSnapshot, onClick: () -> Unit) {
    val best = rec.ranking.firstOrNull() ?: return
    val route = snap.routes[best.metrics.routeId] ?: return
    val second = rec.ranking.getOrNull(1)
    val ess = rec.ess[route.id] ?: 0.0
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Beste Route jetzt · ${rec.objective.label}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(rec.od.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteDot(route.colorIndex, 16)
                Spacer(Modifier.width(10.dp))
                Text(route.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Erwartet ${Fmt.duration(best.metrics.expectedDurationS)}",
                style = MaterialTheme.typography.titleMedium.merge(NumberStyle), fontWeight = FontWeight.Normal,
            )
            if (second != null && rec.ranking.size > 1) {
                val delta = second.metrics.expectedDurationS - best.metrics.expectedDurationS
                val otherName = snap.routes[second.metrics.routeId]?.name ?: "nächste Route"
                Text(
                    if (delta >= 0) "${Fmt.mmss(delta)} min schneller als $otherName"
                    else "${Fmt.mmss(-delta)} min langsamer als $otherName, aber besser im gewählten Ziel",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasisBadge(DataBasis.of(ess), n = (rec.od.trips.count { it.routeId == route.id }))
                Spacer(Modifier.width(8.dp))
                Text(
                    "≈ ${ess.toInt()} Fahrten zu dieser Uhrzeit",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CorridorCard(c: CorridorInfo, onClick: () -> Unit) {
    val out = c.outbound
    val durations = out?.trips?.map { it.durationS }.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${out?.label ?: ""} · ${c.directions.sumOf { it.routes.size }} Routen",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (durations.isEmpty()) "–" else Fmt.duration(Descriptive.median(durations)),
                    style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                )
                BasisBadge(DataBasis.of(durations.size), durations.size)
            }
        }
    }
}
