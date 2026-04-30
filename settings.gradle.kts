pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        // Local-iteration override: when the env var is set, mavenLocal
        // is consulted first so a `./gradlew :rns-core:publishToMavenLocal`
        // in a sibling reticulum-kt checkout is picked up without going
        // through JitPack. No-op when unset. Mirrors the dev-loop pattern
        // documented in Columba's settings.gradle.kts.
        if (System.getenv("LOCAL_RETICULUM_KT_VIA_MAVEN_LOCAL") != null) {
            mavenLocal()
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "lxmf-kt"

include(":lxmf-core")
include(":lxmf-examples")
// conformance-bridge uses the Shadow plugin which isn't compatible with
// Gradle 9 consumers via includeBuild. Opt-in only — set
// INCLUDE_CONFORMANCE_BRIDGE=1 when running the conformance suite.
// Skipped otherwise so a composite-build override from a Gradle-9
// consumer (Columba) works for fast iteration.
if (System.getenv("INCLUDE_CONFORMANCE_BRIDGE") != null) {
    include(":conformance-bridge")
}
