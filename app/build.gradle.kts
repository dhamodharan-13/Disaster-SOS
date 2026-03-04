/*
 * ============================================================================
 *  app/build.gradle.kts — THE APP'S SHOPPING LIST
 * ============================================================================
 *
 *  WHY THIS FILE EXISTS:
 *  ---------------------
 *  This is THE most important build file. It tells Gradle:
 *    1. What Android version to target
 *    2. What is the app's unique ID (like a passport number)
 *    3. What libraries (dependencies) the app needs
 *
 *  Think of it as a shopping list: "To build this app, I need
 *  TensorFlow Lite, Material Design components, etc."
 *
 *  HOW IT WORKS:
 *  -------------
 *  - plugins {} → Activates the Android + Kotlin build plugins
 *  - android {} → Configures Android-specific settings
 *  - dependencies {} → Lists every library the app needs
 *
 *  IMPORTANCE:
 *  -----------
 *  If you forget a dependency, the code that uses it won't compile.
 *  If you set the wrong SDK version, the app won't install on older phones.
 * ============================================================================
 */

plugins {
    // NOW we activate the plugins (remember, the root file only registered them)
    id("com.android.application")        // "I am an Android app"
    id("org.jetbrains.kotlin.android")   // "I am written in Kotlin"
}

android {
    /*
     * NAMESPACE — This is your app's unique Java/Kotlin package name.
     * It MUST be globally unique across ALL Android apps in the world.
     * Convention: reverse domain name → com.innosence.disastersos
     * This prevents conflicts with other apps.
     */
    namespace = "com.innosence.disastersos"

    /*
     * COMPILE SDK — The Android API level used to COMPILE the app.
     * API 34 = Android 14. We compile against the latest to get
     * access to all modern features and APIs.
     */
    compileSdk = 34

    defaultConfig {
        /*
         * APPLICATION ID — The unique identifier for your app on Google Play
         * and on every phone. No two apps can have the same applicationId.
         */
        applicationId = "com.innosence.disastersos"

        /*
         * MIN SDK — The OLDEST Android version your app supports.
         * API 26 = Android 8.0 (Oreo). We chose this because:
         *   - Wi-Fi Aware (for mesh networking) was introduced in API 26
         *   - It covers ~95% of active Android devices
         *   - Any phone from 2017 or newer will work
         */
        minSdk = 26

        /*
         * TARGET SDK — The Android version you've TESTED against.
         * Should match compileSdk. This tells Android: "I've tested
         * my app on Android 14, so apply all the latest behavior."
         */
        targetSdk = 34

        // Version numbers — increment these when you release updates
        versionCode = 1         // Internal version (for Google Play)
        versionName = "1.0"     // Human-readable version (shown to users)
    }

    buildTypes {
        release {
            /*
             * MINIFY — When set to true, Gradle shrinks and obfuscates your code
             * for the release APK. This makes the app smaller and harder to
             * reverse-engineer. We keep it false during development for easier debugging.
             */
            isMinifyEnabled = false
        }
    }

    /*
     * COMPILE OPTIONS — We target Java 8 bytecode because:
     *   - It supports lambdas and modern Java features
     *   - It's compatible with all Android devices we support (API 26+)
     */
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /*
     * VIEW BINDING — A safer way to access UI elements from code.
     * Instead of the old `findViewById(R.id.button)` which can crash
     * if you misspell the ID, View Binding generates type-safe code
     * that guarantees the element exists. Much safer in a disaster app
     * where crashes are unacceptable.
     */
    buildFeatures {
        viewBinding = true
    }
}

/*
 * ============================================================================
 *  DEPENDENCIES — The App's Shopping List of Libraries
 * ============================================================================
 *
 *  Each line below says: "Download this library and include it in my app."
 *
 *  - implementation() → Include in the final APK (available at runtime)
 *  - testImplementation() → Only for unit tests (not in the final APK)
 * ============================================================================
 */
dependencies {
    // ──────────────────────────────────────────────────────────────────
    // CORE ANDROID LIBRARIES
    // ──────────────────────────────────────────────────────────────────

    // Kotlin extensions for Android — provides modern coroutine support
    implementation("androidx.core:core-ktx:1.12.0")

    // AppCompat — ensures your app looks consistent across all Android versions
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Design 3 — Google's beautiful, modern UI component library
    // Gives us styled buttons, cards, text fields, and the bottom navigation
    implementation("com.google.android.material:material:1.11.0")

    // ConstraintLayout — A powerful layout system that lets you position
    // UI elements relative to each other. Like a whiteboard where you
    // pin things using rules like "put this button below that text"
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // RecyclerView — Efficiently displays scrollable lists of data.
    // We use it for the SOS Alert Log (list of received alerts).
    // It "recycles" off-screen items to save memory — critical for
    // a disaster app where battery/memory must be conserved.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CardView — Material Design cards with shadows and rounded corners.
    // Each SOS alert in the log will be displayed as a card.
    implementation("androidx.cardview:cardview:1.0.0")

    // ──────────────────────────────────────────────────────────────────
    // TINYML — TENSORFLOW LITE (The "Smart Listener" AI Engine)
    // ──────────────────────────────────────────────────────────────────

    // TensorFlow Lite — The engine that runs our tiny AI model on the phone.
    // This is the CORE of Phase 2. It takes audio data, feeds it through
    // the neural network, and outputs: "85% chance this is a scream"
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // TF Lite Support Library — Helper utilities for processing audio/images
    // before feeding them into the model. Handles format conversion.
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ──────────────────────────────────────────────────────────────────
    // LOCATION (GPS)
    // ──────────────────────────────────────────────────────────────────

    // Google Play Services Location — The best way to get GPS coordinates.
    // Works WITHOUT internet. GPS satellites communicate directly with
    // the phone's antenna — no cell towers needed.
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // ──────────────────────────────────────────────────────────────────
    // JSON SERIALIZATION (For SOS Packets)
    // ──────────────────────────────────────────────────────────────────

    // Gson — Google's library for converting Kotlin objects to JSON strings
    // and back. We use it to serialize SOSPacket into a string that can
    // be sent over Wi-Fi Direct between phones.
    implementation("com.google.code.gson:gson:2.10.1")

    // ──────────────────────────────────────────────────────────────────
    // TESTING
    // ──────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
}
