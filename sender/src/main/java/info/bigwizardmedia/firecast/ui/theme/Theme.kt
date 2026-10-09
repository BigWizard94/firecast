package info.bigwizardmedia.firecast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FirecastColors = darkColorScheme(
    primary = Color(0xFFFF6D00),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF4A2500),
    secondary = Color(0xFFFFD54F),
    background = Color(0xFF101010),
    surface = Color(0xFF181818),
)

@Composable
fun FirecastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FirecastColors,
        content = content,
    )
}
