// app/build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.tv.az.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.tv.az.player"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // NÃO incluir buildFeatures { compose = true }
    // NÃO incluir composeOptions
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ExoPlayer para reprodução de vídeo (versão mais recente com melhor suporte offline)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")

    // Coroutines para operações assíncronas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp para requisições HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Leanback para Android TV
    implementation("androidx.leanback:leanback:1.0.0")

    // Custom Chrome Tabs para melhor renderização HTML
    implementation("androidx.browser:browser:1.7.0")

    // Chromium WebView moderno para melhor compatibilidade
    implementation("androidx.webkit:webkit:1.8.0")
}