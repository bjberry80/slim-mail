package slim.mail.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slim.mail.R

private object ThreadColors {
    val Divider = Color(0xFFE2E2E6)
    val AvatarSmall = Color(0xFFE4E4E9)
    val AvatarSmallText = Color(0xFF3A3A3F)
    val AvatarLarge = Color(0xFF3A6EA5)
    val AttachmentBorder = Color(0xFFD6D6DA)
    val Pdf = Color(0xFFC8312C)
}

/**
 * Screen 3 des Entwurfs: ältere Nachrichten des Threads zugeklappt, eine
 * aufgeklappt (anfangs die neueste). Ein Tipp auf eine zugeklappte Nachricht
 * klappt stattdessen diese auf.
 */
@Composable
fun ThreadScreen(
    message: Message,
    // null, solange die Nachrichten vom Server laden
    thread: List<ThreadMessage>?,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onToggleFlag: () -> Unit,
    onArchive: (() -> Unit)?,
    onReply: (ThreadMessage) -> Unit,
    onForward: (ThreadMessage) -> Unit,
    onCompose: () -> Unit,
    // Lädt einen Anhang (oder nimmt die schon geladene Datei); null bei Fehler
    loadAttachment: suspend (AttachmentRef) -> File?,
    imagePolicy: RemoteImagePolicy,
    backLabel: String = "Eingang",
) {
    val messages = thread.orEmpty()
    var expandedIndex by remember(message.id, messages.size) { mutableIntStateOf(messages.lastIndex) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Cell)
            .statusBarsPadding(),
    ) {
        ThreadTopBar(backLabel, onBack, onPrevious, onNext)
        // Jede Mail beginnt oben, auch beim Blättern mit den Pfeilen
        val listState = remember(message.id) { LazyListState() }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            state = listState,
        ) {
            item {
                Text(
                    message.subject,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (message.threadCount > 1) {
                    Text(
                        "${message.threadCount} Nachrichten",
                        fontSize = 13.sp,
                        color = AppColors.SecondaryLabel,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
            }
            if (thread == null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = AppColors.SecondaryLabel,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            itemsIndexed(messages) { index, item ->
                HorizontalDivider(thickness = 0.5.dp, color = ThreadColors.Divider)
                if (index == expandedIndex) {
                    ExpandedMessage(item, loadAttachment, imagePolicy)
                } else {
                    CollapsedMessage(item, onClick = { expandedIndex = index })
                }
            }
        }
        ThreadToolbar(
            flagged = message.flagged,
            onToggleFlag = onToggleFlag,
            onArchive = onArchive,
            // Geantwortet und weitergeleitet wird die gerade aufgeklappte Nachricht
            onReply = { messages.getOrNull(expandedIndex)?.let(onReply) },
            onForward = { messages.getOrNull(expandedIndex)?.let(onForward) },
            onCompose = onCompose,
        )
    }
}

@Composable
private fun ThreadTopBar(
    backLabel: String,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_chevron_left),
                contentDescription = null,
                tint = AppColors.Blue,
                modifier = Modifier
                    .size(26.dp)
                    .offset(x = (-6).dp),
            )
            Text(backLabel, color = AppColors.Blue, fontSize = 17.sp)
        }
        Spacer(Modifier.weight(1f))
        NavArrow(R.drawable.ic_chevron_up, "Vorherige E-Mail", onPrevious)
        Spacer(Modifier.width(20.dp))
        NavArrow(R.drawable.ic_chevron_down, "Nächste E-Mail", onNext)
    }
}

@Composable
private fun NavArrow(@DrawableRes icon: Int, description: String, onClick: (() -> Unit)?) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        // Am Anfang oder Ende der Liste ausgegraut
        tint = if (onClick != null) AppColors.Blue else AppColors.Separator,
        modifier = Modifier
            .size(26.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

@Composable
private fun Avatar(initials: String, size: Dp, background: Color, textColor: Color, fontSize: TextUnit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = textColor, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CollapsedMessage(item: ThreadMessage, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.initials, 32.dp, ThreadColors.AvatarSmall, ThreadColors.AvatarSmallText, 13.sp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Row {
                Text(
                    item.sender,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(item.date, fontSize = 13.sp, color = AppColors.SecondaryLabel)
            }
            Text(
                item.body.replace(Regex("\\s+"), " "),
                fontSize = 14.sp,
                color = AppColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpandedMessage(
    item: ThreadMessage,
    loadAttachment: suspend (AttachmentRef) -> File?,
    imagePolicy: RemoteImagePolicy,
) {
    Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(item.initials, 40.dp, ThreadColors.AvatarLarge, Color.White, 15.sp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.sender,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(item.longDate ?: item.date, fontSize = 13.sp, color = AppColors.SecondaryLabel)
                }
                Text(
                    buildAnnotatedString {
                        append("An: Ich ")
                        withStyle(SpanStyle(color = AppColors.Blue)) { append("Details") }
                    },
                    fontSize = 14.sp,
                    color = AppColors.SecondaryLabel,
                )
            }
        }
        // Auch reiner Text läuft durch die WebView, damit sich jede Mail zoomen lässt
        HtmlBody(
            html = item.html ?: plainTextHtml(item.body),
            key = "${item.uid}:${item.date}",
            senderName = item.sender,
            senderAddress = item.senderAddress,
            imagePolicy = imagePolicy,
            modifier = Modifier.padding(top = 16.dp),
        )
        item.attachments.forEach { AttachmentCard(it, loadAttachment) }
    }
}

@Composable
private fun AttachmentCard(attachment: AttachmentRef, loadAttachment: suspend (AttachmentRef) -> File?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(attachment) { mutableStateOf(false) }
    var failed by remember(attachment) { mutableStateOf(false) }

    // Erst laden (falls nötig), dann öffnen bzw. teilen
    fun handle(share: Boolean) {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            val file = loadAttachment(attachment)
            loading = false
            if (file == null) failed = true
            else if (!openAttachment(context, file, attachment.mimeType, share)) failed = true
        }
    }

    val fileName = attachment.name
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Toolbar)
            .border(0.5.dp, ThreadColors.AttachmentBorder, shape)
            .clickable { handle(share = false) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val extension = fileName.substringAfterLast('.', "").uppercase().take(4)
        Box(
            Modifier
                .size(width = 36.dp, height = 44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (extension == "PDF") ThreadColors.Pdf else AppColors.SecondaryLabel),
            contentAlignment = Alignment.Center,
        ) {
            Text(extension, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(fileName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                loading -> "Wird geladen …"
                failed -> "Konnte nicht geöffnet werden"
                else -> listOf(formatSize(attachment.size.toLong()), "Antippen zum Öffnen")
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            }
            Text(status, fontSize = 13.sp, color = AppColors.SecondaryLabel)
        }
        if (loading) {
            CircularProgressIndicator(
                color = AppColors.SecondaryLabel,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_share),
                contentDescription = "Anhang teilen",
                tint = AppColors.Blue,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { handle(share = true) },
            )
        }
    }
}

@Composable
private fun ThreadToolbar(
    flagged: Boolean,
    onToggleFlag: () -> Unit,
    onArchive: (() -> Unit)?,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCompose: () -> Unit,
) {
    var replyMenu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Toolbar)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = AppColors.Separator)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ToolbarIcon(
                R.drawable.ic_mailbox_flagged,
                "Markieren",
                tint = if (flagged) AppColors.SwipeFlag else AppColors.Blue,
                onClick = onToggleFlag,
            )
            ToolbarIcon(R.drawable.ic_folder, "Verschieben", onClick = {})
            ToolbarIcon(
                R.drawable.ic_mailbox_archive,
                "Archivieren",
                tint = if (onArchive != null) AppColors.Blue else AppColors.Separator,
                onClick = { onArchive?.invoke() },
            )
            // Der Pfeil fragt, ob geantwortet oder weitergeleitet wird
            Box {
                ToolbarIcon(R.drawable.ic_reply, "Antworten oder weiterleiten", onClick = { replyMenu = true })
                DropdownMenu(
                    expanded = replyMenu,
                    onDismissRequest = { replyMenu = false },
                    containerColor = AppColors.Cell,
                ) {
                    DropdownMenuItem(
                        text = { Text("Antworten", fontSize = 17.sp) },
                        onClick = {
                            replyMenu = false
                            onReply()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Weiterleiten", fontSize = 17.sp) },
                        onClick = {
                            replyMenu = false
                            onForward()
                        },
                    )
                }
            }
            ToolbarIcon(R.drawable.ic_compose, "Neue E-Mail", onClick = onCompose)
        }
    }
}

@Composable
private fun ToolbarIcon(
    @DrawableRes icon: Int,
    description: String,
    tint: Color = AppColors.Blue,
    onClick: () -> Unit,
) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(26.dp)
            .clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ThreadScreenPreview() {
    SlimTheme {
        ThreadScreen(
            message = sampleMessages[2],
            thread = sampleThread,
            onBack = {},
            onPrevious = {},
            onNext = null,
            onToggleFlag = {},
            onArchive = {},
            onReply = {},
            onForward = {},
            onCompose = {},
            loadAttachment = { null },
            imagePolicy = RemoteImagePolicy(isTrusted = { false }, setTrusted = { _, _ -> }),
        )
    }
}
