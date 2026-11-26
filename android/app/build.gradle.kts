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

    buildFeatures { compose = true }

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
    debugImplementation("androidx.compose.ui:ui-tooling")
}
