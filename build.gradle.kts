plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Das Projekt liegt in einem Nextcloud-Ordner. Der Sync greift sonst in die
// Build-Ausgaben ein, während Gradle schreibt, und lädt hunderte MB
// Zwischenstände hoch. Deshalb landen alle build/-Ordner lokal.
// Überschreibbar mit der Umgebungsvariable SLIMMAIL_BUILD_ROOT.
val buildRoot = System.getenv("SLIMMAIL_BUILD_ROOT")
    ?: "${System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")}/SlimMailBuild"
allprojects {
    layout.buildDirectory.set(file("$buildRoot/${if (path == ":") "root" else name}"))
}
