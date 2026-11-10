import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun signingValue(envName: String, propertyName: String): String? {
    val fromEnv = System.getenv(envName)?.takeIf { it.isNotBlank() }
    if (fromEnv != null) {
        return fromEnv
    }
    return keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStoreFile).isFile

android {
    namespace = "app.familycloud"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.familycloud"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
