#!/bin/zsh
# 一键构建：贴图 → Blender 建模导出 glb → 写入 VRM 1.0 扩展 → base64 文本。
# 用法：build.sh <构建目录> <产物目录> [Python 解释器（需含 Pillow、numpy）]
set -e
HERE="${0:A:h}"
BUILD="$1"; OUT="$2"; PY="${3:-python3}"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
mkdir -p "$BUILD/tex" "$OUT"
"$PY" "$HERE/make_textures.py" "$BUILD/tex"
"$BLENDER" --background --python "$HERE/build_model.py" -- "$BUILD/whale-maid.glb" "$BUILD/tex" "$BUILD/rig.json" 2>&1 | grep -E "MESH|EXPORTED|Error|Traceback|error|File \"|^\s+\^|Exception|line [0-9]+" || true
"$PY" "$HERE/glb_to_vrm.py" "$BUILD/whale-maid.glb" "$BUILD/rig.json" "$OUT/whale-maid.vrm"
base64 -i "$OUT/whale-maid.vrm" -o "$OUT/replica-model.b64.txt"
ls -la "$OUT/whale-maid.vrm"
