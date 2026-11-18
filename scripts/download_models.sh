#!/bin/bash
# Downloads the exact model artifacts the app installs and verifies their SHA-256 digests.
# Usage: scripts/download_models.sh [output-directory]
set -euo pipefail

OUT="${1:-./models}"
mkdir -p "$OUT"

fetch() {
  local url="$1" file="$2" sha="$3"
  local target="$OUT/$file"
  if [[ -f "$target" ]] && echo "$sha  $target" | sha256sum -c --status -; then
    echo "ok       $file (already present)"
    return
  fi
  echo "download $file"
  curl -fL --retry 5 --retry-delay 2 -C - -o "$target" "$url"
  echo "$sha  $target" | sha256sum -c -
}

fetch "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin" \
      "ggml-tiny.bin" \
      "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"

fetch "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin" \
      "ggml-base.bin" \
      "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"

fetch "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite" \
      "efficientnet_lite0.tflite" \
      "6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0"

fetch "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx" \
      "silero_vad.onnx" \
      "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"

fetch "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" \
      "qwen2.5-0.5b-instruct-q4_k_m.gguf" \
      "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"

cat > "$OUT/model-manifest.json" <<JSON
{
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "models": [
    {"id":"whisper-tiny","file":"ggml-tiny.bin","sha256":"be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21","bytes":77691713,"license":"MIT"},
    {"id":"whisper-base","file":"ggml-base.bin","sha256":"60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe","bytes":147951465,"license":"MIT"},
    {"id":"vision-efficientnet-lite0","file":"efficientnet_lite0.tflite","sha256":"6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0","bytes":18582189,"license":"Apache-2.0"},
    {"id":"audio-silero-vad","file":"silero_vad.onnx","sha256":"1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3","bytes":2327524,"license":"MIT"},
    {"id":"llm-qwen2.5-0.5b","file":"qwen2.5-0.5b-instruct-q4_k_m.gguf","sha256":"74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db","bytes":491400032,"license":"Apache-2.0"}
  ]
}
JSON

echo "All artifacts verified in $OUT"
