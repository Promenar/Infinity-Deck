#!/usr/bin/env python3
"""点阵无穷应用图标的参数化生成器。

中心线是一条不对称的 Bernoulli 双纽线，丝带每圈翻转一次形成扭结，再沿丝带排出多条平行点线。
点按亮度和色相合并为少量路径，亮点各带一个径向渐变光晕，对应 VectorDrawable 中每个渐变一条路径。

产物写入 assets/branding/，由 native-ui/buildSrc 的 GenerateLauncherIcons 转成 Android 资源：
  infinity-dots-master.svg      108 × 108，background 与 foreground 两个图层；构建只取 background，
                                彩色前景改用 scripts/branding/GlowIcon.java 生成的发光位图
  infinity-dots-monochrome.svg  108 × 108，主题图标用的单色点阵（点更少、更大）
  infinity-notification.svg     24 × 24，通知栏用的实线无穷剪影
  infinity-dots-dark.svg / infinity-dots-light.svg  512 × 512 展示稿，取中心 72dp 可见区

用法：python3 scripts/branding/infinity_icon.py [--out DIR] [--check]
"""
from __future__ import annotations

import argparse
import math
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "assets/branding"
CANVAS = 108.0
SAFE_RADIUS = 32.5           # 66dp 安全圆半径，留 0.5dp 余量
VISIBLE_VIEW = (18, 18, 72, 72)

CYAN = (190, 238, 255)
BLUE = (120, 172, 255)
VIOLET = (190, 168, 255)
WHITE = (240, 248, 255)
LIGHT_INK = (27, 35, 80)


class Params:
    def __init__(self, **kw):
        self.width = 66.0          # 无穷主体在画布中的宽度
        self.cx, self.cy = 54.0, 55.0
        self.tilt = -11.0          # 整体倾斜角（度）
        self.strands = 7
        self.band = 0.25           # 丝带半宽（相对主体半宽）
        self.spacing = 1.55        # 点间距（画布单位）
        self.r_min, self.r_max = 0.26, 0.72
        self.halo_threshold = 0.74
        self.halo_scale = 3.4
        self.dust = 26
        self.tiers = 5
        self.__dict__.update(kw)


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(round(lerp(x, y, t)) for x, y in zip(c1, c2))


def hexc(c):
    return "#%02X%02X%02X" % c


def center(t):
    """不对称双纽线：右环更大更高，左环像透视里远去的一端。"""
    c, s = math.cos(t), math.sin(t)
    d = 1.0 + s * s
    x = c / d * (1.0 + 0.10 * c)
    y = 1.12 * s * c / d * (1.0 + 0.34 * c)
    y *= 1.0 - 0.35 * max(0.0, -c) ** 3
    return x, y


def normal(t):
    h = 1e-4
    x0, y0 = center(t - h)
    x1, y1 = center(t + h)
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy) or 1
    return -dy / n, dx / n


def half_width(t, p):
    # 丝带每圈翻转一次：cos(φ) 过零处收窄成扭结（左下交汇段），其它地方展开。
    phi = t / 2.0 + 0.55
    return p.band * (1.0 + 0.22 * math.cos(t)) * (0.16 + 0.84 * abs(math.cos(phi))), math.cos(phi)


def brightness(t, x):
    # 高光集中在左下入交汇段（t≈2.55），右环远端偏暗。
    d = math.atan2(math.sin(t - 2.55), math.cos(t - 2.55))
    hot = math.exp(-(d / 0.55) ** 2)
    return min(1.0, 0.50 + 0.12 * (1 - (x + 1) / 2) + 0.75 * hot)


def color_for(b, x):
    hue = mix(BLUE, VIOLET, max(0.0, min(1.0, (x + 0.2) / 1.2)))
    c = mix(hue, CYAN, max(0.0, (b - 0.45) / 0.55))
    if b > 0.85:
        c = mix(c, WHITE, (b - 0.85) / 0.15)
    return c


def to_canvas(x, y, p):
    s = p.width / 2.0
    a = math.radians(p.tilt)
    X, Y = x * s, -y * s
    return p.cx + X * math.cos(a) - Y * math.sin(a), p.cy + X * math.sin(a) + Y * math.cos(a)


def dots(p):
    """返回 (X, Y, 半径, 亮度, 中心线 x) 列表；每条点线先密采样，再按弧长等距取点。"""
    out = []
    n = p.strands
    for j in range(n):
        k = (j - (n - 1) / 2) / ((n - 1) / 2)
        acc, last = 0.0, None
        phase = (j % 2) * p.spacing / 2
        steps = 4000
        for i in range(steps):
            t = 2 * math.pi * i / steps
            x, y = center(t)
            nx, ny = normal(t)
            w, facing = half_width(t, p)
            X, Y = to_canvas(x + nx * w * k, y + ny * w * k, p)
            if last is not None:
                acc += math.hypot(X - last[0], Y - last[1])
            last = (X, Y)
            if acc >= p.spacing - phase:
                acc, phase = 0.0, 0.0
                b = brightness(t, x) * (1 - 0.22 * abs(k)) * (0.82 + 0.18 * (facing > 0))
                out.append((X, Y, lerp(p.r_min, p.r_max, b ** 1.3), b, x))
    return out


def fit(p, radius, cx=54.0, cy=54.0):
    """缩放并平移，使所有点（含半径）落在以 (cx, cy) 为心、radius 为半径的圆内。"""
    for _ in range(3):
        ds = dots(p)
        xs = [d[0] for d in ds]
        ys = [d[1] for d in ds]
        p.cx += cx - (min(xs) + max(xs)) / 2
        p.cy += cy - (min(ys) + max(ys)) / 2
        far = max(math.hypot(X - cx, Y - cy) + r for (X, Y, r, _, _) in dots(p))
        k = radius / far
        p.width *= k
        p.spacing *= k
        p.r_min *= k
        p.r_max *= k
    return p


def circle(X, Y, r):
    return "M%.2f %.2fa%.2f %.2f 0 1 0 %.2f 0a%.2f %.2f 0 1 0 %.2f 0Z" % (X - r, Y, r, r, 2 * r, r, r, -2 * r)


def radial(gid, cx, cy, r, stops):
    items = "".join('<stop offset="%g" stop-color="%s" stop-opacity="%g"/>' % s for s in stops)
    return '<radialGradient id="%s" cx="%.2f" cy="%.2f" r="%.2f" gradientUnits="userSpaceOnUse">%s</radialGradient>' % (
        gid, cx, cy, r, items)


def svg(defs, groups, size, view, title):
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="%g %g %g %g">' % (size, size, *view),
             "<title>%s</title>" % title]
    if defs:
        lines.append("<defs>" + "".join(defs) + "</defs>")
    for gid, paths in groups:
        lines.append('<g id="%s">' % gid)
        lines.extend(paths)
        lines.append("</g>")
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def color_layers(p):
    """彩色主图标：背景径向渐变；前景为环境光、逐点光晕、按亮度与色相分档的点和星尘。"""
    ds = dots(p)
    defs = [radial("bg", 54, 46, 76, [(0, "#18204A", 1), (0.55, "#0B0F26", 1), (1, "#04050C", 1)])]
    background = ['<path d="M0 0H108V108H0Z" fill="url(#bg)"/>']
    foreground = []
    k = p.width / 66.0
    for i, (hx, hy, hr, ha) in enumerate([(33, 63, 15, 0.30), (45, 58, 11, 0.20), (74, 46, 14, 0.10)]):
        X, Y, R = p.cx + (hx - 54) * k, p.cy + (hy - 55) * k, hr * k
        defs.append(radial("amb%d" % i, X, Y, R, [(0, "#4FA8FF", ha), (1, "#4FA8FF", 0)]))
        foreground.append('<path d="%s" fill="url(#amb%d)"/>' % (circle(X, Y, R), i))
    halos = 0
    for (X, Y, r, b, x) in ds:
        if b < p.halo_threshold:
            continue
        c = hexc(color_for(b, x))
        R = r * p.halo_scale
        defs.append(radial("h%d" % halos, X, Y, R, [(0, c, 0.55), (0.35, c, 0.18), (1, c, 0)]))
        foreground.append('<path d="%s" fill="url(#h%d)"/>' % (circle(X, Y, R), halos))
        halos += 1
    tiers = {}
    for (X, Y, r, b, x) in ds:
        tier = min(p.tiers - 1, int(b * p.tiers))
        hue = 0 if x < -0.25 else (1 if x < 0.35 else 2)
        tiers.setdefault((tier, hue), []).append(circle(X, Y, r))
    for (tier, hue), items in sorted(tiers.items()):
        mid = (tier + 0.5) / p.tiers
        c = hexc(color_for(mid, (-0.6, 0.05, 0.7)[hue]))
        foreground.append('<path d="%s" fill="%s" fill-opacity="%.2f"/>' % ("".join(items), c, 0.45 + 0.55 * mid))
    rnd = random.Random(7)
    dust = []
    for _ in range(p.dust):
        X = p.cx + (rnd.uniform(18, 42) - 54) * k
        Y = p.cy + (rnd.uniform(62, 78) - 55) * k
        dust.append(circle(X, Y, rnd.uniform(0.18, 0.42) * k))
    foreground.append('<path d="%s" fill="#7FC4FF" fill-opacity="0.55"/>' % "".join(dust))
    return defs, background, foreground


def light_layers(p):
    ds = dots(p)
    tiers = {}
    for (X, Y, r, b, _) in ds:
        tiers.setdefault(min(p.tiers - 1, int(b * p.tiers)), []).append(circle(X, Y, r))
    paths = ['<path d="%s" fill="%s" fill-opacity="%.2f"/>' % ("".join(items), hexc(LIGHT_INK), 0.45 + 0.55 * (tier + 0.5) / p.tiers)
             for tier, items in sorted(tiers.items())]
    return ['<path d="M0 0H108V108H0Z" fill="#F4F6FB"/>'], paths


def notification_path(size=24.0, stroke=2.0, padding=2.0):
    """沿中心线取点组成闭合折线，缩放到 size 画布内留 padding 的区域；描边后即实线剪影。"""
    p = Params()
    pts = [to_canvas(*center(2 * math.pi * i / 192), p) for i in range(192)]
    xs = [x for x, _ in pts]
    ys = [y for _, y in pts]
    live = size - 2 * padding - stroke
    k = live / max(max(xs) - min(xs), max(ys) - min(ys))
    mx, my = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    d = "".join(("M" if i == 0 else "L") + "%.2f %.2f" % (size / 2 + (x - mx) * k, size / 2 + (y - my) * k)
                for i, (x, y) in enumerate(pts)) + "Z"
    return '<path d="%s" fill="none" stroke="#000000" stroke-width="%g" stroke-linejoin="round"/>' % (d, stroke)


def build():
    color_p = fit(Params(), SAFE_RADIUS)
    mono_p = fit(Params(strands=4, spacing=2.3, r_min=0.6, r_max=1.05, band=0.24), SAFE_RADIUS)
    defs, background, foreground = color_layers(color_p)
    light_bg, light_fg = light_layers(color_p)
    mono = ['<path d="%s" fill="#000000"/>' % "".join(circle(X, Y, r) for (X, Y, r, _, _) in dots(mono_p))]
    full = (0, 0, CANVAS, CANVAS)
    return {
        "infinity-dots-master.svg": svg(defs, [("background", background), ("foreground", foreground)], 108, full,
                                        "Infinity Deck 点阵无穷"),
        "infinity-dots-monochrome.svg": svg([], [("foreground", mono)], 108, full, "Infinity Deck 单色主题图标"),
        "infinity-notification.svg": svg([], [("foreground", [notification_path()])], 24, (0, 0, 24, 24),
                                         "Infinity Deck 通知图标"),
        "infinity-dots-dark.svg": svg(defs, [("background", background), ("foreground", foreground)], 512, VISIBLE_VIEW,
                                      "Infinity Deck 点阵无穷（深色展示）"),
        "infinity-dots-light.svg": svg([], [("background", light_bg), ("foreground", light_fg)], 512, VISIBLE_VIEW,
                                       "Infinity Deck 点阵无穷（浅色展示）"),
    }


NUMBER = re.compile(r"-?\d+(?:\.\d+)?")


def same_geometry(a: str, b: str, tolerance: float = 0.011) -> bool:
    """比较两份 SVG：非数字部分必须一致，数字允许末位舍入差（不同平台 libm 的最后一位可能不同）。"""
    if NUMBER.sub("#", a) != NUMBER.sub("#", b):
        return False
    return all(abs(float(x) - float(y)) <= tolerance for x, y in zip(NUMBER.findall(a), NUMBER.findall(b)))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true", help="只比较已提交的产物与当前参数的生成结果")
    args = parser.parse_args(argv)
    stale = []
    for name, text in build().items():
        target = args.out / name
        if args.check:
            if not target.exists() or not same_geometry(target.read_text(encoding="utf-8"), text):
                stale.append(name)
        else:
            target.write_text(text, encoding="utf-8")
            print("%s %d bytes" % (name, len(text)))
    if stale:
        print("与生成器不一致，请运行 python3 scripts/branding/infinity_icon.py：" + "、".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
