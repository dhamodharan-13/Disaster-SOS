/*
 * ============================================================================
 *  settings.gradle.kts — THE PROJECT'S IDENTITY CARD
 * ============================================================================
 *
 *  WHY THIS FILE EXISTS:
 *  ---------------------
 *  Think of this as the "table of contents" for your entire project.
 *  When you open this project in Android Studio, Gradle (the build tool)
 *  reads THIS file FIRST to understand:
 *    1. What is this project called?
 *    2. Where should I download libraries (dependencies) from?
 *    3. Which modules (sub-projects) are part of this project?
 *
 *  HOW IT WORKS:
 *  -------------
 *  - pluginManagement {} → Tells Gradle where to find build plugins
 *  - dependencyResolutionManagement {} → Tells Gradle where to find libraries
 *  - rootProject.name → The project's display name
 *  - include(":app") → Says "there is a module called 'app' in this project"
 *
 *  IMPORTANCE:
 *  -----------
 *  Without this file, Android Studio won't even recognize this as a project.
 *  It's the very first thing Gradle reads.
 * ============================================================================
 */

pluginManagement {
    /*
     * These are the "stores" where Gradle looks for build plugins.
     * Think of plugins as power-ups that teach Gradle new tricks,
     * like "how to compile Kotlin" or "how to build an Android APK".
     */
    repositories {
        google()            // Google's repository — has Android-specific plugins
        mavenCentral()      // The biggest open-source Java/Kotlin library store
        gradlePluginPortal() // Official Gradle plugin directory
    }
}

dependencyResolutionManagement {
    /*
     * FAIL_ON_PROJECT_REPOS means: "Only use the repositories listed HERE,
     * don't let individual modules add their own." This keeps things clean
     * and predictable — every module downloads from the same places.
     */
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // For Android libraries (AppCompat, Material, etc.)
        mavenCentral()  // For everything else (TensorFlow Lite, JSON, etc.)
    }
}

// The name that appears in Android Studio's title bar
rootProject.name = "DisasterSOS"

// This line says: "Hey Gradle, there's a module called 'app' inside this project."
// A module is like a self-contained package of code. Most Android projects
// have exactly one module called "app" — that's the actual phone application.
include(":app")
