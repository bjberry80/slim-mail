package slim.mail.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dunkle Leiste über der Werkzeugleiste, solange sich das letzte Löschen
 * oder Archivieren zurücknehmen lässt (siehe [MailViewModel.undoMove]).
 */
@Composable
fun UndoBar(label: String?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    // Text festhalten, solange die Leiste ausblendet
    var shownLabel by remember { mutableStateOf("") }
    if (label != null) shownLabel = label

    AnimatedVisibility(
        visible = label != null,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                // Über der Werkzeugleiste mit Status und Verfassen-Knopf
                .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF2C2C2E))
                .padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shownLabel, fontSize = 15.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(
                "Rückgängig",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64A8FF),
                modifier = Modifier
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}
