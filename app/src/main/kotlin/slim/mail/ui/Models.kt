package slim.mail.ui

/** Eine Zeile in der Mailliste; bei Threads die neueste Nachricht. */
data class Message(
    // IMAP-UID der neuesten Nachricht, eindeutig innerhalb des Ordners
    val id: Long,
    val sender: String,
    val time: String,
    val subject: String,
    val preview: String,
    val unread: Boolean = false,
    val threadCount: Int = 1,
    val hasAttachment: Boolean = false,
    val flagged: Boolean = false,
    // Alle UIDs des Threads, älteste zuerst
    val threadUids: List<Long> = listOf(id),
)

/** Eine einzelne Nachricht innerhalb eines Threads. */
data class ThreadMessage(
    val sender: String,
    val initials: String,
    // Kurz für zugeklappte Nachrichten, z. B. „14.10.“
    val date: String,
    // Reiner Text: für Zitat, zugeklappte Vorschau und Mails ohne HTML
    val body: String,
    val attachments: List<AttachmentRef> = emptyList(),
    // Original-HTML, wird aufgeklappt in einer abgesicherten WebView gezeigt
    val html: String? = null,
    // Ausgeschrieben für die aufgeklappte Nachricht, z. B. „Gestern, 17:20“
    val longDate: String? = null,
    // Für die Zitatzeile beim Antworten, z. B. „17.10.2026 um 17:20“
    val quoteDate: String? = null,
    // Für Antworten: Adresse, IMAP-UID und die Header, über die andere
    // Programme die Antwort dem Thread zuordnen
    val senderAddress: String = "",
    val uid: Long? = null,
    val messageId: String? = null,
    val references: String? = null,
)

/** Ein Anhang einer empfangenen Mail; [uid] und [path] finden ihn auf dem Server wieder. */
data class AttachmentRef(
    val uid: Long,
    val path: List<Int>,
    val name: String,
    val mimeType: String,
    val size: Int,
)

/** „234 KB“, „1,2 MB“; leer, wenn die Größe unbekannt ist. */
fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.GERMAN, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Ein Empfänger: [name] für den Chip, [address] für den Versand. */
data class Recipient(val name: String, val address: String)

enum class FolderRole { Inbox, Flagged, Drafts, Sent, Junk, Trash, Archive, Other }

data class MailFolder(
    // Vollständiger IMAP-Name, z. B. „INBOX/Projekte“
    val fullName: String,
    val name: String,
    val role: FolderRole,
    val count: Int? = null,
)

/** Inhalt, mit dem der Verfassen-Screen startet. */
data class Draft(
    val to: List<Recipient> = emptyList(),
    val subject: String = "",
    val quoteHeader: String? = null,
    val quote: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    // UID der beantworteten Mail im geöffneten Ordner, bekommt danach \Answered
    val replyToUid: Long? = null,
    // Weiterleiten: Text ohne „> “, die Anhänge der Originalmail kommen mit
    val forwarded: Boolean = false,
    val attachments: List<AttachmentRef> = emptyList(),
)

/** Ein Suchtreffer; [messageId] ist der Thread, der beim Antippen aufgeht. */
data class SearchItem(
    val messageId: Long,
    val sender: String,
    val date: String,
    val subject: String,
    val preview: String,
    val hasAttachment: Boolean,
    val inInbox: Boolean,
)

fun searchItems(inbox: List<Message>): List<SearchItem> =
    inbox.map {
        SearchItem(it.id, it.sender, it.time, it.subject, it.preview, it.hasAttachment, inInbox = true)
    }

fun replyDraft(message: Message, quoted: ThreadMessage): Draft = Draft(
    to = listOf(Recipient(quoted.sender, quoted.senderAddress)).filter { it.address.isNotBlank() },
    subject = if (message.subject.startsWith("Re:", ignoreCase = true)) message.subject else "Re: ${message.subject}",
    quoteHeader = quoted.quoteDate
        ?.let { "Am $it schrieb ${quoted.sender}:" }
        ?: "${quoted.sender} schrieb:",
    quote = quoted.body,
    inReplyTo = quoted.messageId,
    // RFC 5322: bisherige References plus die Message-ID der beantworteten Mail
    references = listOfNotNull(quoted.references, quoted.messageId).joinToString(" ").ifBlank { null },
    replyToUid = quoted.uid,
)

private val forwardPrefix = Regex("""^\s*(wg|fwd?)\s*:""", RegexOption.IGNORE_CASE)

fun forwardDraft(message: Message, quoted: ThreadMessage): Draft = Draft(
    subject = if (forwardPrefix.containsMatchIn(message.subject)) message.subject else "WG: ${message.subject}",
    // Kopfzeilen der Originalmail über dem Text
    quoteHeader = buildString {
        append("Anfang der weitergeleiteten Nachricht:\n\n")
        append("Von: ").append(quoted.sender)
        if (quoted.senderAddress.isNotBlank() && quoted.senderAddress != quoted.sender) {
            append(" <").append(quoted.senderAddress).append(">")
        }
        quoted.quoteDate?.let { append("\nDatum: ").append(it) }
        append("\nBetreff: ").append(message.subject)
    },
    quote = quoted.body,
    forwarded = true,
    attachments = quoted.attachments,
)

fun initialsOf(name: String): String =
    name.split(" ", ".", "@").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
