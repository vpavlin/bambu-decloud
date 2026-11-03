package mesh.office.bambuwatch

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One place for colour, spacing and type.
 *
 * Before this the screens carried ~80 literal colours and font sizes from 10 to 20sp picked per
 * call site, so "the same" secondary text was three different greys and nothing could be changed
 * without hunting. These are the tokens from the mockup, and the screens use only these.
 */
object Bw {
    // surfaces, darkest first
    val bg = Color(0xFF0E1013)         // the page
    val surface = Color(0xFF171A1F)    // cards
    val surface2 = Color(0xFF1E2229)   // fields, chips, nested
    val line = Color(0xFF262B33)       // hairline borders

    // text, by importance
    val fg = Color(0xFFECEFF3)
    val fg2 = Color(0xFF9BA5B2)
    val fg3 = Color(0xFF6B7583)

    // meaning
    val acc = Color(0xFF4C8DFF)        // the one action, and selection
    val ok = Color(0xFF4ECE8A)
    val warn = Color(0xFFE8B24A)
    val bad = Color(0xFFF26565)

    /** a status colour and a dimmed version of it for fills, so tints stay consistent */
    fun tint(c: Color, alpha: Float) = c.copy(alpha = alpha)
    fun soft(c: Color) = c.copy(alpha = 0.13f)

    val cardShape = RoundedCornerShape(16.dp)
    val chipShape = RoundedCornerShape(11.dp)
    val fieldShape = RoundedCornerShape(11.dp)

    /** numbers that change (temperatures, percentages, layer counts) should not jitter */
    val numeric = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
}

/** small caps section heading, used the same way everywhere */
val sectionLabel = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.2.sp,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

/**
 * The hero card: a soft diagonal wash plus a colour glow in the top corner, tinted by what the
 * printer is doing. Flat surfaces read as a form; this reads as a status panel.
 */
@Composable
fun HeroSurface(tint: Color, modifier: Modifier = Modifier,
                content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth()
            .clip(Bw.cardShape)
            .drawBehind {
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF1D222A), Color(0xFF14171D)),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height)))
                val glowR = size.width * 0.62f
                val glowC = androidx.compose.ui.geometry.Offset(size.width * 1.02f, size.height * -0.05f)
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to tint.copy(alpha = 0.26f),
                            0.55f to tint.copy(alpha = 0.07f),
                            1.0f to tint.copy(alpha = 0f)),
                        center = glowC,
                        radius = glowR),
                    radius = glowR,
                    center = glowC)
            }
            .border(1.dp, Bw.line, Bw.cardShape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun BwTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bw.bg,
            onBackground = Bw.fg,
            surface = Bw.surface,
            onSurface = Bw.fg,
            surfaceVariant = Bw.surface2,
            onSurfaceVariant = Bw.fg2,
            primary = Bw.acc,
            onPrimary = Color(0xFF08101D),
            secondary = Bw.surface2,
            onSecondary = Bw.fg,
            error = Bw.bad,
            onError = Color(0xFF1B0A0A),
            outline = Bw.line,
        ),
        typography = type,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(11.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
