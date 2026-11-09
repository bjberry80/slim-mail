package slim.mail.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import slim.mail.MainActivity
import slim.mail.R
import slim.mail.data.AccountStore

/** Meldet der laufenden App, dass im Eingang etwas Neues angekommen ist. */
object MailEvents {
    private val newMail = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val inboxChanged = newMail.asSharedFlow()

    internal fun notifyInboxChanged() {
        newMail.tryEmit(Unit)
    }
}

/**
 * Vordergrunddienst für Push: hält den [IdleWatcher] am Leben, auch wenn die
 * App geschlossen ist, und zeigt neue Mails als Benachrichtigung. Android
 * verlangt dafür eine dauerhafte Benachrichtigung; sie liegt in einem eigenen,
 * stummen Kanal, den man in den Systemeinstellungen ausblenden kann.
 */
class MailSyncService : Service() {
    private var watcher: IdleWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val account = AccountStore(this).load()
        if (account == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_mailbox_inbox)
                .setContentTitle("Slim.Mail ist verbunden")
                .setContentText("Neue Mails kommen sofort an")
                .setContentIntent(openAppIntent(this))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (watcher == null) {
            watcher = IdleWatcher(account, ::onNewMail).also { it.start() }
        }
        // Wird der Dienst vom System beendet, startet Android ihn neu
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.stop()
        watcher = null
        super.onDestroy()
    }

    private fun onNewMail(mails: List<NewMail>) {
        MailEvents.notifyInboxChanged()
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        mails.forEach { mail ->
            val notification = NotificationCompat.Builder(this, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_mailbox_inbox)
                .setContentTitle(mail.sender)
                .setContentText(mail.subject.ifBlank { "(Kein Betreff)" })
                .setContentIntent(openMailIntent(this, mail.uid))
                .addAction(R.drawable.ic_mailbox_trash, "Löschen", deleteMailIntent(this, mail.uid))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setGroup(GROUP_MAIL)
                .setAutoCancel(true)
                .build()
            try {
                // UID als ID: dieselbe Mail ersetzt ihre Benachrichtigung statt sie zu doppeln
                manager.notify(mail.uid.toInt(), notification)
            } catch (_: SecurityException) {
                // Berechtigung zwischendurch entzogen
            }
        }
    }

    companion object {
        private const val ONGOING_ID = -1
        private const val CHANNEL_SERVICE = "sync"
        private const val CHANNEL_MAIL = "mail"
        private const val GROUP_MAIL = "slim.mail.NEW"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MailSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MailSyncService::class.java))
        }

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Verbindung", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Dauerhafte Anzeige, solange Slim.Mail auf neue Mails wartet"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MAIL, "Neue E-Mails", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Eine Benachrichtigung pro neuer Mail im Eingang"
                },
            )
        }

        /** Extra am Start-Intent: UID einer Mail im Eingang, die gleich geöffnet werden soll. */
        const val EXTRA_INBOX_UID = "slim.mail.INBOX_UID"

        // Eigener requestCode je Mail, sonst überschreiben sich die Extras der Benachrichtigungen
        private fun openMailIntent(context: Context, uid: Long): PendingIntent = PendingIntent.getActivity(
            context,
            uid.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_INBOX_UID, uid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun deleteMailIntent(context: Context, uid: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            uid.toInt(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_DELETE)
                .putExtra(EXTRA_INBOX_UID, uid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
