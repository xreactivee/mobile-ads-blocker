plugins {
    id("com.android.application")
}

android {
    namespace = "app.adblocker"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.adblocker"
        minSdk = 29 // VpnService.Builder.setBlocking() needs Android 10+
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
