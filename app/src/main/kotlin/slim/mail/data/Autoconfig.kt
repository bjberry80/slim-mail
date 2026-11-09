package slim.mail.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Findet IMAP- und SMTP-Server zu einer Adresse über das Autoconfig-Format
 * von Thunderbird, wie es auch FairEmail nutzt:
 *
 * 1. beim Anbieter selbst (autoconfig.<domain>), falls er so eine Datei anbietet
 * 2. in der öffentlichen Anbieterdatenbank von Thunderbird (ISPDB)
 *
 * Verschickt wird dabei nur die Domain, nie Adresse oder Passwort.
 * Gibt null zurück, wenn nichts Brauchbares gefunden wurde.
 */
object Autoconfig {
    suspend fun discover(email: String): ServerSettings? = withContext(Dispatchers.IO) {
        val domain = email.substringAfter('@').lowercase().trim()
        if (domain.isEmpty()) return@withContext null
        val urls = listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml",
            "https://autoconfig.thunderbird.net/v1.1/$domain",
        )
        urls.firstNotNullOfOrNull { url -> runCatching { fetch(url)?.let(::parse) }.getOrNull() }
    }

    private fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        // Keine Weiterleitungen auf fremde Hosts verfolgen, nur die Antwort selbst
        connection.instanceFollowRedirects = false
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private class Server(val type: String, var host: String = "", var port: Int = 0, var socket: String = "")

    /** Nimmt den ersten IMAP- und den ersten SMTP-Server mit SSL bzw. STARTTLS. */
    private fun parse(xml: String): ServerSettings? {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val servers = mutableListOf<Server>()
        var current: Server? = null
        var field: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "incomingServer", "outgoingServer" ->
                        current = Server(parser.getAttributeValue(null, "type").orEmpty())
                    "hostname", "port", "socketType" -> field = parser.name
                }
                XmlPullParser.TEXT -> current?.let { server ->
                    val text = parser.text.trim()
                    when (field) {
                        "hostname" -> server.host = text
                        "port" -> server.port = text.toIntOrNull() ?: 0
                        "socketType" -> server.socket = text
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "incomingServer", "outgoingServer" -> {
                        current?.let(servers::add)
                        current = null
                    }
                    "hostname", "port", "socketType" -> field = null
                }
            }
        }
        // Unverschlüsselte Server (socketType „plain“) kommen nicht in Frage
        val imap = servers.firstOrNull { it.type == "imap" && it.socket == "SSL" && it.host.isNotEmpty() }
            ?: return null
        val smtp = servers.firstOrNull { it.type == "smtp" && it.socket in setOf("SSL", "STARTTLS") && it.host.isNotEmpty() }
            ?: return null
        return ServerSettings(
            imapHost = imap.host,
            imapPort = imap.port.takeIf { it > 0 } ?: 993,
            smtpHost = smtp.host,
            smtpPort = smtp.port.takeIf { it > 0 } ?: if (smtp.socket == "SSL") 465 else 587,
            smtpStartTls = smtp.socket == "STARTTLS",
        )
    }
}
