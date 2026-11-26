// Shared, platform-neutral protocol, crypto and domain logic.
// Pure Kotlin/JVM: no Android APIs, no networking, no telemetry.
plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    `java-library`
}

group = "studio.virgule.pol"
version = "0.1.0"

// Bytecode level 17 so Android (D8) and any JDK 17+ can consume it.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // Opus (RFC 6716) in pure Java, bit-compatible with libopus: wideband "HD voice" for call audio.
    api("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
