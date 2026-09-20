plugins {
    id("com.android.application")
}

// Semantic version, same rule as Folio: versionCode = MAJOR * 10000 + MINOR * 100 + PATCH.
val keysVersion = "0.1.0"

android {
    namespace = "com.mccal.folio.keys"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mccal.folio.keys"
        minSdk = 31
        targetSdk = 36
        versionName = keysVersion
        versionCode = keysVersion.substringBefore('-').split('.')
            .let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["appLabel"] = "Keyd"
        }
        // Its own app id, so a test build sits beside a release instead of replacing the keyboard you rely on.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Keyd Dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Robolectric runs the View on the JVM, which is the closest thing to typing on a phone that a laptop can offer.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// A keyboard has no business on the network, so it asks for no permissions and takes no networking libraries.
dependencies {
    // The one dependency: ExploreByTouchHelper, so TalkBack can find keys that are drawn rather than laid out.
    implementation("androidx.customview:customview:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
