plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Output file becomes RenexaMC-debug.aar / RenexaMC-release.aar
extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
    archivesName.set("RenexaMC")
}

android {
    namespace = "com.renexamc.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

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

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
