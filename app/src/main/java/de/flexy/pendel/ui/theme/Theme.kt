package de.flexy.pendel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Categorical route colors. Fixed order (never cycled), each route keeps its slot for life
 * (color follows the entity, not its rank). Validated CVD-safe order; the first three slots are
 * safe even when all routes are shown together (map). Light and dark are separately stepped.
 */
object RoutePalette {
    private val light = listOf(
        Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF4A3AA7), Color(0xFFE34948),
    )
    private val dark = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
    )
    /** Colors beyond slot 8 fold to a neutral gray instead of generating new hues. */
    private val otherLight = Color(0xFF8A8985)
    private val otherDark = Color(0xFF7C7B76)

    fun color(index: Int, darkTheme: Boolean): Color {
        val list = if (darkTheme) dark else light
        return if (index in list.indices) list[index] else if (darkTheme) otherDark else otherLight
    }
}

/** Single-hue sequential ramp (orange) for wait-time magnitude. */
object WaitRamp {
    val light = listOf(Color(0xFFFDE3D6), Color(0xFFF7B597), Color(0xFFF08A5D), Color(0xFFEB6834), Color(0xFFC24E1F), Color(0xFF8F3511))
    val dark = listOf(Color(0xFF4A2A1C), Color(0xFF7A3A1F), Color(0xFFA94A22), Color(0xFFD95926), Color(0xFFF08A5D), Color(0xFFF7B597))
    fun at(t: Double, darkTheme: Boolean): Color {
        val ramp = if (darkTheme) dark else light
        val i = (t.coerceIn(0.0, 1.0) * (ramp.size - 1)).toInt()
        return ramp[i]
    }
}

data class PendelColors(val dark: Boolean) {
    fun route(index: Int) = RoutePalette.color(index, dark)
}

val LocalPendelColors = staticCompositionLocalOf { PendelColors(false) }

private val fallbackLight = lightColorScheme(
    primary = Color(0xFF2A62B8),
    secondary = Color(0xFF52606F),
    tertiary = Color(0xFF1B8F67),
    surface = Color(0xFFFCFCFB),
    background = Color(0xFFFCFCFB),
)
private val fallbackDark = darkColorScheme(
    primary = Color(0xFF9EC5F4),
    secondary = Color(0xFFBAC8DA),
    tertiary = Color(0xFF7FD8B3),
    surface = Color(0xFF121211),
    background = Color(0xFF121211),
)

private val base = Typography()
private val PendelTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.3.sp),
    bodyMedium = base.bodyMedium,
)

/** Tabular numerals so changing numbers don't jitter. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun PendelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> fallbackDark
        else -> fallbackLight
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPendelColors provides PendelColors(dark)) {
        MaterialTheme(colorScheme = scheme, typography = PendelTypography, content = content)
    }
}
