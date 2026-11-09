package slim.mail.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import slim.mail.data.Account
import slim.mail.data.AccountStore
import slim.mail.data.ImageAllowlist
import slim.mail.data.MailBody
import slim.mail.data.MailCache
import slim.mail.data.MailHeader
import slim.mail.data.MailRepository
import slim.mail.data.OutgoingMail
import slim.mail.data.ServerSettings
import slim.mail.data.toThreadMessage
import slim.mail.data.toThreads
import slim.mail.sync.MailEvents
import slim.mail.sync.MailSyncService
import java.io.File
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hält Konto, Ordner und die Mails des geöffneten Ordners. Änderungen wie
 * Markieren oder Archivieren erscheinen sofort in der Liste und gehen danach
 * an den Server; schlägt das fehl, wird die Liste neu geladen.
 *
 * Ordnerliste und Kopfdaten kommen zuerst aus dem Cache (Speicher, sonst
 * Platte) und werden danach im Hintergrund mit dem Server abgeglichen, damit
 * ein Screenwechsel nicht auf das Netz warten muss.
 */
class MailViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = AccountStore(application)
    private val diskCache = MailCache(application)
    private val imageAllowlist = ImageAllowlist(application)

    /** Absenderadressen, deren externe Bilder immer geladen werden. */
    var trustedImageSenders by mutableStateOf(imageAllowlist.load())
        private set

    fun setImagesTrusted(address: String, trusted: Boolean) {
        if (address.isBlank()) return
        trustedImageSenders = imageAllowlist.set(address, trusted)
    }
    private var repository: MailRepository? = null

    // Ordnername → Kopfdaten, neueste zuerst
    private val headerCache = mutableMapOf<String, List<MailHeader>>()

    // „Ordner/UID“ → Volltext; die ältesten fliegen ab 200 Einträgen raus
    private val bodyCache = object : LinkedHashMap<String, MailBody>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MailBody>) = size > 200
    }

    var account by mutableStateOf<Account?>(null)
        private set
    var folders by mutableStateOf<List<MailFolder>>(emptyList())
        private set
    var currentFolder by mutableStateOf<MailFolder?>(null)
        private set
    val messages = mutableStateListOf<Message>()

    /** Nur für das Herunterziehen der Liste; der Abgleich im Hintergrund zeigt keinen Spinner. */
    var refreshing by mutableStateOf(false)
        private set
    private var runningJobs by mutableIntStateOf(0)
    val syncing: Boolean get() = runningJobs > 0
    var error by mutableStateOf<String?>(null)
        private set
    private var lastRefresh by mutableStateOf<Date?>(null)

    /** Nachrichten des geöffneten Threads; null, solange sie laden. */
    var thread by mutableStateOf<List<ThreadMessage>?>(null)
        private set
    private var threadJob: Job? = null
    private var previewJob: Job? = null

    // „Ordner/UID“, für die schon eine Vorschau versucht wurde; Mails ohne
    // lesbaren Text sollen nicht bei jedem Aktualisieren erneut geladen werden
    private val previewTried = mutableSetOf<String>()

    init {
        accountStore.load()?.let(::useAccount)
        // Push: der Hintergrunddienst meldet neue Mails im Eingang
        viewModelScope.launch {
            MailEvents.inboxChanged.collect {
                refreshFolders()
                if (currentFolder?.role == FolderRole.Inbox) refreshMessages()
            }
        }
    }

    val statusText: String
        get() = when {
            runningJobs > 0 -> "Wird aktualisiert …"
            error != null -> error!!
            else -> lastRefresh?.let {
                if (Date().time - it.time < 60_000) "Gerade aktualisiert"
                else "Aktualisiert um ${SimpleDateFormat("HH:mm", Locale.GERMAN).format(it)}"
            } ?: ""
        }

    val archiveFolder: MailFolder?
        get() = folders.firstOrNull { it.role == FolderRole.Archive }

    val trashFolder: MailFolder?
        get() = folders.firstOrNull { it.role == FolderRole.Trash }

    private val sentFolder: MailFolder?
        get() = folders.firstOrNull { it.role == FolderRole.Sent }

    /**
     * Sendet und liefert null bei Erfolg, sonst eine Meldung für das Sheet.
     * Ablage in „Gesendet“ und \Answered am Original laufen danach im
     * Hintergrund; scheitern sie, ist die Mail trotzdem verschickt.
     */
    suspend fun send(draft: Draft, mail: OutgoingMail): String? {
        val repo = repository ?: return "Nicht angemeldet"
        val folder = currentFolder
        val sent = try {
            repo.send(mail)
        } catch (e: AuthenticationFailedException) {
            return "Nicht gesendet: Der SMTP-Server hat die Anmeldung abgelehnt."
        } catch (e: SendFailedException) {
            val invalid = e.invalidAddresses?.joinToString { it.toString() }
            return if (invalid.isNullOrEmpty()) "Nicht gesendet: ${e.message}" else "Nicht gesendet, ungültig: $invalid"
        } catch (e: MessagingException) {
            Log.w(TAG, "SMTP-Fehler", e)
            return "Nicht gesendet. " + describe(e, account?.smtpHost.orEmpty())
        }

        val target = sentFolder
        launchWithRepo { r ->
            if (target != null) {
                try {
                    r.appendToSent(target.fullName, sent)
                    headerCache.remove(target.fullName)
                } catch (e: MessagingException) {
                    Log.w(TAG, "Ablage in Gesendet fehlgeschlagen", e)
                    error = "Gesendet, aber nicht in „Gesendet“ abgelegt"
                }
            }
            val repliedUid = draft.replyToUid
            if (repliedUid != null && folder != null) {
                r.setAnswered(folder.fullName, listOf(repliedUid))
            }
        }
        return null
    }

    /** Liefert null bei Erfolg, sonst eine Meldung für den Anmelde-Screen. */
    suspend fun signIn(name: String, email: String, password: String, servers: ServerSettings): String? {
        val candidate = Account(email, password, name, servers)
        val repo = MailRepository(candidate)
        return try {
            repo.verify()
            accountStore.save(candidate)
            useAccount(candidate, repo)
            null
        } catch (e: AuthenticationFailedException) {
            "Anmeldung fehlgeschlagen. Bitte E-Mail und Passwort prüfen."
        } catch (e: MessagingException) {
            describe(e, candidate.imapHost)
        }
    }

    fun updateName(name: String) {
        val current = account ?: return
        accountStore.saveName(name.trim())
        account = current.copy(name = name.trim())
    }

    fun signOut() {
        MailSyncService.stop(getApplication())
        val repo = repository
        viewModelScope.launch { repo?.close() }
        accountStore.clear()
        diskCache.clear()
        attachmentDir(getApplication()).deleteRecursively()
        imageAllowlist.clear()
        trustedImageSenders = emptySet()
        headerCache.clear()
        bodyCache.clear()
        repository = null
        account = null
        folders = emptyList()
        currentFolder = null
        messages.clear()
    }

    private fun useAccount(newAccount: Account, repo: MailRepository = MailRepository(newAccount)) {
        repository = repo
        account = newAccount
        diskCache.loadFolders()?.let { folders = it }
        refreshFolders()
        // Läuft schon, wenn er nach Neustart oder Update gestartet wurde; dann ohne Wirkung
        MailSyncService.start(getApplication())
    }

    fun refreshFolders() = launchWithRepo { repo ->
        val loaded = repo.folders()
        val flagged = runCatching { repo.flaggedCount() }.getOrNull()
        // „Markiert“ als Sammelordner direkt nach dem Eingang
        val inboxIndex = loaded.indexOfFirst { it.role == FolderRole.Inbox }
        folders = loaded.toMutableList().apply {
            add(inboxIndex + 1, MailFolder("", "Markiert", FolderRole.Flagged, flagged?.takeIf { it > 0 }))
        }
        withContext(Dispatchers.IO) { diskCache.saveFolders(folders) }
        lastRefresh = Date()
    }

    fun openFolder(folder: MailFolder) {
        if (currentFolder?.fullName != folder.fullName) {
            messages.clear()
            cachedHeaders(folder.fullName)?.let { messages.addAll(toThreads(it)) }
        }
        currentFolder = folder
        refreshMessages()
    }

    /** [byUser]: durch Herunterziehen ausgelöst, dann mit Spinner. */
    fun refreshMessages(byUser: Boolean = false) {
        val folder = currentFolder ?: return
        if (byUser) refreshing = true
        launchWithRepo { repo ->
            try {
                val known = headerCache[folder.fullName].orEmpty().associate { it.uid to it.preview }
                val headers = repo.headers(folder.fullName, knownPreviews = known)
                storeHeaders(folder.fullName, headers)
                // Nur austauschen, wenn sich etwas geändert hat, sonst flackert die Liste
                if (currentFolder?.fullName == folder.fullName) {
                    val threads = toThreads(headers)
                    if (threads != messages.toList()) {
                        messages.clear()
                        messages.addAll(threads)
                    }
                    loadMissingPreviews(folder.fullName, threads)
                }
                lastRefresh = Date()
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Holt fehlende Vorschauen in Zehnerpaketen, nur für die sichtbaren
     * Thread-Köpfe. Zwischen den Paketen ist die Verbindung frei, Öffnen oder
     * Markieren muss also nicht auf alle Vorschauen warten. Bewusst ohne
     * „Wird aktualisiert …“: die Liste ist ja schon da.
     */
    private fun loadMissingPreviews(folderName: String, threads: List<Message>) {
        val missing = threads
            .filter { it.preview.isBlank() && previewTried.add("$folderName/${it.id}") }
            .map { it.id }
        if (missing.isEmpty()) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            for (batch in missing.chunked(10)) {
                val repo = repository ?: return@launch
                val loaded = try {
                    repo.previews(folderName, batch)
                } catch (e: MessagingException) {
                    Log.w(TAG, "Vorschauen nicht geladen", e)
                    // Beim nächsten Aktualisieren erneut versuchen
                    batch.forEach { previewTried.remove("$folderName/$it") }
                    return@launch
                }
                updateCachedHeaders(folderName) { headers ->
                    headers.map { h -> loaded[h.uid]?.let { h.copy(preview = it) } ?: h }
                }
                if (currentFolder?.fullName == folderName) {
                    loaded.forEach { (uid, preview) -> update(uid) { it.copy(preview = preview) } }
                }
            }
        }
    }

    fun openThread(message: Message) {
        val folder = currentFolder ?: return
        val cached = message.threadUids.mapNotNull { bodyCache["${folder.fullName}/$it"] }
        thread = if (cached.size == message.threadUids.size) cached.map(::toThreadMessage) else null
        threadJob?.cancel()
        threadJob = launchWithRepo { repo ->
            if (thread == null) {
                val bodies = repo.bodies(folder.fullName, message.threadUids)
                bodies.forEach { bodyCache["${folder.fullName}/${it.uid}"] = it }
                thread = bodies.map(::toThreadMessage)
            }
            if (message.unread) {
                update(message.id) { it.copy(unread = false) }
                updateCachedHeaders(folder.fullName) { headers ->
                    headers.map { if (it.uid in message.threadUids) it.copy(seen = true) else it }
                }
                repo.setSeen(folder.fullName, message.threadUids, true)
                refreshFolders()
            }
        }
    }

    /**
     * Gibt die lokale Datei eines Anhangs zurück und lädt sie dafür bei Bedarf
     * vom Server. Liegt im App-Cache, Android darf sie bei Platzmangel löschen.
     */
    suspend fun loadAttachment(attachment: AttachmentRef): File? {
        val folder = currentFolder ?: return null
        val repo = repository ?: return null
        // Ordner + UID + Pfad machen den Anhang eindeutig, der Name bleibt lesbar
        val key = "${folder.fullName.hashCode().toUInt()}_${attachment.uid}_${attachment.path.joinToString("-")}"
        val safeName = attachment.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val file = File(File(attachmentDir(getApplication()), key).apply { mkdirs() }, safeName)
        if (file.exists()) return file
        return try {
            repo.download(folder.fullName, attachment.uid, attachment.path, file)
            file
        } catch (e: MessagingException) {
            Log.w(TAG, "Anhang nicht geladen", e)
            error = describe(e, account?.imapHost.orEmpty())
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Anhang nicht gespeichert", e)
            null
        }
    }

    fun toggleFlag(message: Message) {
        val folder = currentFolder ?: return
        val flagged = !message.flagged
        update(message.id) { it.copy(flagged = flagged) }
        updateCachedHeaders(folder.fullName) { headers ->
            headers.map { if (it.uid == message.id) it.copy(flagged = flagged) else it }
        }
        launchWithRepo(reloadOnError = true) { repo ->
            // Markiert wird nur die neueste Nachricht des Threads
            repo.setFlagged(folder.fullName, listOf(message.id), flagged)
        }
    }

    fun archive(message: Message) = moveTo(message, archiveFolder, "Archiviert", "Kein Archivordner gefunden")

    /** Löschen heißt: in den Papierkorb, nicht endgültig. */
    fun delete(message: Message) = moveTo(message, trashFolder, "Gelöscht", "Kein Papierkorb gefunden")

    /** Zuletzt gelöschte oder archivierte Mail, solange sie sich zurückholen lässt. */
    class UndoableMove(
        val label: String,
        internal val message: Message,
        internal val index: Int,
        internal val from: MailFolder,
        internal val to: MailFolder,
        // UIDs im Zielordner, sobald der Server das Verschieben bestätigt
        // hat; null, wenn es fehlgeschlagen ist und die Mail noch dort liegt
        internal val movedUids: CompletableDeferred<List<Long>?>,
    )

    var undoableMove by mutableStateOf<UndoableMove?>(null)
        private set
    private var undoTimer: Job? = null

    private fun moveTo(message: Message, target: MailFolder?, label: String, missing: String) {
        val folder = currentFolder ?: return
        if (target == null) {
            error = missing
            return
        }
        val index = messages.indexOfFirst { it.id == message.id }
        messages.removeAll { it.id == message.id }
        updateCachedHeaders(folder.fullName) { headers -> headers.filterNot { it.uid in message.threadUids } }
        // Der Zielordner hat jetzt neue UIDs, sein Cache stimmt nicht mehr
        headerCache.remove(target.fullName)
        val movedUids = CompletableDeferred<List<Long>?>()
        launchWithRepo(reloadOnError = true) { repo ->
            try {
                movedUids.complete(repo.move(folder.fullName, message.threadUids, target.fullName))
            } finally {
                // Bei einem Fehler wartet „Rückgängig“ sonst ewig
                movedUids.complete(null)
            }
        }
        undoableMove = UndoableMove(label, message, index, folder, target, movedUids)
        undoTimer?.cancel()
        undoTimer = viewModelScope.launch {
            delay(UNDO_MILLIS)
            undoableMove = null
        }
    }

    /** Holt die zuletzt verschobene Mail zurück in ihren Ordner. */
    fun undoMove() {
        val undo = undoableMove ?: return
        undoableMove = null
        undoTimer?.cancel()
        // Sofort wieder an alter Stelle zeigen; der Server folgt
        if (currentFolder?.fullName == undo.from.fullName && messages.none { it.id == undo.message.id }) {
            messages.add(undo.index.coerceIn(0, messages.size), undo.message)
        }
        launchWithRepo(reloadOnError = true) { repo ->
            val uids = undo.movedUids.await()
            if (!uids.isNullOrEmpty()) {
                repo.move(undo.to.fullName, uids, undo.from.fullName)
                headerCache.remove(undo.to.fullName)
            }
            // Zurückgeholt hat die Mail neue UIDs; ohne UIDPLUS fehlt sie
            // wieder, beides zeigt erst der Abgleich richtig
            if (currentFolder?.fullName == undo.from.fullName) refreshMessages()
            if (uids != null && uids.isEmpty()) error = "Rückgängig nicht möglich"
        }
    }

    private fun cachedHeaders(folder: String): List<MailHeader>? =
        headerCache[folder] ?: diskCache.loadHeaders(folder)?.also { headerCache[folder] = it }

    private suspend fun storeHeaders(folder: String, headers: List<MailHeader>) {
        headerCache[folder] = headers
        withContext(Dispatchers.IO) { diskCache.saveHeaders(folder, headers) }
    }

    private fun updateCachedHeaders(folder: String, change: (List<MailHeader>) -> List<MailHeader>) {
        val current = headerCache[folder] ?: return
        val updated = change(current)
        headerCache[folder] = updated
        viewModelScope.launch(Dispatchers.IO) { diskCache.saveHeaders(folder, updated) }
    }

    private fun update(id: Long, change: (Message) -> Message) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = change(messages[index])
    }

    private fun launchWithRepo(reloadOnError: Boolean = false, block: suspend (MailRepository) -> Unit): Job? {
        val repo = repository ?: return null
        return viewModelScope.launch {
            runningJobs++
            error = null
            try {
                block(repo)
            } catch (e: AuthenticationFailedException) {
                error = "Anmeldung abgelehnt"
            } catch (e: MessagingException) {
                Log.w(TAG, "IMAP-Fehler", e)
                error = describe(e, account?.imapHost.orEmpty())
                if (reloadOnError) refreshMessages()
            } finally {
                runningJobs--
            }
        }
    }

    private fun describe(e: MessagingException, host: String): String {
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is UnknownHostException }
        return if (cause != null) "Keine Verbindung zu $host" else "Fehler: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "SlimMail"
        const val UNDO_MILLIS = 5_000L
    }

    override fun onCleared() {
        val repo = repository ?: return
        // Der viewModelScope ist hier schon beendet
        Thread { runBlocking { repo.close() } }.start()
    }
}
