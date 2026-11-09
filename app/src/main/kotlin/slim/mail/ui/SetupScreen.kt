package slim.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import kotlinx.coroutines.launch
import slim.mail.data.Autoconfig
import slim.mail.data.ServerSettings

private val ErrorRed = Color(0xFFD70015)

/**
 * Anmeldung als gruppierte Liste mit Name,
 * E-Mail und Passwort. Die Server sucht die App selbst (siehe [Autoconfig]);
 * unter „Erweitert“ lassen sie sich ansehen und von Hand eintragen.
 * [onSignIn] prüft die Daten am Server und liefert bei Erfolg null, sonst
 * eine Fehlermeldung.
 */
@Composable
fun SetupScreen(
    onSignIn: suspend (name: String, email: String, password: String, servers: ServerSettings) -> String?,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Das Passwort bewusst nicht in rememberSaveable, damit es nicht im
    // gespeicherten Zustand der Activity landet
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Serverfelder unter „Erweitert“. [manual]: von Hand geändert, dann nicht
    // mehr automatisch suchen und überschreiben
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var imapHost by rememberSaveable { mutableStateOf("") }
    var imapPort by rememberSaveable { mutableStateOf("993") }
    var smtpHost by rememberSaveable { mutableStateOf("") }
    var smtpPort by rememberSaveable { mutableStateOf("465") }
    var smtpStartTls by rememberSaveable { mutableStateOf(false) }

    fun fill(servers: ServerSettings) {
        imapHost = servers.imapHost
        imapPort = servers.imapPort.toString()
        smtpHost = servers.smtpHost
        smtpPort = servers.smtpPort.toString()
        smtpStartTls = servers.smtpStartTls
    }

    val canSubmit = !busy && email.contains('@') && password.isNotEmpty()
    fun submit() {
        if (!canSubmit) return
        focusManager.clearFocus()
        busy = true
        error = null
        scope.launch {
            val servers = if (manual && imapHost.isNotBlank() && smtpHost.isNotBlank()) {
                ServerSettings(
                    imapHost = imapHost.trim(),
                    imapPort = imapPort.toIntOrNull() ?: 993,
                    smtpHost = smtpHost.trim(),
                    smtpPort = smtpPort.toIntOrNull() ?: if (smtpStartTls) 587 else 465,
                    smtpStartTls = smtpStartTls,
                )
            } else {
                status = "Server werden gesucht …"
                (Autoconfig.discover(email.trim()) ?: ServerSettings.guess(email.trim())).also(::fill)
            }
            status = "Anmeldung bei ${servers.imapHost} …"
            error = onSignIn(name.trim(), email.trim(), password, servers)
            status = null
            busy = false
            // Bei einem Fehler zeigen, welche Server versucht wurden, damit man sie korrigieren kann
            if (error != null) showAdvanced = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.GroupedBackground)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Konto hinzufügen",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 48.dp),
        )
        Text(
            "Melde dich mit deiner E-Mail-Adresse an. Die Server sucht Slim.Mail selbst; " +
                "dafür wird nur die Domain deiner Adresse beim Anbieter bzw. in der " +
                "Anbieterdatenbank von Thunderbird nachgeschlagen.",
            fontSize = 15.sp,
            color = AppColors.SecondaryLabel,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Cell),
        ) {
            SetupField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "Vor- und Nachname",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                thickness = 0.5.dp,
                color = AppColors.Separator,
            )
            SetupField(
                label = "E-Mail",
                value = email,
                onValueChange = {
                    // Andere Domain: automatisch gefundene Server passen nicht mehr
                    if (!manual && it.substringAfter('@') != email.substringAfter('@')) imapHost = ""
                    email = it
                },
                placeholder = "name@beispiel.de",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                thickness = 0.5.dp,
                color = AppColors.Separator,
            )
            SetupField(
                label = "Passwort",
                value = password,
                onValueChange = { password = it },
                placeholder = "Erforderlich",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        Text(
            "Der Name erscheint als Absender deiner Mails. " +
                "Wenn du für dein Konto die Zwei-Faktor-Anmeldung nutzt (z. B. bei Gmail), " +
                "brauchst du ein eigenes Passwort für Mail-Apps aus den Einstellungen deines Anbieters.",
            fontSize = 13.sp,
            color = AppColors.SecondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        Text(
            if (showAdvanced) "Erweitert ausblenden" else "Erweitert",
            fontSize = 15.sp,
            color = AppColors.Blue,
            modifier = Modifier
                .padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
                .clickable { showAdvanced = !showAdvanced },
        )
        if (showAdvanced) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Cell),
            ) {
                val edit = { update: () -> Unit -> manual = true; update() }
                SetupField(
                    label = "IMAP",
                    value = imapHost,
                    onValueChange = { edit { imapHost = it } },
                    placeholder = "wird gesucht",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                AdvancedDivider()
                SetupField(
                    label = "Port",
                    value = imapPort,
                    onValueChange = { edit { imapPort = it.filter(Char::isDigit) } },
                    placeholder = "993",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                AdvancedDivider()
                SetupField(
                    label = "SMTP",
                    value = smtpHost,
                    onValueChange = { edit { smtpHost = it } },
                    placeholder = "wird gesucht",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                AdvancedDivider()
                SetupField(
                    label = "Port",
                    value = smtpPort,
                    onValueChange = { edit { smtpPort = it.filter(Char::isDigit) } },
                    placeholder = if (smtpStartTls) "587" else "465",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                AdvancedDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SMTP mit STARTTLS", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = smtpStartTls,
                        onCheckedChange = { on ->
                            edit {
                                smtpStartTls = on
                                // Übliche Ports mitziehen, eigene Werte stehen lassen
                                if (on && smtpPort == "465") smtpPort = "587"
                                if (!on && smtpPort == "587") smtpPort = "465"
                            }
                        },
                    )
                }
            }
            Text(
                "IMAP immer mit SSL. SMTP mit SSL (meist Port 465) oder STARTTLS (meist 587). " +
                    "Leer lassen, damit Slim.Mail die Server selbst sucht.",
                fontSize = 13.sp,
                color = AppColors.SecondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        status?.let {
            Text(
                it,
                fontSize = 15.sp,
                color = AppColors.SecondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }

        error?.let {
            Text(
                it,
                fontSize = 15.sp,
                color = ErrorRed,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }

        Box(
            Modifier
                .padding(top = 32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (canSubmit) AppColors.Blue else AppColors.Separator)
                .clickable(enabled = canSubmit, onClick = ::submit)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(
                    "Anmelden",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AdvancedDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = AppColors.Separator,
    )
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 17.sp, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 17.sp, color = AppColors.SecondaryLabel)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
                cursorBrush = SolidColor(AppColors.Blue),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun SetupScreenPreview() {
    SlimTheme { SetupScreen(onSignIn = { _, _, _, _ -> null }) }
}
