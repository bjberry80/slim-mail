package slim.mail.data

import android.content.Context
import androidx.core.content.edit

/**
 * Absender, bei denen externe Bilder ohne Nachfrage geladen werden. Gespeichert
 * wird nur die Adresse in Kleinbuchstaben, lokal auf dem Gerät.
 *
 * Bewusst pro Adresse und nicht pro Domain: Ein „Immer laden“ für einen
 * Newsletter soll nicht automatisch für alle Mails desselben Anbieters gelten.
 */
class ImageAllowlist(context: Context) {
    private val prefs = context.getSharedPreferences("remote_images", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY_SENDERS, emptySet()).orEmpty().toSet()

    fun set(address: String, allowed: Boolean): Set<String> {
        val normalized = address.trim().lowercase()
        val updated = if (allowed) load() + normalized else load() - normalized
        // Immer ein neues Set schreiben, SharedPreferences erkennt sonst keine Änderung
        prefs.edit { putStringSet(KEY_SENDERS, HashSet(updated)) }
        return updated
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_SENDERS = "senders"
    }
}
