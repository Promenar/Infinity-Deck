#!/usr/bin/env python3
"""程序绘制面部 2D 贴花的表情图集（只用 numpy 和标准库，可用 Blender 自带的 Python 运行）。

用法：python make_face_atlas.py <输出目录>
产出：
- eyes.png  1024×768 RGBA，2 列 × 3 行，每格 512×256 画一双眼睛（含腮红）。
  格子（列, 行）：睁眼 (0,0)、闭眼 (1,0)、笑眼 (0,1)、惊讶 (1,1)、难过 (0,2)、半睁 (1,2)
- mouth.png 512×256 RGBA，4 列 × 2 行，每格 128×128。
  格子：微笑 (0,0)、aa (1,0)、ou (2,0)、开心张嘴 (3,0)、难过 (0,1)、惊讶 (1,1)、ee (2,1)、oh (3,1)
画法参照设定图：竖长大眼、上睫毛粗、虹膜由藏青过渡到天蓝、左上大高光。
"""
import os
import struct
import sys
import zlib

import numpy as np

OUT = sys.argv[1]
SS = 3


def write_png(path, rgba):
    h, w = rgba.shape[:2]
    raw = b''.join(b'\x00' + rgba[y].tobytes() for y in range(h))

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def hx(h):
    return np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)], np.float32)


class Canvas:
    def __init__(self, n):
        self.n = n * SS
        self.px = 1.0 / self.n
        ys, xs = np.mgrid[0:self.n, 0:self.n].astype(np.float32)
        self.x, self.y = (xs + 0.5) / self.n, (ys + 0.5) / self.n
        self.rgb = np.zeros((self.n, self.n, 3), np.float32)
        self.a = np.zeros((self.n, self.n), np.float32)

    def paint(self, cov, color):
        """把颜色按覆盖率 cov 叠到画布上；color 可为单色或逐像素颜色。"""
        cov = np.clip(cov, 0, 1)
        col = np.broadcast_to(np.asarray(color, np.float32), self.rgb.shape)
        na = cov + self.a * (1 - cov)
        self.rgb = np.where(na[..., None] > 1e-6,
                            (col * cov[..., None] + self.rgb * (self.a * (1 - cov))[..., None]) / np.maximum(na, 1e-6)[..., None],
                            self.rgb)
        self.a = na

    def cov(self, sdf):
        return np.clip(0.5 - sdf / self.px, 0, 1)

    def out(self):
        n = self.n // SS
        rgb = (self.rgb * self.a[..., None]).reshape(n, SS, n, SS, 3).mean((1, 3))
        a = self.a.reshape(n, SS, n, SS).mean((1, 3))
        rgb = np.where(a[..., None] > 1e-6, rgb / np.maximum(a, 1e-6)[..., None], 0)
        return (np.clip(np.dstack([rgb, a]), 0, 1) * 255 + 0.5).astype(np.uint8)


def ell(x, y, cx, cy, rx, ry, p=2.0):
    return ((np.abs((x - cx) / rx) ** p + np.abs((y - cy) / ry) ** p) ** (1 / p) - 1) * min(rx, ry)


def stroke(x, y, pts, widths):
    """到折线的距离减去半宽（半宽沿线变化），负值在线内。"""
    best = np.full(x.shape, 9.0, np.float32)
    for (ax, ay), (bx, by), w0, w1 in zip(pts[:-1], pts[1:], widths[:-1], widths[1:]):
        dx, dy = bx - ax, by - ay
        t = np.clip(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy + 1e-12), 0, 1)
        d = np.hypot(x - (ax + t * dx), y - (ay + t * dy)) - (w0 + (w1 - w0) * t)
        best = np.minimum(best, d)
    return best


def vgrad(y, stops):
    ys = [s[0] for s in stops]
    cs = np.array([hx(s[1]) for s in stops])
    return np.stack([np.interp(y, ys, cs[:, k]) for k in range(3)], -1).astype(np.float32)


LASH, LASH2 = hx('#1d2048'), hx('#7a4150')
CY = 0.43


def draw_eye(kind, mirror):
    c = Canvas(256)
    x, y = c.x, c.y
    xm = 1 - x if mirror else x          # 镜像后的横坐标：外眼角在 xm 较小的一侧
    # 腮红
    bx = 0.5 + (0.10 if mirror else -0.10)
    c.paint(np.exp(-(((x - bx) / 0.21) ** 2 + ((y - 0.905) / 0.055) ** 2) * 1.6) * 0.55, hx('#ff9a98'))
    if kind in ('closed', 'joy'):
        ts = np.linspace(-1, 1, 25)
        if kind == 'closed':
            pts = [(0.5 + 0.33 * t, 0.60 + 0.075 * (1 - t * t)) for t in ts]
        else:
            pts = [(0.5 + 0.33 * t, 0.56 - 0.13 * (1 - t * t)) for t in ts]
        w = [0.012 + 0.020 * (1 - abs(t) ** 2.5) for t in ts]
        c.paint(c.cov(stroke(xm, y, pts, w)), LASH)
        # 外眼角的小睫毛
        ex, ey = pts[0]
        c.paint(c.cov(stroke(xm, y, [(ex + 0.01, ey), (ex - 0.045, ey + (0.035 if kind == 'closed' else -0.03))],
                             [0.012, 0.003])), LASH)
        return c.out()
    a, b = (0.335, 0.385)
    irx, iry = (0.255, 0.335)
    if kind == 'wide':
        a, b, irx, iry = 0.35, 0.40, 0.185, 0.245
    shape = ell(xm, y, 0.5, CY, a, b, 2.6)
    lid = None
    if kind == 'relaxed':
        lid = (CY - b) + 0.30 + 0.0 * xm
    elif kind == 'sad':
        lid = (CY - b) + 0.17 + 0.20 * (0.5 - xm)   # 外眼角一侧更低
    inside = c.cov(shape)
    if lid is not None:
        inside = inside * np.clip((y - lid) / c.px + 0.5, 0, 1)
    # 眼白：上方带眼睑投下的蓝灰阴影
    k = np.clip((CY - b + 0.30 - y) / 0.28, 0, 1)[..., None]
    c.paint(inside, hx('#ffffff') * (1 - k) + hx('#c3cbea') * k)
    # 虹膜
    iy = CY + 0.035
    iris = ell(x, y, 0.5, iy, irx, iry)
    icol = vgrad((y - (iy - iry)) / (2 * iry), [(0.0, '#161c52'), (0.28, '#22378a'), (0.55, '#3566c8'),
                                                 (0.80, '#62a6ee'), (1.0, '#b4e2ff')])
    pup = np.clip(-ell(x, y, 0.5, iy - 0.05, irx * 0.42, iry * 0.46) / 0.03, 0, 1)[..., None]
    icol = icol * (1 - pup) + hx('#11173f') * pup
    cres = (np.clip(-ell(x, y, 0.5, iy + iry * 0.62, irx * 0.75, iry * 0.30) / 0.03, 0, 1) * 0.55)[..., None]
    icol = icol * (1 - cres) + hx('#cdeeff') * cres
    ring = (np.clip((iris + 0.035) / 0.035, 0, 1) * 0.9)[..., None]
    icol = icol * (1 - ring) + hx('#141a50') * ring
    top = (np.clip(((iy - iry) + 0.20 - y) / 0.18, 0, 1) * 0.5)[..., None]
    icol = icol * (1 - top) + hx('#10143c') * top
    c.paint(inside * c.cov(iris), icol)
    # 高光：左上大高光 + 右下小高光（左右眼同侧，不镜像）
    hl = inside * c.cov(iris - 0.01)
    c.paint(hl * c.cov(ell(x, y, 0.5 - irx * 0.42, iy - iry * 0.50, irx * 0.34, iry * 0.30)), hx('#ffffff'))
    c.paint(hl * c.cov(ell(x, y, 0.5 + irx * 0.45, iy + iry * 0.48, irx * 0.15, iry * 0.12)), hx('#ffffff'))
    # 上睫毛：沿上缘的粗弧，外眼角加粗并上挑
    if lid is None:
        ths = np.radians(np.linspace(196, -14, 30))
        pts, w = [], []
        for th in ths:
            cs, sn = np.cos(th), np.sin(th)
            r = (abs(cs) ** 2.6 + abs(sn) ** 2.6) ** (-1 / 2.6)
            pts.append((0.5 - a * r * cs * 1.02, CY - b * r * sn * 1.02))   # xm 坐标：外眼角在小的一侧
            deg = np.degrees(th)
            w.append(float(np.interp(deg, [-14, 10, 60, 100, 150, 196], [0.012, 0.040, 0.036, 0.030, 0.022, 0.008])))
        c.paint(c.cov(stroke(xm, y, pts, w)), LASH)
        ox, oy = 0.5 - a * 0.98, CY - b * 0.30
        c.paint(c.cov(stroke(xm, y, [(ox + 0.02, oy + 0.02), (ox - 0.065, oy - 0.075)], [0.030, 0.004])), LASH)
    else:
        xs = np.linspace(0.5 - a * 1.02, 0.5 + a * 1.02, 20)
        ly = [(CY - b) + (0.30 if kind == 'relaxed' else 0.17 + 0.20 * (0.5 - xx)) for xx in xs]
        ww = [0.014 + 0.022 * (1 - abs((xx - 0.5) / (a * 1.02)) ** 2) for xx in xs]
        c.paint(c.cov(stroke(xm, y, list(zip(xs, ly)), ww)), LASH)
        c.paint(c.cov(stroke(xm, y, [(xs[0] + 0.01, ly[0]), (xs[0] - 0.05, ly[0] + 0.03)], [0.016, 0.004])), LASH)
    # 下睫毛：外下方一小段细线
    ths = np.radians(np.linspace(-150, -100, 10))
    pts = []
    for th in ths:
        cs, sn = np.cos(th), np.sin(th)
        r = (abs(cs) ** 2.6 + abs(sn) ** 2.6) ** (-1 / 2.6)
        pts.append((0.5 + a * r * cs * 1.01, CY - b * r * sn * 1.01))
    c.paint(c.cov(stroke(xm, y, pts, [0.004 + 0.007 * np.sin(np.pi * i / 9) for i in range(10)])), LASH2)
    return c.out()


MLINE, MDARK, MTONGUE = hx('#8c3f52'), hx('#b8485c'), hx('#f7a0a2')


def draw_mouth(kind):
    c = Canvas(128)
    x, y = c.x, c.y
    ts = np.linspace(-1, 1, 17)

    def curve(w, y0, bend, thick=0.028):
        pts = [(0.5 + w * t, y0 + bend * (1 - t * t)) for t in ts]
        c.paint(c.cov(stroke(x, y, pts, [thick * (0.45 + 0.55 * (1 - abs(t) ** 2)) for t in ts])), MLINE)

    def opened(w, y0, depth, top=0.03):
        """上缘略弧、下缘半椭圆的张嘴形。"""
        sd = np.maximum(ell(x, y, 0.5, y0, w, depth), -(y - y0 + top * (1 - ((x - 0.5) / w) ** 2)))
        fill = vgrad((y - y0) / depth, [(0.0, '#a53e54'), (0.45, '#cf6276'), (0.62, '#f59a9d'), (1.0, '#ffb6b0')])
        c.paint(c.cov(sd), fill)
        c.paint(c.cov(np.abs(sd) - 0.022), MLINE)

    def round_(rx, ry):
        sd = ell(x, y, 0.5, 0.5, rx, ry)
        c.paint(c.cov(sd), vgrad((y - (0.5 - ry)) / (2 * ry), [(0, '#a53e54'), (0.6, '#cf6276'), (1, '#f59a9d')]))
        c.paint(c.cov(np.abs(sd) - 0.022), MLINE)
    if kind == 'smile':
        curve(0.17, 0.47, 0.06)
    elif kind == 'sad':
        curve(0.16, 0.54, -0.055)
    elif kind == 'aa':
        opened(0.24, 0.36, 0.34)
    elif kind == 'happy':
        opened(0.30, 0.30, 0.42)
    elif kind == 'ee':
        opened(0.27, 0.42, 0.16)
    elif kind == 'ou':
        round_(0.09, 0.11)
    elif kind == 'oh':
        round_(0.14, 0.19)
    elif kind == 'surprised':
        round_(0.11, 0.16)
    return c.out()


def main():
    os.makedirs(OUT, exist_ok=True)
    eyes = np.zeros((768, 1024, 4), np.uint8)
    for (col, row), kind in {(0, 0): 'open', (1, 0): 'closed', (0, 1): 'joy', (1, 1): 'wide', (0, 2): 'sad',
                             (1, 2): 'relaxed'}.items():
        # 画面左半是角色右眼（外眼角朝左），右半是角色左眼
        eyes[row * 256:(row + 1) * 256, col * 512:col * 512 + 256] = draw_eye(kind, mirror=False)
        eyes[row * 256:(row + 1) * 256, col * 512 + 256:(col + 1) * 512] = draw_eye(kind, mirror=True)
    write_png(os.path.join(OUT, 'eyes.png'), eyes)
    mouth = np.zeros((256, 512, 4), np.uint8)
    for i, kind in enumerate(['smile', 'aa', 'ou', 'happy', 'sad', 'surprised', 'ee', 'oh']):
        r, col = divmod(i, 4)
        mouth[r * 128:(r + 1) * 128, col * 128:(col + 1) * 128] = draw_mouth(kind)
    write_png(os.path.join(OUT, 'mouth.png'), mouth)
    # 围裙上的小鲸鱼图案：128×128，朝左，尾巴上翘，头顶喷水
    c = Canvas(128)
    x, y = c.x, c.y
    blue, light = hx('#4562b8'), hx('#b9cdf5')
    body = ell(x, y, 0.42, 0.60, 0.32, 0.21)
    tail = np.minimum.reduce([stroke(x, y, [(0.66, 0.58), (0.80, 0.46), (0.84, 0.30)], [0.085, 0.055, 0.035]),
                              ell(x, y, 0.74, 0.24, 0.10, 0.055), ell(x, y, 0.93, 0.25, 0.085, 0.05)])
    c.paint(c.cov(np.minimum(body, tail)), blue)
    c.paint(c.cov(np.maximum(ell(x, y, 0.40, 0.70, 0.24, 0.10), body + 0.02)), light)
    c.paint(c.cov(ell(x, y, 0.24, 0.56, 0.035, 0.035)), hx('#ffffff'))
    c.paint(c.cov(stroke(x, y, [(0.40, 0.36), (0.40, 0.22)], [0.018, 0.018])), blue)
    c.paint(c.cov(stroke(x, y, [(0.40, 0.22), (0.31, 0.15)], [0.018, 0.012])), blue)
    c.paint(c.cov(stroke(x, y, [(0.40, 0.22), (0.49, 0.15)], [0.018, 0.012])), blue)
    write_png(os.path.join(OUT, 'whale.png'), c.out())
    print('ATLAS ->', OUT)


main()
