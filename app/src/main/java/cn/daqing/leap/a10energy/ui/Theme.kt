package cn.daqing.leap.a10energy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF0B6B58)
private val GreenDark = Color(0xFF3DDCB4)
private val Ink = Color(0xFF1A1D21)
private val Blue = Color(0xFF1F4E89)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F3EA),
    onPrimaryContainer = Color(0xFF07382E),
    secondary = Blue,
    onSecondary = Color.White,
    background = Color(0xFFF4F6F8),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6EEF2),
    onSurfaceVariant = Color(0xFF3E4A54),
    error = Color(0xFF9B2C2C),
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF043028),
    primaryContainer = Color(0xFF0E3A32),
    onPrimaryContainer = Color(0xFFD5F3EA),
    secondary = Color(0xFF9CC4F5),
    background = Color(0xFF101417),
    onBackground = Color(0xFFE8EEF2),
    surface = Color(0xFF1A2128),
    onSurface = Color(0xFFE8EEF2),
    surfaceVariant = Color(0xFF24303A),
    onSurfaceVariant = Color(0xFFB7C3CE),
    error = Color(0xFFFFB4AB),
)

val SocNumberStyle = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
val BatteryTempNumberStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
fun A10Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
