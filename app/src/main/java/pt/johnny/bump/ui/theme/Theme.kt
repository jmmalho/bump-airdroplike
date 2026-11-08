package pt.johnny.bump.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** BUMP brand: deep ink, an optical blue, pearl surfaces; a cyan→violet light for the handoff. */
object Brand {
    val Ink = Color(0xFF0D1629)
    val Blue = Color(0xFF1F6BFF)
    val BlueDeep = Color(0xFF1450D6)
    val Cyan = Color(0xFF5CE1FF)
    val Violet = Color(0xFF8A7CFF)
    val Pearl = Color(0xFFF4F6FB)
    val Success = Color(0xFF16A36A)
    val Danger = Color(0xFFE5484D)
}

private val Light = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF00205F),
    secondary = Color(0xFF4D5B76),
    secondaryContainer = Color(0xFFE6EBF5),
    onSecondaryContainer = Color(0xFF1A2438),
    tertiary = Brand.Violet,
    background = Brand.Pearl,
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEDF0F6),
    onSurfaceVariant = Color(0xFF5B677D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFEBEFF6),
    outline = Color(0xFFC5CCD9),
    outlineVariant = Color(0xFFE2E6EE),
    error = Brand.Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DB1FF),
    onPrimary = Color(0xFF002E85),
    primaryContainer = Color(0xFF1846B8),
    onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFB7C3DB),
    secondaryContainer = Color(0xFF2A3447),
    onSecondaryContainer = Color(0xFFDCE3F1),
    tertiary = Color(0xFFBCB3FF),
    background = Color(0xFF080C16),
    onBackground = Color(0xFFE6EAF3),
    surface = Color(0xFF0F1524),
    onSurface = Color(0xFFE6EAF3),
    surfaceVariant = Color(0xFF1A2233),
    onSurfaceVariant = Color(0xFF9BA6BB),
    surfaceContainerLowest = Color(0xFF0A0F1B),
    surfaceContainerLow = Color(0xFF121929),
    surfaceContainer = Color(0xFF161E2F),
    surfaceContainerHigh = Color(0xFF1C2537),
    outline = Color(0xFF3A4559),
    outlineVariant = Color(0xFF263044),
    error = Color(0xFFFF8A8E),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun BumpTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = AppShapes, content = content)
}
