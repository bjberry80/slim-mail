package slim.mail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wo IMAP und SMTP eines Anbieters erreichbar sind. IMAP immer mit SSL;
 * SMTP entweder mit SSL (meist Port 465) oder mit STARTTLS (meist 587).
 */
data class ServerSettings(
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 465,
    val smtpStartTls: Boolean = false,
) {
    companion object {
        /** Letzter Ausweg, wenn nichts gefunden wurde: die übliche Konvention imap./smtp.<domain>. */
        fun guess(email: String): ServerSettings {
            val domain = email.substringAfter('@').lowercase()
            return ServerSettings(imapHost = "imap.$domain", smtpHost = "smtp.$domain")
        }
    }
}

data class Account(
    val email: String,
    val password: String,
    // Anzeigename im Absender, z. B. „Björn Bergfelder <…>“; leer = nur Adresse
    val name: String = "",
    val servers: ServerSettings = ServerSettings.guess(email),
) {
    val imapHost: String get() = servers.imapHost
    val smtpHost: String get() = servers.smtpHost
}

/**
 * Speichert das Konto in den SharedPreferences. Das Passwort wird vorher mit
 * einem AES-Schlüssel aus dem Android-Keystore verschlüsselt; der Schlüssel
 * selbst verlässt den Keystore nie.
 */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun load(): Account? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(iv)))
            Account(
                email = email,
                password = String(cipher.doFinal(decode(encrypted)), Charsets.UTF_8),
                name = prefs.getString(KEY_NAME, "").orEmpty(),
                // Ältere Installationen haben noch keine Serverdaten gespeichert
                servers = prefs.getString(KEY_IMAP_HOST, null)?.let { imapHost ->
                    ServerSettings(
                        imapHost = imapHost,
                        imapPort = prefs.getInt(KEY_IMAP_PORT, 993),
                        smtpHost = prefs.getString(KEY_SMTP_HOST, null) ?: ServerSettings.guess(email).smtpHost,
                        smtpPort = prefs.getInt(KEY_SMTP_PORT, 465),
                        smtpStartTls = prefs.getBoolean(KEY_SMTP_STARTTLS, false),
                    )
                } ?: ServerSettings.guess(email),
            )
        } catch (e: Exception) {
            // Schlüssel weg (z. B. nach Gerätewechsel): neu anmelden lassen
            null
        }
    }

    fun save(account: Account) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(account.password.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_EMAIL, account.email)
            putString(KEY_NAME, account.name)
            putString(KEY_IMAP_HOST, account.servers.imapHost)
            putInt(KEY_IMAP_PORT, account.servers.imapPort)
            putString(KEY_SMTP_HOST, account.servers.smtpHost)
            putInt(KEY_SMTP_PORT, account.servers.smtpPort)
            putBoolean(KEY_SMTP_STARTTLS, account.servers.smtpStartTls)
            putString(KEY_IV, encode(cipher.iv))
            putString(KEY_PASSWORD, encode(encrypted))
        }
    }

    /** Ändert nur den Namen, das Passwort bleibt unangetastet. */
    fun saveName(name: String) {
        prefs.edit { putString(KEY_NAME, name) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "slimmail_account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_IMAP_HOST = "imap_host"
        const val KEY_IMAP_PORT = "imap_port"
        const val KEY_SMTP_HOST = "smtp_host"
        const val KEY_SMTP_PORT = "smtp_port"
        const val KEY_SMTP_STARTTLS = "smtp_starttls"
        const val KEY_IV = "password_iv"
        const val KEY_PASSWORD = "password"
    }
}
