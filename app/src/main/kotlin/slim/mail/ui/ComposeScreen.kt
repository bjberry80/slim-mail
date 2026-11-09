package slim.mail.ui

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import android.net.Uri
import java.io.File
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextOverflow
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slim.mail.R
import slim.mail.data.OutgoingAttachment
import slim.mail.data.OutgoingMail

private object ComposeColors {
    val Backdrop = Color(0xFF3A3A3F)
    val SheetBehind = Color(0xFF8E8E93)
    val FieldDivider = Color(0xFFD6D6DA)
    val Chip = Color(0xFFE8F0FB)
    val ToolbarIcon = Color(0xFF3A3A3F)
    val QuoteBar = Color(0xFFC6C6C8)
    val Error = Color(0xFFD70015)
}

private const val Signature = "Von meinem Android gesendet"

// Grenze vor dem Senden; mailbox.org nimmt mehr an, viele Empfänger-Server nicht
private const val MaxAttachmentBytes = 25L * 1024 * 1024

private fun isValidAddress(address: String) =
    runCatching { InternetAddress(address, true) }.isSuccess && '@' in address

/** Text, Signatur und Zitat so, wie sie im Sheet stehen, als reiner Text. */
private fun composeText(body: String, draft: Draft): String = buildString {
    append(body.trimEnd())
    append("\n\n")
    append(Signature)
    draft.quote?.let { quote ->
        append("\n\n")
        draft.quoteHeader?.let { append(it).append("\n\n") }
        if (draft.forwarded) {
            // Weitergeleitet wird der Text unverändert
            append(quote)
        } else {
            // Zitat wie üblich mit „> “ vor jeder Zeile
            append(quote.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" })
        }
    }
}

/**
 * Screen 4 des Entwurfs: Sheet über abgedunkeltem Hintergrund, darüber der
 * Rand des darunterliegenden Screens als Andeutung eines Kartenstapels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    draft: Draft,
    from: String,
    fromName: String,
    onCancel: () -> Unit,
    // Liefert null bei Erfolg, sonst eine Fehlermeldung
    onSend: suspend (OutgoingMail) -> String?,
    // Lädt einen Anhang der Originalmail, für Weiterleitungen; null bei Fehler
    loadAttachment: suspend (AttachmentRef) -> File?,
) {
    val context = LocalContext.current
    val recipients = remember { mutableStateListOf(*draft.to.toTypedArray()) }
    var newRecipient by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf(draft.subject) }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val attachments = remember { mutableStateListOf<PickedAttachment>() }

    // Anhänge der weitergeleiteten Mail kommen erst vom Server
    var forwardedLoading by remember { mutableIntStateOf(draft.attachments.size) }
    LaunchedEffect(Unit) {
        draft.attachments.forEach { ref ->
            val file = loadAttachment(ref)
            if (file != null) {
                attachments.add(PickedAttachment(Uri.fromFile(file), ref.name, file.length(), ref.mimeType))
            } else {
                sendError = "Anhang nicht geladen: ${ref.name}"
            }
            forwardedLoading--
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachments.addAll(uris.map { pickedAttachment(context, it) })
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        attachments.addAll(uris.map { pickedAttachment(context, it) })
    }

    val recipientFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Bei einer Antwort steht der Empfänger schon fest, also gleich in den Text
        if (recipients.isEmpty()) recipientFocus.requestFocus() else bodyFocus.requestFocus()
    }

    fun commitRecipient() {
        val address = newRecipient.trim().trimEnd(',', ';').trim()
        if (address.isNotEmpty()) recipients.add(Recipient(address, address))
        newRecipient = ""
    }

    fun send() {
        commitRecipient()
        val invalid = recipients.filterNot { isValidAddress(it.address) }
        if (invalid.isNotEmpty()) {
            sendError = "Ungültige Adresse: " + invalid.joinToString { it.address }
            return
        }
        val totalSize = attachments.sumOf { it.size.coerceAtLeast(0) }
        if (totalSize > MaxAttachmentBytes) {
            sendError = "Anhänge zu groß (${formatSize(totalSize)}), höchstens ${formatSize(MaxAttachmentBytes)}"
            return
        }
        sending = true
        sendError = null
        scope.launch {
            // Erst jetzt die Dateien lesen, die Berechtigung dafür gilt solange das Sheet offen ist
            val files = try {
                withContext(Dispatchers.IO) {
                    attachments.map { picked ->
                        val data = context.contentResolver.openInputStream(picked.uri)?.use { it.readBytes() }
                            ?: throw java.io.IOException(picked.name)
                        OutgoingAttachment(picked.name, picked.mimeType, data)
                    }
                }
            } catch (e: java.io.IOException) {
                sending = false
                sendError = "Anhang nicht lesbar: ${e.message}"
                return@launch
            }
            val mail = OutgoingMail(
                fromName = fromName,
                to = recipients.map { it.address },
                subject = subject.trim(),
                text = composeText(body, draft),
                inReplyTo = draft.inReplyTo,
                references = draft.references,
                attachments = files,
            )
            val error = onSend(mail)
            sending = false
            if (error == null) {
                Toast.makeText(context, "Gesendet", Toast.LENGTH_SHORT).show()
                onCancel()
            } else {
                sendError = error
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColors.Backdrop)
            .statusBarsPadding(),
    ) {
        Box(
            Modifier
                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(ComposeColors.SheetBehind),
        )
        Column(
            Modifier
                .padding(top = 20.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(AppColors.Cell)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Abbrechen",
                        color = AppColors.Blue,
                        fontSize = 17.sp,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                    Spacer(Modifier.weight(1f))
                    SendButton(
                        enabled = !sending && forwardedLoading == 0 &&
                            (recipients.isNotEmpty() || newRecipient.isNotBlank()),
                        sending = sending,
                        onClick = ::send,
                    )
                }
                sendError?.let {
                    Text(
                        it,
                        fontSize = 15.sp,
                        color = ComposeColors.Error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Text(
                    subject.ifBlank { "Neue E-Mail" },
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )

                HeaderField("An:") {
                    FlowRow(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        recipients.forEach { recipient ->
                            RecipientChip(recipient.name, onRemove = { recipients.remove(recipient) })
                        }
                        PlainField(
                            value = newRecipient,
                            onValueChange = {
                                // Komma oder Semikolon schließt eine Adresse ab
                                if (it.endsWith(",") || it.endsWith(";")) {
                                    newRecipient = it
                                    commitRecipient()
                                } else {
                                    newRecipient = it
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onNext = { commitRecipient() }),
                            modifier = Modifier
                                .widthIn(min = 80.dp)
                                .padding(vertical = 4.dp)
                                .focusRequester(recipientFocus),
                        )
                    }
                    Icon(
                        painterResource(R.drawable.ic_plus_circle),
                        contentDescription = "Empfänger hinzufügen",
                        tint = AppColors.Blue,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { recipientFocus.requestFocus() },
                    )
                }
                HeaderField("Kopie/Blindkopie, Von:") {
                    Text(from, fontSize = 16.sp, maxLines = 1)
                }
                HeaderField("Betreff:") {
                    PlainField(
                        value = subject,
                        onValueChange = { subject = it },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                        modifier = Modifier.weight(1f),
                    )
                }

                PlainField(
                    value = body,
                    onValueChange = { body = it },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .focusRequester(bodyFocus),
                )
                attachments.forEach { picked ->
                    PickedAttachmentRow(picked, onRemove = { attachments.remove(picked) })
                }
                if (forwardedLoading > 0) {
                    Text(
                        if (forwardedLoading == 1) "Anhang wird geladen …" else "$forwardedLoading Anhänge werden geladen …",
                        fontSize = 15.sp,
                        color = AppColors.SecondaryLabel,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Text(
                    Signature,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    color = AppColors.SecondaryLabel,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (draft.quote != null) {
                    Quote(draft.quoteHeader, draft.quote)
                }
                Spacer(Modifier.height(24.dp))
            }
            ComposeToolbar(
                onAttachFile = { pickFiles.launch(arrayOf("*/*")) },
                onAttachPhoto = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
            )
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled || sending) AppColors.Blue else AppColors.Separator)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (sending) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_arrow_up),
                contentDescription = "Senden",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun HeaderField(label: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 16.sp, color = AppColors.SecondaryLabel)
            Spacer(Modifier.width(6.dp))
            content()
        }
        HorizontalDivider(thickness = 0.5.dp, color = ComposeColors.FieldDivider)
    }
}

@Composable
private fun RecipientChip(name: String, onRemove: () -> Unit) {
    Text(
        name,
        color = AppColors.Blue,
        fontSize = 15.sp,
        modifier = Modifier
            .padding(end = 6.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ComposeColors.Chip)
            // Antippen entfernt den Empfänger wieder, bis es ein Kontextmenü gibt
            .clickable(onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = Color.Black),
        cursorBrush = SolidColor(AppColors.Blue),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
    )
}

@Composable
private fun Quote(header: String?, text: String) {
    Column(
        Modifier
            .padding(top = 20.dp)
            .drawBehind {
                drawLine(
                    ComposeColors.QuoteBar,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 12.dp),
    ) {
        header?.let {
            Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.SecondaryLabel)
        }
        Text(
            text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = AppColors.SecondaryLabel,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ComposeToolbar(onAttachFile: () -> Unit, onAttachPhoto: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.Toolbar)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = AppColors.Separator)
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Aa",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColors.ToolbarIcon,
                modifier = Modifier.padding(end = 24.dp),
            )
            ToolbarIcon(R.drawable.ic_paperclip, "Datei anhängen", onAttachFile)
            ToolbarIcon(R.drawable.ic_photo, "Foto einfügen", onAttachPhoto)
            // Kamera und Formatierung (Aa) folgen später
            ToolbarIcon(R.drawable.ic_camera, "Kamera", onClick = null)
        }
    }
}

@Composable
private fun ToolbarIcon(@DrawableRes icon: Int, description: String, onClick: (() -> Unit)?) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = if (onClick != null) ComposeColors.ToolbarIcon else AppColors.Separator,
        modifier = Modifier
            .padding(end = 24.dp)
            .size(24.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

@Composable
private fun PickedAttachmentRow(picked: PickedAttachment, onRemove: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Toolbar)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_paperclip),
            contentDescription = null,
            tint = AppColors.SecondaryLabel,
            modifier = Modifier.size(18.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(picked.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            formatSize(picked.size).takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 13.sp, color = AppColors.SecondaryLabel)
            }
        }
        Icon(
            painterResource(R.drawable.ic_clear),
            contentDescription = "Anhang entfernen",
            tint = AppColors.SecondaryLabel,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Preview
@Composable
private fun ComposeScreenPreview() {
    SlimTheme {
        ComposeScreen(
            draft = Draft(
                to = listOf(Recipient("Thomas Klein", "thomas@example.org")),
                subject = "Re: Angebot Werkstatt",
                quoteHeader = "Am 17.10.2026 um 17:20 schrieb Thomas Klein:",
                quote = "danke für die schnelle Rückmeldung.",
            ),
            from = "name@mailbox.org",
            fromName = "Vorname Nachname",
            loadAttachment = { null },
            onCancel = {},
            onSend = { null },
        )
    }
}
