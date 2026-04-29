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
include(":conformance-bridge")
