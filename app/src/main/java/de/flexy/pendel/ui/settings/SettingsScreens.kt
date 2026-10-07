package de.flexy.pendel.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import de.flexy.pendel.core.optimize.Weights
import de.flexy.pendel.data.settings.MatcherChoice
import de.flexy.pendel.data.settings.Settings
import de.flexy.pendel.providers.OverpassIntersectionSource
import de.flexy.pendel.providers.PassthroughMatcher
import de.flexy.pendel.providers.ValhallaMatcher
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.Perms
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.rememberAutoDetectPermission
import kotlin.math.roundToInt

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val demoCount by vm.demoCount.collectAsStateWithLifecycle(0)
    val pointCount by vm.pointCount.collectAsStateWithLifecycle(0)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s = snap.settings
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var crash by remember { mutableStateOf(de.flexy.pendel.CrashLog.read(context)) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var renamePlace by remember { mutableStateOf<Long?>(null) }
    var deleteRange by remember { mutableStateOf(false) }
    val lastRun by vm.latestAnalysisRun.collectAsStateWithLifecycle(null)
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importJson(context, uri)
    }

    val requestAuto = rememberAutoDetectPermission(context) { ok ->
        vm.setAutoDetect(ok, context)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportJson(context, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ------------------------------------------------------------ recording
            item { SectionHeader("Aufzeichnung") }
            item {
                SettingsCard {
                    SwitchRow(
                        "Fahrten automatisch erkennen",
                        "Startet die Aufzeichnung, sobald Android Radfahren erkennt, und beendet sie nach längerem Stillstand.",
                        s.autoDetect,
                    ) { enable -> if (enable) requestAuto() else vm.setAutoDetect(false, context) }
                    if (s.autoDetect && !Perms.hasBackgroundLocation(context)) {
                        Text(
                            "Für den automatischen Start braucht Pendel „Standort: Immer zulassen“.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("App-Berechtigungen öffnen") }
                    }
                    Note(
                        "Akku: GPS läuft nur während einer Fahrt (2-s-Takt in Bewegung, 5 s im Stand, gebündelte Zustellung bei ausgeschaltetem Display). " +
                            "Die Bewegungserkennung läuft auf dem stromsparenden Sensor-Hub des Telefons.",
                    )
                }
            }

            // ------------------------------------------------------------ optimization
            item { SectionHeader("Eigene Gewichtung", "Für das Ziel „Eigene Gewichtung“ im Routenvergleich") }
            item { WeightsCard(s.customWeights) { vm.setWeights(it) } }

            // ------------------------------------------------------------ places
            if (snap.places.isNotEmpty()) {
                item { SectionHeader("Orte", "Automatisch aus Start- und Zielpunkten erkannt") }
                item {
                    SettingsCard {
                        snap.places.values.sortedBy { it.id }.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { renamePlace = p.id }) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    Text((p.parentPlaceId?.let { pp -> "gehört zu ${snap.places[pp]?.name ?: "?"} · " } ?: "") + "%.4f, %.4f".format(p.lat, p.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.Edit, "Umbenennen")
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------------ network services
            item { SectionHeader("Online-Dienste", "Alle optional und standardmäßig aus. Aufzeichnung und Analyse funktionieren komplett offline.") }
            item { MatcherCard(s) { m, url -> vm.setMatcher(m, url) } }
            item { OverpassCard(s) { enabled, url -> vm.setOverpass(enabled, url, context) } }
            item { MapStyleCard(s) { l, d -> vm.setMapStyles(l, d) } }

            // ------------------------------------------------------------ data
            item { SectionHeader("Daten") }
            item {
                SettingsCard {
                    Text("${snap.allTrips.size} Fahrten · $pointCount GPS-Punkte · lokal gespeichert", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.generateDemo() }, enabled = busy == null) { Text("Demo-Daten erzeugen") }
                        if (demoCount > 0) OutlinedButton(onClick = { vm.deleteDemo() }) { Text("Demo löschen ($demoCount)") }
                    }
                    busy?.let { Note(it) }
                    HorizontalDivider()
                    Text("Analyse", style = MaterialTheme.typography.titleSmall)
                    Note(
                        "Algorithmus-Version ${de.flexy.pendel.core.analysis.AnalysisVersion.CURRENT}. " +
                            (lastRun?.let { r -> "Letzter Lauf: ${r.status}" + (r.finishedAt?.let { " · ${de.flexy.pendel.ui.components.Fmt.dateTime(it)}" } ?: "") } ?: "Noch keine Analyse."),
                    )
                    OutlinedButton(onClick = { vm.reanalyze(context) }) { Text("Alles aus Rohdaten neu analysieren") }
                    Note("Rohdaten (GPS-Punkte, Zeitstempel, Genauigkeit) bleiben unverändert. Stopps, Wartezeiten, Routen und Statistiken werden daraus neu berechnet.")
                    crash?.let { c ->
                        HorizontalDivider()
                        Text("Letzter Absturz", style = MaterialTheme.typography.titleSmall)
                        Note(c.lineSequence().take(4).joinToString("\n"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(c)) }) { Text("Fehlerbericht kopieren") }
                            TextButton(onClick = { de.flexy.pendel.CrashLog.clear(context); crash = null }) { Text("Verwerfen") }
                        }
                    }
                    HorizontalDivider()
                    Text("Export & Gerätewechsel", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("pendel-backup.json") }) { Text("JSON") }
                        OutlinedButton(onClick = { csvLauncher.launch("pendel-fahrten.csv") }) { Text("CSV") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, enabled = busy == null) { Text("Importieren") }
                    }
                    Note(
                        "JSON = vollständiges Backup (Roh-GPS-Daten + deine Ortsnamen); auf dem neuen Handy importieren, " +
                            "der Rest wird neu berechnet. Doppelte Fahrten werden erkannt. CSV = eine Zeile pro Fahrt für Tabellen. GPX pro Fahrt in der Fahrtansicht.",
                    )
                    HorizontalDivider()
                    Text("Löschen", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { deleteRange = true }) { Text("Zeitraum löschen") }
                        TextButton(onClick = { confirmDeleteAll = true }) {
                            Text("Alle Daten löschen", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Dest.PRIVACY) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Datenschutz & übertragene Daten", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Alle Daten löschen?") },
            text = { Text("Alle Fahrten, GPS-Punkte, Routen, Orte und Kreuzungen werden endgültig vom Gerät gelöscht. Das kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteEverything(); confirmDeleteAll = false }) { Text("Endgültig löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") } },
        )
    }
    renamePlace?.let { pid ->
        val place = snap.places[pid]
        PlaceDialog(
            place = place,
            others = snap.places.values.filter { it.id != pid && it.parentPlaceId == null }.sortedBy { it.name },
            onDismiss = { renamePlace = null },
            onSave = { name, kind, parent ->
                vm.renamePlace(pid, name, kind)
                if (parent != place?.parentPlaceId) vm.setPlaceParent(pid, parent)
                renamePlace = null
            },
        )
    }
    if (deleteRange) {
        DeleteRangeDialog(onDismiss = { deleteRange = false }) { from, to ->
            vm.deleteRange(from, to)
            deleteRange = false
        }
    }
}

private val placeSuggestions = listOf("Zuhause" to "HOME", "Uni" to "UNI", "Sport" to "OTHER", "Arbeit" to "OTHER", "Bahnhof" to "OTHER")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlaceDialog(
    place: de.flexy.pendel.data.db.PlaceEntity?,
    others: List<de.flexy.pendel.data.db.PlaceEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit,
) {
    var text by remember(place?.id) { mutableStateOf(place?.name ?: "") }
    var kind by remember(place?.id) { mutableStateOf(place?.kind ?: "OTHER") }
    var parent by remember(place?.id) { mutableStateOf(place?.parentPlaceId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ort benennen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") })
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    placeSuggestions.forEach { (n, k) ->
                        androidx.compose.material3.SuggestionChip(onClick = { text = n; kind = k }, label = { Text(n) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(kind == "HOME", { kind = if (it) "HOME" else "OTHER" })
                    Text("Das ist mein Zuhause")
                }
                Note("Fahrten zu verschiedenen Orten werden als eigene Strecken ausgewertet (z. B. Uni und Sport) – nie als alternative Wege.")
                if (others.isNotEmpty()) {
                    Text("Gehört zu", style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(parent == null, onClick = { parent = null }, label = { Text("Eigenständig") })
                        others.forEach { o ->
                            androidx.compose.material3.FilterChip(parent == o.id, onClick = { parent = o.id }, label = { Text(o.name) })
                        }
                    }
                    Note("Z. B. Mensa → Uni: Fahrten ab der Mensa zählen dann zur Uni-Strecke.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.ifBlank { place?.name ?: "Ort" }, kind, parent) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteRangeDialog(onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val state = androidx.compose.material3.rememberDateRangePickerState()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val from = state.selectedStartDateMillis
            val to = state.selectedEndDateMillis ?: from
            TextButton(
                enabled = from != null,
                onClick = {
                    // picker returns UTC midnight of the chosen dates → convert to local day bounds
                    val zone = java.time.ZoneId.systemDefault()
                    val start = java.time.Instant.ofEpochMilli(from!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    val end = java.time.Instant.ofEpochMilli(to!!).atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)
                    onConfirm(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
                },
            ) { Text("Fahrten löschen", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    ) {
        androidx.compose.material3.DateRangePicker(
            state = state,
            title = { Text("Zeitraum löschen", Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.height(480.dp),
        )
    }
}

@Composable
private fun WeightsCard(current: Weights, onChange: (Weights) -> Unit) {
    var w by remember(current) { mutableStateOf(current) }
    val total = w.sum.takeIf { it > 0 } ?: 1.0
    SettingsCard {
        @Composable
        fun slider(label: String, value: Double, set: (Double) -> Weights) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value.toFloat(),
                    onValueChange = { w = set((it * 20).roundToInt() / 20.0) },
                    onValueChangeFinished = { onChange(w) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text("${(value / total * 100).roundToInt()} %", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
        }
        slider("Fahrzeit", w.duration) { w.copy(duration = it) }
        slider("Wartezeit", w.wait) { w.copy(wait = it) }
        slider("Distanz", w.distance) { w.copy(distance = it) }
        slider("Stopps", w.stops) { w.copy(stops = it) }
        slider("Streuung", w.spread) { w.copy(spread = it) }
        slider("Radfreundlich", w.bike) { w.copy(bike = it) }
        TextButton(onClick = { w = Weights.DEFAULT_CUSTOM; onChange(w) }) { Text("Zurücksetzen (70/15/10/5)") }
    }
}

@Composable
private fun MatcherCard(s: Settings, onSave: (MatcherChoice, String) -> Unit) {
    var choice by remember(s.matcher) { mutableStateOf(s.matcher) }
    var url by remember(s.valhallaUrl) { mutableStateOf(s.valhallaUrl) }
    SettingsCard {
        Text("Map Matching", style = MaterialTheme.typography.titleSmall)
        MatcherChoice.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { choice = m }) {
                RadioButton(choice == m, { choice = m })
                Text(m.label)
            }
        }
        if (choice == MatcherChoice.VALHALLA) {
            OutlinedTextField(
                url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Valhalla-URL") }, placeholder = { Text("http://192.168.178.10:8002") },
            )
            Note("Empfohlen: Valhalla selbst auf deinem Heimserver betreiben (Docker). Dann verlässt dein Track dein Netz nicht.")
        }
        val transmits = when {
            choice == MatcherChoice.VALHALLA && url.isNotBlank() -> ValhallaMatcher(url).transmits
            else -> PassthroughMatcher.transmits
        }
        Note("Übertragen wird: $transmits")
        LaunchedEffect(choice) { if (choice == MatcherChoice.NONE && s.matcher != MatcherChoice.NONE) onSave(choice, url) }
        if (choice == MatcherChoice.VALHALLA) FilledTonalButton(onClick = { onSave(choice, url) }, enabled = url.isNotBlank()) { Text("Übernehmen") }
    }
}

@Composable
private fun OverpassCard(s: Settings, onSave: (Boolean, String) -> Unit) {
    var url by remember(s.overpassUrl) { mutableStateOf(s.overpassUrl) }
    SettingsCard {
        SwitchRow(
            "Ampeln & Straßennamen aus OpenStreetMap",
            "Lädt Ampeln, Querungen und Straßennamen über die Overpass API und speichert sie lokal.",
            s.overpassEnabled,
        ) { onSave(it, url) }
        if (s.overpassEnabled) {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Overpass-URL") })
            TextButton(onClick = { onSave(true, url) }) { Text("URL übernehmen & neu laden") }
        }
        Note("Übertragen wird: ${OverpassIntersectionSource(url).transmits}")
        Note("Ohne diese Option lernt Pendel Kreuzungen aus deinen eigenen wiederholten Stopps – nur ohne Straßennamen.")
    }
}

@Composable
private fun MapStyleCard(s: Settings, onSave: (String, String) -> Unit) {
    var light by remember(s.mapStyleUrl) { mutableStateOf(s.mapStyleUrl) }
    var dark by remember(s.mapStyleDarkUrl) { mutableStateOf(s.mapStyleDarkUrl) }
    SettingsCard {
        Text("Kartenstil", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(light, { light = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Hell") })
        OutlinedTextField(dark, { dark = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Dunkel") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSave(light, dark) }) { Text("Übernehmen") }
            TextButton(onClick = {
                light = Settings.DEFAULT_MAP_STYLE; dark = Settings.DEFAULT_MAP_STYLE_DARK; onSave(light, dark)
            }) { Text("Standard") }
        }
        Note("Kartenkacheln (OpenFreeMap, OpenStreetMap-Daten) werden nur geladen, wenn du die Karte öffnest. Der Kachelserver sieht dabei den angezeigten Ausschnitt, nicht deine Fahrten.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val s = snap.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Datenschutz") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard {
                    Text("Deine Standortdaten bleiben auf dem Gerät", style = MaterialTheme.typography.titleMedium)
                    Note(
                        "Pendel speichert alle Fahrten, GPS-Punkte und Auswertungen ausschließlich lokal in einer App-eigenen Datenbank. " +
                            "Es gibt kein Konto, keine Cloud, keine Analyse-/Tracking-SDKs und keine Werbung. Daten werden an niemanden weitergegeben.",
                    )
                    Note("Android-Backups sind für diese App deaktiviert, damit Standortdaten nicht unbemerkt in einer Cloud-Sicherung landen.")
                }
            }
            item {
                SettingsCard {
                    Text("Was wann übertragen wird", style = MaterialTheme.typography.titleMedium)
                    PrivacyRow("Aufzeichnung & Analyse", "Nichts. Läuft vollständig offline.", false)
                    PrivacyRow("Kartenansicht", "Angezeigter Kartenausschnitt an den Kachelserver (${hostOf(s.mapStyleUrl)}), nur wenn die Karte geöffnet ist.", true)
                    PrivacyRow(
                        "OpenStreetMap-Kreuzungen",
                        if (s.overpassEnabled) "Aktiv: ein auf ~1 km gerundetes Rechteck an ${hostOf(s.overpassUrl)}, höchstens alle 30 Tage." else "Aus.",
                        s.overpassEnabled,
                    )
                    PrivacyRow(
                        "Map Matching",
                        if (s.matcher == MatcherChoice.VALHALLA && s.valhallaUrl.isNotBlank()) "Aktiv: Koordinaten + Zeitstempel jeder Fahrt an ${hostOf(s.valhallaUrl)}." else "Aus – keine Fahrtdaten verlassen das Gerät.",
                        s.matcher == MatcherChoice.VALHALLA,
                    )
                }
            }
            item {
                SettingsCard {
                    Text("Deine Rechte", style = MaterialTheme.typography.titleMedium)
                    Note("Export: Einstellungen → Daten → JSON (vollständig), CSV (Tabelle) oder pro Fahrt als GPX.")
                    Note("Gerätewechsel: JSON-Export auf dem alten Handy, Import auf dem neuen. Es gibt bewusst kein automatisches Cloud-Backup.")
                    Note("Löschen: einzelne Fahrten in der Fahrtdetailansicht, Demo-Daten oder alles unter Einstellungen → Daten. Beim Deinstallieren der App werden alle Daten entfernt.")
                    Note("Berechtigungen: Standort (für die Aufzeichnung), „Immer zulassen“ nur für die automatische Erkennung, körperliche Aktivität (Erkennung von Radfahren), Benachrichtigungen (laufende Aufzeichnung).")
                }
            }
        }
    }
}

@Composable
private fun PrivacyRow(title: String, text: String, active: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (active) "Netzwerk möglich" else "lokal",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Note(text)
    }
}

private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url
