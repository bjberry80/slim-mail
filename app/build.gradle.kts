import java.util.Properties

plugins {
    // Seit AGP 9 ist Kotlin im Android-Plugin eingebaut, kein kotlin-android nötig.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "slim.mail"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "slim.mail"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 5
        versionName = "0.2.0"
    }

    // Release-Schlüssel liegt in release/ (nicht im Git). Fehlt er, lässt sich
    // nur die Debug-Version bauen.
    val releaseKeys = rootProject.file("release/keystore.properties")
    signingConfigs {
        if (releaseKeys.exists()) {
            create("release") {
                val props = Properties().apply { releaseKeys.inputStream().use { load(it) } }
                storeFile = rootProject.file("release/" + props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Ohne R8: Angus Mail lädt seine Protokolle per ServiceLoader, das
            // bräuchte eigene Keep-Regeln. Für Testversionen reicht die volle Größe.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Angus Mail und Jakarta Activation bringen jeweils eigene Lizenzdateien mit
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)

    // IMAP/SMTP (Jakarta Mail), dieselbe Bibliotheksfamilie wie bei FairEmail
    implementation(libs.angus.mail)

    debugImplementation(libs.compose.ui.tooling)
}
