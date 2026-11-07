#!/usr/bin/env bash
# 从视觉实验室素材生成官网媒体：截图转 WebP，录屏转 30 fps H.264。
# 用法：website/scripts/prepare-media.sh <footage 目录>
# footage 目录即宣传片工程的 footage/（含中文素材与 en/ 子目录），不在本仓库。
set -euo pipefail
SRC="${1:?缺少 footage 目录}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/public/media"
mkdir -p "$OUT/zh" "$OUT/en"
stills=(inner-terminal.state.fastfetch inner-chat.state.branch-plan inner-chat.state.subagents inner-chat.state.ops-approval inner-inspector.state.plan)
for lang in zh en; do
  dir="$SRC"; [[ $lang == en ]] && dir="$SRC/en"
  for n in "${stills[@]}"; do cwebp -quiet -q 78 -resize 1200 0 "$dir/$n.png" -o "$OUT/$lang/$n.webp"; done
  cwebp -quiet -q 80 -resize 640 0 "$dir/outer-chat.state.ops-report.png" -o "$OUT/$lang/outer-chat.state.ops-report.webp"
done
# 录屏只有中文界面；终端录屏不含界面文案，中英文共用
encode() { ffmpeg -v error -y -threads 4 -i "$1" -an -vf "fps=30,scale=1000:-2" -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -movflags +faststart "$2"; }
encode "$SRC/inner-apk-add.mp4" "$OUT/apk-add.mp4"
encode "$SRC/inner-ade-json.mp4" "$OUT/zh/ade.mp4"
encode "$SRC/inner-ops-502.mp4" "$OUT/zh/ops.mp4"
