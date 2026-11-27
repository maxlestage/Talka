// Talka pour Android : l'application native (Kotlin, Jetpack Compose).
//
// - `brocolis` : le protocole v3 (sessions à double cliquet, X25519 +
//   ML-KEM-768), portage Kotlin de BrocolisProto (la crate Rust du
//   navigateur), vérifié contre ses vecteurs ;
// - `talkakit` : le cœur de Talka (codes, chiffrement, relais, discussions),
//   portage Kotlin de TalkaKit (l'iPhone), vérifié contre les vecteurs du
//   navigateur ;
// - `app` : l'application Android ;
// - `wear` : l'application de la montre (Wear OS), qui passe par le
//   téléphone comme celle de l'Apple Watch passe par l'iPhone.
//
// `brocolis` et `talkakit` sont du Kotlin pour la JVM, sans Android : leurs
// tests tournent partout (`gradle :talkakit:test`).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        // Robolectric (les tests de l'app) depuis le miroir de Maven Central
        // tenu par Google : son image d'Android est lourde, et Maven Central
        // la refuse souvent aux machines partagées (429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}

rootProject.name = "talka-android"
include(":brocolis", ":talkakit")
// Les applications demandent le SDK Android : seulement s'il est là.
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":app", ":wear")
}
