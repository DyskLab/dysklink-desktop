pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dysklink-desktop"

// The protocol and connection code shared with the phone app lives in DyskLab/dysklink-android, checked out as the
// android submodule (git clone --recursive, or git submodule update --init). DYSKLINK_ANDROID_DIR can name another
// checkout instead, to build against a core/ that is being changed there.
val androidDir = System.getenv("DYSKLINK_ANDROID_DIR")?.takeIf { it.isNotBlank() }?.let { file(it) } ?: file("android")
val coreDir = androidDir.resolve("core")
if (!coreDir.resolve("build.gradle.kts").exists()) {
    throw GradleException("$coreDir is missing. Run: git submodule update --init")
}
include(":core")
project(":core").projectDir = coreDir
include(":desktop")
