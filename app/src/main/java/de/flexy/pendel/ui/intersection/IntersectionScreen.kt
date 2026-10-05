package de.flexy.pendel.ui.intersection

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.core.insights.CellStat
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.core.stats.DayGroup
import de.flexy.pendel.data.db.IntersectionSource
import de.flexy.pendel.data.db.displayName
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.BasisBadge
import de.flexy.pendel.ui.components.EmptyHint
import de.flexy.pendel.ui.components.Fmt
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.components.StatTile
import de.flexy.pendel.ui.dashboard.ChartCard
import de.flexy.pendel.ui.map.MapLayers
import de.flexy.pendel.ui.map.MapMarker
import de.flexy.pendel.ui.map.PendelMap
import de.flexy.pendel.ui.theme.LocalPendelColors
import de.flexy.pendel.ui.theme.WaitRamp

private val kindLabels = mapOf(
    "TRAFFIC_SIGNALS" to "Ampel",
    "CROSSING_SIGNALS" to "Fußgängerampel",
    "STOP_SIGN" to "Stoppschild",
    "GIVE_WAY" to "Vorfahrt achten",
    "CROSSING" to "Querung",
    "LEARNED" to "Aus deinen Stopps gelernt",
    "JUNCTION" to "Kreuzung",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntersectionScreen(vm: AppViewModel, nav: NavHostController, id: Long) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val info = snap.intersections.firstOrNull { it.entity.id == id }
    var rename by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🚦 Kreuzung") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = { IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Umbenennen") } },
            )
        },
    ) { padding ->
        if (info == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Für diese Kreuzung gibt es keine Durchfahrten.")
            }
            return@Scaffold
        }
        val x = info.entity
        val s = info.stats
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(x.displayName(), style = MaterialTheme.typography.headlineSmall)
                Text(
                    (kindLabels[x.kind] ?: x.kind) + if (x.source == IntersectionSource.OSM.name) " · OpenStreetMap" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                BasisBadge(s.basis, null)
            }
            item {
                PendelMap(
                    layers = MapLayers(intersections = listOf(MapMarker(x.id, x.lat, x.lon, s.totalWaitS, "intersection")), markers = listOf(MapMarker(x.id, x.lat, x.lon, 0.0, "center")), showMarkers = false),
                    styleUrlLight = snap.settings.mapStyleUrl,
                    styleUrlDark = snap.settings.mapStyleDarkUrl,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp)),
                    interactive = false,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Durchfahrten", "${s.passes}", Modifier.weight(1f), sub = "davon ${s.stopsWithWait} mit Halt")
                    StatTile("Ø Wartezeit", Fmt.wait(s.avgWaitPerPassS), Modifier.weight(1f), sub = "pro Durchfahrt, inkl. grüner Welle")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Wenn du hältst", Fmt.wait(s.medianWaitWhenStoppedS), Modifier.weight(1f), sub = "Median · Ø ${Fmt.wait(s.avgWaitWhenStoppedS)}")
                    StatTile("Haltequote", Fmt.percent(s.stopProbability), Modifier.weight(1f), sub = "Anteil Durchfahrten mit Stopp")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Gesamte Wartezeit", Fmt.wait(s.totalWaitS), Modifier.weight(1f))
                    StatTile("Ø Konfidenz", if (s.stopsWithWait > 0) Fmt.percent(s.avgConfidence) else "–", Modifier.weight(1f), sub = "Wahrscheinlichkeit echter Wartezeit")
                }
            }
            item {
                ChartCard("Wartezeit nach Wochentag und Uhrzeit", "Ø pro Durchfahrt · Zahl = Durchfahrten · antippen für Details") {
                    WaitGrid(s.byDayHour)
                }
            }
            val notable = s.byDayHour.flatMap { (d, hours) -> hours.map { (h, c) -> Triple(d, h, c) } }
                .filter { it.third.passes >= 2 }
                .sortedByDescending { it.third.avgWaitPerPassS }
                .take(6)
            if (notable.isNotEmpty()) {
                item { SectionHeader("Auffällige Zeitfenster", "Feste Stunden nur zur Darstellung – Zahl der Durchfahrten beachten") }
                items(notable, key = { "${it.first}-${it.second}" }) { (d, h, c) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${DayGroup.DAY_NAMES[d - 1]} ${"%02d".format(h)}–${"%02d".format(h + 1)} Uhr", Modifier.weight(1f))
                        Text(Fmt.wait(c.avgWaitPerPassS), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        BasisBadge(DataBasis.of(c.passes), c.passes)
                    }
                }
            }
            if (s.stopsWithWait == 0) {
                item { EmptyHint("Keine Wartezeiten", "Du bist hier bisher immer ohne erkennbaren Halt durchgefahren.") }
            }
        }
    }
    if (rename && info != null) {
        var text by remember { mutableStateOf(info.entity.userName ?: info.entity.name ?: "") }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Kreuzung benennen") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("z. B. Karlstraße × Kriegsstraße") }) },
            confirmButton = { TextButton(onClick = { vm.renameIntersection(info.entity.id, text); rename = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Abbrechen") } },
        )
    }
}

/** Weekday × hour matrix, single-hue sequential color + the pass count in each cell. */
@Composable
private fun WaitGrid(cells: Map<Int, Map<Int, CellStat>>) {
    val hours = cells.values.flatMap { it.keys }.let { hs -> if (hs.isEmpty()) (7..9).toList() else (hs.min()..hs.max()).toList() }
    val maxWait = cells.values.flatMap { it.values }.maxOfOrNull { it.avgWaitPerPassS }?.takeIf { it > 0 } ?: 1.0
    val dark = LocalPendelColors.current.dark
    var selected by remember { mutableStateOf<String?>(null) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Spacer(Modifier.height(18.dp))
                (1..7).forEach { d ->
                    Box(Modifier.height(34.dp).width(28.dp), contentAlignment = Alignment.CenterStart) {
                        Text(DayGroup.DAY_SHORT[d - 1], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$h", style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(18.dp))
                    (1..7).forEach { d ->
                        val c = cells[d]?.get(h)
                        val bg = if (c == null) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
                        else WaitRamp.at(c.avgWaitPerPassS / maxWait, dark)
                        val text = c?.let { "${DayGroup.DAY_NAMES[d - 1]} $h–${h + 1} Uhr: ${Fmt.wait(it.avgWaitPerPassS)} (${Fmt.trips(it.passes)})" }
                        Box(
                            Modifier.padding(1.dp).size(32.dp).clip(RoundedCornerShape(6.dp)).background(bg)
                                .clickable(enabled = c != null) { selected = text },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c != null) {
                                val light = c.avgWaitPerPassS / maxWait > 0.55
                                Text(
                                    "${c.passes}", style = MaterialTheme.typography.labelSmall,
                                    color = if (light xor dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                                )
                            }
                        }
                    }
                }
            }
        }
        selected?.let {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(it, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
