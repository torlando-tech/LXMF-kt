plugins {
    kotlin("jvm")
    `maven-publish`
}

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}

val coroutinesVersion: String by project
val junitVersion: String by project
val kotestVersion: String by project

dependencies {
    // Depend on rns-core for Reticulum functionality
    implementation("com.github.torlando-tech.reticulum-kt:rns-core:v0.0.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Test dependencies for live networking tests
    testImplementation("com.github.torlando-tech.reticulum-kt:rns-interfaces:v0.0.3")

    // MessagePack for serialization (already in rns-core, but explicit)
    implementation("org.msgpack:msgpack-core:0.9.8")

    // Logging — SLF4J API only; consumers supply their own binding
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")

    // Interop testing - reuse Python bridge infrastructure from rns-test
    testImplementation("com.github.torlando-tech.reticulum-kt:rns-test:v0.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Compression - Apache Commons Compress for BZ2 interop tests
    testImplementation("org.apache.commons:commons-compress:1.26.0")
}
