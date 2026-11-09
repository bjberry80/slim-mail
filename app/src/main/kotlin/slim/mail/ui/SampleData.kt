package slim.mail.ui

// Beispieldaten aus dem Entwurf v0.1, nur noch für die Compose-Vorschauen

val sampleMessages = listOf(
    Message(1, "Anna Weber", "15:02", "Treffen am Dienstag",
        "Hallo, passt dir Dienstag um 18 Uhr? Ich bringe die Unterlagen mit und wir gehen alles einmal durch.",
        unread = true),
    Message(2, "Paketzustellung", "13:47", "Deine Sendung kommt morgen",
        "Zustellung voraussichtlich zwischen 10 und 14 Uhr. Sendungsverfolgung in der App.",
        unread = true),
    Message(3, "Thomas Klein", "Gestern", "Re: Angebot Werkstatt",
        "Danke für die schnelle Rückmeldung. Anbei das überarbeitete Angebot mit den neuen Positionen.",
        threadCount = 4, hasAttachment = true),
    Message(4, "Kalender", "Gestern", "Einladung: Projektrunde",
        "Mittwoch, 21. Oktober, 10:00 bis 11:00. Bitte zu- oder absagen."),
    Message(5, "Vereinsnewsletter", "Freitag", "Termine im November",
        "Offene Werkstatt jeden Donnerstag ab 18 Uhr"),
    Message(6, "Jana Schulte", "16.10.26", "Fotos vom Wochenende",
        "Hier die versprochenen Bilder, die besten sind im Anhang.",
        hasAttachment = true),
)

val sampleThread = listOf(
    ThreadMessage("Thomas Klein", "TK", "14.10.",
        "Wie besprochen schicke ich dir das erste Angebot für die Werkstatt."),
    ThreadMessage("Ich", "Ich", "15.10.",
        "Danke! Können wir die Beleuchtung noch als eigene Position aufnehmen?"),
    ThreadMessage("Thomas Klein", "TK", "16.10.",
        "Klar, ich rechne das durch und melde mich bis morgen."),
    ThreadMessage("Thomas Klein", "TK", "17.10.",
        "Hallo,\n\ndanke für die schnelle Rückmeldung. Anbei das überarbeitete Angebot mit den " +
            "neuen Positionen. Den Termin für die Montage können wir flexibel legen.\n\n" +
            "Viele Grüße\nThomas",
        attachments = listOf(AttachmentRef(4, listOf(1), "Angebot_Werkstatt_v2.pdf", "application/pdf", 245_000)),
        longDate = "Gestern, 17:20",
        quoteDate = "17.10.2026 um 17:20"),
)

val sampleFolders = listOf(
    MailFolder("INBOX", "Eingang", FolderRole.Inbox, 2),
    MailFolder("", "Markiert", FolderRole.Flagged, 1),
    MailFolder("Drafts", "Entwürfe", FolderRole.Drafts, 3),
    MailFolder("Sent", "Gesendet", FolderRole.Sent),
    MailFolder("Junk", "Werbung", FolderRole.Junk),
    MailFolder("Trash", "Papierkorb", FolderRole.Trash),
    MailFolder("Archive", "Archiv", FolderRole.Archive),
    MailFolder("Projekte", "Projekte", FolderRole.Other),
    MailFolder("Rechnungen", "Rechnungen", FolderRole.Other),
)
