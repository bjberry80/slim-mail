package slim.mail.data

import android.text.Html
import android.util.Base64
import jakarta.activation.DataHandler
import jakarta.mail.FetchProfile
import jakarta.mail.MessagingException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import slim.mail.ui.FolderRole
import slim.mail.ui.MailFolder
import java.io.File
import java.util.Date
import java.util.Properties

/** Kopfdaten einer Mail, wie sie die Liste braucht. */
data class MailHeader(
    val uid: Long,
    val sender: String,
    val date: Date,
    val subject: String,
    val preview: String,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

/** Vollständige Mail für die Thread-Ansicht. */
data class MailBody(
    val uid: Long,
    val sender: String,
    val senderAddress: String,
    val date: Date,
    val text: String,
    // Original-HTML, wenn die Mail eins hat; [text] bleibt für Zitat und Vorschau
    val html: String?,
    val attachments: List<AttachmentInfo>,
    val messageId: String?,
    val references: String?,
)

/**
 * Ein Anhang innerhalb einer Mail. [path] sind die Indizes durch die
 * verschachtelten multipart-Teile bis zu diesem Teil; leer, wenn die ganze
 * Mail selbst der Anhang ist.
 */
data class AttachmentInfo(
    val name: String,
    val mimeType: String,
    // Größe wie vom Server gemeldet (kodiert), -1 wenn unbekannt
    val size: Int,
    val path: List<Int>,
)

/** Ein Anhang zum Mitschicken. */
class OutgoingAttachment(val name: String, val mimeType: String, val data: ByteArray)

/**
 * Was in einer einzeiligen Vorschau nur stört: Trennlinien aus =, -, _ oder *,
 * Bildbeschriftungen aus Text-Alternativen wie „<H24-Logo>:“ oder „[Logo]“
 * und nackte Links.
 */
private val previewNoise = Regex(
    "[=_*~#\\-]{3,}" +
        "|<[^<>\\s][^<>]{0,60}>:?" +
        "|\\[(?:image|bild|logo)[^\\]]{0,40}]" +
        "|https?://\\S+",
    RegexOption.IGNORE_CASE,
)

/** Eine zu sendende Mail, als reiner Text, optional mit Anhängen. */
data class OutgoingMail(
    // Anzeigename des Absenders; die Adresse kommt aus dem Konto
    val fromName: String,
    val to: List<String>,
    val subject: String,
    val text: String,
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
)

// Eingebettete Bilder bis zu dieser Größe direkt ins HTML übernehmen
private const val MaxInlineImageBytes = 3 * 1024 * 1024

/**
 * Eine IMAP-Verbindung pro Konto. Alle Aufrufe laufen nacheinander (Mutex) auf
 * dem IO-Dispatcher, weil Jakarta Mail blockiert und ein Store nicht
 * threadsicher ist. Bricht die Verbindung ab, wird beim nächsten Aufruf neu
 * verbunden.
 */
class MailRepository(private val account: Account) {
    private val mutex = Mutex()
    private var store: Store? = null

    private val session: Session = mailSession()

    /**
     * Was Liste und Thread von jeder Mail brauchen. Bewusst ohne ENVELOPE:
     * Angus Mail verwirft dort die ganze Antwort, wenn eine Adresse nicht
     * streng gültig ist (z. B. „root@MISSING_DOMAIN“ von Dovecot bei Mails
     * ohne Absenderdomain). Die rohen Kopfzeilen werten wir selbst tolerant aus.
     */
    private val headerProfile = FetchProfile().apply {
        add("From")
        add("Subject")
        add(IMAPFolder.FetchProfileItem.INTERNALDATE)
        add(FetchProfile.Item.FLAGS)
        add(FetchProfile.Item.CONTENT_INFO)
        add(UIDFolder.FetchProfileItem.UID)
    }

    // Für die Thread-Ansicht zusätzlich, was eine Antwort braucht
    private val bodyProfile = FetchProfile().apply {
        headerProfile.items.forEach(::add)
        headerProfile.headerNames.forEach(::add)
        add("Message-ID")
        add("References")
    }

    private fun connectedStore(): Store {
        store?.takeIf { it.isConnected }?.let { return it }
        val newStore = session.getStore("imaps")
        newStore.connect(account.imapHost, account.servers.imapPort, account.email, account.password)
        store = newStore
        return newStore
    }

    private suspend fun <T> withStore(block: (Store) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(connectedStore()) }
    }

    private suspend fun <T> withFolder(fullName: String, mode: Int, block: (IMAPFolder) -> T): T =
        withStore { store ->
            val folder = store.getFolder(fullName) as IMAPFolder
            folder.open(mode)
            try {
                block(folder)
            } finally {
                folder.close(false)
            }
        }

    /** Prüft nur, ob die Anmeldung klappt. */
    suspend fun verify() {
        withStore { }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { store?.close() }
            store = null
        }
    }

    suspend fun folders(): List<MailFolder> = withStore { store ->
        val imapFolders = store.defaultFolder.list("*")
            .filterIsInstance<IMAPFolder>()
            .filter { it.type and Folder.HOLDS_MESSAGES != 0 }

        imapFolders.map { folder ->
            val role = roleOf(folder)
            // Je Ordner eine STATUS-Abfrage, also ein Hin und Her mit dem Server.
            // Bei großen Postfächern mit vielen Ordnern und schlechtem Netz (ICE)
            // summiert sich das, deshalb nur dort, wo Zahlen wirklich helfen.
            val count = when (role) {
                FolderRole.Inbox -> folder.unreadMessageCount.takeIf { it > 0 }
                FolderRole.Drafts -> folder.messageCount.takeIf { it > 0 }
                else -> null
            }
            MailFolder(folder.fullName, displayName(folder, role), role, count)
        }
    }

    /** Anzahl markierter Mails im Eingang, für die virtuelle Zeile „Markiert“. */
    suspend fun flaggedCount(): Int = withFolder("INBOX", Folder.READ_ONLY) { folder ->
        folder.search(FlagTerm(Flags(Flags.Flag.FLAGGED), true)).size
    }

    /**
     * Die neuesten [limit] Mails eines Ordners, neueste zuerst, nur aus den
     * Kopfzeilen: ein einziger FETCH, ohne einen Mail-Text zu laden. Damit
     * steht die Liste auch bei großen Postfächern und schlechtem Netz schnell.
     * Der Ordner wird nur lesend geöffnet (EXAMINE).
     *
     * Vorschauen kommen aus [knownPreviews] (UID → Vorschau, aus dem Cache);
     * fehlende lädt [previews] danach im Hintergrund.
     */
    suspend fun headers(
        fullName: String,
        limit: Int = 50,
        knownPreviews: Map<Long, String> = emptyMap(),
    ): List<MailHeader> =
        withFolder(fullName, Folder.READ_ONLY) { folder ->
            val total = folder.messageCount
            if (total == 0) return@withFolder emptyList()
            val messages = folder.getMessages(maxOf(1, total - limit + 1), total)
            folder.fetch(messages, headerProfile)
            messages.reversed().map { message ->
                val uid = folder.getUID(message)
                MailHeader(
                    uid = uid,
                    sender = senderOf(message),
                    date = message.receivedDate ?: Date(0),
                    subject = subjectOf(message),
                    // Kein Mail-Text hier: Vorschauen lädt previews() danach stückweise
                    preview = knownPreviews[uid].orEmpty(),
                    seen = message.isSet(Flags.Flag.SEEN),
                    flagged = message.isSet(Flags.Flag.FLAGGED),
                    hasAttachment = attachmentsOf(message).isNotEmpty(),
                )
            }
                // Nach Eingangsdatum, nicht nach Reihenfolge im Ordner: Eine
                // zurückgeholte Mail landet dort am Ende, gehört aber an ihren Platz
                .sortedByDescending { it.date }
        }

    /** Volltexte der angegebenen Mails, in der Reihenfolge der UIDs. */
    suspend fun bodies(fullName: String, uids: List<Long>): List<MailBody> =
        withFolder(fullName, Folder.READ_ONLY) { folder ->
            val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            folder.fetch(messages, bodyProfile)
            messages.map { message ->
                MailBody(
                    uid = folder.getUID(message),
                    sender = senderOf(message),
                    senderAddress = fromAddressOf(message)?.address.orEmpty(),
                    date = message.receivedDate ?: Date(0),
                    text = textOf(message)?.trim().orEmpty(),
                    html = runCatching { htmlOf(message)?.let { embedInlineImages(it, message) } }.getOrNull(),
                    attachments = attachmentsOf(message),
                    messageId = message.getHeader("Message-ID")?.firstOrNull()?.trim(),
                    references = message.getHeader("References")?.firstOrNull()
                        ?.let(MimeUtility::unfold)?.trim(),
                )
            }
        }

    /**
     * Sendet per SMTP (SSL oder STARTTLS, je nach Konto) und gibt die gesendete Mail zurück,
     * damit sie danach in „Gesendet“ abgelegt werden kann. Eigene Verbindung
     * pro Versand, unabhängig von der IMAP-Verbindung.
     */
    suspend fun send(mail: OutgoingMail): MimeMessage = withContext(Dispatchers.IO) {
        val message = MimeMessage(session).apply {
            // Name als RFC-2047-Wort kodiert, damit Umlaute wie in „Björn“ ankommen
            setFrom(InternetAddress(account.email, mail.fromName.ifBlank { null }, "UTF-8"))
            setRecipients(Message.RecipientType.TO, mail.to.map(::InternetAddress).toTypedArray())
            setSubject(mail.subject, "UTF-8")
            if (mail.attachments.isEmpty()) {
                setText(mail.text, "UTF-8")
            } else {
                setContent(
                    MimeMultipart().apply {
                        addBodyPart(MimeBodyPart().apply { setText(mail.text, "UTF-8") })
                        mail.attachments.forEach { attachment ->
                            addBodyPart(
                                MimeBodyPart().apply {
                                    dataHandler = DataHandler(ByteArrayDataSource(attachment.data, attachment.mimeType))
                                    // Umlaute im Dateinamen kodiert Jakarta Mail nach RFC 2231
                                    fileName = attachment.name
                                    disposition = Part.ATTACHMENT
                                },
                            )
                        }
                    },
                )
            }
            sentDate = Date()
            mail.inReplyTo?.let { setHeader("In-Reply-To", it) }
            mail.references?.let { setHeader("References", MimeUtility.fold(12, it)) }
            saveChanges()
        }
        // STARTTLS: unverschlüsselt verbinden, dann auf TLS umschalten; ohne TLS bricht
        // mail.smtp.starttls.required ab, statt das Passwort im Klartext zu senden
        val transport = session.getTransport(if (account.servers.smtpStartTls) "smtp" else "smtps")
        transport.connect(account.smtpHost, account.servers.smtpPort, account.email, account.password)
        try {
            transport.sendMessage(message, message.allRecipients)
        } finally {
            transport.close()
        }
        message
    }

    /**
     * Lädt einen Anhang in [target]. Nur lesend geöffnet, damit das Laden die
     * Mail nicht als gelesen markiert.
     */
    suspend fun download(fullName: String, uid: Long, path: List<Int>, target: File) {
        withFolder(fullName, Folder.READ_ONLY) { folder ->
            val message = folder.getMessageByUID(uid)
                ?: throw MessagingException("Die Mail ist nicht mehr in diesem Ordner")
            val temp = File(target.parentFile, target.name + ".part")
            partAt(message, path).inputStream.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            temp.renameTo(target)
        }
    }

    /** Legt eine gesendete Mail als gelesen im Ordner [sentFolder] ab. */
    suspend fun appendToSent(sentFolder: String, message: MimeMessage) {
        withStore { store ->
            message.setFlag(Flags.Flag.SEEN, true)
            store.getFolder(sentFolder).appendMessages(arrayOf(message))
        }
    }

    suspend fun setSeen(fullName: String, uids: List<Long>, seen: Boolean) =
        setFlag(fullName, uids, Flags.Flag.SEEN, seen)

    suspend fun setFlagged(fullName: String, uids: List<Long>, flagged: Boolean) =
        setFlag(fullName, uids, Flags.Flag.FLAGGED, flagged)

    suspend fun setAnswered(fullName: String, uids: List<Long>) =
        setFlag(fullName, uids, Flags.Flag.ANSWERED, true)

    private suspend fun setFlag(fullName: String, uids: List<Long>, flag: Flags.Flag, value: Boolean) {
        withFolder(fullName, Folder.READ_WRITE) { folder ->
            val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            folder.setFlags(messages, Flags(flag), value)
        }
    }

    /**
     * Verschiebt Mails und liefert ihre UIDs im Zielordner, damit sich das
     * Verschieben rückgängig machen lässt. Ohne UIDPLUS beim Server ist die
     * Liste leer.
     */
    suspend fun move(fullName: String, uids: List<Long>, targetFolder: String): List<Long> =
        withFolder(fullName, Folder.READ_WRITE) { folder ->
            val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            // IMAP MOVE, fällt in Angus Mail selbst auf COPY + Löschen zurück
            folder.moveUIDMessages(messages, folder.store.getFolder(targetFolder))
                .orEmpty().mapNotNull { it?.uid }
        }

    private fun roleOf(folder: IMAPFolder): FolderRole {
        // SPECIAL-USE-Attribute (RFC 6154), sonst übliche Namen
        val attributes = folder.attributes.map { it.lowercase() }
        return when {
            folder.fullName.equals("INBOX", ignoreCase = true) -> FolderRole.Inbox
            "\\drafts" in attributes -> FolderRole.Drafts
            "\\sent" in attributes -> FolderRole.Sent
            "\\junk" in attributes -> FolderRole.Junk
            "\\trash" in attributes -> FolderRole.Trash
            "\\archive" in attributes -> FolderRole.Archive
            else -> when (folder.name.lowercase()) {
                "drafts", "entwürfe" -> FolderRole.Drafts
                "sent", "sent items", "sent messages", "gesendet", "gesendete objekte" -> FolderRole.Sent
                "junk", "spam" -> FolderRole.Junk
                "trash", "deleted items", "papierkorb" -> FolderRole.Trash
                "archive", "archiv" -> FolderRole.Archive
                else -> FolderRole.Other
            }
        }
    }

    // Systemordner bekommen feste deutsche Namen
    private fun displayName(folder: IMAPFolder, role: FolderRole) = when (role) {
        FolderRole.Inbox -> "Eingang"
        FolderRole.Drafts -> "Entwürfe"
        FolderRole.Sent -> "Gesendet"
        FolderRole.Junk -> "Werbung"
        FolderRole.Trash -> "Papierkorb"
        FolderRole.Archive -> "Archiv"
        else -> folder.name
    }

    /**
     * Vorschautexte für die angegebenen Mails. Statt des ganzen Textes wird
     * per Teilabruf (BODY.PEEK[x]<0.n>) nur der Anfang des Text-Teils geholt:
     * 2 KB bei reinem Text, 16 KB bei HTML, weil dort oft erst lange
     * Stilangaben kommen. Mails ohne lesbaren Text bekommen eine leere Vorschau.
     */
    suspend fun previews(fullName: String, uids: List<Long>): Map<Long, String> =
        withFolder(fullName, Folder.READ_ONLY) { folder ->
            val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            folder.fetch(
                messages,
                FetchProfile().apply {
                    add(FetchProfile.Item.CONTENT_INFO)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
            messages.associate { message ->
                val text = runCatching { partialText(folder, message) }
                    .onFailure { Log.w("SlimMail", "Vorschau nicht geladen", it) }
                    .getOrNull()
                folder.getUID(message) to cleanPreview(text.orEmpty())
            }
        }

    private fun partialText(folder: IMAPFolder, message: Message): String? {
        // Erst reinen Text suchen, sonst HTML
        val (part, path) = findPart(message, emptyList(), "text/plain")
            ?: findPart(message, emptyList(), "text/html")
            ?: return null
        val isHtml = part.isMimeType("text/html")
        // Bei einer einteiligen Mail ist der Text der ganze Rumpf
        val section = if (path.isEmpty()) "TEXT" else path.joinToString(".") { (it + 1).toString() }
        val limit = if (isHtml) 16 * 1024 else 2 * 1024
        val raw = folder.doCommand { protocol ->
            (protocol as IMAPProtocol).peekBody(message.messageNumber, section, 0, limit)?.byteArray?.newBytes
        } as? ByteArray ?: return null

        val decoded = decodeTransfer(raw, (part as? MimePart)?.encoding)
        val charset = runCatching {
            ContentType(part.contentType).getParameter("charset")?.let { Charset.forName(MimeUtility.javaCharset(it)) }
        }.getOrNull() ?: Charsets.UTF_8
        val text = String(decoded, charset)
        return if (isHtml) htmlToText(text) else text
    }

    private fun findPart(part: Part, path: List<Int>, mimeType: String): Pair<Part, List<Int>>? {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
        if (part.isMimeType(mimeType)) return part to path
        if (!part.isMimeType("multipart/*")) return null
        val multipart = part.content as Multipart
        return (0 until multipart.count).firstNotNullOfOrNull { findPart(multipart.getBodyPart(it), path + it, mimeType) }
    }

    /**
     * Base64 und Quoted-Printable eines abgeschnittenen Anfangs dekodieren. Am
     * Schnitt kann ein Zeichen halb sein, das wird einfach weggelassen.
     */
    private fun decodeTransfer(raw: ByteArray, encoding: String?): ByteArray = when (encoding?.lowercase()) {
        "base64" -> {
            val clean = String(raw, Charsets.ISO_8859_1).filterNot { it.isWhitespace() }
            Base64.decode(clean.substring(0, clean.length / 4 * 4), Base64.DEFAULT)
        }
        "quoted-printable" -> {
            val out = ByteArrayOutputStream()
            val stream = MimeUtility.decode(ByteArrayInputStream(raw), "quoted-printable")
            try {
                while (true) {
                    val b = stream.read()
                    if (b < 0) break
                    out.write(b)
                }
            } catch (_: IOException) {
                // Abgeschnittene Escape-Folge am Ende
            }
            out.toByteArray()
        }
        else -> raw
    }

    private fun cleanPreview(text: String): String = text.lineSequence()
        // Zitate und Leerzeilen gehören nicht in die Vorschau
        .filterNot { it.trimStart().startsWith(">") }
        .joinToString(" ")
        .replace(previewNoise, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(200)

    /**
     * HTML-Teil einer Mail, falls vorhanden. Bei multipart/alternative der
     * HTML-Zweig, bei multipart/mixed oder /related der erste HTML-Teil, der
     * kein Anhang ist.
     */
    private fun htmlOf(part: Part): String? {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
        return when {
            part.isMimeType("text/html") -> part.content as? String
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                (0 until multipart.count).firstNotNullOfOrNull { htmlOf(multipart.getBodyPart(it)) }
            }
            part.isMimeType("message/rfc822") -> (part.content as? Part)?.let(::htmlOf)
            else -> null
        }
    }

    /** Text einer Mail: bevorzugt text/plain, sonst aus HTML umgewandelt. */
    private fun textOf(part: Part): String? {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
        return when {
            part.isMimeType("text/plain") -> part.content as? String
            part.isMimeType("text/html") -> (part.content as? String)?.let(::htmlToText)
            part.isMimeType("multipart/alternative") -> {
                val multipart = part.content as Multipart
                val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
                parts.firstOrNull { it.isMimeType("text/plain") }?.let(::textOf)
                    ?: parts.firstNotNullOfOrNull(::textOf)
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                (0 until multipart.count).firstNotNullOfOrNull { textOf(multipart.getBodyPart(it)) }
            }
            part.isMimeType("message/rfc822") -> (part.content as? Part)?.let(::textOf)
            else -> null
        }
    }

    private fun htmlToText(html: String): String {
        val withoutStyles = html
            .replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), "")
        // LEGACY setzt zwischen Absätze eine Leerzeile, COMPACT nur einen Umbruch
        return Html.fromHtml(withoutStyles, Html.FROM_HTML_MODE_LEGACY).toString()
            // Html.fromHtml ersetzt Bilder durch dieses Objektzeichen
            .replace("￼", "")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    private fun attachmentsOf(part: Part, path: List<Int> = emptyList()): List<AttachmentInfo> {
        if (part.isMimeType("multipart/*")) {
            val multipart = runCatching { part.content as Multipart }.getOrNull() ?: return emptyList()
            return (0 until multipart.count).flatMap { attachmentsOf(multipart.getBodyPart(it), path + it) }
        }
        // Eingebettete Bilder gehören zum HTML und erscheinen dort, nicht als Anhang
        if (isInlineImage(part)) return emptyList()
        val isAttachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
            (part.fileName != null && !part.isMimeType("text/*"))
        if (!isAttachment) return emptyList()
        val name = part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) } ?: "Anhang"
        val mimeType = runCatching { ContentType(part.contentType).baseType.lowercase() }
            .getOrDefault("application/octet-stream")
        return listOf(AttachmentInfo(name, mimeType, decodedSize(part), path))
    }

    /**
     * Der Server meldet die kodierte Größe. Base64 macht aus 3 Bytes 4 Zeichen
     * und bricht alle 76 Zeichen um, die Datei ist also gut ein Viertel kleiner.
     */
    private fun decodedSize(part: Part): Int {
        val size = part.size
        val encoding = (part as? MimePart)?.encoding
        return if (size > 0 && encoding.equals("base64", ignoreCase = true)) (size * 0.73).toInt() else size
    }

    private fun isInlineImage(part: Part): Boolean =
        part.isMimeType("image/*") &&
            !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) &&
            part.getHeader("Content-ID")?.firstOrNull() != null

    private fun partAt(message: Message, path: List<Int>): Part =
        path.fold(message as Part) { part, index -> (part.content as Multipart).getBodyPart(index) }

    /**
     * Ersetzt cid:-Verweise im HTML durch data:-URIs mit den eingebetteten
     * Bildern der Mail. Die kommen aus der Mail selbst, nicht aus dem Netz,
     * und dürfen deshalb auch bei gesperrten externen Bildern erscheinen.
     */
    private fun embedInlineImages(html: String, message: Message): String {
        if (!html.contains("cid:", ignoreCase = true)) return html
        val images = mutableMapOf<String, String>()
        fun collect(part: Part) {
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as Multipart
                (0 until multipart.count).forEach { collect(multipart.getBodyPart(it)) }
            } else if (part.isMimeType("image/*")) {
                val contentId = part.getHeader("Content-ID")?.firstOrNull()?.trim()?.removeSurrounding("<", ">")
                    ?: return
                if (part.size > MaxInlineImageBytes) return
                val bytes = part.inputStream.use { it.readBytes() }
                val type = ContentType(part.contentType).baseType.lowercase()
                images[contentId.lowercase()] = "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        collect(message)
        return Regex("""(?i)cid:([^"'\s)>]+)""").replace(html) { match ->
            images[match.groupValues[1].lowercase()] ?: match.value
        }
    }
}

/**
 * Gemeinsame Einstellungen für IMAP und SMTP; auch der IDLE-Wächter im
 * Hintergrunddienst nutzt sie für seine eigene Verbindung.
 */
internal fun mailSession(): Session = Session.getInstance(
    Properties().apply {
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "30000")
        put("mail.imaps.writetimeout", "30000")
        put("mail.imaps.ssl.checkserveridentity", "true")
        // Mail-Texte in 64-KB-Stücken holen statt in den voreingestellten 16 KB
        put("mail.imaps.fetchsize", "65536")
        put("mail.smtps.auth", "true")
        put("mail.smtps.connectiontimeout", "15000")
        put("mail.smtps.timeout", "30000")
        put("mail.smtps.writetimeout", "60000")
        put("mail.smtps.ssl.checkserveridentity", "true")
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
        put("mail.smtp.ssl.checkserveridentity", "true")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "60000")
    },
)

// Kopfzeilen tolerant auswerten, siehe headerProfile in MailRepository

internal fun fromAddressOf(message: Message): InternetAddress? {
    val header = message.getHeader("From")?.joinToString(",") ?: return null
    // strict = false: auch Adressen ohne gültige Domain durchlassen
    return runCatching { InternetAddress.parseHeader(header, false).firstOrNull() }.getOrNull()
}

internal fun senderOf(message: Message): String {
    val address = fromAddressOf(message)
        ?: return message.getHeader("From")?.firstOrNull()?.let(MimeUtility::unfold)?.trim() ?: "(Unbekannt)"
    return address.personal?.takeIf { it.isNotBlank() } ?: address.address.orEmpty()
}

internal fun subjectOf(message: Message): String {
    val header = message.getHeader("Subject")?.firstOrNull() ?: return ""
    val unfolded = MimeUtility.unfold(header)
    return runCatching { MimeUtility.decodeText(unfolded) }.getOrDefault(unfolded).trim()
}
