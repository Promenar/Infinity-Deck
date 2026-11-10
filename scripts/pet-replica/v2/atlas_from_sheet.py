#!/usr/bin/env python3
"""把图像模型画的表情素材表（纯肤色底、等分格子）整理成贴花图集：按格裁切、去底成透明、重排到固定版面。

用法：
  python atlas_from_sheet.py eyes  <素材表.png> <输出 eyes.png>    # 2 列 × 3 行 → 1024×768，每格 512×256
  python atlas_from_sheet.py mouth <素材表.png> <输出 mouth.png>   # 4 列 × 2 行 → 512×256，每格 128×128
依赖：Pillow、numpy（素材整理用，不属于模型转换流程）。

眼睛：每格左右各取一个正方形窗口（边长取格高），缩放到 256×256，左右眼各占图集格子的一半。
去底：按与背景色的最大通道差算透明度，并还原前景色，腮红这类接近肤色的部分自然成为半透明。
"""
import sys

import numpy as np
from PIL import Image

kind, src, dst = sys.argv[1:4]
im = np.asarray(Image.open(src).convert('RGB')).astype(np.float32)
H, W = im.shape[:2]
bg = np.median(np.concatenate([im[:12, :12].reshape(-1, 3), im[:12, -12:].reshape(-1, 3),
                               im[-12:, :12].reshape(-1, 3), im[-12:, -12:].reshape(-1, 3)]), axis=0)


def key(tile):
    d = np.abs(tile - bg).max(-1)
    a = np.clip((d - 5.0) / 16.0, 0, 1)
    fg = np.where(a[..., None] > 1e-3, (tile - bg * (1 - a[..., None])) / np.maximum(a[..., None], 1e-3), tile)
    return np.dstack([np.clip(fg, 0, 255), a * 255]).astype(np.uint8)


def window(cx, cy, size):
    x0, y0 = int(round(cx - size / 2)), int(round(cy - size / 2))
    tile = np.tile(bg, (size, size, 1)).astype(np.float32)
    sx0, sy0, sx1, sy1 = max(x0, 0), max(y0, 0), min(x0 + size, W), min(y0 + size, H)
    tile[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = im[sy0:sy1, sx0:sx1]
    return tile


if kind == 'eyes':
    cols, rows, cw, ch = 2, 3, W / 2, H / 3
    out = Image.new('RGBA', (1024, 768), (0, 0, 0, 0))
    size = int(ch)
    for r in range(rows):
        for c in range(cols):
            for half in range(2):
                cx = c * cw + cw * (0.25 + 0.5 * half)
                cy = r * ch + ch / 2
                t = Image.fromarray(key(window(cx, cy, size)), 'RGBA').resize((256, 256), Image.LANCZOS)
                out.paste(t, (c * 512 + half * 256, r * 256))
else:
    cols, rows, cw, ch = 4, 2, W / 4, H / 2
    out = Image.new('RGBA', (512, 256), (0, 0, 0, 0))
    size = int(min(cw, ch) * float(sys.argv[4]) if len(sys.argv) > 4 else min(cw, ch))
    for r in range(rows):
        for c in range(cols):
            t = Image.fromarray(key(window(c * cw + cw / 2, r * ch + ch / 2, size)), 'RGBA').resize((128, 128), Image.LANCZOS)
            out.paste(t, (c * 128, r * 128))
out.save(dst)
print('ATLAS', kind, '->', dst, 'bg', bg.astype(int).tolist(), 'window', size)
