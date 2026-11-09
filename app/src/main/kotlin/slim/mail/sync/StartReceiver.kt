package slim.mail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import slim.mail.data.AccountStore

/** Startet den Push-Dienst nach einem Neustart des Handys und nach App-Updates. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (AccountStore(context).load() != null) MailSyncService.start(context)
            }
        }
    }
}
