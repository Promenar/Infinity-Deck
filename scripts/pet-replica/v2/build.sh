#!/bin/zsh
# v2 一键流程：图生 3D 网格 → 减面分区 → 烘焙 → 绑定 → VRM 1.0。
# 用法：build.sh <输入.glb> <工作目录> <产物目录> [起始阶段 1-4]
# 只用 Blender 自带的 Python（含 numpy），不需要额外依赖。
set -e
HERE="${0:A:h}"
SRC="$1"; WORK="$2"; OUT="$3"; FROM="${4:-1}"
BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
BPY=$(ls "${BLENDER:h:h}"/Resources/*/python/bin/python3* | head -1)
F='^(ORIGIN|HIGH|REMESHED|HIDDEN|DECIMATED|CRUMBS|CUT|CAP|PARTS|LOW|AREA|BAKED|VISIBLE|TRANSFER|SAVED|WEIGHTS|EXPORTED)|Error|Traceback|line [0-9]'
mkdir -p "$WORK" "$OUT"
[ "$FROM" -le 1 ] && "$BLENDER" --background --python "$HERE/s1_prepare.py" -- "$SRC" "$WORK" 2>&1 | grep -E "$F"
[ "$FROM" -le 2 ] && "$BLENDER" --background --python "$HERE/s2_lowpoly.py" -- "$WORK" 2>&1 | grep -E "$F"
[ "$FROM" -le 3 ] && "$BLENDER" --background --python "$HERE/s3_bake.py" -- "$WORK" 2>&1 | grep -E "$F"
[ "$FROM" -le 4 ] && "$BLENDER" --background --python "$HERE/s4_rig.py" -- "$WORK" 2>&1 | grep -E "$F"
"$BPY" "$HERE/../glb_to_vrm.py" "$WORK/whale-maid.glb" "$WORK/rig.json" "$OUT/whale-maid.vrm"
