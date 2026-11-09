package slim.mail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import slim.mail.ui.FolderRole
import slim.mail.ui.MailFolder
import java.io.File
import java.util.Date

/**
 * Zuletzt geladene Ordnerliste und Kopfdaten pro Ordner als JSON im
 * App-Speicher. Damit steht die Liste beim Wechsel und nach einem Neustart
 * sofort da, während im Hintergrund vom Server aktualisiert wird.
 *
 * Nur Kopfdaten und kurze Vorschauen, keine vollständigen Mails. Der Ordner
 * liegt im privaten App-Bereich und ist durch allowBackup=false vom Backup
 * ausgenommen.
 */
class MailCache(context: Context) {
    private val dir = File(context.filesDir, "mailcache").apply { mkdirs() }

    fun loadFolders(): List<MailFolder>? = read("folders")?.let { array ->
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MailFolder(
                fullName = o.getString("fullName"),
                name = o.getString("name"),
                role = FolderRole.valueOf(o.getString("role")),
                count = if (o.has("count")) o.getInt("count") else null,
            )
        }
    }

    fun saveFolders(folders: List<MailFolder>) = write("folders", JSONArray().apply {
        folders.forEach { f ->
            put(JSONObject().apply {
                put("fullName", f.fullName)
                put("name", f.name)
                put("role", f.role.name)
                f.count?.let { put("count", it) }
            })
        }
    })

    fun loadHeaders(folder: String): List<MailHeader>? = read(fileFor(folder))?.let { array ->
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MailHeader(
                uid = o.getLong("uid"),
                sender = o.getString("sender"),
                date = Date(o.getLong("date")),
                subject = o.getString("subject"),
                preview = o.getString("preview"),
                seen = o.getBoolean("seen"),
                flagged = o.getBoolean("flagged"),
                hasAttachment = o.getBoolean("attachment"),
            )
        }
    }

    fun saveHeaders(folder: String, headers: List<MailHeader>) = write(fileFor(folder), JSONArray().apply {
        headers.forEach { h ->
            put(JSONObject().apply {
                put("uid", h.uid)
                put("sender", h.sender)
                put("date", h.date.time)
                put("subject", h.subject)
                put("preview", h.preview)
                put("seen", h.seen)
                put("flagged", h.flagged)
                put("attachment", h.hasAttachment)
            })
        }
    })

    /** Beim Abmelden alles löschen. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    // Ordnernamen können / und Sonderzeichen enthalten. Die Versionsnummer
    // erhöhen, wenn sich die Vorschau-Aufbereitung ändert: alte Dateien werden
    // dann nicht mehr gelesen und die Vorschauen einmal neu geladen.
    private fun fileFor(folder: String) =
        "folder_v${CACHE_VERSION}_" + folder.toByteArray().joinToString("") { "%02x".format(it) }

    private companion object {
        const val CACHE_VERSION = 2
    }

    private fun read(name: String): JSONArray? = runCatching {
        File(dir, "$name.json").takeIf { it.exists() }?.readText()?.let(::JSONArray)
    }.getOrNull()

    private fun write(name: String, array: JSONArray) {
        // Erst in eine Temp-Datei, damit ein Abbruch keinen halben Cache hinterlässt
        val target = File(dir, "$name.json")
        val temp = File(dir, "$name.tmp")
        runCatching {
            temp.writeText(array.toString())
            temp.renameTo(target)
        }
    }
}
