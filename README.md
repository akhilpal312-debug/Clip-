# AI Clip Creator

Native Android app that finds the interesting moments in long videos and live streams, cuts them
into short-form clips with burned-in captions and saves them to the phone gallery — with the AI
work done on the device.

```
Import (SAF) ─┐
              ├─> audio extract ─> VAD ─> Whisper (whisper.cpp) ─┐
Live HLS ─────┘                                                 ├─> multi-signal ranking ─> clips
              └─> adaptive frames ─> scene/face/scene labels ───┘        │
                                                                          ├─> captions (SRT + burn-in)
                                                                          └─> H.264/AAC render ─> MediaStore
```

## What it does

- **Import** MP4 (and other MediaExtractor-supported containers) through the Storage Access
  Framework, with metadata, thumbnails and multi-file queueing. Originals are never modified.
- **Analyse in chunks**: audio is decoded to 16 kHz mono PCM in 30 s windows; frames are sampled
  adaptively; nothing loads a whole multi-hour file into memory.
- **Real local inference**: whisper.cpp (multilingual Tiny/Base) for timestamped speech,
  LiteRT EfficientNet-Lite0 for scene labels, platform face detection for framing, Silero VAD
  (ONNX Runtime) for speech boundaries, optional llama.cpp + Qwen2.5-0.5B for caption writing.
- **Ranking** combines dialogue content, audio peaks, scene cuts, motion, faces, laughter cues,
  questions, sentence completeness and pacing. Loudness or faces alone never decide a clip.
- **Two quality modes**: *Best Moments* (strict threshold) and *Balanced* (more variety), with
  AI-decided clip lengths bounded by user-configurable limits.
- **Rendering** uses MediaCodec + OpenGL: cover-fit reframing to 9:16 / 16:9 / 1:1 with smart
  face-aware crop, burned-in captions, audio normalisation, H.264 + AAC output, no upscaling.
- **Gallery export** to `Movies/AI Clip Creator/{Best Moments,Balanced Clips,Live Streams,Projects}`.
- **Queue** with pause/resume/cancel/retry, per-stage checkpoints, restart recovery, foreground
  service and a WorkManager recovery worker.
- **Live monitoring** for HLS / progressive HTTP sources the user is authorised to record,
  reusing the exact same analysis and ranking engine.

## Verified deliverables

Built in this workspace with JDK 17, Android SDK 34, NDK 26.1, Gradle 8.7:

| Artifact | Path |
| --- | --- |
| Debug APK | `artifacts/app-debug.apk` |
| Release APK (signed with the project keystore) | `artifacts/app-release.apk` |
| Release AAB | `artifacts/app-release.aab` |
| Model manifest (JSON) | `artifacts/model-manifest.json` |

All three artifacts were built in this workspace: Kotlin compiles for every module, whisper.cpp
builds with the NDK and is packaged into the APK/AAB, R8 release builds are signed with
`keystore/release.jks`, and 16 JVM unit tests pass. See [TESTING.md](TESTING.md) for the exact
commands, checksums and the list of checks that still need a physical device.

```
7d18871d62eb84f3ed54a17d1d074da61497f63c78d227dd46f70eed9c3992c4  artifacts/app-debug.apk
87d1aec6a0cd625c6bee833faf79ca6af115261877e2a3eae9976e7824a5caee  artifacts/app-release.apk
22734177211ddc2dd0608744cff87ae1e75bc272cd7a4573e2e5a18934a8ead2  artifacts/app-release.aab
```

## Building

```bash
# JDK 17, Android SDK 34 + NDK 26.1.10909125 + CMake 3.22.1
export ANDROID_SDK_ROOT=/path/to/android-sdk
./gradlew :app:assembleDebug          # arm64-v8a by default
./gradlew :app:assembleDebug -Pabi="arm64-v8a,x86_64"
./gradlew :core-ai:testDebugUnitTest :core-audio:testDebugUnitTest   # 16 JVM unit tests
./gradlew :app:bundleRelease          # AAB (requires keystore/release.jks)
```

The first build fetches whisper.cpp v1.7.4 with CMake `FetchContent` (network required) and
compiles the native inference library for the selected ABI.

The optional language model runtime is off by default because it adds a large native build:

```bash
./gradlew :app:assembleDebug -PnativeLlm=true
```

Without it, titles/descriptions are produced by the extractive caption generator (words taken
only from the transcript) and the model manager reports the LLM runtime as unavailable.

For UI-only iteration, `-PnativeAi=false` swaps in `core-ai/src/main/cpp-disabled/CMakeLists.txt`
(a stub `libaiclipai.so`) so Gradle skips the whisper.cpp compile. Such a build cannot transcribe,
so never ship it.

Signing: `keystore/release.jks` (password `aiclipcreator`, alias `aiclipcreator`) is a
project-local self-signed key created for these builds. Override with `AICLIP_KEYSTORE_PASSWORD`,
`AICLIP_KEY_ALIAS` and `AICLIP_KEY_PASSWORD`, or replace the file with your own key.

## Models

Downloaded by the app, verified against pinned SHA-256 digests, then self-tested with a real
inference pass before being marked ready. See [MODELS.md](MODELS.md). `scripts/download_models.sh`
performs the same downloads on a workstation for offline testing.

## Requirements and limits

- Android 8.0 (API 26) or newer, arm64 device recommended.
- Whisper needs ~1 GB free RAM while transcribing; the app picks Tiny on 4 GB devices.
- Rendering runs at roughly real time because decoding is paced by MediaCodec.
- Live recording supports HLS/progressive HTTP you are allowed to record. YouTube watch pages are
  metadata-only (official Data API); DRM is never bypassed. RTMP ingestion is not supported.
- Hinglish is handled by Whisper's language-agnostic decoding (Hindi language hint, English
  tokens preserved); captions keep whatever the model heard.

## Privacy

Videos, audio, transcripts, frames and clips stay in app storage or the gallery. The only network
traffic is model download and the optional YouTube metadata lookup. No API keys are required for
analysis; the YouTube key is optional and stored locally.
