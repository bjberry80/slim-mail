package slim.mail.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize

// Externe Bilder (http/https), auch in CSS-Hintergründen
private val remoteContent = Regex("""(?i)(src|background)\s*=\s*["']?\s*https?:|url\(\s*["']?\s*https?:""")

// Skripte, Formulare, eingebettete Fenster usw. fliegen vorab raus, auch wenn
// die WebView sie ohnehin nicht ausführen oder absenden kann
private val unsafeElements = Regex("""(?is)<(script|iframe|object|embed|form)\b.*?(</\1\s*>|/?>)""")

/**
 * Passt typische Newsletter-Layouts (feste 600 px breite Tabellen, große
 * Bilder) an die Bildschirmbreite an, ohne die Mail sonst umzugestalten.
 */
private const val ReaderCss = """
html, body { margin: 0; padding: 0; background: transparent; }
body { font-family: sans-serif; font-size: 16px; line-height: 1.45; color: #000;
       word-wrap: break-word; overflow-wrap: anywhere; }
img { max-width: 100% !important; height: auto !important; }
table { max-width: 100% !important; }
td, th { word-break: break-word; }
pre { white-space: pre-wrap; }
blockquote { margin: 0 0 0 8px; padding-left: 12px; border-left: 2px solid #C6C6C8; color: #5E5E63; }
"""

// Viewport-Angaben der Mail selbst; die letzte gewinnt, und manche Newsletter
// setzen dort z. B. initial-scale=2.3, dann ist die Mail stark vergrößert
private val mailViewport = Regex("""(?is)<meta\b[^>]*\bname\s*=\s*["']?viewport\b[^>]*>""")

// Feste Breiten aus Newsletter-Layouts: width="480" oder width: 600px an Tabellen, Zellen, Blöcken
private val fixedWidth = Regex(
    """(?i)<(?:table|td|div|center|img)\b[^>]*?(?:\bwidth\s*=\s*["']?(\d{3,4})\b|\bwidth\s*:\s*(\d{3,4})px)""",
)

/**
 * Breite, für die die Mail gebaut ist, falls sie breiter als ein Handy ist.
 * Werte über 1000 sind meist Fehler oder 100%-Ersatz und werden ignoriert.
 */
private fun designWidth(html: String): Int? = fixedWidth.findAll(html)
    .mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
    .filter { it in 360..1000 }
    .maxOrNull()

// Für die Mobil-Ansicht: Blöcke dürfen nie breiter als der Bildschirm werden,
// auch wenn eine Breite in einem <style>-Block der Mail steht
private const val MobileCss = """
table, td, th, div, center { max-width: 100% !important; box-sizing: border-box; }
"""

// Layout-Tags, deren feste Breiten die Mobil-Ansicht umrechnet (Bilder regelt max-width)
private val layoutTag = Regex("""(?i)<(?:table|td|th|div|center)\b[^>]*>""")
private val widthAttr = Regex("""(?i)\bwidth\s*=\s*(["']?)(\d+)(?:px)?\1""")
private val widthStyle = Regex("""(?i)(?<![-\w])width\s*:\s*(\d+)px""")
private val minWidthStyle = Regex("""(?i)min-width\s*:\s*\d+px""")

/**
 * Rechnet feste Pixelbreiten in Anteile der Design-Breite um: Aus width="280"
 * in einer 600 px breiten Mail werden 47 %. Spalten behalten so ihr
 * Verhältnis, die Schrift aber ihre Originalgröße. Kleine Breiten (Abstände,
 * Symbole) bleiben, wie sie sind.
 */
private fun fluid(html: String, design: Int): String = html.replace(layoutTag) { tag ->
    fun percent(px: Int) = "${(px * 100 / design).coerceAtMost(100)}%"
    tag.value
        .replace(widthAttr) { m ->
            val px = m.groupValues[2].toInt()
            if (px >= 100) "width=\"${percent(px)}\"" else m.value
        }
        .replace(widthStyle) { m ->
            val px = m.groupValues[1].toInt()
            if (px >= 100) "width:${percent(px)}" else m.value
        }
        .replace(minWidthStyle, "min-width:0")
}

/**
 * [original] = false (Standard): Mobil-Ansicht. Mails mit fester Breite werden
 * auf Bildschirmbreite umgebrochen, die Schrift bleibt lesbar groß.
 * [original] = true: die Mail in ihrer Design-Breite setzen und
 * als Ganzes verkleinern (useWideViewPort + loadWithOverviewMode); zum
 * Heranzoomen gedacht, falls die Mobil-Ansicht ein Layout zerlegt.
 */
private fun wrap(html: String, original: Boolean = false): String {
    val cleaned = html.replace(unsafeElements, "").replace(mailViewport, "")
    val design = designWidth(cleaned)
    val deviceWidth = "width=device-width, initial-scale=1"
    val (viewport, body, css) = when {
        design == null -> Triple(deviceWidth, cleaned, ReaderCss)
        original -> Triple("width=${design + 16}", cleaned, ReaderCss)
        else -> Triple(deviceWidth, fluid(cleaned, design), ReaderCss + MobileCss)
    }
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="$viewport">
<style>$css</style></head><body>$body</body></html>"""
}

/** Ob die Mobil-Ansicht die Mail verändert, sich ein Blick aufs Original also lohnt. */
private fun hasFixedLayout(html: String) = designWidth(html) != null

private val webLink = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

/** Reiner Mail-Text als HTML: Zeilenumbrüche bleiben, Links werden antippbar. */
fun plainTextHtml(text: String): String {
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    // Links im Rohtext suchen, sonst endeten sie z. B. bei <https://…> mitten in „&gt;“
    val html = StringBuilder()
    var last = 0
    webLink.findAll(text).forEach { link ->
        html.append(escape(text.substring(last, link.range.first)))
        val url = escape(link.value)
        html.append("""<a href="$url">$url</a>""")
        last = link.range.last + 1
    }
    html.append(escape(text.substring(last)))
    return """<div style="white-space: pre-wrap">$html</div>"""
}

/**
 * Ob externe Bilder eines Absenders immer geladen werden, und wie man das
 * ändert. Kommt vom ViewModel, damit die Wahl gespeichert bleibt.
 */
class RemoteImagePolicy(
    val isTrusted: (address: String) -> Boolean,
    val setTrusted: (address: String, trusted: Boolean) -> Unit,
)

/**
 * HTML-Mail in einer abgesicherten WebView: kein JavaScript, kein Zugriff auf
 * Dateien, Links öffnen im Browser. Externe Inhalte bleiben wie bei FairEmail
 * gesperrt, weil sie oft als Zählpixel dienen. Freigeben lassen sie sich
 * einmal für diese Mail oder dauerhaft für den Absender.
 */
@Composable
fun HtmlBody(
    html: String,
    key: String,
    senderName: String,
    senderAddress: String,
    imagePolicy: RemoteImagePolicy,
    modifier: Modifier = Modifier,
) {
    var loadedOnce by rememberSaveable(key) { mutableStateOf(false) }
    val trusted = senderAddress.isNotBlank() && imagePolicy.isTrusted(senderAddress.lowercase())
    val loadRemote = loadedOnce || trusted
    val hasRemote = remember(html) { remoteContent.containsMatchIn(html) }
    val document = remember(html) { wrap(html) }
    val fixedLayout = remember(html) { hasFixedLayout(html) }
    var showOriginal by remember(key) { mutableStateOf(false) }
    val who = senderName.ifBlank { senderAddress }

    Column(modifier) {
        if (hasRemote && !loadRemote) {
            Column(
                Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SearchField)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("Externe Bilder blockiert", fontSize = 14.sp, color = AppColors.SecondaryLabel)
                Row(Modifier.padding(top = 8.dp)) {
                    BarButton("Einmal laden") { loadedOnce = true }
                    if (senderAddress.isNotBlank()) {
                        Spacer(Modifier.width(20.dp))
                        // Ohne Namen, der wäre bei Absendern wie „Firma AG - Slogan“ abgeschnitten
                        BarButton("Immer für diesen Absender", Modifier.weight(1f, fill = false)) {
                            imagePolicy.setTrusted(senderAddress, true)
                        }
                    }
                }
            }
        } else if (hasRemote && trusted) {
            // Leiser Hinweis, damit eine dauerhafte Freigabe nicht vergessen wird
            Row(
                Modifier.padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Bilder von $who werden immer geladen · ",
                    fontSize = 13.sp,
                    color = AppColors.SecondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "Widerrufen",
                    fontSize = 13.sp,
                    color = AppColors.Blue,
                    modifier = Modifier.clickable {
                        loadedOnce = false
                        imagePolicy.setTrusted(senderAddress, false)
                    },
                )
            }
        }
        if (fixedLayout) {
            Text(
                "Original ansehen",
                fontSize = 13.sp,
                color = AppColors.Blue,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 8.dp)
                    .clickable { showOriginal = true },
            )
        }
        MailWebView(document, loadRemote, Modifier.fillMaxWidth())
    }

    if (showOriginal) {
        OriginalDialog(html, loadRemote, onClose = { showOriginal = false })
    }
}

/**
 * Die Mail unverändert in ihrer Design-Breite, bildschirmfüllend. Hier scrollt
 * und zoomt die WebView selbst wie ein Browser, statt in der Thread-Liste zu
 * stecken, in der sie mit dem Scrollen der Liste um die Gesten konkurriert.
 */
@Composable
private fun OriginalDialog(html: String, loadRemote: Boolean, onClose: () -> Unit) {
    val document = remember(html) { wrap(html, original = true) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AppColors.Cell),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Original", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "Fertig",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Blue,
                    modifier = Modifier.clickable(onClick = onClose),
                )
            }
            MailWebView(document, loadRemote, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.Blue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun MailWebView(document: String, loadRemote: Boolean, modifier: Modifier) {
    // Die Bilderfreigabe wird beim Erzeugen der WebView gesetzt: Eine einmal
    // gesperrte WebView lädt nach dem Umschalten nicht zuverlässig nach.
    // Deshalb bei einer Änderung eine neue WebView statt umzuschalten.
    key(loadRemote) {
        AndroidView(
            factory = { context -> createMailWebView(context, loadRemote) },
            update = { webView ->
                // Nur neu laden, wenn sich der Inhalt ändert, nicht bei jeder Neuzusammensetzung
                val state = document.hashCode()
                if (webView.tag != state) {
                    webView.tag = state
                    webView.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier,
        )
    }
}

private fun createMailWebView(context: android.content.Context, loadRemote: Boolean) =
    WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = !loadRemote
        // Für Mails mit fester Breite (siehe wrap): auf Bildschirmbreite verkleinern
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // Zoomen mit zwei Fingern immer erlauben, auch wenn die Mail es per
        // Viewport verbieten wollte (die Angaben der Mail fliegen in wrap raus)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.TRANSPARENT)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                    // z. B. ein Link-Schema ohne passende App
                }
                return true
            }
        }
    }
