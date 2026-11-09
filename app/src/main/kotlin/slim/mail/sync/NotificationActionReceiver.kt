package slim.mail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import slim.mail.data.AccountStore
import slim.mail.data.MailRepository
import slim.mail.ui.FolderRole

/**
 * Knopf „Löschen“ in der Benachrichtigung einer neuen Mail: verschiebt sie
 * wie in der App in den Papierkorb, ohne die App zu öffnen.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELETE) return
        val uid = intent.getLongExtra(MailSyncService.EXTRA_INBOX_UID, -1L)
        if (uid < 0) return
        // Sofort weg, damit der Knopf nicht doppelt gedrückt wird
        NotificationManagerCompat.from(context).cancel(uid.toInt())
        val account = AccountStore(context).load() ?: return

        // Android gibt einem Empfänger nur wenige Sekunden; goAsync verlängert
        // das bis zum finish(), genug für eine IMAP-Verbindung
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val repo = MailRepository(account)
            try {
                withTimeout(20_000) {
                    val trash = repo.folders().firstOrNull { it.role == FolderRole.Trash }
                    if (trash != null) {
                        repo.move("INBOX", listOf(uid), trash.fullName)
                        MailEvents.notifyInboxChanged()
                    } else {
                        Log.w(TAG, "Kein Papierkorb gefunden")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Löschen aus der Benachrichtigung fehlgeschlagen", e)
            } finally {
                repo.close()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DELETE = "slim.mail.action.DELETE"
        private const val TAG = "SlimNotify"
    }
}
