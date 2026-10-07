package de.flexy.pendel.ui.stats

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.flexy.pendel.Dest
import de.flexy.pendel.data.AnalyticsSnapshot
import de.flexy.pendel.data.TripView
import de.flexy.pendel.ui.AppViewModel
import de.flexy.pendel.ui.components.BarChart
import de.flexy.pendel.ui.components.BarDatum
import de.flexy.pendel.ui.components.EmptyHint
import de.flexy.pendel.ui.components.Fmt
import de.flexy.pendel.ui.components.HBarDatum
import de.flexy.pendel.ui.components.HorizontalBars
import de.flexy.pendel.ui.components.SectionHeader
import de.flexy.pendel.ui.components.StatTile
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Lifetime totals – everything ever ridden (demo rides excluded). */
data class Totals(
    val trips: List<TripView>,
    val distanceM: Double,
    val durationS: Double,
    val movingS: Double,
    val waitS: Double,
    val stoppedS: Double,
    val elevationGainM: Double?,
    val rideDays: Int,
    val since: Long?,
    val longest: TripView?,
    val fastest: TripView?,
    val topSpeed: TripView?,
    /** km per week, oldest first (last 8 weeks). */
    val weeks: List<Pair<LocalDate, Double>>,
    val perDestination: List<HBarDatum>,
) {
    val avgSpeedMs: Double get() = if (movingS > 0) distanceM / movingS else 0.0
}

fun computeTotals(snap: AnalyticsSnapshot, zone: ZoneId = ZoneId.systemDefault()): Totals {
    val trips = snap.allTrips.filter { !it.isDemo }
    fun day(t: TripView) = Instant.ofEpochMilli(t.startTime).atZone(zone).toLocalDate()
    val thisWeek = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (7 downTo 0).map { k ->
        val start = thisWeek.minusWeeks(k.toLong())
        start to trips.filter { val d = day(it); !d.isBefore(start) && d.isBefore(start.plusWeeks(1)) }.sumOf { it.distanceM }
    }
    // per destination: the corridors (Uni, Sport …); everything else is "Sonstige"
    val seen = HashSet<Long>()
    val dest = snap.corridors.mapNotNull { c ->
        val ts = c.trips.filter { !it.isDemo }
        seen += ts.map { it.id }
        if (ts.isEmpty()) null else HBarDatum(c.label, ts.sumOf { it.distanceM }, "${Fmt.km(ts.sumOf { it.distanceM })} km", "${Fmt.trips(ts.size)} · ${Fmt.duration(ts.sumOf { it.durationS })}")
    }.toMutableList()
    val rest = trips.filter { it.id !in seen }
    if (rest.isNotEmpty()) {
        dest += HBarDatum("Sonstige Fahrten", rest.sumOf { it.distanceM }, "${Fmt.km(rest.sumOf { it.distanceM })} km", "${Fmt.trips(rest.size)} · ${Fmt.duration(rest.sumOf { it.durationS })}")
    }
    val gains = trips.mapNotNull { it.elevationGainM }
    return Totals(
        trips = trips,
        distanceM = trips.sumOf { it.distanceM },
        durationS = trips.sumOf { it.durationS },
        movingS = trips.sumOf { it.movingS },
        waitS = trips.sumOf { it.waitS },
        stoppedS = trips.sumOf { it.stoppedS },
        elevationGainM = if (gains.isEmpty()) null else gains.sum(),
        rideDays = trips.map { day(it) }.distinct().size,
        since = trips.minOfOrNull { it.startTime },
        longest = trips.maxByOrNull { it.distanceM },
        fastest = trips.filter { it.distanceM >= 1000 }.maxByOrNull { it.avgMovingSpeed },
        topSpeed = trips.maxByOrNull { it.maxSpeed },
        weeks = weeks,
        perDestination = dest.sortedByDescending { it.value },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: AppViewModel, nav: NavHostController) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val t = remember(snap) { computeTotals(snap) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gesamtstatistik") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (t.trips.isEmpty()) {
                item { EmptyHint("Noch keine Fahrten", "Sobald du Fahrten aufgezeichnet hast, siehst du hier, wie viel du insgesamt unterwegs warst.") }
                return@LazyColumn
            }
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Insgesamt gefahren", style = MaterialTheme.typography.labelLarge)
                        Text("${Fmt.km(t.distanceM)} km", style = MaterialTheme.typography.displaySmall)
                        Text(
                            "${Fmt.trips(t.trips.size)} an ${t.rideDays} ${if (t.rideDays == 1) "Tag" else "Tagen"}" +
                                (t.since?.let { " · seit ${Fmt.date(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Fahrzeit gesamt", Fmt.duration(t.durationS), Modifier.weight(1f), sub = "Abfahrt bis Ankunft", icon = Icons.Outlined.Timer)
                    StatTile("In Bewegung", Fmt.duration(t.movingS), Modifier.weight(1f), sub = "ohne Stillstand", icon = Icons.Outlined.AvTimer)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Ø Tempo", Fmt.speedKmh(t.avgSpeedMs), Modifier.weight(1f), sub = "in Bewegung", icon = Icons.Outlined.Speed)
                    StatTile(
                        "Ø pro Fahrt", "${Fmt.km(t.distanceM / t.trips.size)} km", Modifier.weight(1f),
                        sub = Fmt.duration(t.durationS / t.trips.size), icon = Icons.Outlined.Straighten,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Wartezeit", Fmt.duration(t.waitS), Modifier.weight(1f), sub = "an Kreuzungen, geschätzt", icon = Icons.Outlined.Traffic)
                    StatTile(
                        "Höhenmeter", t.elevationGainM?.let { "${it.toInt()} m" } ?: "–", Modifier.weight(1f),
                        sub = if (t.elevationGainM == null) "keine verlässlichen Daten" else "bergauf", icon = Icons.Outlined.Terrain,
                    )
                }
            }

            if (t.weeks.any { it.second > 0 }) item { SectionHeader("Kilometer pro Woche", "Letzte 8 Wochen") }
            if (t.weeks.any { it.second > 0 }) item {
                val fmt = DateTimeFormatter.ofPattern("d.M.", Locale.GERMANY)
                BarChart(
                    data = t.weeks.map { (w, m) ->
                        BarDatum(fmt.format(w), m / 1000, 99, "Woche ab ${fmt.format(w)}: ${Fmt.km(m)} km")
                    },
                    valueFmt = { "%.0f".format(it) },
                )
            }

            if (t.perDestination.isNotEmpty()) {
                item { SectionHeader("Nach Ziel") }
                item { HorizontalBars(t.perDestination) }
            }

            item { SectionHeader("Rekorde") }
            t.longest?.let { r -> item { RecordRow("Längste Fahrt", "${Fmt.km(r.distanceM)} km", r) { nav.navigate(Dest.trip(r.id)) } } }
            t.fastest?.let { r -> item { RecordRow("Schnellste Fahrt (Ø)", Fmt.speedKmh(r.avgMovingSpeed), r) { nav.navigate(Dest.trip(r.id)) } } }
            t.topSpeed?.let { r -> item { RecordRow("Höchstgeschwindigkeit", Fmt.speedKmh(r.maxSpeed), r) { nav.navigate(Dest.trip(r.id)) } } }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, trip: TripView, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(Fmt.dateTime(trip.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(0.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
