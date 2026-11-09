package slim.mail.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** Ordner im App-Cache für geladene Anhänge; der FileProvider gibt nur ihn frei. */
fun attachmentDir(context: Context) = File(context.cacheDir, "attachments").apply { mkdirs() }

/**
 * Öffnet eine Datei in einer passenden App oder bietet sie zum Teilen an.
 * Die andere App bekommt nur eine zeitweise Leseberechtigung für genau diese
 * Datei. Gibt false zurück, wenn es keine passende App gibt.
 */
fun openAttachment(context: Context, file: File, mimeType: String, share: Boolean): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = if (share) {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            file.name,
        )
    } else {
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Ein im Verfassen-Sheet ausgewählter Anhang, gelesen wird er erst beim Senden. */
data class PickedAttachment(val uri: Uri, val name: String, val size: Long, val mimeType: String)

fun pickedAttachment(context: Context, uri: Uri): PickedAttachment {
    var name = uri.lastPathSegment ?: "Anhang"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return PickedAttachment(uri, name, size, mimeType)
}
