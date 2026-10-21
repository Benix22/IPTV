plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.benix22.parrilla"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.benix22.parrilla"
        minSdk = 22
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
    }

    // Clave fija para que las actualizaciones se instalen encima de la versión anterior.
    // Es una clave de uso personal, no sirve para publicar en tiendas.
    signingConfigs {
        create("fija") {
            storeFile = file("../parrilla.keystore")
            storePassword = "parrilla"
            keyAlias = "parrilla"
            keyPassword = "parrilla"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug {
            signingConfig = signingConfigs.getByName("fija")
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
