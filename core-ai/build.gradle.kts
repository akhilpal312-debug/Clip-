plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")

}
android {
    namespace = "com.aiclipcreator.core.ai"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}
dependencies {
    implementation(project(":core-models"))
    implementation(project(":core-common"))
    implementation(project(":core-network"))
    implementation(project(":core-storage"))
    implementation(project(":core-video"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Native inference runtimes (whisper.cpp + llama.cpp) are compiled with the NDK.
val nativeAi = (project.findProperty("nativeAi") ?: "true").toString().toBoolean()
val nativeLlm = (project.findProperty("nativeLlm") ?: "false").toString().toBoolean()
val abiFilter = (project.findProperty("abi") ?: "arm64-v8a").toString()

android {
    ndkVersion = "26.1.10909125"
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DAICLIP_WITH_LLM=" + if (nativeLlm) "ON" else "OFF",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-O2"
            }
        }
        ndk {
            abiFilters += abiFilter.split(",")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    if (!nativeAi) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp-disabled/CMakeLists.txt")
            }
        }
    }
    buildFeatures {
        prefab = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
