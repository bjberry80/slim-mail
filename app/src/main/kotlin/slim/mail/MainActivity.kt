package slim.mail

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import slim.mail.sync.MailSyncService
import androidx.lifecycle.viewmodel.compose.viewModel
import slim.mail.data.Account
import slim.mail.ui.ComposeScreen
import slim.mail.ui.Message
import slim.mail.ui.RemoteImagePolicy
import slim.mail.ui.Draft
import slim.mail.ui.FolderRole
import slim.mail.ui.InboxScreen
import slim.mail.ui.MailViewModel
import slim.mail.ui.MailboxesScreen
import slim.mail.ui.SearchScreen
import slim.mail.ui.SearchState
import slim.mail.ui.SetupScreen
import slim.mail.ui.SlimTheme
import slim.mail.ui.ThreadScreen
import slim.mail.ui.UndoBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import slim.mail.ui.replyDraft
import slim.mail.ui.forwardDraft
import slim.mail.ui.searchItems

private enum class Screen { Mailboxes, Inbox, Search, Thread }

class MainActivity : ComponentActivity() {
    // Mail aus einer angetippten Benachrichtigung, bis sie geöffnet ist
    private var notifiedUid by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nach einer Drehung o. Ä. nicht erneut öffnen
        if (savedInstanceState == null) takeNotifiedUid(intent)
        setSystemBars(darkBackground = false)
        setContent {
            SlimTheme {
                val vm: MailViewModel = viewModel()
                val account = vm.account
                if (account == null) {
                    SetupScreen(onSignIn = vm::signIn)
                } else {
                    MailApp(vm, account)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeNotifiedUid(intent)
    }

    private fun takeNotifiedUid(intent: Intent?) {
        val uid = intent?.getLongExtra(MailSyncService.EXTRA_INBOX_UID, -1L) ?: -1L
        if (uid >= 0) notifiedUid = uid
    }

    @Composable
    private fun MailApp(vm: MailViewModel, account: Account) {
        // Ab Android 13 braucht jede Benachrichtigung eine Erlaubnis; einmal fragen
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Einfacher Stack; ein Thread kehrt dorthin zurück, wo er
        // geöffnet wurde (Ordner oder Suche).
        var screen by rememberSaveable { mutableStateOf(Screen.Mailboxes) }
        var threadParent by rememberSaveable { mutableStateOf(Screen.Inbox) }
        var openMessageId by rememberSaveable { mutableLongStateOf(0L) }
        val searchState = remember { SearchState() }
        val folder = vm.currentFolder
        // Im Archiv selbst und ohne Archivordner gibt es kein Archivieren
        val canArchive = vm.archiveFolder != null && folder?.role != FolderRole.Archive
        // Ebenso: im Papierkorb selbst kein Löschen, endgültiges Löschen fehlt noch
        val canDelete = vm.trashFolder != null && folder?.role != FolderRole.Trash

        // Verfassen liegt als Sheet über dem aktuellen Screen
        var draft by remember { mutableStateOf<Draft?>(null) }
        // Hält den Inhalt fest, solange das Sheet nach unten wegfährt
        var shownDraft by remember { mutableStateOf(Draft()) }
        fun openCompose(newDraft: Draft) {
            shownDraft = newDraft
            draft = newDraft
        }
        LaunchedEffect(draft != null) { setSystemBars(darkBackground = draft != null) }

        // Benachrichtigung angetippt: Eingang öffnen und dort auf die Mail warten.
        // Läuft neu an, sobald die Ordnerliste da ist (beim Kaltstart erst nach dem Laden).
        val uid = notifiedUid
        val inbox = vm.folders.firstOrNull { it.role == FolderRole.Inbox }
        LaunchedEffect(uid, inbox) {
            if (uid == null || inbox == null) return@LaunchedEffect
            draft = null
            if (vm.currentFolder?.fullName != inbox.fullName) vm.openFolder(inbox)
            screen = Screen.Inbox
            val message = withTimeoutOrNull(15_000) {
                snapshotFlow { vm.messages.firstOrNull { uid in it.threadUids } }.filterNotNull().first()
            }
            if (message != null) {
                openMessageId = message.id
                threadParent = Screen.Inbox
                vm.openThread(message)
                screen = Screen.Thread
            }
            notifiedUid = null
        }

        Box {
            when {
                screen == Screen.Mailboxes || folder == null -> {
                    LaunchedEffect(Unit) { vm.refreshFolders() }
                    MailboxesScreen(
                        accountName = account.email,
                        senderName = account.name,
                        folders = vm.folders,
                        statusText = vm.statusText,
                        onOpenFolder = {
                            vm.openFolder(it)
                            screen = Screen.Inbox
                        },
                        onCompose = { openCompose(Draft()) },
                        onRenameSender = vm::updateName,
                        onSignOut = vm::signOut,
                    )
                }
                screen == Screen.Inbox -> {
                    BackHandler { screen = Screen.Mailboxes }
                    InboxScreen(
                        title = folder.name,
                        messages = vm.messages,
                        loading = vm.refreshing,
                        syncing = vm.syncing,
                        statusText = vm.statusText,
                        onBack = { screen = Screen.Mailboxes },
                        onRefresh = { vm.refreshMessages(byUser = true) },
                        onOpenMessage = { message ->
                            openMessageId = message.id
                            threadParent = Screen.Inbox
                            vm.openThread(message)
                            screen = Screen.Thread
                        },
                        onToggleFlag = vm::toggleFlag,
                        onArchive = if (canArchive) vm::archive else null,
                        onDelete = if (canDelete) vm::delete else null,
                        onCompose = { openCompose(Draft()) },
                        onSearch = { screen = Screen.Search },
                    )
                }
                screen == Screen.Search -> {
                    val closeSearch = {
                        searchState.reset()
                        screen = Screen.Inbox
                    }
                    BackHandler(onBack = closeSearch)
                    SearchScreen(
                        state = searchState,
                        items = searchItems(vm.messages),
                        onCancel = closeSearch,
                        onOpen = { hit ->
                            vm.messages.firstOrNull { it.id == hit.messageId }?.let { message ->
                                openMessageId = message.id
                                threadParent = Screen.Search
                                vm.openThread(message)
                                screen = Screen.Thread
                            }
                        },
                    )
                }
                else -> {
                    BackHandler { screen = threadParent }
                    val index = vm.messages.indexOfFirst { it.id == openMessageId }
                    if (index < 0) {
                        // Nach dem Archivieren ist die Mail nicht mehr in der Liste
                        LaunchedEffect(Unit) { screen = threadParent }
                    } else {
                        val message = vm.messages[index]
                        val openOther = { other: Message ->
                            openMessageId = other.id
                            vm.openThread(other)
                        }
                        ThreadScreen(
                            message = message,
                            thread = vm.thread,
                            onBack = { screen = threadParent },
                            onPrevious = vm.messages.getOrNull(index - 1)?.let { { openOther(it) } },
                            onNext = vm.messages.getOrNull(index + 1)?.let { { openOther(it) } },
                            onToggleFlag = { vm.toggleFlag(message) },
                            onArchive = if (canArchive) ({ vm.archive(message) }) else null,
                            onReply = { quoted -> openCompose(replyDraft(message, quoted)) },
                            onForward = { quoted -> openCompose(forwardDraft(message, quoted)) },
                            onCompose = { openCompose(Draft()) },
                            loadAttachment = vm::loadAttachment,
                            imagePolicy = RemoteImagePolicy(
                                isTrusted = { it in vm.trustedImageSenders },
                                setTrusted = vm::setImagesTrusted,
                            ),
                            backLabel = if (threadParent == Screen.Search) "Suche" else folder.name,
                        )
                    }
                }
            }

            UndoBar(
                label = vm.undoableMove?.label,
                onUndo = vm::undoMove,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            AnimatedVisibility(
                visible = draft != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BackHandler { draft = null }
                ComposeScreen(
                    draft = shownDraft,
                    from = account.email,
                    fromName = account.name,
                    onCancel = { draft = null },
                    onSend = { mail -> vm.send(shownDraft, mail) },
                    loadAttachment = vm::loadAttachment,
                )
            }
        }
    }

    /**
     * Auf hellem Grund dunkle Symbole in Status- und Navigationsleiste,
     * hinter dem Verfassen-Sheet (dunkler Rand oben) helle.
     */
    private fun setSystemBars(darkBackground: Boolean) {
        val style = if (darkBackground) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = style,
            // Unten liegt auch beim Sheet die helle Werkzeugleiste
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }
}
