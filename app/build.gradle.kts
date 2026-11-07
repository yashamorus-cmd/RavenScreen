import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Власний ключ підпису: шлях і паролі лежать у keystore.properties (його немає в git).
// Якщо файлу немає (напр. на іншому комп'ютері), release підписується debug-ключем — збірка не ламається.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.ravenscreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ravenscreen"
        minSdk = 26
        targetSdk = 37
        // Збільшувати при кожному новому APK, який даєш іншим, — інакше Android може не оновити.
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Оновлення встановлюються поверх старої версії лише з тим самим підписом,
            // тому для APK, який даєш іншим, потрібен свій постійний ключ, а не debug.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}