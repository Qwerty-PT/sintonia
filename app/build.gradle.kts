import java.util.Properties

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
        versionCode = 13
        versionName = "1.12"
    }

    // Assinatura: a chave NÃO está no repositório (é público). Vem de:
    //  - local:  ficheiro keystore.properties na raiz (fora do Git) com storeFile/storePassword/keyAlias/keyPassword
    //  - GitHub Actions: segredos SINTONIA_KEYSTORE_BASE64 / SINTONIA_KEYSTORE_PASSWORD (ver release.yml)
    // Tem de ser sempre a mesma chave, senão as atualizações não instalam por cima.
    val signProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signValue(prop: String, env: String): String? =
        signProps.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    val storePath = signValue("storeFile", "SINTONIA_KEYSTORE_FILE")
    val hasSigning = storePath != null && file(storePath).exists()

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(storePath!!)
                storePassword = signValue("storePassword", "SINTONIA_KEYSTORE_PASSWORD")
                keyAlias = signValue("keyAlias", "SINTONIA_KEY_ALIAS") ?: "sintonia"
                keyPassword = signValue("keyPassword", "SINTONIA_KEY_PASSWORD")
                    ?: signValue("storePassword", "SINTONIA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sem chave: compila na mesma, mas o APK sai sem assinatura (não instala por cima do oficial)
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
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
