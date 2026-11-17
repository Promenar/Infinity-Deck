#!/usr/bin/env bash
# 从视觉实验室素材生成官网媒体：截图转 WebP，录屏转 30 fps H.264。
# 用法：website/scripts/prepare-media.sh <footage 目录>
# footage 目录即宣传片工程的 footage/（含中文素材与 en/ 子目录），不在本仓库。
set -euo pipefail
SRC="${1:?缺少 footage 目录}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/public/media"
mkdir -p "$OUT/zh" "$OUT/en"
stills=(
  inner-terminal.state.fastfetch
  inner-chat.state.branch-plan
  inner-chat.state.subagents
  inner-chat.state.ops-approval
  inner-chat.state.model-network
  inner-inspector.state.plan
  inner-inspector.state.production
  inner-library.state.ask
  inner-recording.state.workbench
  inner-settings.state.local-models
)
for lang in zh en; do
  dir="$SRC"; [[ -d "$SRC/$lang" ]] && dir="$SRC/$lang"
  for n in "${stills[@]}"; do cwebp -quiet -q 78 -resize 1200 0 "$dir/$n.png" -o "$OUT/$lang/$n.webp"; done
  cwebp -quiet -q 80 -resize 640 0 "$dir/outer-chat.state.ops-report.png" -o "$OUT/$lang/outer-chat.state.ops-report.webp"
  cwebp -quiet -q 80 -resize 640 0 "$dir/outer-recording.state.settings.png" -o "$OUT/$lang/outer-recording.state.settings.webp"
done
if [[ "${STILLS_ONLY:-0}" != "1" ]]; then
  # 录屏只有中文界面；终端录屏不含界面文案，中英文共用
  # 第三个参数为开头裁掉的秒数：源录屏开头带上一段的残帧和系统状态栏（时间、通知图标）滑出动画
  encode() { ffmpeg -v error -y -threads 4 -i "$1" -ss "$3" -an -vf "fps=30,scale=1000:-2" -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -movflags +faststart "$2"; }
  encode "$SRC/inner-apk-add.mp4" "$OUT/apk-add.mp4" 1.2
  encode "$SRC/inner-ade-json.mp4" "$OUT/zh/ade.mp4" 1.75
  encode "$SRC/inner-ops-502.mp4" "$OUT/zh/ops.mp4" 1.6
fi
