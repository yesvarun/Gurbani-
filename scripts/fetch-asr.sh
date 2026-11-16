#!/usr/bin/env bash
# Downloads the built-in Punjabi speech recognition pieces at build time:
#  - sherpa-onnx Android library  -> libs/sherpa-onnx.aar
#  - Shruti Punjabi model (Udgam Labs, Apache-2.0) -> src/main/assets/asr/
set -euo pipefail

SHERPA_VER="2.0.1"
MODEL_BASE="https://huggingface.co/udgamlabs/Shruti-ONNX/resolve/main"
ASR_DIR="src/main/assets/asr"

mkdir -p libs "$ASR_DIR"

if [ ! -s libs/sherpa-onnx.aar ]; then
  curl -fsSL --retry 5 -o libs/sherpa-onnx.aar \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VER}/sherpa-onnx-${SHERPA_VER}.aar"
fi

for f in preprocess.onnx encode.onnx uncached_decode.onnx cached_decode.onnx tokens.txt; do
  if [ ! -s "$ASR_DIR/$f" ]; then
    curl -fsSL --retry 5 -o "$ASR_DIR/$f" "$MODEL_BASE/pa/sherpa-onnx/$f"
  fi
done
# The model's licence asks that its NOTICE file is kept with it.
if [ ! -s "$ASR_DIR/NOTICE" ]; then
  curl -fsSL --retry 5 -o "$ASR_DIR/NOTICE" "$MODEL_BASE/NOTICE"
fi

# sanity: refuse to build with a truncated model
test "$(stat -c %s "$ASR_DIR/cached_decode.onnx")" -gt 30000000
test "$(stat -c %s "$ASR_DIR/encode.onnx")" -gt 15000000
ls -lh libs "$ASR_DIR"
