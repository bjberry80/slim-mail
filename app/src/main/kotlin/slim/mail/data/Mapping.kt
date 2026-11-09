package slim.mail.data

import slim.mail.ui.AttachmentRef
import slim.mail.ui.Message
import slim.mail.ui.ThreadMessage
import slim.mail.ui.initialsOf
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private val time = DateTimeFormatter.ofPattern("HH:mm")
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN)
private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yy")
private val dayMonth = DateTimeFormatter.ofPattern("dd.MM.")
private val fullDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Kurz und lesbar: Uhrzeit, „Gestern“, Wochentag oder Datum. */
fun listDate(date: Date): String {
    val dateTime = date.toInstant().atZone(zone)
    val day = dateTime.toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        day == today -> dateTime.format(time)
        day == today.minusDays(1) -> "Gestern"
        day.isAfter(today.minusDays(7)) -> dateTime.format(weekday)
        else -> dateTime.format(shortDate)
    }
}

private fun longDate(date: Date): String {
    val dateTime = date.toInstant().atZone(zone)
    val day = dateTime.toLocalDate()
    val today = LocalDate.now(zone)
    val dayText = when (day) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> dateTime.format(fullDate)
    }
    return "$dayText, ${dateTime.format(time)}"
}

private fun quoteDate(date: Date): String {
    val dateTime = date.toInstant().atZone(zone)
    return "${dateTime.format(fullDate)} um ${dateTime.format(time)}"
}

// Antwort- und Weiterleitungspräfixe in den gängigen Sprachen, auch mehrfach
private val subjectPrefix = Regex("^\\s*((re|aw|fwd?|wg|sv|antw)(\\[\\d+])?\\s*:\\s*)+", RegexOption.IGNORE_CASE)

private fun threadKey(subject: String) = subject.replace(subjectPrefix, "").trim().lowercase()

/**
 * Fasst Mails mit gleichem Betreff (ohne „Re:“ usw.) zu Threads zusammen.
 * Einfacher als FairEmails Abgleich über Message-ID und References, reicht
 * aber für den Anfang. [headers] kommt neueste zuerst und bleibt so sortiert.
 */
fun toThreads(headers: List<MailHeader>): List<Message> {
    val groups = LinkedHashMap<String, MutableList<MailHeader>>()
    headers.forEach { header ->
        // Mails ohne Betreff nicht zusammenwerfen
        val key = threadKey(header.subject).ifEmpty { "uid:${header.uid}" }
        groups.getOrPut(key) { mutableListOf() }.add(header)
    }
    return groups.values.map { group ->
        val latest = group.first()
        Message(
            id = latest.uid,
            sender = latest.sender,
            time = listDate(latest.date),
            subject = latest.subject.ifBlank { "(Kein Betreff)" },
            preview = latest.preview,
            unread = group.any { !it.seen },
            threadCount = group.size,
            hasAttachment = group.any { it.hasAttachment },
            flagged = group.any { it.flagged },
            threadUids = group.map { it.uid }.reversed(),
        )
    }
}

fun toThreadMessage(body: MailBody): ThreadMessage = ThreadMessage(
    sender = body.sender,
    initials = initialsOf(body.sender),
    date = body.date.toInstant().atZone(zone).format(dayMonth),
    body = body.text,
    html = body.html,
    attachments = body.attachments.map { AttachmentRef(body.uid, it.path, it.name, it.mimeType, it.size) },
    longDate = longDate(body.date),
    quoteDate = quoteDate(body.date),
    senderAddress = body.senderAddress,
    uid = body.uid,
    messageId = body.messageId,
    references = body.references,
)
