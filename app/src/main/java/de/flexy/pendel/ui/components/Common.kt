package de.flexy.pendel.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.flexy.pendel.core.insights.Insight
import de.flexy.pendel.core.insights.InsightType
import de.flexy.pendel.core.stats.DataBasis
import de.flexy.pendel.ui.theme.LocalPendelColors
import de.flexy.pendel.ui.theme.NumberStyle

/**
 * How trustworthy a number is. Rendered with increasing visual weight – a statement based on
 * 2 rides must never look as solid as one based on 50.
 */
@Composable
fun BasisBadge(basis: DataBasis, n: Int? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg, filled) = when (basis) {
        DataBasis.NONE -> Triple(Color.Transparent, cs.onSurfaceVariant, 0)
        DataBasis.PRELIMINARY -> Triple(Color.Transparent, cs.onSurfaceVariant, 1)
        DataBasis.TENDENCY -> Triple(cs.surfaceVariant, cs.onSurfaceVariant, 2)
        DataBasis.GOOD -> Triple(cs.secondaryContainer, cs.onSecondaryContainer, 3)
        DataBasis.HIGH -> Triple(cs.primaryContainer, cs.onPrimaryContainer, 4)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bg,
        border = if (filled <= 1) BorderStroke(1.dp, cs.outlineVariant) else null,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBars(level = filled, color = fg)
            Spacer(Modifier.width(6.dp))
            Text(
                text = basis.label + (n?.let { " · ${Fmt.trips(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
    }
}

/** Four little bars – secondary (non-color) encoding of the data basis. */
@Composable
private fun SignalBars(level: Int, color: Color) {
    Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
        val w = size.width / 4f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            val active = i < level
            drawRoundRect(
                color = if (active) color else color.copy(alpha = 0.25f),
                topLeft = androidx.compose.ui.geometry.Offset(i * w + 1f, size.height - h),
                size = androidx.compose.ui.geometry.Size(w - 2f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f),
            )
        }
    }
}

@Composable
fun RouteDot(colorIndex: Int, size: Int = 10) {
    val c = LocalPendelColors.current.route(colorIndex)
    Box(Modifier.size(size.dp).clip(CircleShape).background(c))
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, icon: ImageVector? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall.merge(NumberStyle))
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyHint(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Human sentence for an insight. */
fun insightText(i: Insight, routeName: (Long) -> String): Pair<String, String> {
    val w = routeName(i.winnerRouteId)
    val o = routeName(i.otherRouteId)
    val group = Fmt.dayGroup(i.dayGroup)
    return when (i.type) {
        InsightType.ADVANTAGE ->
            "$group ${Fmt.minuteOfDay(i.startMinute)}–${Fmt.minuteOfDay(i.endMinute)} Uhr ist $w im Median ${Fmt.mmss(i.deltaS)} min schneller als $o." to
                "${i.winnerTrips} vs. ${i.otherTrips} Fahrten · ${(i.probability * 100).toInt()} % Sicherheit"
        InsightType.ADVANTAGE_FADES ->
            "$group ab ${Fmt.minuteOfDay(i.startMinute)} Uhr verschwindet der Vorteil von $w gegenüber $o." to
                "Danach kein belastbarer Unterschied mehr"
    }
}

@Composable
fun InsightCard(i: Insight, routeName: (Long) -> String, colorOf: (Long) -> Int, modifier: Modifier = Modifier) {
    val (title, sub) = insightText(i, routeName)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                RouteDot(colorOf(i.winnerRouteId), 12)
                if (i.type == InsightType.ADVANTAGE) {
                    Spacer(Modifier.height(4.dp))
                    DashedLine(Modifier.height(14.dp).width(2.dp))
                    Spacer(Modifier.height(4.dp))
                    RouteDot(colorOf(i.otherRouteId), 8)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (i.type == InsightType.ADVANTAGE) BasisBadge(i.basis)
            }
        }
    }
}

@Composable
private fun DashedLine(modifier: Modifier) {
    val c = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        drawLine(
            c, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
            androidx.compose.ui.geometry.Offset(size.width / 2, size.height),
            strokeWidth = size.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )
    }
}

/**
 * Tabs for destinations ("Strecken": Uni, Sport …). Each corridor is analysed on its own – routes
 * to different destinations are never compared. The primary corridor carries a star.
 */
@Composable
fun CorridorTabs(corridors: List<de.flexy.pendel.data.CorridorInfo>, selectedKey: String?, onSelect: (String) -> Unit) {
    if (corridors.isEmpty()) return
    val index = corridors.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    androidx.compose.material3.ScrollableTabRow(selectedTabIndex = index, edgePadding = 16.dp) {
        corridors.forEachIndexed { i, c ->
            androidx.compose.material3.Tab(
                selected = i == index,
                onClick = { onSelect(c.key) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.isPrimary) {
                            Icon(Icons.Rounded.Star, "Hauptstrecke", Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("${c.label} · ${c.tripCount}", maxLines = 1)
                    }
                },
            )
        }
    }
}
