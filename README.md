# Slim.Mail

Ein schlanker Android-Mail-Client mit ruhiger, aufgeräumter Bedienung, für eigene IMAP/SMTP-Postfächer (entwickelt und getestet mit mailbox.org). Lizenz: [GPL-3.0](LICENSE). Paket: `slim.mail`.

Stand: 09.11.2026, Version 0.1.3 (Testphase, noch nicht im Play Store).

## Funktionen

- Postfächer und Ordner, Liste nur aus Kopfzeilen (schnell auch bei großen Postfächern), Vorschauen im Hintergrund
- Unterhaltungen als Threads, Antworten, Weiterleiten mit Anhängen, Verfassen mit Anhängen (bis 25 MB)
- Wischen: rechts Löschen, links Mehr / Markieren / Archivieren; „Rückgängig“ 5 Sekunden lang
- Push per IMAP IDLE im Vordergrunddienst; Benachrichtigung öffnet die Mail, Knopf „Löschen“
- HTML-Mails in abgesicherter WebView: kein JavaScript, externe Bilder gesperrt (Freigabe einmal oder pro Absender)
- Mobil-Ansicht für Newsletter mit fester Breite, „Original ansehen“ im Vollbild mit Zoom
- Einrichtung über Autoconfig, Passwort mit dem Android Keystore verschlüsselt, keine Tracker oder Analyse-Bibliotheken

## Technik

- Kotlin, Jetpack Compose, Android Gradle Plugin 9, minSdk 26, targetSdk 36
- IMAP/SMTP: [Angus Mail](https://github.com/eclipse-ee4j/angus-mail) (Jakarta Mail)
- Wichtige Dateien: `ui/MailViewModel.kt` (Zustand, Cache), `data/MailRepository.kt` (IMAP/SMTP), `ui/HtmlBody.kt` (Mail-Darstellung), `sync/` (Push, Benachrichtigungen)
- Als Referenz für IMAP-Details diente [FairEmail](https://github.com/M66B/FairEmail) (ebenfalls GPL-3.0)

## Bauen

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

- Build-Ausgabe liegt außerhalb des Projektordners: `%LOCALAPPDATA%\SlimMailBuild` (änderbar mit der Variable `SLIMMAIL_BUILD_ROOT`)
- Release-Signatur: `release\keystore.properties` und der Schlüssel in `release\`. Der Ordner ist nicht in Git und darf nicht weitergegeben werden. Ohne ihn lassen sich keine Updates über bestehende Installationen spielen. Ohne ihn baut nur die Debug-Version.
- Test-APKs: `release\SlimMail-<Version>.apk`, vorher `versionCode` und `versionName` in `app\build.gradle.kts` erhöhen

## Offene Punkte

Siehe [offen.md](offen.md).
