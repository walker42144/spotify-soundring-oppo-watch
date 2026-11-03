plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "local.watchremote"
    compileSdk = 36
    defaultConfig { applicationId = "local.watchremote"; minSdk = 30; targetSdk = 30; versionCode = 11; versionName = "1.0.8"; testInstrumentationRunner = "local.watchremote.ValidationInstrumentation" }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("release") {
            // Local builds use the builder's Android debug key; no private signing key is distributed.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    // Personal sideload for the inspected Android 11 watch; not a Google Play release.
    lint { disable += "ExpiredTargetSdkVersion" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
