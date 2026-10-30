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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B3A33),
    onPrimary = Color(0xFFF3EBDD),
    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1B1C1A),
)

private val DarkColors = darkColorScheme(
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
)

private val LightBoard = BoardPalette(
    lightSquare = Color(0xFFEBDCC0),
    darkSquare = Color(0xFF8CA38F),
    selectedMove = Color(0xFF2B3A33),
    onSelectedMove = Color(0xFFF3EBDD),
)

private val DarkBoard = BoardPalette(
    lightSquare = Color(0xFFB5B09F),
    darkSquare = Color(0xFF4F6354),
    selectedMove = Color(0xFFB9CDBF),
    onSelectedMove = Color(0xFF14181A),
)

val LocalBoardPalette = staticCompositionLocalOf { LightBoard }

/** Sizes and spacing used across the screens. */
object Spacing {
    val screen = 16.dp
    val gap = 12.dp
    val small = 4.dp
    val moveCell = 6.dp
    val moveNumberWidth = 40.dp
}

@Composable
fun PostmortemTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBoardPalette provides if (dark) DarkBoard else LightBoard) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
