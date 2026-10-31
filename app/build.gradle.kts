plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.renexamc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.renexamc.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 49
        versionName = "1.5.9 fix"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("../renexamc.keystore")
            storePassword = "renexamc123"
            keyAlias = "renexamc"
            keyPassword = "renexamc123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":lib"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
}
