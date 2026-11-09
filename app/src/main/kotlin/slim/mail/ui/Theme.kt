package slim.mail.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Farben aus dem Design-Entwurf v0.1. Blau und Grau sind dort etwas dunkler
// als übliche Systemfarben, für besseren Kontrast.
object AppColors {
    val Blue = Color(0xFF0A66D1)
    val GroupedBackground = Color(0xFFF2F2F7)
    val Cell = Color.White
    val Separator = Color(0xFFC6C6C8)
    val SecondaryLabel = Color(0xFF5E5E63)
    val SearchField = Color(0xFFEEEEF0)
    val Toolbar = Color(0xFFF7F7F8)

    // Swipe-Aktionen in der Mailliste
    val SwipeMore = Color(0xFF636368)
    val SwipeFlag = Color(0xFFB95600)
    val SwipeArchive = Color(0xFF6A3BB8)
    // Dunkles Rot, damit weiße Schrift gut lesbar bleibt
    val SwipeDelete = Color(0xFFD70015)
}

@Composable
fun SlimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AppColors.Blue,
            background = AppColors.GroupedBackground,
            surface = AppColors.Cell,
        ),
        content = content,
    )
}
