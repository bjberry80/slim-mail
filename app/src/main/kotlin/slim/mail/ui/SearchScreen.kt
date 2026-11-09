package slim.mail.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slim.mail.R

private object SearchColors {
    val Divider = Color(0xFFE2E2E6)
    val SuggestionIcon = Color(0xFFE8F0FB)
    val Highlight = Color(0xFFFFE58A)
}

/**
 * Suchbegriff und Filter. Liegt außerhalb des Screens, damit die Suche beim
 * Öffnen eines Treffers und Zurückkehren erhalten bleibt.
 */
@Stable
class SearchState {
    var query by mutableStateOf("")
    var inboxOnly by mutableStateOf(false)
    var subjectOnly by mutableStateOf(false)
    var person by mutableStateOf<String?>(null)
    var withAttachments by mutableStateOf(false)

    fun reset() {
        query = ""
        inboxOnly = false
        subjectOnly = false
        person = null
        withAttachments = false
    }

    fun matches(item: SearchItem): Boolean {
        person?.let { if (item.sender != it) return false }
        return matchesIgnoringPerson(item)
    }

    fun matchesIgnoringPerson(item: SearchItem): Boolean {
        if (inboxOnly && !item.inInbox) return false
        if (withAttachments && !item.hasAttachment) return false
        val q = query.trim()
        if (q.isEmpty()) return person != null || withAttachments
        return if (subjectOnly) {
            item.subject.contains(q, ignoreCase = true)
        } else {
            item.subject.contains(q, ignoreCase = true) ||
                item.sender.contains(q, ignoreCase = true) ||
                item.preview.contains(q, ignoreCase = true)
        }
    }
}

/** Screen 5 des Entwurfs. */
@Composable
fun SearchScreen(
    state: SearchState,
    items: List<SearchItem>,
    onCancel: () -> Unit,
    onOpen: (SearchItem) -> Unit,
) {
    val q = state.query.trim()
    val hits = items.filter(state::matches)
    // Absender der Treffer, ohne den Personenfilter selbst, damit man zwischen
    // Personen wechseln kann
    val people = if (q.isEmpty()) emptyList()
    else items
        .filter { state.matchesIgnoringPerson(it) }
        .map { it.sender }
        .distinct()
        .take(3)

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Cell)
            .statusBarsPadding()
            .imePadding(),
    ) {
        SearchBar(state, onCancel)
        ScopePicker(state.inboxOnly, onChange = { state.inboxOnly = it })

        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            item { SectionTitle("Vorschläge") }
            if (q.isNotEmpty()) {
                item {
                    Suggestion(
                        icon = R.drawable.ic_text_lines,
                        title = "Betreff enthält „$q“",
                        subtitle = "Nur im Betreff suchen",
                        active = state.subjectOnly,
                        onClick = { state.subjectOnly = !state.subjectOnly },
                    )
                }
            }
            items(people) { name ->
                Suggestion(
                    icon = R.drawable.ic_person,
                    title = name,
                    subtitle = "Personen",
                    active = state.person == name,
                    onClick = { state.person = if (state.person == name) null else name },
                )
            }
            item {
                Suggestion(
                    icon = R.drawable.ic_paperclip,
                    title = "Mit Anhängen",
                    subtitle = "Filter",
                    active = state.withAttachments,
                    onClick = { state.withAttachments = !state.withAttachments },
                )
            }

            if (q.isNotEmpty() || hits.isNotEmpty()) {
                item { SectionTitle("Top-Treffer") }
                if (hits.isEmpty()) {
                    item {
                        Text(
                            "Keine Treffer",
                            fontSize = 15.sp,
                            color = AppColors.SecondaryLabel,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(hits) { hit -> SearchHit(hit, q, onClick = { onOpen(hit) }) }
            }
        }
    }
}

@Composable
private fun SearchBar(state: SearchState, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Nur beim ersten Öffnen die Tastatur zeigen, nicht nach der Rückkehr aus einem Treffer
    var autoFocused by remember { mutableStateOf(state.query.isNotEmpty()) }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            focus.requestFocus()
            autoFocused = true
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.SearchField)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = AppColors.SecondaryLabel,
                modifier = Modifier.size(18.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            ) {
                if (state.query.isEmpty()) {
                    Text("Suchen", fontSize = 17.sp, color = AppColors.SecondaryLabel)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
                    cursorBrush = SolidColor(AppColors.Blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
            if (state.query.isNotEmpty()) {
                Icon(
                    painterResource(R.drawable.ic_clear),
                    contentDescription = "Suchbegriff löschen",
                    tint = AppColors.SecondaryLabel,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { state.query = "" },
                )
            }
        }
        Text(
            "Abbrechen",
            color = AppColors.Blue,
            fontSize = 17.sp,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp),
        )
    }
}

/** Segmentierte Auswahl: weißes Feld mit Schatten auf grauem Grund. */
@Composable
private fun ScopePicker(inboxOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(AppColors.SearchField)
            .padding(2.dp),
    ) {
        Segment("Alle Postfächer", selected = !inboxOnly, onClick = { onChange(false) })
        Segment("Eingang", selected = inboxOnly, onClick = { onChange(true) })
    }
}

@Composable
private fun RowScope.Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .weight(1f)
            .then(
                if (selected) Modifier
                    .shadow(1.dp, shape)
                    .background(AppColors.Cell, shape)
                else Modifier,
            )
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Suggestion(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(SearchColors.SuggestionIcon),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = AppColors.Blue,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(title, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = AppColors.SecondaryLabel)
            }
            if (active) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = "Aktiv",
                    tint = AppColors.Blue,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = SearchColors.Divider)
    }
}

@Composable
private fun SearchHit(hit: SearchItem, query: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.sender,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(hit.date, fontSize = 14.sp, color = AppColors.SecondaryLabel)
            }
            Text(
                highlight(hit.subject, query),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                snippet(hit.preview, query),
                fontSize = 14.sp,
                color = AppColors.SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = SearchColors.Divider)
    }
}

/** Markiert alle Vorkommen von [query] gelb, ohne Groß-/Kleinschreibung zu beachten. */
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isEmpty()) return@buildAnnotatedString
    var start = text.indexOf(query, ignoreCase = true)
    while (start >= 0) {
        addStyle(SpanStyle(background = SearchColors.Highlight), start, start + query.length)
        start = text.indexOf(query, start + query.length, ignoreCase = true)
    }
}

/**
 * Beginnt die Vorschau mit dem Satz, der den Suchbegriff enthält, damit der
 * Treffer in der einen Zeile sichtbar ist.
 */
private fun snippet(text: String, query: String): String {
    if (query.isEmpty()) return text
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return text
    val sentenceStart = text.lastIndexOf(". ", index)
    return if (sentenceStart < 0) text else text.substring(sentenceStart + 2)
}

@Preview
@Composable
private fun SearchScreenPreview() {
    SlimTheme {
        SearchScreen(
            state = remember { SearchState().apply { query = "Angebot" } },
            items = searchItems(sampleMessages),
            onCancel = {},
            onOpen = {},
        )
    }
}
