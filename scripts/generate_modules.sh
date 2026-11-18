#!/bin/bash
# Generates the Android library build files + manifests for the AI Clip Creator modules.
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)

lib_module () {
  local name="$1" ns="$2" compose="$3" deps="$4"
  mkdir -p "$name/src/main"
  cat > "$name/build.gradle.kts" <<EOF
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
$([ "$compose" = "true" ] && echo '    id("org.jetbrains.kotlin.plugin.compose")')
}

android {
    namespace = "$ns"
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
        compose = $compose
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
$deps
}
EOF
  : > "$name/consumer-rules.pro"
  cat > "$name/src/main/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
EOF
  # remove empty lines left by unused compose plugin slot
  awk 'NF || !blank++' "$name/build.gradle.kts" > "$name/build.gradle.kts.tmp" && mv "$name/build.gradle.kts.tmp" "$name/build.gradle.kts"
}

COMPOSE_DEPS='    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("io.coil-kt:coil-compose:2.7.0")'

lib_module core-common com.aiclipcreator.core.common true "    implementation(project(\":core-models\"))
    implementation(\"androidx.core:core-ktx:1.13.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
$COMPOSE_DEPS"

lib_module core-database com.aiclipcreator.core.database false "    implementation(project(\":core-models\"))
    implementation(\"androidx.room:room-runtime:2.6.1\")
    implementation(\"androidx.room:room-ktx:2.6.1\")
    ksp(\"androidx.room:room-compiler:2.6.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    testImplementation(\"junit:junit:4.13.2\")"
# database module needs ksp plugin
python3 - <<'PY'
p='core-database/build.gradle.kts'
s=open(p).read()
s=s.replace('id("org.jetbrains.kotlin.android")','id("org.jetbrains.kotlin.android")\n    id("com.google.devtools.ksp")')
open(p,'w').write(s)
PY

lib_module core-storage com.aiclipcreator.core.storage false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(\"androidx.documentfile:documentfile:1.0.1\")
    implementation(\"androidx.core:core-ktx:1.13.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    testImplementation(\"junit:junit:4.13.2\")"

lib_module core-network com.aiclipcreator.core.network false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1\")
    testImplementation(\"junit:junit:4.13.2\")"

lib_module core-audio com.aiclipcreator.core.audio false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    testImplementation(\"junit:junit:4.13.2\")
    testImplementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1\")"

lib_module core-video com.aiclipcreator.core.video false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    implementation(\"androidx.core:core-ktx:1.13.1\")
    testImplementation(\"junit:junit:4.13.2\")"

lib_module core-ai com.aiclipcreator.core.ai false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(project(\":core-network\"))
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1\")
    implementation(\"org.tensorflow:tensorflow-lite:2.16.1\")
    implementation(\"com.microsoft.onnxruntime:onnxruntime-android:1.19.2\")
    testImplementation(\"junit:junit:4.13.2\")
    testImplementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1\")"
python3 - <<'PY'
p='core-ai/build.gradle.kts'
s=open(p).read()
s=s.replace('id("org.jetbrains.kotlin.android")','id("org.jetbrains.kotlin.android")\n    id("org.jetbrains.kotlin.plugin.serialization")')
extra = '''
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
                path = file("src/main/cpp/CMakeLists.disabled.txt")
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
'''
s = s + extra
open(p,'w').write(s)
PY

FEATURE_DEPS='    implementation(project(":core-common"))
    implementation(project(":core-models"))
    implementation(project(":core-data"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")'

lib_module feature-home com.aiclipcreator.feature.home true "$FEATURE_DEPS
$COMPOSE_DEPS"
lib_module feature-video-import com.aiclipcreator.feature.importvideo true "$FEATURE_DEPS
$COMPOSE_DEPS"
lib_module feature-live-monitor com.aiclipcreator.feature.live true "$FEATURE_DEPS
$COMPOSE_DEPS"
lib_module feature-analysis com.aiclipcreator.feature.analysis true "$FEATURE_DEPS
$COMPOSE_DEPS"
lib_module feature-clips com.aiclipcreator.feature.clips true "$FEATURE_DEPS
$COMPOSE_DEPS
    implementation(\"androidx.media3:media3-exoplayer:1.4.1\")
    implementation(\"androidx.media3:media3-ui:1.4.1\")"
lib_module feature-editor com.aiclipcreator.feature.editor true "$FEATURE_DEPS
$COMPOSE_DEPS
    implementation(\"androidx.media3:media3-exoplayer:1.4.1\")
    implementation(\"androidx.media3:media3-ui:1.4.1\")"
lib_module feature-model-manager com.aiclipcreator.feature.models true "$FEATURE_DEPS
$COMPOSE_DEPS"
lib_module feature-settings com.aiclipcreator.feature.settings true "$FEATURE_DEPS
$COMPOSE_DEPS"

lib_module core-data com.aiclipcreator.core.data false "    implementation(project(\":core-models\"))
    implementation(project(\":core-common\"))
    implementation(project(\":core-database\"))
    implementation(project(\":core-storage\"))
    implementation(project(\":core-network\"))
    implementation(project(\":core-audio\"))
    implementation(project(\":core-video\"))
    implementation(project(\":core-ai\"))
    implementation(\"androidx.datastore:datastore-preferences:1.1.1\")
    implementation(\"androidx.core:core-ktx:1.13.1\")
    implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1\")
    testImplementation(\"junit:junit:4.13.2\")
    testImplementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1\")"
python3 - <<'PY'
p='core-data/build.gradle.kts'
s=open(p).read()
s=s.replace('id("org.jetbrains.kotlin.android")','id("org.jetbrains.kotlin.android")\n    id("org.jetbrains.kotlin.plugin.serialization")')
open(p,'w').write(s)
PY

echo "GENERATED:"
ls -d */build.gradle.kts | wc -l
