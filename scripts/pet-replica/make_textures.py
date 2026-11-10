#!/usr/bin/env python3
"""程序绘制鲸鱼女仆复刻模型的全部贴图（不使用任何生成模型）。

用法：python make_textures.py <输出目录>
依赖：Pillow、numpy。

输出：
- eye.png    512²   眼睛（巩膜 + 虹膜 + 高光），供 Eye 材质独占，表情用贴图变换移动与缩放虹膜
- hair.png   256×1024  头发：纵向为世界高度渐变，横向为发丝条纹
- skin.png   512²   皮肤：头部正面平面投影，含腮红
- cloth.png  1024²  服装图集：上半为裙子（含金色刺绣），左下为围裙（含小鲸鱼），右下为色块
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
SS = 4  # 超采样倍数


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def grad(stops, t):
    """stops: [(位置, 颜色)]，位置升序。"""
    if t <= stops[0][0]:
        return stops[0][1]
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return lerp(c0, c1, (t - p0) / (p1 - p0))
    return stops[-1][1]


def save(img, name, size):
    img = img.resize(size, Image.LANCZOS)
    img.save(os.path.join(OUT, name))


# ---------------------------------------------------------------- 眼睛
def make_eye():
    n = 512 * SS
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32) / n
    img = np.zeros((n, n, 3), np.float32)
    # 巩膜：白色，上方带一点眼睑投下的蓝灰阴影
    sclera = np.array(hexrgb('#ffffff'), np.float32)
    shadow = np.array(hexrgb('#c9cfe8'), np.float32)
    k = np.clip((0.34 - yy) / 0.30, 0, 1)[..., None]
    img[:] = sclera * (1 - k) + shadow * k
    # 虹膜：竖椭圆，自上而下由藏青过渡到天蓝
    cx, cy, rx, ry = 0.5, 0.50, 0.335, 0.415
    d = np.sqrt(((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2)
    stops = [(0.0, hexrgb('#1a2460')), (0.30, hexrgb('#24398c')), (0.55, hexrgb('#3566c8')),
             (0.80, hexrgb('#62a6ee')), (1.0, hexrgb('#a8dcff'))]
    t = np.clip((yy - (cy - ry)) / (2 * ry), 0, 1)
    iris = np.zeros_like(img)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (t >= p0) & (t <= p1)
        f = ((t - p0) / (p1 - p0))[..., None]
        seg = np.array(c0, np.float32) * (1 - f) + np.array(c1, np.float32) * f
        iris[m] = seg[m]
    # 瞳孔：偏上的深色竖椭圆，边缘柔和
    pd = np.sqrt(((xx - cx) / 0.125) ** 2 + ((yy - (cy - 0.07)) / 0.17) ** 2)
    pk = np.clip((1.0 - pd) / 0.35, 0, 1)[..., None]
    iris = iris * (1 - pk) + np.array(hexrgb('#131a4a'), np.float32) * pk
    # 虹膜下缘的亮月牙
    cd = np.sqrt(((xx - cx) / 0.24) ** 2 + ((yy - (cy + 0.25)) / 0.11) ** 2)
    ck = (np.clip((1.0 - cd) / 0.5, 0, 1) * 0.55)[..., None]
    iris = iris * (1 - ck) + np.array(hexrgb('#c9ecff'), np.float32) * ck
    # 虹膜深色外圈
    ring = (np.clip((d - 0.86) / 0.14, 0, 1) * 0.85)[..., None]
    iris = iris * (1 - ring) + np.array(hexrgb('#161d55'), np.float32) * ring
    a = np.clip((1.0 - d) * n * rx / 1.5, 0, 1)[..., None]
    img = img * (1 - a) + iris * a
    # 上眼睑阴影压在虹膜上部
    sk = (np.clip((0.30 - yy) / 0.22, 0, 1) * 0.55)[..., None]
    img = img * (1 - sk) + np.array(hexrgb('#141a48'), np.float32) * sk * a + img * sk * (1 - a)
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    dr = ImageDraw.Draw(out)

    def ell(cx_, cy_, rx_, ry_, col):
        dr.ellipse([(cx_ - rx_) * n, (cy_ - ry_) * n, (cx_ + rx_) * n, (cy_ + ry_) * n], fill=col)

    # 高光：左上大高光 + 右下小高光
    ell(0.365, 0.285, 0.105, 0.125, (255, 255, 255))
    ell(0.655, 0.685, 0.045, 0.05, (255, 255, 255))
    ell(0.60, 0.78, 0.022, 0.024, (232, 246, 255))
    save(out, 'eye.png', (512, 512))


# ---------------------------------------------------------------- 头发
HAIR_Z0, HAIR_Z1 = 0.08, 1.12  # 贴图纵向覆盖的世界高度范围（米）


def make_hair():
    w, h = 256 * 2, 1024 * 2
    stops = [(0.08, hexrgb('#9ccbf6')), (0.20, hexrgb('#86b9ee')), (0.32, hexrgb('#74a6e4')),
             (0.46, hexrgb('#5f89d2')), (0.60, hexrgb('#4f70b8')), (0.76, hexrgb('#4762a4')),
             (1.12, hexrgb('#3f5596'))]
    img = np.zeros((h, w, 3), np.float32)
    for y in range(h):
        z = HAIR_Z1 - (HAIR_Z1 - HAIR_Z0) * y / (h - 1)
        img[y, :] = grad(stops, z)
    # 发丝条纹：细的纵向明暗线
    rnd = random.Random(7)
    for _ in range(26):
        x = rnd.randrange(w)
        wd = rnd.choice([2, 2, 3, 4])
        amt = rnd.uniform(-0.10, 0.07)
        img[:, x:x + wd] *= (1 + amt)
    # 头顶高光带（天使环）：锯齿状亮带，位于 z≈0.86
    band = np.zeros((h, w), np.float32)
    for x in range(w):
        u = x / w
        zig = 0.012 * (abs(((u * 9) % 1) - 0.5) * 2) + 0.006 * math.sin(u * 40)
        zc = 0.868 + zig
        half = 0.016 + 0.008 * math.sin(u * 23 + 1)
        y0 = int((HAIR_Z1 - (zc + half)) / (HAIR_Z1 - HAIR_Z0) * h)
        y1 = int((HAIR_Z1 - (zc - half)) / (HAIR_Z1 - HAIR_Z0) * h)
        band[y0:y1, x] = 1
    band = np.asarray(Image.fromarray((band * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)), np.float32) / 255
    hl = np.array(hexrgb('#6c8acb'), np.float32)
    img = img * (1 - band[..., None] * 0.75) + hl * band[..., None] * 0.75
    save(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)), 'hair.png', (256, 1024))


# ---------------------------------------------------------------- 皮肤
# 头部 UV：u = 0.5 + x / SKIN_SPAN，v = 0.5 + (z - SKIN_ZC) / SKIN_SPAN
SKIN_SPAN, SKIN_ZC = 0.5, 0.762


def make_skin():
    n = 512 * SS
    base = np.array(hexrgb('#fff0e6'), np.float32)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32) / n
    img = np.tile(base, (n, n, 1))
    blush = np.array(hexrgb('#ff9f9c'), np.float32)
    for sx in (-1, 1):
        u = 0.5 + sx * 0.112 / SKIN_SPAN
        v = 0.5 + (0.612 - SKIN_ZC) / SKIN_SPAN
        d = ((xx - u) / (0.036 / SKIN_SPAN)) ** 2 + (((1 - yy) - v) / (0.017 / SKIN_SPAN)) ** 2
        k = (np.exp(-d * 1.1) * 0.62)[..., None]
        img = img * (1 - k) + blush * k
    save(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)), 'skin.png', (512, 512))


# ---------------------------------------------------------------- 服装图集
SW = {  # 色块：名称 -> (列, 行)，4×4 网格位于图集右下角；行 0 在最下
    'white': (0, 3), 'navy': (1, 3), 'shoe': (2, 3), 'sockw': (3, 3),
    'tail': (0, 2), 'belly': (1, 2), 'ear': (2, 2), 'earin': (3, 2),
    'lash': (0, 1), 'mouth': (1, 1), 'gold': (2, 1), 'black': (3, 1),
    'bowdark': (0, 0), 'frillshade': (1, 0), 'mouthline': (2, 0), 'bow': (3, 0),
}
SW_COL = {
    'white': '#fbf7f9', 'navy': '#2c3766', 'shoe': '#2d3052', 'sockw': '#fbf7f9', 'bow': '#5a8de6',
    'tail': '#46588f', 'belly': '#f7f3f8', 'ear': '#3e4b84', 'earin': '#f3eef6',
    'lash': '#232447', 'gold': '#c8a868', 'black': '#23233c',
    'bowdark': '#3f68bd', 'frillshade': '#dcd6ea', 'mouthline': '#8c3f52',
}


def swatch_uv(name, fu=0.5, fv=0.5):
    """色块内的 UV；fu、fv 为格内相对位置。"""
    c, r = SW[name]
    return (0.5 + (c + fu) * 0.125, (r + fv) * 0.125)


def whale_icon(dr, cx, cy, s, col):
    """小鲸鱼图标：圆胖身体 + 上翘尾巴 + 喷水。"""
    body = []
    for i in range(64):
        a = 2 * math.pi * i / 64
        r = 1.0 + 0.10 * math.cos(a - 0.4)
        body.append((cx + s * 1.25 * r * math.cos(a), cy + s * 0.82 * r * math.sin(a)))
    dr.polygon(body, fill=col)
    # 尾巴：从身体右侧向右上翘起，末端分叉
    tail = [(cx + s * 0.9, cy - s * 0.1), (cx + s * 1.55, cy - s * 0.35), (cx + s * 1.8, cy - s * 0.95),
            (cx + s * 1.45, cy - s * 1.25), (cx + s * 1.75, cy - s * 1.05), (cx + s * 1.95, cy - s * 1.2),
            (cx + s * 2.25, cy - s * 1.15), (cx + s * 2.1, cy - s * 0.8), (cx + s * 1.95, cy - s * 0.1),
            (cx + s * 1.3, cy + s * 0.45)]
    dr.polygon(tail, fill=col)
    # 肚皮与眼睛
    dr.pieslice([cx - s * 1.2, cy - s * 0.2, cx + s * 0.9, cy + s * 1.0], 20, 170, fill=hexrgb('#b9cdf5'))
    dr.ellipse([cx - s * 0.78, cy - s * 0.2, cx - s * 0.56, cy + s * 0.02], fill=(255, 255, 255))
    # 喷水
    w = max(2, int(s * 0.16))
    dr.line([(cx - s * 0.2, cy - s * 0.95), (cx - s * 0.2, cy - s * 1.45)], fill=col, width=w)
    dr.arc([cx - s * 0.75, cy - s * 1.75, cx - s * 0.2, cy - s * 1.2], 270, 360, fill=col, width=w)
    dr.arc([cx - s * 0.2, cy - s * 1.75, cx + s * 0.35, cy - s * 1.2], 180, 270, fill=col, width=w)


def make_cloth():
    n = 1024 * SS
    img = Image.new('RGB', (n, n), hexrgb(SW_COL['white']))
    dr = ImageDraw.Draw(img)
    # 上半：裙子。v ∈ [0.5, 1]，0.5 为裙摆，1 为腰。
    navy = hexrgb('#2f3a6c')
    navy2 = hexrgb('#283260')
    for y in range(n // 2):
        t = y / (n // 2)  # 0 腰 → 1 裙摆
        dr.line([(0, y), (n, y)], fill=tuple(int(c) for c in lerp(navy, navy2, t)))
    gold = hexrgb('#bfa066')
    # 金色刺绣：裙摆上方一条细线 + 等距小菱形 + 点
    y_line = int(n // 2 * 0.86)
    dr.line([(0, y_line), (n, y_line)], fill=gold, width=SS * 2)
    y_line2 = int(n // 2 * 0.895)
    dr.line([(0, y_line2), (n, y_line2)], fill=gold, width=SS)
    reps = 28
    for i in range(reps):
        cx = (i + 0.5) * n / reps
        cy = n // 2 * 0.79
        r = SS * 5
        dr.polygon([(cx, cy - r * 1.5), (cx + r, cy), (cx, cy + r * 1.5), (cx - r, cy)], outline=gold, width=SS)
        for dx in (-0.5, 0.5):
            px = cx + dx * n / reps
            dr.ellipse([px - SS * 1.6, cy - SS * 1.6, px + SS * 1.6, cy + SS * 1.6], fill=gold)
        dr.arc([cx - n / reps / 2, cy - r * 4.2, cx + n / reps / 2, cy - r * 1.2], 200, 340, fill=gold, width=SS)
    # 左下：围裙。u ∈ [0, 0.5]，v ∈ [0, 0.5]；白底，右下角小鲸鱼。
    whale_icon(dr, n * 0.335, n * (1 - 0.365), n * 0.024, hexrgb('#4562b8'))
    # 右下：色块
    cell = n // 8
    for name, (c, r) in SW.items():
        x0 = n // 2 + c * cell
        y0 = n - (r + 1) * cell
        if name == 'mouth':
            # 口腔：自上而下由深玫红过渡到舌头的浅粉
            for y in range(cell):
                t = y / cell
                col = grad([(0.0, hexrgb('#b8485c')), (0.45, hexrgb('#d8687a')), (0.62, hexrgb('#f59a9d')), (1.0, hexrgb('#ffb3ad'))], t)
                dr.line([(x0, y0 + y), (x0 + cell, y0 + y)], fill=tuple(int(v) for v in col))
        else:
            dr.rectangle([x0, y0, x0 + cell, y0 + cell], fill=hexrgb(SW_COL[name]))
    save(img, 'cloth.png', (1024, 1024))


if __name__ == '__main__':
    make_eye()
    make_hair()
    make_skin()
    make_cloth()
    print('textures ->', OUT)
