plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// SHARED TEST SIGNING KEY — every CI build is signed with this same key, so a new APK
// installs as an UPDATE instead of a conflicting install. That is what keeps grant state alive:
// overlay / notification-listener / accessibility grants are tied to the package, and only die
// when you UNINSTALL. Before this, each CI debug build used a throwaway debug keystore, which
// forced uninstall + reinstall + re-granting all four permissions on every test.
// Never use this key for a Play Store upload (it is committed to the repo, in plaintext passwords).
val hipTestStoreFile = rootProject.file("keystore/hyperisland-test.jks")

// Monotonic build id: CI run number, offset so it is always above the old hard-coded 3.
// The versionName now carries it, so you can tell which build is on the phone at a glance.
val hipBuildId: Int = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 1000

android {
    namespace = "com.hyperisland.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hyperisland.pro"
        minSdk = 33
        targetSdk = 35
        versionCode = hipBuildId
        versionName = "0.3.6-phase3.6+b$hipBuildId"
    }

    signingConfigs {
        create("testKey") {
            storeFile = hipTestStoreFile
            storePassword = "hyperisland-test"
            keyAlias = "hyperisland"
            keyPassword = "hyperisland-test"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("testKey")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Same key as release: debug <-> release swaps still install as updates during testing.
            signingConfig = signingConfigs.getByName("testKey")
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pure-JVM unit tests (app/src/test). No Robolectric: the logic under test — conversation
    // identity ranking, ring merge/cap — is deliberately Android-free so CI can actually run it.
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}
