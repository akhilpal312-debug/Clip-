plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreFile = rootProject.file("keystore/release.jks")
val keystoreExists = keystoreFile.exists()

android {
    namespace = "com.aiclipcreator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aiclipcreator.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += (project.findProperty("abi") ?: "arm64-v8a").toString().split(",")
        }
    }

    signingConfigs {
        if (keystoreExists) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("AICLIP_KEYSTORE_PASSWORD") ?: "aiclipcreator"
                keyAlias = System.getenv("AICLIP_KEY_ALIAS") ?: "aiclipcreator"
                keyPassword = System.getenv("AICLIP_KEY_PASSWORD") ?: "aiclipcreator"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreExists) signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*")
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-models"))
    implementation(project(":core-data"))
    implementation(project(":core-ai"))
    implementation(project(":core-video"))
    implementation(project(":feature-home"))
    implementation(project(":feature-video-import"))
    implementation(project(":feature-live-monitor"))
    implementation(project(":feature-analysis"))
    implementation(project(":feature-clips"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-model-manager"))
    implementation(project(":feature-settings"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
