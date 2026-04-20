/*
 * ============================================================================
 *  build.gradle.kts (ROOT LEVEL) — THE PROJECT'S MASTER RECIPE
 * ============================================================================
 *
 *  WHY THIS FILE EXISTS:
 *  ---------------------
 *  This is the TOP-LEVEL build file. It applies to the ENTIRE project,
 *  not just one module. Think of it as the "company policy" that all
 *  departments (modules) must follow.
 *
 *  HOW IT WORKS:
 *  -------------
 *  The `plugins` block declares which build tools we need, but `apply false`
 *  means "download them but don't activate them here — let each module
 *  decide if it needs them." This is a best practice so the root file
 *  stays clean.
 *
 *  IMPORTANCE:
 *  -----------
 *  Without this, Gradle won't know which version of the Android or Kotlin
 *  plugin to use. It's the version control center.
 * ============================================================================
 */

plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
