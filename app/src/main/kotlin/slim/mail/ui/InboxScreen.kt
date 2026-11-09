package slim.mail.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import slim.mail.R
import kotlin.math.roundToInt

private val SwipeActionWidth = 74.dp

// Text und Trennlinien beginnen hier, links davon sitzt der Ungelesen-Punkt
private val RowInset = 28.dp

/** Mailliste eines Ordners (Screen 2 des Entwurfs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    title: String,
    messages: List<Message>,
    // Spinner beim Herunterziehen
    loading: Boolean,
    // Abgleich im Hintergrund; solange keine leere Liste als „Keine E-Mails“ zeigen
    syncing: Boolean,
    statusText: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMessage: (Message) -> Unit,
    onToggleFlag: (Message) -> Unit,
    onArchive: ((Message) -> Unit)?,
    onDelete: ((Message) -> Unit)?,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
) {
    // Es ist immer höchstens eine Zeile aufgeklappt
    var openId by remember { mutableStateOf<Long?>(null) }
    var openSide by remember { mutableStateOf<SwipeSide?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Cell)
            .statusBarsPadding(),
    ) {
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { InboxHeader(title, onBack, onSearch) }
                if (messages.isEmpty() && !loading && !syncing) {
                    item {
                        Text(
                            "Keine E-Mails",
                            fontSize = 17.sp,
                            color = AppColors.SecondaryLabel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    SwipeableRow(
                        openSide = openSide.takeIf { openId == message.id },
                        onOpenChange = { side ->
                            openId = if (side != null) message.id else null
                            openSide = side
                        },
                        // Im Papierkorb selbst gibt es (noch) kein Löschen
                        leading = onDelete?.let { delete ->
                            LeadingAction("Löschen", R.drawable.ic_mailbox_trash, AppColors.SwipeDelete) {
                                openId = null
                                delete(message)
                            }
                        },
                        actions = {
                            SwipeAction("Mehr", R.drawable.ic_more, AppColors.SwipeMore) {
                                openId = null
                            }
                            SwipeAction("Markieren", R.drawable.ic_mailbox_flagged, AppColors.SwipeFlag) {
                                onToggleFlag(message)
                                openId = null
                            }
                            // Ohne Archivordner (oder im Archiv selbst) entfällt die Aktion
                            if (onArchive != null) {
                                SwipeAction("Archivieren", R.drawable.ic_mailbox_archive, AppColors.SwipeArchive) {
                                    onArchive(message)
                                    openId = null
                                }
                            }
                        },
                        actionCount = if (onArchive != null) 3 else 2,
                    ) {
                        MessageRow(message, onClick = {
                            // Ein Tipp auf eine aufgeklappte Zeile klappt sie nur zu
                            if (openId == message.id) openId = null else onOpenMessage(message)
                        })
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = RowInset),
                        thickness = 0.5.dp,
                        color = AppColors.Separator,
                    )
                }
            }
        }
        InboxToolbar(statusText, unreadCount = messages.count { it.unread }, onCompose = onCompose)
    }
}

@Composable
private fun InboxHeader(title: String, onBack: () -> Unit, onSearch: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
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
                Text("Postfächer", color = AppColors.Blue, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("Bearbeiten", color = AppColors.Blue, fontSize = 17.sp)
        }
        Text(
            title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        SearchField(onClick = onSearch)
    }
}

/** Nur die Attrappe des Suchfelds; getippt wird im Such-Screen. */
@Composable
private fun SearchField(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.SearchField)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = AppColors.SecondaryLabel,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Suchen",
            color = AppColors.SecondaryLabel,
            fontSize = 17.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
        )
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = "Diktieren",
            tint = AppColors.SecondaryLabel,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MessageRow(message: Message, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Cell)
            .clickable(onClick = onClick)
            .padding(start = RowInset, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (message.unread) {
            Box(
                Modifier
                    .offset(x = -(RowInset + 10.dp) / 2, y = 6.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AppColors.Blue),
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.sender,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.flagged) {
                    SmallIcon(R.drawable.ic_mailbox_flagged, AppColors.SwipeFlag)
                }
                if (message.hasAttachment) {
                    SmallIcon(R.drawable.ic_paperclip, AppColors.SecondaryLabel)
                }
                Text(message.time, fontSize = 15.sp, color = AppColors.SecondaryLabel)
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = AppColors.SecondaryLabel,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.subject,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.threadCount > 1) {
                    ThreadBadge(message.threadCount)
                }
            }
            Text(
                message.preview,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = AppColors.SecondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SmallIcon(@DrawableRes icon: Int, tint: Color) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .padding(end = 6.dp)
            .size(15.dp),
    )
}

@Composable
private fun ThreadBadge(count: Int) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(20.dp)
            .border(1.dp, AppColors.SecondaryLabel, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", fontSize = 13.sp, color = AppColors.SecondaryLabel)
    }
}

// Offen halten lassen sich nur die Aktionen rechts; nach rechts springt die Zeile zurück
enum class SwipeSide { Trailing }

/** Eine Aktion links, die beim Wischen nach rechts erscheint (z. B. Löschen). */
private class LeadingAction(
    val label: String,
    @DrawableRes val icon: Int,
    val color: Color,
    val onTrigger: () -> Unit,
)

/**
 * Zeile, die sich nach links (Aktionen rechts) oder nach rechts (eine Aktion
 * links) aufziehen lässt. Links lässt sie sich offen halten, nach rechts
 * springt sie beim Loslassen zurück. Erst ein Wischen über
 * die halbe Breite nach rechts die linke Aktion aus.
 */
@Composable
private fun SwipeableRow(
    openSide: SwipeSide?,
    onOpenChange: (SwipeSide?) -> Unit,
    leading: LeadingAction?,
    actions: @Composable RowScope.() -> Unit,
    actionCount: Int,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val trailingOpen = with(density) { -(SwipeActionWidth * actionCount).toPx() }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    // Der Finger setzt die Position direkt (ohne Coroutine). Früher lief jeder
    // Schritt über snapTo in einer eigenen Coroutine; ein verspäteter Schritt
    // brach dann das Zurückspringen ab und die Zeile blieb halb offen stehen.
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun settleTo(target: Float, then: () -> Unit = {}) {
        settling?.cancel()
        settling = scope.launch {
            animate(offset, target) { value, _ -> offset = value }
            then()
        }
    }

    LaunchedEffect(openSide) {
        settleTo(if (openSide == SwipeSide.Trailing) trailingOpen else 0f)
    }

    // Zeile ganz hinausschieben, dann verschwindet sie aus der Liste
    fun trigger(action: LeadingAction) = settleTo(rowWidth, action.onTrigger)

    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { rowWidth = it.width.toFloat() },
    ) {
        if (leading != null && offset > 0f) {
            // Die Fläche wächst mit dem Finger, das Symbol bleibt an der Kante der Zeile
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { offset.toDp() })
                    .background(leading.color)
                    .clickable { trigger(leading) },
                contentAlignment = Alignment.CenterEnd,
            ) {
                SwipeActionLabel(leading.label, leading.icon, Modifier.width(SwipeActionWidth))
            }
        }
        if (offset < 0f) {
            Row(
                Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.End,
                content = actions,
            )
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val max = if (leading != null) rowWidth else 0f
                        offset = (offset + delta).coerceIn(trailingOpen, max)
                    },
                    onDragStarted = { settling?.cancel() },
                    onDragStopped = { velocity ->
                        val x = offset
                        if (leading != null && x > rowWidth / 2) {
                            trigger(leading)
                            return@draggable
                        }
                        val side = when {
                            x >= 0f -> null
                            velocity < -1500f -> SwipeSide.Trailing
                            velocity > 1500f -> null
                            else -> if (x < trailingOpen / 2) SwipeSide.Trailing else null
                        }
                        settleTo(if (side == SwipeSide.Trailing) trailingOpen else 0f)
                        onOpenChange(side)
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeAction(label: String, @DrawableRes icon: Int, color: Color, onClick: () -> Unit) {
    SwipeActionLabel(
        label,
        icon,
        Modifier
            .width(SwipeActionWidth)
            .fillMaxHeight()
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SwipeActionLabel(label: String, @DrawableRes icon: Int, modifier: Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun InboxToolbar(statusText: String, unreadCount: Int, onCompose: () -> Unit) {
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_filter),
                contentDescription = "Filtern",
                tint = AppColors.Blue,
                modifier = Modifier.size(24.dp),
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(statusText, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unreadCount > 0) {
                    Text(
                        "$unreadCount ungelesen",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = AppColors.SecondaryLabel,
                    )
                }
            }
            Icon(
                painterResource(R.drawable.ic_compose),
                contentDescription = "Neue E-Mail",
                tint = AppColors.Blue,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onCompose),
            )
        }
    }
}

@Preview
@Composable
private fun InboxScreenPreview() {
    SlimTheme {
        InboxScreen(
            title = "Eingang",
            messages = sampleMessages,
            loading = false,
            syncing = false,
            statusText = "Gerade aktualisiert",
            onBack = {},
            onRefresh = {},
            onOpenMessage = {},
            onToggleFlag = {},
            onArchive = {},
            onDelete = {},
            onCompose = {},
            onSearch = {},
        )
    }
}
