plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Baked into BuildConfig only when the release workflow passes them (-PreleaseTag=... on a tag
// build). A CI or locally built APK carries neither, and the in-app update checker treats that as
// "this build cannot say what release it is" rather than guessing — see GitHubReleaseUpdateSource.
val releaseTag: String = (project.findProperty("releaseTag") as? String).orEmpty()
val releasePublishedAt: String = (project.findProperty("releasePublishedAt") as? String).orEmpty()

android {
    namespace = "com.condorino.weekend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.condorino.weekend"
        // minSdk 26 gives us java.time (Instant/ZoneId/ZonedDateTime) without desugaring.
        // Correct time-zone maths is central to this app, so this is a deliberate choice.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha-01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // German is the default; English (US) is the second shipped language.
        resourceConfigurations += listOf("de", "en")

        buildConfigField("String", "RELEASE_TAG", "\"$releaseTag\"")
        buildConfigField("String", "RELEASE_PUBLISHED_AT", "\"$releasePublishedAt\"")
        // The repository the in-app update checker asks GitHub about. A fork only needs to change
        // these two lines to point the same feature at its own releases.
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"kevinluca1-ctrl\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"Condorino\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release variant is signed with the debug keystore so that CI can produce an
            // installable APK. Replace with a real keystore before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Room writes its schema JSON here so migrations can be diffed in review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
