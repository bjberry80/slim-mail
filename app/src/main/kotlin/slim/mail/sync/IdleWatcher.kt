package slim.mail.sync

import android.util.Log
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import org.eclipse.angus.mail.imap.IMAPFolder
import slim.mail.data.Account
import slim.mail.data.mailSession
import slim.mail.data.senderOf
import slim.mail.data.subjectOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Eine neu eingetroffene Mail, so viel wie die Benachrichtigung braucht. */
data class NewMail(val uid: Long, val sender: String, val subject: String)

/**
 * Hält eine eigene IMAP-Verbindung zum Eingang offen und wartet per IDLE
 * (RFC 2177) auf neue Mails. Läuft in einem eigenen Thread, weil idle()
 * blockiert. Bricht die Verbindung ab (Funkloch, Server-Neustart), wird mit
 * wachsender Pause neu verbunden.
 */
class IdleWatcher(
    private val account: Account,
    private val onNewMail: (List<NewMail>) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var store: Store? = null
    private var thread: Thread? = null

    // Unterbricht IDLE regelmäßig, damit weder Server (Timeout meist 30 min)
    // noch NAT-Router dazwischen die stille Verbindung kappen
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "imap-idle").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        // Schließen beendet ein laufendes idle() sofort mit einer Exception
        Thread { runCatching { store?.close() } }.start()
        thread?.interrupt()
        keepAlive.shutdownNow()
    }

    private fun loop() {
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            try {
                val connected = mailSession().getStore("imaps")
                connected.connect(account.imapHost, account.servers.imapPort, account.email, account.password)
                store = connected
                val inbox = connected.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                inbox.addMessageCountListener(object : MessageCountAdapter() {
                    override fun messagesAdded(event: MessageCountEvent) = report(inbox, event)
                })
                Log.i(TAG, "IDLE verbunden")
                backoffMs = MIN_BACKOFF_MS

                val ping = keepAlive.scheduleWithFixedDelay(
                    { runCatching { inbox.messageCount } },
                    KEEP_ALIVE_MIN, KEEP_ALIVE_MIN, TimeUnit.MINUTES,
                )
                try {
                    // idle() kehrt nach jeder Unterbrechung zurück und wird neu gestartet
                    while (running && inbox.isOpen) inbox.idle()
                } finally {
                    ping.cancel(false)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "IDLE unterbrochen, neuer Versuch in ${backoffMs / 1000} s", e)
            } finally {
                runCatching { store?.close() }
                store = null
            }
            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        Log.i(TAG, "IDLE beendet")
    }

    private fun report(inbox: IMAPFolder, event: MessageCountEvent) {
        try {
            val messages = event.messages
            inbox.fetch(
                messages,
                FetchProfile().apply {
                    add("From")
                    add("Subject")
                    add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.FLAGS)
                    add(IMAPFolder.FetchProfileItem.INTERNALDATE)
                },
            )
            // Nur wirklich neu eingegangene Mails melden. Zurückgeholte oder
            // hineinverschobene behalten ihr altes Eingangsdatum (und sind oft
            // schon gelesen), die App lädt aber trotzdem neu.
            val recent = System.currentTimeMillis() - FRESH_MS
            val fresh = messages.filter {
                !it.isSet(Flags.Flag.SEEN) && (it.receivedDate?.time ?: Long.MAX_VALUE) > recent
            }
            onNewMail(fresh.map { NewMail(inbox.getUID(it), senderOf(it), subjectOf(it)) })
        } catch (e: Exception) {
            Log.w(TAG, "Neue Mail nicht gelesen", e)
            // Die App soll trotzdem neu laden, nur ohne Details
            onNewMail(emptyList())
        }
    }

    private companion object {
        const val TAG = "SlimIdle"
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val KEEP_ALIVE_MIN = 9L
        const val FRESH_MS = 30 * 60_000L
    }
}
