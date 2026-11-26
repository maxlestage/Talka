// BrocolisProto en Kotlin : le même protocole, octet pour octet, que la crate
// Rust `brocolis-proto` (le navigateur) et son portage Swift (l'iPhone).
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
    // X25519, Ed25519, ML-KEM-768, Argon2id, HKDF : une bibliothèque auditée.
    api("org.bouncycastle:bcprov-jdk18on:1.82")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
