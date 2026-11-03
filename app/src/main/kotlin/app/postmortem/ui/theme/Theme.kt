package app.postmortem.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2B3A33),
        onPrimary = Color(0xFFF3EBDD),
        background = Color(0xFFFFFBF5),
        onBackground = Color(0xFF1B1C1A),
        surface = Color(0xFFFFFBF5),
        onSurface = Color(0xFF1B1C1A),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFB9CDBF),
        onPrimary = Color(0xFF14181A),
        background = Color(0xFF14181A),
        onBackground = Color(0xFFE6E2DA),
        surface = Color(0xFF14181A),
        onSurface = Color(0xFFE6E2DA),
    )

/** Colours of the board and the move list that the Material scheme has no role for. */
data class BoardPalette(
    val lightSquare: Color,
    val darkSquare: Color,
    val selectedMove: Color,
    val onSelectedMove: Color,
    /** The live engine's arrows (one colour; rank is shown by width and opacity) and the pill with the eval at their head. */
    val arrow: Color,
    val arrowLabel: Color,
    val onArrowLabel: Color,
)

private val LightBoard =
    BoardPalette(
        lightSquare = Color(0xFFEBDCC0),
        darkSquare = Color(0xFF8CA38F),
        selectedMove = Color(0xFF2B3A33),
        onSelectedMove = Color(0xFFF3EBDD),
        arrow = Color(0xFF2F6FA8),
        arrowLabel = Color(0xCC1B1C1A),
        onArrowLabel = Color(0xFFFFFBF5),
    )

private val DarkBoard =
    BoardPalette(
        lightSquare = Color(0xFFB5B09F),
        darkSquare = Color(0xFF4F6354),
        selectedMove = Color(0xFFB9CDBF),
        onSelectedMove = Color(0xFF14181A),
        arrow = Color(0xFF6FB1F0),
        arrowLabel = Color(0xCC0B0E0F),
        onArrowLabel = Color(0xFFE6E2DA),
    )

val LocalBoardPalette = staticCompositionLocalOf { LightBoard }

/** The evaluation chart: White's advantage fills above the zero line, Black's below it. */
data class ChartPalette(
    val background: Color,
    val whiteFill: Color,
    val blackFill: Color,
    val line: Color,
    val zeroLine: Color,
    val marker: Color,
    val dotOutline: Color,
)

private val LightChart =
    ChartPalette(
        background = Color(0xFFD3CCBC),
        whiteFill = Color(0xFFFFFBF5),
        blackFill = Color(0xFF2B3A33),
        line = Color(0xFF6E7F73),
        zeroLine = Color(0xFF8C8570),
        marker = Color(0xFFB4532A),
        dotOutline = Color(0xFFFFFBF5),
    )

private val DarkChart =
    ChartPalette(
        background = Color(0xFF3A4240),
        whiteFill = Color(0xFFE6E2DA),
        blackFill = Color(0xFF0B0E0F),
        line = Color(0xFFB9CDBF),
        zeroLine = Color(0xFF7C8784),
        marker = Color(0xFFE59A6B),
        dotOutline = Color(0xFF14181A),
    )

/** Lichess's colour-blind friendly colours for the judged moves, the same in light and dark. */
object JudgementColors {
    val inaccuracy = Color(0xFF56B4E9)
    val mistake = Color(0xFFE69F00)
    val blunder = Color(0xFFDB3031)
}

/**
 * The live engine's arrows by rank (1 is the best move): the best is the thickest, most opaque and has the biggest label, the
 * others get less of each. A rank beyond the list gets what the last one gets.
 */
object ArrowStyle {
    private val widths = listOf(1f, 0.7f, 0.5f)
    private val alphas = listOf(0.85f, 0.6f, 0.45f)
    private val labelScales = listOf(1f, 0.85f, 0.7f)

    /** The widest shaft, as a fraction of a square, and the head's length and width relative to it. */
    const val SHAFT = 0.2f
    const val HEAD_LENGTH = 0.5f
    const val HEAD_WIDTH = 0.6f

    /** Where the arrow starts and stops, as fractions of a square from the centre of its squares. */
    const val START_GAP = 0.2f
    const val TIP_GAP = 0.08f

    /** The eval label: text size and padding as fractions of a square, scaled by rank; its centre sits this far above the head. */
    const val LABEL_TEXT = 0.26f
    const val LABEL_PADDING_X = 0.12f
    const val LABEL_PADDING_Y = 0.06f
    const val LABEL_RISE = 0.55f

    fun width(rank: Int) = widths[(rank - 1).coerceIn(0, widths.lastIndex)]

    fun alpha(rank: Int) = alphas[(rank - 1).coerceIn(0, alphas.lastIndex)]

    fun labelScale(rank: Int) = labelScales[(rank - 1).coerceIn(0, labelScales.lastIndex)]
}

val LocalChartPalette = staticCompositionLocalOf { LightChart }

/** Sizes and spacing used across the screens. */
object Spacing {
    val screen = 16.dp
    val gap = 12.dp
    val small = 4.dp
    val moveCell = 6.dp
    val moveNumberWidth = 40.dp
    val splashFade = 96.dp
    val splashSpinner = 16.dp
    val evalBarHeight = 20.dp
    val evalBarTick = 1.dp
    val evalBarTickAlpha = 0.5f
    val evalBarLabelPadding = 2.dp
    val evalBarBorder = 1.dp
    val evalBarMillis = 300
    val chartLabelGap = 6.dp
    val chartLabelInset = 6.dp
    val chartLabelCorner = 8.dp
    val chartLabelPaddingX = 8.dp
    val chartLabelPaddingY = 4.dp
    val chartDot = 3.dp
    val chartDotSelected = 4.5.dp
    val chartDotOutline = 1.5.dp
    val chartLine = 2.dp
    val chartMarker = 2.dp
    val chartZeroLine = 1.dp
    val engineToggleIcon = 18.dp
    val touchTarget = 48.dp
    val engineOffAlpha = 0.38f
}

@Composable
fun PostmortemTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalBoardPalette provides if (dark) DarkBoard else LightBoard,
        LocalChartPalette provides if (dark) DarkChart else LightChart,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
