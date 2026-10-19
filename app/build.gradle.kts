plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.nuno.sintonia"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.nuno.sintonia"
        minSdk = 21          // Fire TV Stick antigos (Fire OS 5) incluídos
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"
    }

    // Chave própria no repositório: o APK compilado aqui, no Android Studio ou no
    // GitHub Actions tem sempre a mesma assinatura, por isso as atualizações instalam
    // por cima sem desinstalar. Manter o repositório PRIVADO.
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/sintonia.jks")
            storePassword = "sintonia2026"
            keyAlias = "sintonia"
            keyPassword = "sintonia2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.tukaani:xz:1.10") // guias .xml.xz (ex.: M3UPT)

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser real nos testes JVM
    testImplementation("org.json:json:20240303")   // ler os .expected.json de spec/
}
