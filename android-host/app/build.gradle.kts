plugins { id("com.android.application") }
android {
    namespace = "pl.remotevpn.host"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.remotevpn.host"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
