// Talka sur la montre (Wear OS) : les discussions récentes du téléphone,
// lues, répondues (vocal, dictée ou clavier, réponses rapides, emoji) et
// marquées comme lues depuis le poignet ; et le nombre de non-lus en
// complication. Le téléphone reste l'appareil Talka : la montre passe par
// lui (la couche de données de Wear OS, voir shared/…/WearProtocol.kt),
// comme l'Apple Watch par l'iPhone (ios/TalkaWatch).
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

/**
 * Ce que la montre reprend du cœur de Talka, compilé tel quel depuis
 * talkakit : le résumé, les actions et les widgets (WatchLink.kt,
 * WidgetData.kt) et le JSON de Talka (Json.kt) ; comme l'Apple Watch
 * reprend WatchLink.swift et WidgetData.swift (ios/project.yml). La montre
 * n'a ni clés ni relais : le reste du cœur (et son chiffrement) n'a rien à
 * faire dans son application.
 */
abstract class TalkaKitSources : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("talka/kit")
        outputDir.get().asFile.deleteRecursively()
        out.mkdirs()
        sources.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val talkaKit = tasks.register<TalkaKitSources>("talkaKitSources") {
    val kit = rootProject.file("talkakit/src/main/kotlin/talka/kit")
    sources.from(listOf("WatchLink.kt", "WidgetData.kt", "Json.kt").map { kit.resolve(it) })
    outputDir.set(layout.buildDirectory.dir("generated/talkakit"))
}

androidComponents {
    onVariants { variant -> variant.sources.java?.addGeneratedSourceDirectory(talkaKit, TalkaKitSources::outputDir) }
}

android {
    namespace = "talka.wear"
    compileSdk = 35

    defaultConfig {
        // Le même identifiant que l'application du téléphone : la couche de
        // données de Wear OS ne relie que deux applications du même nom,
        // signées de la même clé.
        applicationId = "com.maxlestage.talka"
        minSdk = 30
        targetSdk = 35
        // Les mêmes numéros que l'application du téléphone (la CI les donne).
        versionCode = (System.getenv("TALKA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("TALKA_VERSION_NAME") ?: "1.0"
    }

    // La même clé que l'application du téléphone (voir app/build.gradle.kts) :
    // celle du dépôt si elle est là, sinon la clé de débogage.
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

    buildFeatures { compose = true }

    // Ce que la montre partage avec l'application du téléphone : le lien
    // entre les deux (WearProtocol), les textes des complications.
    sourceSets["main"].kotlin.srcDir("../shared/kotlin")

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }
}

dependencies {
    // Le JSON de Talka (Json.kt, voir `talkaKitSources`).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Compose pour Wear OS.
    val wearCompose = "1.4.1"
    implementation("androidx.wear.compose:compose-material:$wearCompose")
    implementation("androidx.wear.compose:compose-foundation:$wearCompose")
    implementation("androidx.wear.compose:compose-navigation:$wearCompose")
    // Écrire : la dictée, le clavier ou l'écriture à la main de la montre (`TextFieldLink`).
    implementation("androidx.wear:wear-input:1.1.0")
    // Les complications du cadran (les widgets de la montre).
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    // Le lien avec le téléphone (WatchConnectivity sur l'Apple Watch).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

// L'image d'Android que Robolectric fait tourner (voir app/build.gradle.kts).
val robolectricAndroid: Configuration by configurations.creating { isTransitive = false }
dependencies { robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
tasks.withType<Test>().configureEach {
    val dir = layout.buildDirectory.dir("robolectric").get().asFile
    inputs.files(robolectricAndroid)
    doFirst { project.copy { from(robolectricAndroid); into(dir) } }
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", dir.path)
}
