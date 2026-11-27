// L'application Android de Talka : Jetpack Compose, au-dessus de `talkakit`.
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "talka.app"
    compileSdk = 35

    defaultConfig {
        // Le même identifiant que l'iPhone.
        applicationId = "com.maxlestage.talka"
        minSdk = 26
        targetSdk = 35
        // La CI donne le numéro de version (son numéro d'exécution).
        versionCode = (System.getenv("TALKA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("TALKA_VERSION_NAME") ?: "1.0"
        // Le relais : celui que la CI intègre (`TALKA_SERVER`), sinon le
        // relais officiel (`TalkaServer.official`), comme `TalkaServer` dans
        // l'Info.plist de l'iPhone. Rien à choisir dans l'application.
        val server = (System.getenv("TALKA_SERVER") ?: "").trim()
        buildConfigField("String", "TALKA_SERVER", "\"${server.replace("\"", "")}\"")
        // Les liens d'invitation (`https://<relais>/c/…`) s'ouvrent dans Talka.
        manifestPlaceholders["talkaHost"] = Regex("^https?://([^/:]+)").find(server)?.groupValues?.get(1)
            ?: "talka-maxlestage-e497dd868a8c.herokuapp.com"
    }

    // Signée par la clé du dépôt (secrets de la CI) si elle est là : une
    // nouvelle version s'installe alors par-dessus l'ancienne. Sinon, la
    // clé de débogage.
    val keystore = System.getenv("TALKA_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("talka") {
                storeFile = file(keystore)
                storePassword = System.getenv("TALKA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TALKA_KEY_ALIAS")
                keyPassword = System.getenv("TALKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore != null) signingConfigs.getByName("talka") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Les tests de la JVM avec Robolectric : SQLite d'Android, sans appareil.
    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }
}

dependencies {
    implementation(project(":talkakit"))
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // L'empreinte et le visage (`BiometricPrompt`, qui demande une `FragmentActivity`).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // Le fil principal d'Android comme fil du moteur (`Dispatchers.Main`).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Les appels audio et vidéo : WebRTC de Google (`org.webrtc`), compilé
    // pour Android et publié sur Maven Central par Stream (la même
    // bibliothèque que l'iPhone, `stasel/WebRTC`, à la même branche de
    // Chromium ou presque). Rien d'autre de Stream : ni service ni compte.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Les écrans dans les tests (Robolectric) : l'activité vide où ils se dessinent.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// L'image d'Android que Robolectric fait tourner, téléchargée par Gradle (qui
// réessaie et la garde en cache) plutôt que par Robolectric au milieu des tests.
val robolectricAndroid: Configuration by configurations.creating { isTransitive = false }
dependencies { robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
tasks.withType<Test>().configureEach {
    val dir = layout.buildDirectory.dir("robolectric").get().asFile
    inputs.files(robolectricAndroid)
    doFirst { project.copy { from(robolectricAndroid); into(dir) } }
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", dir.path)
}
