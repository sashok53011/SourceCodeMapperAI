package online.devhorizon.sourcecodemapper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C8DF6),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF46C46B),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF171A23),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF1E2230),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF2A3040),
    error = Color(0xFFE5484D)
)

@Composable
fun ScmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
