import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives OUTSIDE the repository (COK-22): credentials are read from
// <user.home>/.cookies-extractor/keystore.properties (store.file absolute path,
// store.password, key.alias, key.password). Debug builds and the test suite never
// touch it; release builds fail loudly when the file is missing or incomplete, so no
// unsigned artifact can silently ship (COK-22 review).
val keystorePropertiesFile = file(System.getProperty("user.home") + "/.cookies-extractor/keystore.properties")
val releaseKeystore = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseCredentials = !releaseKeystore.isEmpty() &&
    listOf("store.file", "store.password", "key.alias", "key.password")
        .all { !releaseKeystore.getProperty(it).isNullOrBlank() }

// Fails loudly when the file exists but a key is missing or blank.
fun releaseCredential(key: String): String =
    releaseKeystore.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("$keystorePropertiesFile is missing or has a blank '$key'")

android {
    namespace = "com.cookiesextractor.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cookiesextractor.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseCredentials) {
            create("release") {
                // a relative store.file resolves against the properties file's directory
                storeFile = keystorePropertiesFile.parentFile.resolve(releaseCredential("store.file"))
                storePassword = releaseCredential("store.password")
                keyAlias = releaseCredential("key.alias")
                keyPassword = releaseCredential("key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseCredentials) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Release builds must never ship unsigned (COK-22 review): a validator task that
    // only release build tasks depend on fails at execution time with the recovery
    // hint, while every other invocation (help, assembleDebug, tests) stays green.
    val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
        doLast {
            check(hasReleaseCredentials) {
                "release build requires $keystorePropertiesFile (absolute store.file, " +
                    "store.password, key.alias, key.password); see README, Release signing"
            }
        }
    }
    tasks.matching { it.name in setOf("assembleRelease", "bundleRelease") }.configureEach {
        dependsOn(validateReleaseCredentials)
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // the real org.json on the test classpath shadows the android.jar stub, so
    // MonitorAlerts' JSON parsing is unit-testable (COK-26)
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
