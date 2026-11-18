# Testing and verification

Everything below was executed in this workspace on 2026-11-17 (Linux, 2 vCPU, 3 GB RAM, no KVM).
Anything that needs an Android device or emulator is called out explicitly — this machine cannot run
one, so those items are **implemented but not device-tested**, never reported as passing.

## Toolchain actually used

| Component | Version |
| --- | --- |
| JDK | Temurin 17 |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin / KSP | 2.0.20 / 2.0.20-1.0.25 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| Native deps | whisper.cpp v1.7.4 (CMake FetchContent), llama.cpp b3982 (opt-in) |

## Checks that ran, with real results

| # | Check | Command | Result |
| --- | --- | --- | --- |
| 1 | Full Kotlin compile of all 17 modules | `./gradlew :app:compileDebugKotlin -PnativeAi=false` | **PASS** – `BUILD SUCCESSFUL` |
| 2 | JVM unit tests | `./gradlew :core-ai:testDebugUnitTest :core-audio:testDebugUnitTest` | **PASS** – 16 tests, 0 failures |
| 3 | Native AI build (NDK, whisper.cpp) | `./gradlew :app:assembleDebug` | **PASS** – `:core-ai:buildCMakeRelease[arm64-v8a]` succeeded |
| 4 | Debug APK | same build | **PASS** – `artifacts/app-debug.apk`, 45 MB |
| 5 | Release APK with R8 | `./gradlew :app:assembleRelease` | **PASS** – `artifacts/app-release.apk`, 26 MB |
| 6 | Release AAB | `./gradlew :app:bundleRelease` | **PASS** – `artifacts/app-release.aab`, 23 MB |
| 7 | APK signature | `apksigner verify --print-certs` | **PASS** – v2/v3 signed, `CN=AI Clip Creator`, SHA-256 `b71a8e39…7ab04ac7` |
| 8 | Native libraries packaged | `unzip -l app-release.apk` | **PASS** – 11 `.so` files incl. `libaiclipai.so`, `libwhisper.so`, `libggml*.so`, `libonnxruntime.so`, `libtensorflowlite_jni.so` |
| 9 | Manifest sanity | `aapt2 dump badging` | **PASS** – `com.aiclipcreator.app`, versionName 1.0.0, minSdk 26, targetSdk 34, expected permissions |
| 10 | R8 mapping produced | `app/build/outputs/mapping/release/mapping.txt` | **PASS** – stored as `artifacts/mapping-release.txt.gz` |

Unit test files (16 tests):

- `core-ai/.../ClipDetectorTest.kt` (5) — strong-moment detection and Best-quality flag, duplicate
  merging, min/max duration bounds, audio-only fallback, Best-mode filtering.
- `core-ai/.../CaptionBuilderTest.kt` (5) — word re-timing without inventing text, SRT formatting,
  cues clamped to the clip window, long-text splitting, cue rebasing.
- `core-audio/.../AudioPipelineTest.kt` (6) — PCM downmix/resample maths, VAD segmentation, feature
  extraction on synthetic signals, WAV round-trip.

Two genuine code fixes came out of running these tests rather than from guessing:

1. `MonoResampler` emitted a silent first sample (interpolation carried zero across blocks); it now
   seeds from the first real sample.
2. `ClipDetector` normalised every score by the weight of all nine signals, so a podcast with no
   camera motion could never clear the "Best" threshold even with perfect dialogue. Weights are now
   normalised over the signals that can actually be measured for that candidate, and the audio-only
   path scores peak loudness per window instead of the window average (brief shouts were invisible
   before).

## Not run here (no device, no KVM, no emulator)

These are the checks a real phone must still perform. They are not claimed as passing.

| Area | Why it needs hardware |
| --- | --- |
| Whisper transcription quality (Hindi/English/Hinglish) | Needs a real GGML model + arm64 CPU; the JVM cannot run the JNI runtime |
| Silero VAD / LiteRT / MediaPipe-face inference | Same — native inference only runs on device |
| MediaCodec decode + EGL render + MediaMuxer output | Hardware codecs are absent; only compiles |
| MediaStore export to `Movies/AI Clip Creator/…` | Android storage provider required |
| Foreground service + recovery after process death | Requires a real Android runtime |
| HLS live recording and YouTube metadata lookup | Needs device networking and a signed-in environment |
| UI navigation, state restoration, permission flows | Needs a running Activity |

Suggested device smoke test (≈10 minutes): install `artifacts/app-debug.apk` with
`adb install -r`, open **AI Model Manager → Install** for Whisper Tiny, import a 5–10 minute MP4,
run **Analyse**, confirm clips appear under **Best Clips** / **Balanced Clips**, preview one, export
one, and check `Movies/AI Clip Creator/` in the gallery.

## Requirement status

| Requirement | Status | Evidence |
| --- | --- | --- |
| Native Kotlin + Jetpack Compose app, 17 modules | Complete and tested (build) | `:app:compileDebugKotlin`, APK builds |
| MP4 (and other container) import via SAF, multi-file queue | Implemented but not device-tested | compiles; SAF flow needs a device |
| Live stream input (HLS / progressive HTTP), authorised recording | Implemented but not device-tested | `LiveRecorder`, `LiveMonitor` compile; network capture untested |
| YouTube link handling | Implemented but not device-tested | metadata-only via Data API v3; needs an API key and network |
| Local transcription with whisper.cpp, Tiny/Base models | Implemented but not device-tested | native library built and packaged; inference needs arm64 hardware |
| Local visual analysis (LiteRT EfficientNet + face detection) | Implemented but not device-tested | compiled; no device inference possible |
| Silero VAD (ONNX) with DSP fallback | Implemented but not device-tested | compiled; DSP path unit-tested indirectly via feature tests |
| Multi-signal moment ranking, Best + Balanced modes | Complete and tested (unit) | `ClipDetectorTest`, 5 tests |
| AI-decided clip durations within user limits | Complete and tested (unit) | `ClipDetectorTest.respects minimum and maximum clip duration` |
| Caption generation (SRT + burned-in), Hindi/English/Hinglish text preserved | Complete and tested (unit, timing/SRT/splitting) | `CaptionBuilderTest`, 5 tests; ASR quality itself needs a device |
| Clip rendering H.264/AAC with 9:16 / 16:9 / 1:1 reframing | Implemented but not device-tested | `ClipRenderer` compiles; MediaCodec/EGL cannot run here |
| Gallery export with the four subfolders | Implemented but not device-tested | `MediaStoreExporter` compiles; MediaStore needs Android |
| Processing queue, pause/resume/cancel, checkpoints, restart recovery | Implemented but not device-tested | `ProcessingService` + `RecoveryWorker` compile; lifecycle needs a device |
| AI Model Manager (download, SHA-256 verify, self-test, pause/resume) | Implemented but not device-tested | downloader/resume logic compiled; needs network + device |
| Gallery history, settings, storage monitor, download manager screens | Implemented but not device-tested | all screens present and compiled |
| On-device LLM titles/descriptions | Blocked by default (opt-in) | llama.cpp is off by default (`-PnativeLlm=true` to enable); extractive generator is the default path |
| Signed release artifacts (APK + AAB) | Complete and tested | `artifacts/`, signature verified, SHA-256 in `artifacts/SHA256SUMS.txt` |
| No fake analysis, fake progress or hard-coded clips | Complete (code review) | the pipeline only emits results derived from decoded audio/frames; the only sample data in the repo is in unit tests |

## Reproducing the checks

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk     # platform 34, build-tools 34.0.0, NDK 26.1.10909125, cmake 3.22.1
./gradlew :app:compileDebugKotlin -PnativeAi=false   # fast Kotlin-only compile
./gradlew :core-ai:testDebugUnitTest :core-audio:testDebugUnitTest
./gradlew :app:assembleDebug                         # compiles whisper.cpp via FetchContent
./gradlew :app:assembleRelease :app:bundleRelease    # needs keystore/release.jks
```

`-PnativeAi=false` builds against `core-ai/src/main/cpp-disabled/CMakeLists.txt`, which produces the
stub `libaiclipai.so`; use it only for fast UI iteration — the resulting APK cannot transcribe.
