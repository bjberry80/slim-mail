package slim.mail.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import slim.mail.R

// Feste Reihenfolge der Systemordner
private val systemRoles = listOf(
    FolderRole.Inbox, FolderRole.Flagged, FolderRole.Drafts, FolderRole.Sent,
    FolderRole.Junk, FolderRole.Trash, FolderRole.Archive,
)

@DrawableRes
private fun iconFor(role: FolderRole) = when (role) {
    FolderRole.Inbox -> R.drawable.ic_mailbox_inbox
    FolderRole.Flagged -> R.drawable.ic_mailbox_flagged
    FolderRole.Drafts -> R.drawable.ic_mailbox_drafts
    FolderRole.Sent -> R.drawable.ic_mailbox_sent
    FolderRole.Junk -> R.drawable.ic_mailbox_junk
    FolderRole.Trash -> R.drawable.ic_mailbox_trash
    FolderRole.Archive -> R.drawable.ic_mailbox_archive
    FolderRole.Other -> R.drawable.ic_folder
}

@Composable
fun MailboxesScreen(
    accountName: String,
    senderName: String,
    folders: List<MailFolder>,
    statusText: String,
    onOpenFolder: (MailFolder) -> Unit,
    onCompose: () -> Unit,
    onRenameSender: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var showAccount by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val system = systemRoles.flatMap { role -> folders.filter { it.role == role } }
    val others = folders.filter { it.role == FolderRole.Other }
    val inbox = folders.firstOrNull { it.role == FolderRole.Inbox }
    // „Markiert“ ist kein echter IMAP-Ordner und öffnet sich noch nicht
    val onClick = { folder: MailFolder -> if (folder.role != FolderRole.Flagged) onOpenFolder(folder) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.GroupedBackground)
            .statusBarsPadding(),
    ) {
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    "Bearbeiten",
                    color = AppColors.Blue,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { showAccount = true },
                    textAlign = TextAlign.End,
                )
                Text(
                    "Postfächer",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }
            if (inbox != null) {
                item { MailboxGroup(listOf(inbox.copy(name = "Alle Eingänge")), onClick) }
            }
            item { SectionHeader(accountName.substringAfter('@')) }
            item {
                if (system.isEmpty()) {
                    Text(
                        "Ordner werden geladen …",
                        fontSize = 15.sp,
                        color = AppColors.SecondaryLabel,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else {
                    MailboxGroup(system, onClick)
                }
            }
            if (others.isNotEmpty()) {
                item { SectionHeader("Ordner") }
                item { MailboxGroup(others, onClick) }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
        BottomBar(statusText, onCompose)
    }

    if (showAccount) {
        AccountDialog(
            email = accountName,
            senderName = senderName,
            onSave = {
                onRenameSender(it)
                showAccount = false
            },
            onSignOut = {
                showAccount = false
                confirmSignOut = true
            },
            onDismiss = { showAccount = false },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Abmelden?") },
            text = { Text("$accountName wird von diesem Gerät entfernt. Das Passwort wird gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Abmelden") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Abbrechen") }
            },
        )
    }
}

/** Kontoeinstellungen: Absendername ändern oder abmelden. */
@Composable
private fun AccountDialog(
    email: String,
    senderName: String,
    onSave: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(senderName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto") },
        text = {
            Column {
                Text(email, fontSize = 15.sp, color = AppColors.SecondaryLabel)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Absendername") },
                    placeholder = { Text("Vor- und Nachname") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
                Text(
                    "Erscheint bei Empfängern als Absender deiner Mails.",
                    fontSize = 13.sp,
                    color = AppColors.SecondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = onSignOut,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Abmelden …", color = AppColors.SwipeDelete)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun MailboxGroup(mailboxes: List<MailFolder>, onClick: (MailFolder) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Cell),
    ) {
        mailboxes.forEachIndexed { index, mailbox ->
            MailboxRow(mailbox, onClick = { onClick(mailbox) })
            if (index < mailboxes.lastIndex) {
                // Trennlinie beginnt erst hinter dem Icon-Bereich
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    thickness = 0.5.dp,
                    color = AppColors.Separator,
                )
            }
        }
    }
}

@Composable
private fun MailboxRow(mailbox: MailFolder, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 16 + 24 + 12 = 52 dp, bündig mit dem Einzug der Trennlinie
        Icon(
            painterResource(iconFor(mailbox.role)),
            contentDescription = null,
            tint = AppColors.Blue,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(mailbox.name, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            mailbox.count?.let {
                Text("$it", fontSize = 17.sp, color = AppColors.SecondaryLabel)
            }
            if (mailbox.role != FolderRole.Flagged) {
                Box(Modifier.padding(start = 8.dp)) {
                    Text("›", fontSize = 20.sp, color = AppColors.SecondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(statusText: String, onCompose: () -> Unit) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(24.dp))
            Text(
                statusText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
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
private fun MailboxesScreenPreview() {
    SlimTheme {
        MailboxesScreen(
            accountName = "name@mailbox.org",
            senderName = "Vorname Nachname",
            folders = sampleFolders,
            statusText = "Gerade aktualisiert",
            onOpenFolder = {},
            onCompose = {},
            onRenameSender = {},
            onSignOut = {},
        )
    }
}
