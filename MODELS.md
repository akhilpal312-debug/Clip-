# Local model registry

Every artifact is the upstream file, and the SHA-256 below was measured from that exact URL
during development. Installation fails closed: a mismatched digest deletes the download.

| ID | Artifact | Runtime | Size | License | RAM | ABI |
| --- | --- | --- | --- | --- | --- | --- |
| `whisper-tiny` | `ggml-tiny.bin` | whisper.cpp | 77.7 MB | MIT (OpenAI Whisper weights) | 900 MB | arm64-v8a, x86_64, armeabi-v7a |
| `whisper-base` | `ggml-base.bin` | whisper.cpp | 148.0 MB | MIT | 1400 MB | arm64-v8a, x86_64 |
| `vision-efficientnet-lite0` | `efficientnet_lite0.tflite` | LiteRT | 18.6 MB | Apache-2.0 (Google AI Edge) | 400 MB | arm64-v8a, x86_64, armeabi-v7a |
| `audio-silero-vad` | `silero_vad.onnx` | ONNX Runtime | 2.3 MB | MIT (Silero) | 200 MB | arm64-v8a, x86_64, armeabi-v7a |
| `llm-qwen2.5-0.5b` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | llama.cpp | 491.4 MB | Apache-2.0 (Qwen) | 2000 MB | arm64-v8a |

Pinned digests (also compiled into `ModelRegistry.kt`):

```
be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21  ggml-tiny.bin
60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe  ggml-base.bin
6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0  efficientnet_lite0.tflite
1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3  silero_vad.onnx
74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db  qwen2.5-0.5b-instruct-q4_k_m.gguf
```

Sources:

- whisper.cpp models — <https://huggingface.co/ggerganov/whisper.cpp> (code: <https://github.com/ggml-org/whisper.cpp>)
- EfficientNet-Lite0 (MediaPipe model zoo) — <https://developers.google.com/edge/litert>
- Silero VAD — <https://github.com/snakers4/silero-vad>
- Qwen2.5-0.5B-Instruct GGUF — <https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF>

## Install flow (inside the app)

1. Hardware check: ABI, RAM, runtime compiled into the build.
2. Storage check: artifact size + 120 MB headroom.
3. Resumable download to `<file>.part` using HTTP Range, with progress persisted.
4. SHA-256 verification of the completed file.
5. Runtime load + real inference self-test (whisper benchmark tone, LiteRT neutral frame,
   ONNX VAD frame, llama.cpp token generation).
6. State persisted as `READY`; failures store the reason and can be retried without re-downloading
   the already verified bytes.

Pause cancels the transfer and keeps the `.part` file; resume continues from the existing offset.
Delete removes the artifact and unloads the runtime. Verify re-hashes the file and repeats the
self-test.

## Workstation download for offline testing

`scripts/download_models.sh` downloads the same URLs and checks the same digests. Push the files
to a device with `adb push` into the app's `files/ai_clip_creator/models/` directory if you want to
skip the in-app download.
