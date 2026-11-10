plugins { id("com.android.application") }

android {
    namespace = "pl.remotevpn.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.remotevpn.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
