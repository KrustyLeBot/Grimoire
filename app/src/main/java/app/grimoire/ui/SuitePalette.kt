// Generated: the same recipe in every app of the suite (Synchrotron, Griffonote, Ecoutify, Météorite).
package app.grimoire.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #B832D0: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF9B00B4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFCDFF),
    onPrimaryContainer = Color(0xFF67416A),
    inversePrimary = Color(0xFFF9ACFF),
    secondary = Color(0xFF6C596C),
    onSecondary = Color(0xFFFFF7FA),
    secondaryContainer = Color(0xFFF4DBF1),
    onSecondaryContainer = Color(0xFF5E4C5E),
    tertiary = Color(0xFF85505A),
    onTertiary = Color(0xFFFFF7F7),
    tertiaryContainer = Color(0xFFFCB7C2),
    onTertiaryContainer = Color(0xFF64333D),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF383038),
    surface = Color(0xFFFFF7FA),
    onSurface = Color(0xFF383038),
    surfaceVariant = Color(0xFFEDDEE9),
    onSurfaceVariant = Color(0xFF665C65),
    surfaceTint = Color(0xFFA00DB9),
    inverseSurface = Color(0xFF110D11),
    inverseOnSurface = Color(0xFFA39BA0),
    error = Color(0xFFA8364B),
    onError = Color(0xFFFFF7F7),
    errorContainer = Color(0xFFF97386),
    onErrorContainer = Color(0xFF6E0523),
    outline = Color(0xFF837781),
    outlineVariant = Color(0xFFBBAEB8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF7FA),
    surfaceContainer = Color(0xFFF7EAF3),
    surfaceContainerHigh = Color(0xFFF2E4EE),
    surfaceContainerHighest = Color(0xFFEDDEE9),
    surfaceContainerLow = Color(0xFFFCF0F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4D6E0),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFFF9ACFF),
    onPrimary = Color(0xFF570066),
    primaryContainer = Color(0xFF664868),
    onPrimaryContainer = Color(0xFFFFD7FE),
    inversePrimary = Color(0xFFA00DB9),
    secondary = Color(0xFFD7BFD5),
    onSecondary = Color(0xFF4C3B4C),
    secondaryContainer = Color(0xFF473647),
    onSecondaryContainer = Color(0xFFD0B8CE),
    tertiary = Color(0xFFFFCAD2),
    onTertiary = Color(0xFF6E3C46),
    tertiaryContainer = Color(0xFFFCB7C2),
    onTertiaryContainer = Color(0xFF64333D),
    background = Color(0xFF110D11),
    onBackground = Color(0xFFF0E1EC),
    surface = Color(0xFF110D11),
    onSurface = Color(0xFFF0E1EC),
    surfaceVariant = Color(0xFF2B232B),
    onSurfaceVariant = Color(0xFFB4A7B1),
    surfaceTint = Color(0xFFF9ACFF),
    inverseSurface = Color(0xFFFFF7FA),
    inverseOnSurface = Color(0xFF595358),
    error = Color(0xFFF97386),
    onError = Color(0xFF490013),
    errorContainer = Color(0xFF871C34),
    onErrorContainer = Color(0xFFFF97A3),
    outline = Color(0xFF7D727B),
    outlineVariant = Color(0xFF4E454D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF322A31),
    surfaceContainer = Color(0xFF1E181D),
    surfaceContainerHigh = Color(0xFF241D24),
    surfaceContainerHighest = Color(0xFF2B232B),
    surfaceContainerLow = Color(0xFF171217),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF110D11),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
