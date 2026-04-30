plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val coroutinesVersion: String by project

dependencies {
    implementation(project(":lxmf-core"))
    // Match the rns-core/rns-interfaces version :lxmf-core depends on.
    // Was pinned to v0.0.3 — stale; lxmf-core has been on v0.0.14 since
    // the rns-core bump in lxmf-core/build.gradle.kts:23. Mismatch caused
    // the bridge to ship an older Resource implementation than what the
    // tests against the bridge actually exercise.
    implementation("com.github.torlando-tech.reticulum-kt:rns-core:main-SNAPSHOT")
    implementation("com.github.torlando-tech.reticulum-kt:rns-interfaces:main-SNAPSHOT")

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
