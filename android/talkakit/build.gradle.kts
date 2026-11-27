// TalkaKit en Kotlin : le cœur de l'application (sans interface), le même
// que l'iPhone (ios/Sources/TalkaKit), vérifié contre les vecteurs du
// navigateur (ios/Tests/TalkaKitTests/vectors.json).
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Du bytecode Java 17 (celui d'Android), quel que soit le JDK qui compile.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":brocolis"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    // SQLite pour les tests JVM (sur Android : `android.database.sqlite`, branché par l'application).
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    // Un faux relais WebSocket pour les tests du relais.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    // Les vecteurs produits par le navigateur, partagés avec l'iPhone.
    systemProperty("talka.vectors", rootProject.file("../ios/Tests/TalkaKitTests/vectors.json").absolutePath)
}
