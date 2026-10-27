import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
}

// The API address and Google client id are read from local.properties (git-ignored) or the
// environment, so they never end up in version control. Missing values degrade gracefully at
// runtime: the app still works fully offline, it just skips the remote catalogue sync.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun buildSecret(key: String, fallback: String = ""): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: fallback

android {
    namespace = "com.aniruddha81.gaalifinderv2"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aniruddha81.gaalifinderv2"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp.arg("room.schemaLocation", "$projectDir/schemas")

        // Base URL of the Gaali API, e.g. https://api.example.com (no trailing slash needed).
        buildConfigField("String", "API_BASE_URL", "\"${buildSecret("API_BASE_URL")}\"")
        // OAuth *Web* client id from the gaali-finder Google Cloud project; Credential Manager
        // asks Google for an ID token addressed to it, and the API checks that audience.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${buildSecret("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {

    // One BOM, applied to both the main and the androidTest classpaths — declared once here so
    // lint does not read the two platform() calls below as a duplicated dependency.
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // ui-test-junit4 is versioned by the BOM, so the androidTest classpath needs it too.
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.paging.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose) // Hilt ViewModel Integration ( hiltViewModel() )

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.messaging)

}