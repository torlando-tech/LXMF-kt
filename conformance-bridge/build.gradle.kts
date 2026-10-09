plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val coroutinesVersion: String by project

dependencies {
    implementation(project(":lxmf-core"))
    // Match the rns-core/rns-interfaces version :lxmf-core depends on
    // (lxmf-core/build.gradle.kts:23). Pin the explicit tag, not a
    // floating ref: the old `main-SNAPSHOT` here was resolved down to
    // lxmf-core's explicit pin by Gradle's conflict resolution, so the
    // bridge silently shipped a 4-month-old rns-core (v0.0.22, June 13)
    // while the conformance suite claimed to test current main - masking
    // the kotlin->python opportunistic-ack flake that the v0.0.22
    // receipt/transmit path (pre-8188f8d) reproduces under CPU load.
    implementation("com.github.torlando-tech.reticulum-kt:rns-core:v0.0.23")
    implementation("com.github.torlando-tech.reticulum-kt:rns-interfaces:v0.0.23")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.json:json:20231013")

    // msgpack-core for the byte-level lxmf_decode_bytes command, which
    // operates directly on the wire format rather than going through
    // the LXMessage class. Same version as :lxmf-core uses internally.
    implementation("org.msgpack:msgpack-core:0.9.8")

    // Logging — use slf4j-simple but at WARN by default so RNS chatter
    // doesn't interleave with the JSON-RPC stdout protocol.
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("network.reticulum.lxmf.conformance.MainKt")
}

tasks {
    shadowJar {
        // Produce build/libs/LXMFConformanceBridge.jar (no version suffix).
        archiveBaseName.set("LXMFConformanceBridge")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes["Main-Class"] = "network.reticulum.lxmf.conformance.MainKt"
        }
        // SLF4J simple at WARN to keep stdout clean for JSON-RPC.
        // The bridge sets these at runtime too, but pinning here is belt-and-suspenders.
        mergeServiceFiles()
    }

    // `gradle build` in this subproject should produce the runnable shadow jar.
    build {
        dependsOn(shadowJar)
    }

    // Disable the plain jar to avoid confusing developers about which artifact runs.
    named<Jar>("jar") {
        enabled = false
    }
}
