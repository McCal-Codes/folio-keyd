plugins {
    id("com.android.application")
}

// Semantic version, same rule as Folio: versionCode = MAJOR * 10000 + MINOR * 100 + PATCH.
val keysVersion = "0.1.0"

android {
    namespace = "com.mccal.folio.keys"
    compileSdk = 36
    defaultConfig {
        // The id Folio's Market knows Keyd by, and names in its <queries> so it can tell Keyd is installed. It can
        // never change after the first release: Android treats a new id as a different app.
        applicationId = "com.mccal.keyd"
        minSdk = 31
        targetSdk = 36
        versionName = keysVersion
        versionCode = keysVersion.substringBefore('-').split('.')
            .let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
    }
    // Signed with the same key as Folio, from the same four variables, so there is one keystore to look after.
    // Without all four a release build comes out unsigned, which is fine for checking it and useless for shipping it.
    val releaseSigning = listOf(
        "FOLIO_RELEASE_STORE_FILE", "FOLIO_RELEASE_STORE_PASSWORD", "FOLIO_RELEASE_KEY_ALIAS", "FOLIO_RELEASE_KEY_PASSWORD",
    ).associateWith { System.getenv(it) }
    if (releaseSigning.values.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigning.getValue("FOLIO_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("FOLIO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("FOLIO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("FOLIO_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
