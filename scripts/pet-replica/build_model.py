"""Q 版鲸鱼女仆复刻模型：Blender 无界面脚本建模、绑定并导出 glb。

用法：
  Blender --background --python build_model.py -- <输出.glb> <贴图目录> <rig.json>

产物：
- glb：带骨骼蒙皮与形态键的网格，材质只含基础色贴图；
- rig.json：人形骨骼映射、弹簧骨骼、碰撞体、表情与 MToon 参数，供 glb_to_vrm.py 写入 VRM 1.0 扩展。

坐标：Blender 坐标，Z 向上，角色面向 -Y，角色左手在 +X；整体高度 1.0 米（不含呆毛）。
"""
import json
import math
import os
import random
import sys

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from meshkit import (V, Part, catmull, chain_weights, clamp, ellipsoid, frill_strip, grid_solid, grid_surface, interp,
                     part_to_object, path_params, polar, smooth, sweep)

argv = sys.argv[sys.argv.index('--') + 1:]
OUT_GLB, TEX_DIR, RIG_JSON = argv[0], argv[1], argv[2]
rnd = random.Random(20261110)
PI = math.pi
PARTS = []


def part(name, mat, **kw):
    p = Part(name, mat, **kw)
    PARTS.append(p)
    return p


# ================================================================ 贴图坐标
HAIR_Z0, HAIR_Z1 = 0.08, 1.12
SKIN_SPAN, SKIN_ZC = 0.5, 0.762
SW = {'white': (0, 3), 'navy': (1, 3), 'shoe': (2, 3), 'sockw': (3, 3),
      'tail': (0, 2), 'belly': (1, 2), 'ear': (2, 2), 'earin': (3, 2),
      'lash': (0, 1), 'mouth': (1, 1), 'gold': (2, 1), 'black': (3, 1),
      'bowdark': (0, 0), 'frillshade': (1, 0), 'mouthline': (2, 0), 'bow': (3, 0)}


def sw(name, fu=0.5, fv=0.5):
    c, r = SW[name]
    return (0.5 + (c + fu) * 0.125, (r + fv) * 0.125)


def bicolor(left, x):
    """在图集中左右相邻的两个色块之间取 UV；x ∈ [-1, 1]，负值落在 left 色块。"""
    c, r = SW[left]
    return (0.5 + (c + 1.0 + 0.4 * clamp(x, -1, 1)) * 0.125, (r + 0.5) * 0.125)


def hair_uv(u, z):
    return (u, clamp((z - HAIR_Z0) / (HAIR_Z1 - HAIR_Z0), 0.004, 0.996))


def skin_uv(p):
    return (0.5 + p.x / SKIN_SPAN, 0.5 + (p.z - SKIN_ZC) / SKIN_SPAN)


SKIN_PLAIN = (0.06, 0.94)
W_HEAD = {'head': 1.0}

# ================================================================ 头部
HC = V(0, 0, 0.762)


def head_r(d):
    rx, rz = 0.195, 0.205
    ry = 0.185 if d.y < 0 else 0.205
    p = 2.3
    r = (abs(d.x / rx) ** p + abs(d.y / ry) ** p + abs(d.z / rz) ** p) ** (-1 / p)
    c = V(0, -1, -0.8).normalized()
    r += 0.030 * math.exp(-(d.angle(c) / 0.55) ** 2)  # 下颌前部隆起，让嘴部更靠前
    for sx in (-1, 1):
        c = V(sx * 0.75, -0.6, -0.5).normalized()
        r += 0.010 * math.exp(-(d.angle(c) / 0.4) ** 2)  # 脸颊
    return r


def head_p(d, off=0.0):
    d = d.normalized()
    return HC + d * (head_r(d) + off)


def sph(az, el):
    """方位角（自正前方向 +X）与仰角 → 单位方向。"""
    return V(math.cos(el) * math.sin(az), -math.cos(el) * math.cos(az), math.sin(el))


def face_pt(x, z, off=0.0):
    """脸部正面上 (x, z) 处的表面点，沿表面法线外推 off。"""
    def solve(xx, zz):
        lo, hi = -0.32, 0.0
        for _ in range(40):
            mid = (lo + hi) / 2
            p = V(xx, mid, zz) - HC
            if p.length - head_r(p.normalized()) > 0:
                lo = mid
            else:
                hi = mid
        return V(xx, (lo + hi) / 2, zz)
    p = solve(x, z)
    if off:
        e = 0.002
        n = (solve(x + e, z) - solve(x - e, z)).cross(solve(x, z + e) - solve(x, z - e))
        n.normalize()
        if n.y > 0:
            n = -n
        p = p + n * off
    return p


def build_head():
    pt = part('head', 'Skin')
    P = []
    for i in range(29):
        th = PI * i / 28
        P.append([head_p(V(math.sin(th) * math.sin(ph), -math.sin(th) * math.cos(ph), math.cos(th)))
                  for ph in [2 * PI * j / 40 for j in range(41)]])
    grid_surface(pt, P, lambda i, j, p: skin_uv(p), lambda i, j, p: W_HEAD)
    # 脖子
    nk = part('neck', 'Skin')
    zs = [0.50, 0.53, 0.56, 0.59]
    sweep(nk, [V(0, 0.005, z) for z in zs], [0.03] * 4, [0.028] * 4, lambda *a: SKIN_PLAIN,
          lambda i, t, p: chain_weights(-p.z, [-0.52, -0.56], ['neck', 'head'], 'upperChest'), nseg=12, n0=V(0, 1, 0))


# ================================================================ 脸部贴花：眼睛、睫毛、嘴
EYE_X, EYE_Z, EYE_A, EYE_B = 0.086, 0.664, 0.049, 0.055


def eye_outline(th):
    """眼眶轮廓（局部坐标，外眼角在 +x）。上缘略平、下缘圆。"""
    c, s = math.cos(th), math.sin(th)
    p = 2.5
    r = (abs(c) ** p + abs(s) ** p) ** (-1 / p)
    ex, ez = EYE_A * r * c, EYE_B * r * s
    if s > 0:
        ez *= 0.93
    return ex, ez


def eye_curves(xn):
    """各闭眼形态下的眼线高度（局部 z），xn 为归一化横坐标。"""
    q = 1 - xn * xn
    return {'close': -0.30 * EYE_B - 0.24 * EYE_B * q, 'joy': -0.28 * EYE_B + 0.46 * EYE_B * q}


def build_eyes():
    eye = part('eyes', 'Eye', obj='Face', weld=False, orient=V(0, -1, 0))
    line = part('lashes', 'FaceLine', obj='Face', weld=False, orient=V(0, -1, 0))
    for sx, side in ((1, 'L'), (-1, 'R')):
        def place(ex, ez, off):
            return face_pt(sx * (EYE_X + ex), EYE_Z + ez, off)

        def add(pt, ex, ez, off, uv, kind, f=1.0):
            vi = pt.vert(place(ex, ez, off), uv, W_HEAD)
            xn = clamp(ex / EYE_A, -1.15, 1.15)
            cv = eye_curves(clamp(xn, -1, 1))
            for mname, key in (('eyeClose_' + side, 'close'), ('eyeJoy_' + side, 'joy')):
                if kind == 'fill':
                    tz = cv[key]
                elif kind == 'upper':
                    tz = cv[key] + (f - 1.0) * EYE_B * 0.55
                else:
                    tz = cv[key] - (f - 1.0) * EYE_B * 0.25
                pt.set_morph(mname, vi, place(ex, tz, off))
            up = max(0.0, ez / EYE_B)
            # 惊讶：眼睛整体放大
            pt.set_morph('eyeWide', vi, place(ex * 1.05, ez * 1.10 + 0.002, off))
            # 难过：上缘压低，外眼角下垂
            pt.set_morph('eyeSad', vi, place(ex, ez - EYE_B * (0.16 * up + 0.16 * xn * (0.4 + up)), off))
            # 生气：内眼角压低
            pt.set_morph('eyeAngry', vi, place(ex, ez - EYE_B * (0.10 * up - 0.20 * xn * (0.3 + up)) - 0.004, off))
            return vi

        # 眼白与虹膜：同心环网格，UV 不镜像，供贴图变换统一移动虹膜
        NR, NS = 5, 28
        ring = []
        for r in range(NR + 1):
            row = []
            for k in range(NS):
                th = 2 * PI * k / NS
                ox, oz = eye_outline(th)
                ex, ez = ox * r / NR, oz * r / NR
                uv = (0.5 + sx * ex / (2 * EYE_A * 1.04), 0.5 + ez / (2 * EYE_B * 1.04))
                row.append(add(eye, ex, ez, 0.0022, uv, 'fill'))
            ring.append(row)
        for r in range(NR):
            for k in range(NS):
                k2 = (k + 1) % NS
                q = (ring[r][k], ring[r + 1][k], ring[r + 1][k2], ring[r][k2])
                eye.quad(*(q if sx > 0 else q[::-1]))
        # 上睫毛：沿上缘的粗弧线，外眼角上挑
        NL = 22
        a0, a1 = math.radians(-12), math.radians(196)
        inner, outer = [], []
        for k in range(NL + 1):
            th = a0 + (a1 - a0) * k / NL
            ox, oz = eye_outline(th)
            thick = interp([(-12, 0.05), (10, 0.22), (60, 0.20), (100, 0.17), (150, 0.12), (196, 0.03)], math.degrees(th))
            inner.append(add(line, ox * 0.955, oz * 0.955, 0.0034, sw('lash'), 'upper', 0.955))
            outer.append(add(line, ox * (1 + thick), oz * (1 + thick), 0.0034, sw('lash'), 'upper', 1 + thick))
        for k in range(NL):
            q = (inner[k], outer[k], outer[k + 1], inner[k + 1])
            line.quad(*(q if sx > 0 else q[::-1]))
        # 外眼角的睫毛尖
        ox, oz = eye_outline(math.radians(18))
        b0 = add(line, ox * 1.0, oz * 1.0, 0.0034, sw('lash'), 'upper', 1.0)
        ox2, oz2 = eye_outline(math.radians(40))
        b1 = add(line, ox2 * 1.12, oz2 * 1.12, 0.0034, sw('lash'), 'upper', 1.12)
        tip = add(line, EYE_A * 1.36, EYE_B * 0.50, 0.0034, sw('lash'), 'upper', 1.25)
        q = (b0, tip, b1)
        line.tri(*(q if sx > 0 else q[::-1]))
        # 下睫毛：外下方一小段细线
        NL2 = 8
        li, lo = [], []
        for k in range(NL2 + 1):
            th = math.radians(-78 + 56 * k / NL2)
            ox, oz = eye_outline(th)
            tk = 0.045 * math.sin(PI * k / NL2) + 0.012
            li.append(add(line, ox * 0.985, oz * 0.985, 0.0034, sw('lash'), 'lower', 0.985))
            lo.append(add(line, ox * (1 + tk), oz * (1 + tk), 0.0034, sw('lash'), 'lower', 1 + tk))
        for k in range(NL2):
            q = (li[k], lo[k], lo[k + 1], li[k + 1])
            line.quad(*(q if sx > 0 else q[::-1]))


def build_brows():
    line = part('brows', 'FaceLine', obj='Face', weld=False, orient=V(0, -1, 0))
    zb = EYE_Z + EYE_B + 0.020
    shapes = {
        'base': lambda u: 0.005 * math.sin(PI * (0.15 + 0.8 * u)),
        'eyeSad': lambda u: 0.012 - 0.020 * u,
        'eyeAngry': lambda u: -0.012 + 0.018 * u,
        'eyeWide': lambda u: 0.013 + 0.005 * math.sin(PI * (0.15 + 0.8 * u)),
        'eyeJoy': lambda u: 0.006 + 0.006 * math.sin(PI * (0.15 + 0.8 * u)),
    }
    n = 9
    for sx, side in ((1, 'L'), (-1, 'R')):
        def pos(u, dz, shape):
            x = sx * (0.040 + 0.070 * u)
            return face_pt(x, zb + shapes[shape](u) + dz, 0.040 - 0.012 * u)
        top, bot = [], []
        for k in range(n):
            u = k / (n - 1)
            th = 0.0022 * (0.45 + 0.55 * math.sin(PI * (0.1 + 0.8 * u)))
            for lst, dz in ((top, th), (bot, -th)):
                vi = line.vert(pos(u, dz, 'base'), sw('lash'), W_HEAD)
                for mn, key in (('eyeSad', 'eyeSad'), ('eyeAngry', 'eyeAngry'), ('eyeWide', 'eyeWide'),
                                ('eyeJoy_' + side, 'eyeJoy')):
                    line.set_morph(mn, vi, pos(u, dz, key))
                lst.append(vi)
        for k in range(n - 1):
            q = (top[k], bot[k], bot[k + 1], top[k + 1])
            line.quad(*(q if sx > 0 else q[::-1]))


MOUTH_Z = 0.596
MOUTH_SHAPES = {}


def _mouth_defs():
    def closed(w, c0, c1):
        f = lambda xn: c0 + c1 * (1 - xn * xn)
        return (w, f, f)

    def opened(w, t0, t1, b0, b1):
        return (w, lambda xn: t0 + t1 * math.sqrt(max(0.0, 1 - xn * xn)),
                lambda xn: b0 - b1 * math.sqrt(max(0.0, 1 - xn * xn)))
    MOUTH_SHAPES.update({
        'base': closed(0.032, 0.0018, -0.0048),
        'mouthAa': opened(0.060, 0.004, 0.002, 0.004, 0.040),
        'mouthIh': opened(0.046, 0.003, 0.001, 0.003, 0.016),
        'mouthOu': opened(0.020, 0.0, 0.008, 0.0, 0.012),
        'mouthEe': opened(0.058, 0.003, 0.001, 0.003, 0.011),
        'mouthOh': opened(0.032, 0.0, 0.013, 0.0, 0.021),
        'mouthSmile': closed(0.052, 0.0050, -0.0125),
        'mouthFrown': closed(0.034, -0.0045, 0.0065),
        'mouthO': opened(0.026, 0.0, 0.010, 0.0, 0.016),
    })


def build_mouth():
    _mouth_defs()
    fill = part('mouth', 'FaceLine', obj='Face', weld=False)
    NC, NRW = 17, 6

    def pos(shape, xn, r, dz=0.0, off=0.0021):
        w, top, bot = MOUTH_SHAPES[shape]
        z = top(xn) * (1 - r) + bot(xn) * r + dz
        return face_pt(w / 2 * xn, MOUTH_Z + z, off)

    def add(xn, r, uv, dz=0.0, off=0.0021):
        vi = fill.vert(pos('base', xn, r, dz, off), uv, W_HEAD)
        for name in MOUTH_SHAPES:
            if name != 'base':
                fill.set_morph(name, vi, pos(name, xn, r, dz, off))
        return vi

    xs = [-1 + 2 * j / (NC - 1) for j in range(NC)]
    g = [[add(xn, i / (NRW - 1), sw('mouth', 0.5, 0.92 - 0.84 * i / (NRW - 1))) for xn in xs] for i in range(NRW)]
    for i in range(NRW - 1):
        for j in range(NC - 1):
            fill.quad(g[i][j], g[i + 1][j], g[i + 1][j + 1], g[i][j + 1])
    # 轮廓线：上唇线与下唇线各一条细带，闭嘴时重合成一条嘴线
    th = 0.0017
    for r, name in ((0.0, 'top'), (1.0, 'bot')):
        a = [add(xn, r, sw('mouthline'), +th * (0.35 + 0.65 * math.sqrt(max(0, 1 - xn * xn))), 0.0031) for xn in xs]
        b = [add(xn, r, sw('mouthline'), -th * (0.35 + 0.65 * math.sqrt(max(0, 1 - xn * xn))), 0.0031) for xn in xs]
        for j in range(NC - 1):
            fill.quad(a[j], b[j], b[j + 1], a[j + 1])


# ================================================================ 头发
def cap_window(d):
    """脸部开窗：发帽不覆盖的方向。"""
    az = math.atan2(d.x, -d.y)
    el = math.asin(clamp(d.z, -1, 1))
    lim = math.radians(66)
    return d.y < 0 and abs(az) < lim and el < math.radians(40) - math.radians(16) * (az / lim) ** 2


def cap_off(d):
    return 0.022 + 0.014 * max(0.0, d.z)


def build_hair_cap():
    pt = part('hair_cap', 'Hair')
    P, D = [], []
    for i in range(27):
        th = math.radians(128) * i / 26
        row, drow = [], []
        for j in range(49):
            ph = 2 * PI * j / 48
            d = V(math.sin(th) * math.sin(ph), -math.sin(th) * math.cos(ph), math.cos(th))
            row.append(head_p(d, cap_off(d)))
            drow.append(d)
        P.append(row)
        D.append(drow)

    def skip(i, j):
        d = (D[i][j] + D[i][j + 1] + D[i + 1][j] + D[i + 1][j + 1]).normalized()
        return cap_window(d)
    grid_surface(pt, P, lambda i, j, p: hair_uv(j / 48 * 4, p.z), lambda i, j, p: W_HEAD, skip_fn=skip)


def build_bangs():
    pt = part('hair_bangs', 'Hair')
    prof = [(0, 0.5), (0.3, 0.95), (0.70, 1.0), (0.88, 0.62), (1, 0)]
    # (发梢方向, 半宽, 发根横向偏移, 发梢贴脸距离)
    locks = [
        (face_pt(0.010, 0.672) - HC, 0.026, 0.02, 0.012),
        (face_pt(-0.052, 0.735) - HC, 0.058, -0.09, 0.012),
        (face_pt(-0.128, 0.712) - HC, 0.060, -0.26, 0.013),
        (face_pt(0.062, 0.737) - HC, 0.058, 0.11, 0.012),
        (face_pt(0.134, 0.714) - HC, 0.060, 0.28, 0.013),
        (sph(math.radians(-67), math.radians(-40)), 0.030, -0.46, 0.014),
        (sph(math.radians(68), math.radians(-39)), 0.030, 0.48, 0.014),
    ]
    for li, (dt, hw0, rx, tip_off) in enumerate(locks):
        d1 = dt.normalized()
        d0 = V(rx, -0.22, 1.0).normalized()
        n = 14
        pts, dirs = [], []
        for k in range(n):
            t = k / (n - 1)
            d = d0.slerp(d1, t)
            off = 0.044 + (tip_off - 0.044) * (t ** 0.75)
            pts.append(head_p(d, off))
            dirs.append(d)
        hw = [hw0 * interp(prof, k / (n - 1)) for k in range(n)]
        ht = [max(0.0, min(0.012, w * 0.30)) for w in hw]
        u0 = rnd.random()
        sweep(pt, pts, hw, ht, lambda i, t, k, p, ca, sa: hair_uv(u0 + 0.5 + 0.5 * ca, p.z),
              lambda i, t, p: W_HEAD, nseg=8, normal_fn=lambda i, p: dirs[i])


# 长发披风的半径随高度变化
CLOAK_R = [(0.10, 0.40), (0.18, 0.435), (0.28, 0.425), (0.40, 0.365), (0.52, 0.30), (0.62, 0.25), (0.70, 0.218),
           (0.76, 0.205)]
HAIR_CHAIN_PHI = [70, 105, 140, 180, 220, 255, 290]  # 度，自正前方向 +X
HAIR_LEVELS = [0.70, 0.56, 0.42, 0.28]
HAIR_END = 0.14


def cloak_scale(phi_deg):
    """靠前的发束贴身一些。"""
    a = abs(((phi_deg + 180) % 360) - 180)
    return 0.86 + 0.14 * smooth((a - 72) / 40)


def hair_weights(phi_deg, z):
    ph = phi_deg % 360
    ph = clamp(ph, HAIR_CHAIN_PHI[0], HAIR_CHAIN_PHI[-1])
    for c in range(len(HAIR_CHAIN_PHI) - 1):
        if ph <= HAIR_CHAIN_PHI[c + 1]:
            f = (ph - HAIR_CHAIN_PHI[c]) / (HAIR_CHAIN_PHI[c + 1] - HAIR_CHAIN_PHI[c])
            break
    w = {}
    for ci, cw in ((c, 1 - f), (c + 1, f)):
        if cw <= 1e-3:
            continue
        names = ['hair%d_%d' % (ci, k) for k in range(len(HAIR_LEVELS))]
        for name, val in chain_weights(z, HAIR_LEVELS, names, 'head', end=HAIR_END).items():
            w[name] = w.get(name, 0.0) + val * cw
    return w


def build_long_hair():
    pt = part('hair_long', 'Hair')
    prof = [(0, 0.5), (0.12, 0.85), (0.5, 1.0), (0.8, 0.82), (0.93, 0.5), (1, 0)]
    locks = []
    for k, a in enumerate((76, 92, 108, 124, 140, 156, 172)):
        for s in (1, -1):
            locks.append((s * a, 0.012 if k % 2 == 0 else -0.010, 1.0))  # 相邻发束里外错开，叠出层次与描边
    for a in (84, 116, 148, 180):
        for s in ((1, -1) if a != 180 else (1,)):
            locks.append((s * a, -0.035, 0.82))
    for phi_deg, dr, lenk in locks:
        a = abs(phi_deg)
        z_top = 0.745
        z_bot = interp([(76, 0.36), (92, 0.25), (108, 0.15), (180, 0.12)], a) + rnd.uniform(0.0, 0.04)
        z_bot = z_top - (z_top - z_bot) * lenk
        ks = cloak_scale(phi_deg)
        f = rnd.uniform(1.15, 1.6)
        ph0 = rnd.uniform(0, 2 * PI)
        amp_phi = rnd.uniform(0.13, 0.20)
        amp_r = rnd.uniform(0.030, 0.045)
        curl_t = rnd.uniform(0.72, 0.80)
        curl_turn = rnd.uniform(1.6, 2.6)
        curl_r = rnd.uniform(0.030, 0.048)
        curl_dir = rnd.choice([1, 1, -1])
        n = 30
        pts = []
        for k in range(n):
            t = k / (n - 1)
            z = z_top + (z_bot - z_top) * t
            s = smooth((t - 0.28) / 0.72) ** 1.3
            R = interp(CLOAK_R, z)
            R = 0.20 + (R - 0.20) * ks + dr * smooth((0.72 - z) / 0.15)
            wob = math.sin(2 * PI * f * t + ph0)
            R += amp_r * s * math.cos(2 * PI * f * t + ph0)
            phi = math.radians(phi_deg) + amp_phi * s * wob
            ky = 1.06 if math.cos(phi) < 0 else 1.0
            p = polar(phi, R, z, ky)
            s2 = clamp((t - curl_t) / (1 - curl_t))
            if s2 > 0:  # 发梢向外或向内卷起
                ang = curl_turn * s2 ** 1.15
                rad = polar(phi, 1.0, 0.0)
                p = p + rad * (curl_dir * curl_r * math.sin(ang)) + V(0, 0, curl_r * (1 - math.cos(ang)) * 0.9)
            pts.append(p)
        hw = [max(0.05, interp(CLOAK_R, p.z)) * math.radians(16) * 0.60 * interp(prof, k / (n - 1))
              for k, p in enumerate(pts)]
        ht = [min(0.022, w * 0.36) for w in hw]
        u0 = rnd.random()
        sweep(pt, pts, hw, ht, lambda i, t, k, p, ca, sa: hair_uv(u0 + 0.5 + 0.5 * ca, p.z),
              lambda i, t, p: hair_weights(phi_deg, p.z), nseg=8,
              normal_fn=lambda i, p: V(p.x, p.y, 0))
    # 内层发体：挡住发束之间的缝隙
    core = part('hair_core', 'Hair')
    P = []
    zs = [0.73 - (0.73 - 0.27) * i / 12 for i in range(13)]
    phs = [math.radians(86 + (274 - 86) * j / 24) for j in range(25)]
    for z in zs:
        R = 0.20 + (interp(CLOAK_R, z) - 0.20) * 0.9 - 0.05 * smooth((0.70 - z) / 0.15)
        P.append([polar(ph, R, z, 1.06 if math.cos(ph) < 0 else 1.0) for ph in phs])
    grid_solid(core, P, 0.012, lambda i, j, p, layer: hair_uv(j / 24 * 3, p.z),
               lambda i, j, p: hair_weights(math.degrees(phs[j]), p.z), hint_fn=lambda p: V(p.x, p.y, 0))


AHOGE = [V(-0.02, 0.0, 0.985), V(-0.028, 0.0, 1.04), V(-0.07, 0.0, 1.072), V(-0.125, 0.0, 1.066),
         V(-0.162, 0.0, 1.03), V(-0.158, 0.0, 0.998), V(-0.138, 0.0, 0.985)]


def build_ahoge():
    pt = part('hair_ahoge', 'Hair')
    pts = catmull(AHOGE, 6)
    ts = path_params(pts)
    r = [0.0125 * (1 - 0.7 * t) if t < 0.98 else 0.0 for t in ts]

    def w(i, t, p):
        return chain_weights(-t, [0.0, -0.3, -0.62], ['ahoge0', 'ahoge1', 'ahoge2'], 'head')
    sweep(pt, pts, r, r, lambda i, t, k, p, ca, sa: hair_uv(0.3 + 0.2 * k, 0.9), w, nseg=8, n0=V(0, 1, 0))


# ================================================================ 头饰、蝴蝶结、鲸鳍耳
def build_headdress():
    pt = part('headdress', 'Cloth')
    n = 73
    base, out, dirs = [], [], []
    for j in range(n):
        al = math.radians(-100 + 200 * j / (n - 1))
        d = V(math.sin(al), -0.11 * max(0.0, math.cos(al)) - 0.02, math.cos(al)).normalized()
        base.append(head_p(d, cap_off(d) + 0.002))
        out.append(d)
        dirs.append(d)
    frill_strip(pt, base, out, 0.047, 0.014, 11, lambda i, j, p, layer: sw('white'),
                lambda i, j, p: W_HEAD, rows=5, thick=0.006, scallop=0.14, hint_fn=lambda p: V(0, -1, 0))
    band = part('headband', 'Cloth')
    sweep(band, [b + d * 0.002 for b, d in zip(base, dirs)], [0.016] * n, [0.005] * n,
          lambda *a: sw('white'), lambda i, t, p: W_HEAD, nseg=8, normal_fn=lambda i, p: dirs[i])


def build_bow(pt, center, axis, up, size, col='bow', knot='bowdark', w=None):
    """蝴蝶结：两个向中心收拢的扁环 + 中间的结。axis 为左右方向，up 为上方向。"""
    w = w or W_HEAD
    nrm = axis.cross(up).normalized()
    up = nrm.cross(axis).normalized()
    for s in (-1, 1):
        def deform(l, s=s):
            k = 0.30 + 0.70 * smooth((l.x * s + 1) / 2 * 1.15)
            return V(l.x, l.y * (0.55 + 0.45 * k), l.z * k)
        ellipsoid(pt, center + axis * (s * size * 0.52), (size * 0.55, size * 0.17, size * 0.42),
                  lambda i, j, p: sw(col), lambda i, j, p: w, nu=12, nv=8, deform=deform, axes=(axis, nrm, up))
    ellipsoid(pt, center + nrm * (size * 0.05), (size * 0.17, size * 0.17, size * 0.2),
              lambda i, j, p: sw(knot), lambda i, j, p: w, nu=10, nv=6, axes=(axis, nrm, up))


def build_hair_bows():
    pt = part('hair_bows', 'Cloth')
    for sx in (1, -1):
        d = sph(sx * math.radians(64), math.radians(20))
        c = head_p(d, cap_off(d) + 0.024)
        axis = V(0, 0, 1).cross(d).normalized()
        build_bow(pt, c, axis, V(0, 0, 1), 0.086)


EAR_ROOT = {}


def build_ears():
    pt = part('ears', 'Cloth')
    for sx, side in ((1, 'L'), (-1, 'R')):
        root = V(sx * 0.200, 0.02, 0.700)
        ctrl = [root, V(sx * 0.27, 0.035, 0.695), V(sx * 0.335, 0.05, 0.668), V(sx * 0.388, 0.06, 0.628)]
        EAR_ROOT[side] = (root, ctrl[-1])
        pts = catmull(ctrl, 6)
        ts = path_params(pts)
        hw = [interp([(0, 0.024), (0.3, 0.050), (0.62, 0.040), (0.9, 0.016), (1, 0)], t) for t in ts]
        ht = [min(0.013, w * 0.4) for w in hw]
        sweep(pt, pts, hw, ht, lambda i, t, k, p, ca, sa: bicolor('ear', (-sa - 0.25) * 4),
              lambda i, t, p: {'ear' + side: 1.0}, nseg=10, normal_fn=lambda i, p: V(0, -1, 0.6))
        # 耳根下方的白色荷叶边
        n = 15
        base = [root + (ctrl[2] - root) * (0.05 + 0.70 * j / (n - 1)) + V(0, -0.006, -0.030) for j in range(n)]
        frill_strip(pt, base, [V(0, -0.2, -1)] * n, 0.026, 0.006, 4, lambda i, j, p, layer: sw('earin'),
                    lambda i, j, p: {'ear' + side: 1.0}, rows=3, thick=0.005, scallop=0.15,
                    hint_fn=lambda p: V(0, -1, 0.3))


# ================================================================ 身体与服装
TORSO = [(0.380, 0.0), (0.385, 0.064), (0.42, 0.071), (0.47, 0.082), (0.505, 0.078), (0.53, 0.058), (0.548, 0.034),
         (0.552, 0.0)]


def torso_w(z):
    return chain_weights(-z, [-0.30, -0.36, -0.42, -0.47, -0.52], ['hips', 'spine', 'chest', 'upperChest', 'neck'],
                         'hips')


def torso_pt(phi, z, off=0.0):
    r = interp(TORSO, z) + off
    return V(r * math.sin(phi), -r * math.cos(phi) * 0.80, z)


def build_torso():
    pt = part('torso', 'Cloth')
    zs = [0.380, 0.385, 0.40, 0.42, 0.445, 0.47, 0.49, 0.505, 0.52, 0.53, 0.54, 0.548, 0.552]
    P = [[torso_pt(2 * PI * j / 24, z) for j in range(25)] for z in zs]
    grid_surface(pt, P, lambda i, j, p: sw('navy'), lambda i, j, p: torso_w(p.z))
    # 围裙上身：胸前白色小片
    bib = part('bib', 'Cloth')
    P = [[torso_pt(-0.62 + 1.24 * j / 8, z, 0.005) for j in range(9)] for z in [0.505, 0.48, 0.455, 0.43, 0.405]]
    grid_solid(bib, P, 0.004, lambda i, j, p, layer: sw('white'), lambda i, j, p: torso_w(p.z),
               hint_fn=lambda p: V(0, -1, 0))
    # 领子：白色圆领
    col = part('collar', 'Cloth')
    n = 33
    base = [V(0.036 * math.sin(2 * PI * j / (n - 1)), 0.004 - 0.034 * math.cos(2 * PI * j / (n - 1)), 0.547)
            for j in range(n)]
    out = [V(math.sin(2 * PI * j / (n - 1)), -math.cos(2 * PI * j / (n - 1)), -0.55) for j in range(n)]
    frill_strip(col, base, out, 0.036, 0.0, 1, lambda i, j, p, layer: sw('white'),
                lambda i, j, p: {'upperChest': 0.6, 'neck': 0.4}, rows=3, thick=0.005, closed=True,
                hint_fn=lambda p: V(p.x, p.y, 0.5))
    # 领口黑色蝴蝶结
    tie = part('collar_bow', 'Cloth')
    build_bow(tie, V(0, -0.072, 0.522), V(1, 0, 0), V(0, 0, 1), 0.034, col='black', knot='gold', w={'upperChest': 1.0})
    # 肩部荷叶边（围裙肩带）
    sh = part('shoulder_frill', 'Cloth')
    for sx in (1, -1):
        n = 21
        base, out = [], []
        for j in range(n):
            al = math.radians(-75 + 150 * j / (n - 1))
            base.append(V(sx * 0.058, 0.066 * math.sin(al), 0.468 + 0.074 * math.cos(al)))
            out.append(V(sx * 1.0, 0.25 * math.sin(al), 0.45 * math.cos(al)))
        frill_strip(sh, base, out, 0.040, 0.007, 5, lambda i, j, p, layer: sw('white' if layer == 0 else 'frillshade'),
                    lambda i, j, p: {'upperChest': 0.7, ('leftShoulder' if sx > 0 else 'rightShoulder'): 0.3},
                    rows=4, thick=0.005, scallop=0.08, hint_fn=lambda p, sx=sx: V(sx * 0.3, 0, 1))


SKIRT_TOP, SKIRT_HEM = 0.402, 0.232
SKIRT_PHI = [0, 45, 90, 135, 180, 225, 270, 315]
SKIRT_LEVELS = [0.385, 0.305]
SKIRT_END = 0.21


def skirt_R(z):
    t = clamp((SKIRT_TOP - z) / (SKIRT_TOP - SKIRT_HEM))
    return 0.068 + 0.158 * (1 - (1 - t) ** 2.2)


def skirt_pt(phi, z, off=0.0):
    t = clamp((SKIRT_TOP - z) / (SKIRT_TOP - SKIRT_HEM))
    R = skirt_R(z) + off + 0.008 * t * t * math.cos(9 * phi)
    return V(R * math.sin(phi), -R * math.cos(phi) * 0.94, z)


def skirt_w(phi, z):
    ph = math.degrees(phi) % 360
    c = int(ph // 45) % 8
    f = (ph - c * 45) / 45
    w = {}
    for ci, cw in ((c, 1 - f), ((c + 1) % 8, f)):
        if cw <= 1e-3:
            continue
        names = ['skirt%d_%d' % (ci, k) for k in range(2)]
        for name, val in chain_weights(z, SKIRT_LEVELS, names, 'hips', end=SKIRT_END).items():
            w[name] = w.get(name, 0.0) + val * cw
    return w


def build_skirt():
    pt = part('skirt', 'Cloth')
    nz, nj = 12, 72
    zs = [SKIRT_TOP - (SKIRT_TOP - SKIRT_HEM) * (i / (nz - 1)) for i in range(nz)]
    phs = [2 * PI * j / nj for j in range(nj + 1)]
    P = [[skirt_pt(ph, z) for ph in phs] for z in zs]
    grid_solid(pt, P, 0.006,
               lambda i, j, p, layer: (j / nj, 1.0 - 0.5 * i / (nz - 1)) if layer == 0 else sw('navy'),
               lambda i, j, p: skirt_w(phs[j], p.z), closed_u=True, hint_fn=lambda p: V(p.x, p.y, 0))
    # 裙摆白色荷叶边
    fr = part('skirt_frill', 'Cloth')
    n = 145
    base = [skirt_pt(2 * PI * j / (n - 1), SKIRT_HEM + 0.012, -0.012) for j in range(n)]
    out = [V(math.sin(2 * PI * j / (n - 1)) * 0.42, -math.cos(2 * PI * j / (n - 1)) * 0.42, -1.0) for j in range(n)]
    frill_strip(fr, base, out, 0.072, 0.012, 18, lambda i, j, p, layer: sw('white' if layer == 0 else 'frillshade'),
                lambda i, j, p: skirt_w(2 * PI * j / (n - 1), p.z), rows=5, thick=0.006, closed=True, scallop=0.05,
                hint_fn=lambda p: V(p.x, p.y, 0))
    # 腰带
    wb = part('waistband', 'Cloth')
    n = 41
    ring = [V(0.071 * math.sin(2 * PI * j / (n - 1)), -0.071 * 0.86 * math.cos(2 * PI * j / (n - 1)), 0.400)
            for j in range(n)]
    sweep(wb, ring, [0.012] * n, [0.006] * n, lambda *a: sw('white'), lambda i, t, p: {'hips': 0.5, 'spine': 0.5},
          nseg=8, normal_fn=lambda i, p: V(p.x, p.y, 0), closed=True)


APRON_TOP, APRON_BOT, APRON_PHI = 0.396, 0.268, 0.80


def apron_half(z):
    s = clamp((APRON_TOP - z) / (APRON_TOP - APRON_BOT))
    return APRON_PHI * (0.55 + 0.45 * smooth(s / 0.25)) * (1 - 0.42 * s ** 4)


def build_apron():
    pt = part('apron', 'Cloth')
    nr, nc = 12, 17
    P, PH = [], []
    for i in range(nr):
        z = APRON_TOP - (APRON_TOP - APRON_BOT) * i / (nr - 1)
        h = apron_half(z)
        row = [(-h + 2 * h * j / (nc - 1)) for j in range(nc)]
        PH.append(row)
        P.append([skirt_pt(ph, z, 0.011) for ph in row])
    grid_solid(pt, P, 0.004, lambda i, j, p, layer: (0.03 + 0.44 * j / (nc - 1), 0.47 - 0.17 * i / (nr - 1)),
               lambda i, j, p: skirt_w(PH[i][j], p.z), hint_fn=lambda p: V(p.x, p.y, 0))
    # 围裙荷叶边：沿两侧与下缘
    fr = part('apron_frill', 'Cloth')
    path = []
    for i in range(nr):
        z = APRON_TOP - (APRON_TOP - APRON_BOT) * i / (nr - 1)
        path.append((-apron_half(z), z))
    hb = apron_half(APRON_BOT)
    for j in range(1, 16):
        path.append((-hb + 2 * hb * j / 16, APRON_BOT))
    for i in range(nr - 1, -1, -1):
        z = APRON_TOP - (APRON_TOP - APRON_BOT) * i / (nr - 1)
        path.append((apron_half(z), z))
    base = [skirt_pt(ph, z, 0.012) for ph, z in path]
    n = len(base)
    cen = skirt_pt(0, (APRON_TOP + APRON_BOT) / 2 + 0.02, 0.012)
    out = []
    for j in range(n):
        a, b = base[max(j - 1, 0)], base[min(j + 1, n - 1)]
        tan = (b - a).normalized()
        radial = V(base[j].x, base[j].y, 0).normalized()
        o = tan.cross(radial)
        if o.dot(base[j] - cen) < 0:
            o = -o
        out.append(o)
    frill_strip(fr, base, out, 0.026, 0.006, 15, lambda i, j, p, layer: sw('white' if layer == 0 else 'frillshade'),
                lambda i, j, p: skirt_w(path[j][0], p.z), rows=4, thick=0.004, scallop=0.08,
                hint_fn=lambda p: V(p.x, p.y, 0))
    # 背后的白色大蝴蝶结与飘带
    bb = part('apron_bow', 'Cloth')
    build_bow(bb, V(0, 0.078, 0.395), V(1, 0, 0), V(0, 0, 1), 0.085, col='white', knot='white',
              w={'hips': 0.5, 'spine': 0.5})
    for sx in (1, -1):
        P = []
        for i in range(8):
            t = i / 7
            z = 0.39 - 0.16 * t
            ph = PI + sx * (0.10 + 0.16 * t)
            hwid = 0.022 + 0.012 * t
            row = []
            for j in range(4):
                dphi = (j / 3 - 0.5) * 2 * hwid / max(0.06, skirt_R(z))
                row.append(skirt_pt(ph + dphi, z, 0.012 + 0.004 * math.sin(t * 5)))
            P.append(row)
        grid_solid(bb, P, 0.004, lambda i, j, p, layer: sw('white'),
                   lambda i, j, p: skirt_w(math.atan2(p.x, -p.y), p.z), hint_fn=lambda p: V(p.x, p.y, 0))


def build_arms():
    sl = part('sleeves', 'Cloth')
    cf = part('cuffs', 'Cloth')
    hd = part('hands', 'Skin')
    Z = 0.515
    for sx, side in ((1, 'left'), (-1, 'right')):
        names = [side + 'UpperArm', side + 'LowerArm', side + 'Hand']

        def aw(p, names=names, side=side):
            w = chain_weights(-abs(p.x), [-0.07, -0.16, -0.245], names, side + 'Shoulder')
            if abs(p.x) < 0.07:
                k = clamp((0.07 - abs(p.x)) / 0.03)
                w = {side + 'Shoulder': 1 - 0.6 * k, 'upperChest': 0.6 * k}
            return w
        xs = [0.045, 0.06, 0.075, 0.09, 0.105, 0.125, 0.15, 0.175, 0.20, 0.22, 0.232]
        rr = [interp([(0.045, 0.0), (0.06, 0.04), (0.085, 0.055), (0.12, 0.052), (0.17, 0.043), (0.215, 0.037),
                      (0.232, 0.035)], x) for x in xs]
        sweep(sl, [V(sx * x, 0, Z) for x in xs], rr, rr, lambda *a: sw('navy'), lambda i, t, p: aw(p), nseg=14,
              n0=V(0, 0, 1))
        # 袖口：白色袖带 + 荷叶边 + 黑色纽扣
        xs2 = [0.222, 0.226, 0.236, 0.246, 0.250]
        r2 = [0.0, 0.041, 0.042, 0.041, 0.0]
        sweep(cf, [V(sx * x, 0, Z) for x in xs2], r2, r2, lambda *a: sw('white'), lambda i, t, p: aw(p), nseg=14,
              n0=V(0, 0, 1))
        n = 29
        base = [V(sx * 0.246, 0.036 * math.sin(2 * PI * j / (n - 1)), Z + 0.036 * math.cos(2 * PI * j / (n - 1)))
                for j in range(n)]
        out = [V(sx * 0.9, math.sin(2 * PI * j / (n - 1)) * 0.75, math.cos(2 * PI * j / (n - 1)) * 0.75)
               for j in range(n)]
        frill_strip(cf, base, out, 0.024, 0.005, 8, lambda i, j, p, layer: sw('white' if layer == 0 else 'frillshade'),
                    lambda i, j, p: aw(p), rows=3, thick=0.004, closed=True,
                    hint_fn=lambda p: V(0, p.y, p.z - Z))
        ellipsoid(cf, V(sx * 0.236, -0.03, Z + 0.03), (0.007, 0.007, 0.007), lambda i, j, p: sw('black'),
                  lambda i, j, p: aw(p), nu=8, nv=6)
        # 手：连指小圆手 + 拇指
        ellipsoid(hd, V(sx * 0.272, 0, Z), (0.036, 0.027, 0.030), lambda i, j, p: SKIN_PLAIN,
                  lambda i, j, p: {side + 'Hand': 1.0}, nu=14, nv=10)
        ellipsoid(hd, V(sx * 0.262, -0.022, Z + 0.006), (0.016, 0.012, 0.012), lambda i, j, p: SKIN_PLAIN,
                  lambda i, j, p: {side + 'Hand': 1.0}, nu=10, nv=6)


LEG_X = 0.057


def build_legs():
    sk = part('legs', 'Skin')
    so = part('socks', 'Cloth')
    sh = part('shoes', 'Cloth')
    for sx, side in ((1, 'left'), (-1, 'right')):
        names = [side + 'UpperLeg', side + 'LowerLeg', side + 'Foot']

        def lw(p, names=names):
            return chain_weights(p.z, [0.30, 0.18, 0.065], names, 'hips')
        zs = [0.325, 0.31, 0.27, 0.22, 0.18, 0.15, 0.12]
        rr = [0.0, 0.040, 0.041, 0.039, 0.036, 0.034, 0.033]
        sweep(sk, [V(sx * LEG_X, 0, z) for z in zs], rr, rr, lambda *a: SKIN_PLAIN, lambda i, t, p: lw(p), nseg=12,
              n0=V(0, 1, 0))
        zs = [0.140, 0.136, 0.11, 0.08, 0.05, 0.035]
        rr = [0.0, 0.038, 0.038, 0.037, 0.036, 0.034]
        sweep(so, [V(sx * LEG_X, 0, z) for z in zs], rr, rr, lambda *a: sw('white'), lambda i, t, p: lw(p), nseg=12,
              n0=V(0, 1, 0))
        n = 25
        base = [V(sx * LEG_X + 0.037 * math.sin(2 * PI * j / (n - 1)), -0.037 * math.cos(2 * PI * j / (n - 1)), 0.132)
                for j in range(n)]
        out = [V(math.sin(2 * PI * j / (n - 1)), -math.cos(2 * PI * j / (n - 1)), 0.75) for j in range(n)]
        frill_strip(so, base, out, 0.022, 0.0045, 7,
                    lambda i, j, p, layer: sw('white' if layer == 0 else 'frillshade'), lambda i, j, p: lw(p), rows=3,
                    thick=0.004, closed=True, hint_fn=lambda p, sx=sx: V(p.x - sx * LEG_X, p.y, 0))
        # 鞋：圆头，底部压平；脚背处露出白袜
        c = V(sx * LEG_X, -0.024, 0.036)

        def deform(l):
            z = l.z
            if z < -0.78:
                z = -0.78 - (-0.78 - z) * 0.15
            return V(l.x * (1.0 - 0.10 * max(0.0, l.y)), l.y, z)

        def shoe_uv(i, j, p):
            lz = (p.z - c.z) / 0.036
            ly = (p.y - c.y) / 0.080
            open_ = min(lz - 0.42, (ly + 0.30) * 2.2, (0.80 - abs(p.x - c.x) / 0.047) * 2.5)
            return bicolor('shoe', open_ * 6)

        def fw(i, j, p, side=side):
            k = clamp((-0.045 - p.y) / 0.03)
            return {side + 'Foot': 1 - 0.8 * k, side + 'Toes': 0.8 * k}
        ellipsoid(sh, c, (0.047, 0.080, 0.036), shoe_uv, fw, nu=20, nv=14, deform=deform)
        # 鞋带
        n = 25
        ring = [V(sx * LEG_X + 0.040 * math.sin(2 * PI * j / (n - 1)), -0.006 - 0.042 * math.cos(2 * PI * j / (n - 1)),
                  0.064 - 0.006 * math.cos(2 * PI * j / (n - 1))) for j in range(n)]
        sweep(sh, ring, [0.0075] * n, [0.004] * n, lambda *a: sw('shoe'),
              lambda i, t, p, side=side: {side + 'Foot': 1.0}, nseg=6,
              normal_fn=lambda i, p, sx=sx: V(p.x - sx * LEG_X, p.y + 0.006, 0), closed=True)


# ================================================================ 尾巴
TAIL = [V(0.02, 0.07, 0.27), V(0.09, 0.21, 0.195), V(0.20, 0.30, 0.115), V(0.32, 0.285, 0.088),
        V(0.41, 0.19, 0.118), V(0.445, 0.08, 0.205), V(0.445, 0.03, 0.30)]
TAIL_R = [0.070, 0.075, 0.070, 0.060, 0.047, 0.034, 0.025]
TAIL_PTS = []


def build_tail():
    pt = part('tail', 'Cloth')
    pts = catmull(TAIL, 7)
    ts = path_params(pts)
    TAIL_PTS.extend(pts)
    ctrl_t = path_params(TAIL)
    rr = [interp(list(zip(ctrl_t, TAIL_R)), t) for t in ts]
    rr[0] = 0.0
    pts = pts + [pts[-1] + (pts[-1] - pts[-2]).normalized() * 0.012]
    ts = ts + [1.0]
    rr = rr + [0.0]
    nb = 6
    names = ['tail%d' % k for k in range(nb)]

    def tw(t):
        return chain_weights(-t, [-k / nb for k in range(nb)], names, 'tailRoot', end=-1.0)
    sweep(pt, pts, rr, rr, lambda i, t, k, p, ca, sa: bicolor('tail', (-sa - 0.30) * 5),
          lambda i, t, p: tw(t), nseg=14, n0=V(0, -0.3, 1))
    # 尾鳍：两片水平展开的叶片
    tip = pts[-2]
    T = (pts[-2] - pts[-4]).normalized()
    L = V(1.0, -0.1, 0)
    L = (L - T * L.dot(T)).normalized()
    Nf = T.cross(L).normalized()
    fl = part('fluke', 'Cloth')
    for s in (-1, 1):
        ctrl = [tip - T * 0.02, tip + T * 0.050 + L * (s * 0.060), tip + T * 0.095 + L * (s * 0.115),
                tip + T * 0.105 + L * (s * 0.155)]
        lp = catmull(ctrl, 6)
        lt = path_params(lp)
        hw = [interp([(0, 0.028), (0.25, 0.060), (0.6, 0.050), (0.88, 0.022), (1, 0)], t) for t in lt]
        ht = [min(0.014, w * 0.33) for w in hw]
        sweep(fl, lp, hw, ht, lambda *a: sw('tail'), lambda i, t, p: {names[-1]: 1.0}, nseg=10,
              normal_fn=lambda i, p: Nf)
    ellipsoid(fl, tip + T * 0.012, (0.03, 0.02, 0.03), lambda i, j, p: sw('tail'), lambda i, j, p: {names[-1]: 1.0},
              nu=10, nv=6, axes=(L, Nf, T))


# ================================================================ 骨骼
BONES = {}  # 名称 -> (头, 尾, 父)


def bone(name, head, tail, parent):
    BONES[name] = (Vector(head), Vector(tail), parent)


SPRINGS = []    # {name, joints, stiffness, gravity, drag, radius, groups}
COLLIDERS = []  # {node, pos(Blender 世界坐标), radius, tail?, group}


def define_bones():
    bone('hips', (0, 0, 0.30), (0, 0, 0.36), None)
    bone('spine', (0, 0, 0.36), (0, 0, 0.42), 'hips')
    bone('chest', (0, 0, 0.42), (0, 0, 0.47), 'spine')
    bone('upperChest', (0, 0, 0.47), (0, 0, 0.52), 'chest')
    bone('neck', (0, 0, 0.52), (0, 0, 0.56), 'upperChest')
    bone('head', (0, 0, 0.56), (0, 0, 0.76), 'neck')
    bone('headEnd', (0, 0, 1.0), (0, 0, 1.03), 'head')
    for sx, side in ((1, 'left'), (-1, 'right')):
        bone(side + 'Shoulder', (sx * 0.025, 0, 0.515), (sx * 0.07, 0, 0.515), 'upperChest')
        bone(side + 'UpperArm', (sx * 0.07, 0, 0.515), (sx * 0.16, 0, 0.515), side + 'Shoulder')
        bone(side + 'LowerArm', (sx * 0.16, 0, 0.515), (sx * 0.245, 0, 0.515), side + 'UpperArm')
        bone(side + 'Hand', (sx * 0.245, 0, 0.515), (sx * 0.30, 0, 0.515), side + 'LowerArm')
        bone(side + 'UpperLeg', (sx * LEG_X, 0, 0.30), (sx * LEG_X, 0, 0.18), 'hips')
        bone(side + 'LowerLeg', (sx * LEG_X, 0, 0.18), (sx * LEG_X, 0, 0.065), side + 'UpperLeg')
        bone(side + 'Foot', (sx * LEG_X, 0, 0.065), (sx * LEG_X, -0.06, 0.02), side + 'LowerLeg')
        bone(side + 'Toes', (sx * LEG_X, -0.06, 0.02), (sx * LEG_X, -0.10, 0.02), side + 'Foot')
    # 长发：7 条链
    for ci, deg in enumerate(HAIR_CHAIN_PHI):
        ph = math.radians(deg)
        zs = HAIR_LEVELS + [HAIR_END]
        ps = [polar(ph, (0.20 + (interp(CLOAK_R, z) - 0.20) * cloak_scale(deg)) * 0.96, z,
                    1.06 if math.cos(ph) < 0 else 1.0) for z in zs]
        par = 'head'
        names = []
        for k in range(len(zs) - 1):
            nm = 'hair%d_%d' % (ci, k)
            bone(nm, ps[k], ps[k + 1], par)
            par = nm
            names.append(nm)
        end = 'hair%d_end' % ci
        bone(end, ps[-1], ps[-1] + V(0, 0, -0.04), par)
        SPRINGS.append(dict(name='hair%d' % ci, joints=names + [end], stiffness=[1.2, 0.9, 0.7, 0.55],
                            gravity=0.10, drag=0.45, radius=0.03, groups=['body', 'skirt']))
    # 呆毛
    ap = catmull(AHOGE, 6)
    at = path_params(ap)

    def at_t(t):
        i = min(range(len(at)), key=lambda k: abs(at[k] - t))
        return ap[i]
    a_pts = [at_t(0.0), at_t(0.3), at_t(0.62), at_t(1.0)]
    par = 'head'
    names = []
    for k in range(3):
        nm = 'ahoge%d' % k
        bone(nm, a_pts[k], a_pts[k + 1], par)
        par = nm
        names.append(nm)
    bone('ahoge_end', a_pts[3], a_pts[3] + V(0.02, 0, 0), par)
    SPRINGS.append(dict(name='ahoge', joints=names + ['ahoge_end'], stiffness=[2.2, 1.8, 1.5], gravity=0.0, drag=0.25,
                        radius=0.01, groups=[]))
    # 鲸鳍耳
    for side in ('L', 'R'):
        root, tip = EAR_ROOT[side]
        bone('ear' + side, root, tip, 'head')
        bone('ear%s_end' % side, tip, tip + (tip - root).normalized() * 0.03, 'ear' + side)
        SPRINGS.append(dict(name='ear' + side, joints=['ear' + side, 'ear%s_end' % side], stiffness=[3.2], gravity=0.0,
                            drag=0.35, radius=0.02, groups=[]))
    # 裙摆：8 条链
    for ci, deg in enumerate(SKIRT_PHI):
        ph = math.radians(deg)
        zs = SKIRT_LEVELS + [SKIRT_END]
        ps = [V(skirt_R(z) * math.sin(ph), -skirt_R(z) * math.cos(ph) * 0.94, z) for z in zs]
        par = 'hips'
        names = []
        for k in range(2):
            nm = 'skirt%d_%d' % (ci, k)
            bone(nm, ps[k], ps[k + 1], par)
            par = nm
            names.append(nm)
        end = 'skirt%d_end' % ci
        bone(end, ps[-1], ps[-1] + V(0, 0, -0.03), par)
        SPRINGS.append(dict(name='skirt%d' % ci, joints=names + [end], stiffness=[2.6, 2.0], gravity=0.05, drag=0.5,
                            radius=0.03, groups=[]))
    # 尾巴
    bone('tailRoot', (0, 0.03, 0.29), TAIL_PTS[0], 'hips')
    nb = 6
    tps = []
    tpar = path_params(TAIL_PTS)
    for k in range(nb + 1):
        t = k / nb
        i = min(range(len(TAIL_PTS)), key=lambda q: abs(tpar[q] - t))
        tps.append(TAIL_PTS[i])
    par = 'tailRoot'
    names = []
    for k in range(nb):
        nm = 'tail%d' % k
        bone(nm, tps[k], tps[k + 1], par)
        par = nm
        names.append(nm)
    bone('tail_end', tps[-1], tps[-1] + V(0, 0, 0.1), par)
    SPRINGS.append(dict(name='tail', joints=names + ['tail_end'], stiffness=[2.4, 2.0, 1.6, 1.3, 1.1, 0.9],
                        gravity=0.0, drag=0.3, radius=0.03, groups=[]))
    # 碰撞体
    COLLIDERS.append(dict(node='spine', pos=(0, 0, 0.36), tail=(0, 0, 0.50), radius=0.095, group='body'))
    COLLIDERS.append(dict(node='head', pos=(0, 0.01, 0.76), radius=0.20, group='body'))
    COLLIDERS.append(dict(node='hips', pos=(0, 0, 0.28), radius=0.205, group='skirt'))
    for sx, side in ((1, 'left'), (-1, 'right')):
        COLLIDERS.append(dict(node=side + 'UpperLeg', pos=(sx * LEG_X, 0, 0.30), tail=(sx * LEG_X, 0, 0.18),
                              radius=0.05, group='legs'))


EXPRESSIONS = {
    'happy': dict(morphs=[('eyeJoy_L', 1), ('eyeJoy_R', 1), ('mouthSmile', 1)], overrideBlink='block'),
    'angry': dict(morphs=[('eyeAngry', 1), ('mouthFrown', 0.7)]),
    'sad': dict(morphs=[('eyeSad', 1), ('mouthFrown', 1)]),
    'relaxed': dict(morphs=[('eyeClose_L', 0.22), ('eyeClose_R', 0.22), ('mouthSmile', 0.45)]),
    'surprised': dict(morphs=[('eyeWide', 1), ('mouthO', 1)], iris_scale=1.28),
    'aa': dict(morphs=[('mouthAa', 1)]),
    'ih': dict(morphs=[('mouthIh', 1)]),
    'ou': dict(morphs=[('mouthOu', 1)]),
    'ee': dict(morphs=[('mouthEe', 1)]),
    'oh': dict(morphs=[('mouthOh', 1)]),
    'blink': dict(morphs=[('eyeClose_L', 1), ('eyeClose_R', 1)]),
    'blinkLeft': dict(morphs=[('eyeClose_L', 1)]),
    'blinkRight': dict(morphs=[('eyeClose_R', 1)]),
    'lookUp': dict(morphs=[], iris_offset=(0, -0.06)),
    'lookDown': dict(morphs=[], iris_offset=(0, 0.06)),
    'lookLeft': dict(morphs=[], iris_offset=(0.075, 0)),
    'lookRight': dict(morphs=[], iris_offset=(-0.075, 0)),
    'neutral': dict(morphs=[]),
}

MATERIALS = {
    # 名称: (贴图, MToon 参数)
    'Skin': ('skin.png', dict(shade=(1.0, 0.86, 0.84), outline=0.0022, outline_color=(0.42, 0.22, 0.25), toony=0.9,
                              shift=-0.25)),
    'Hair': ('hair.png', dict(shade=(0.74, 0.76, 0.93), outline=0.0026, outline_color=(0.12, 0.14, 0.34), toony=0.9,
                              shift=-0.1)),
    'Cloth': ('cloth.png', dict(shade=(0.80, 0.80, 0.93), outline=0.0026, outline_color=(0.13, 0.14, 0.30), toony=0.9,
                                shift=-0.1)),
    'Eye': ('eye.png', dict(shade=(1, 1, 1), outline=0.0, toony=1.0, shift=-1.0, flat=True)),
    'FaceLine': ('cloth.png', dict(shade=(1, 1, 1), outline=0.0, toony=1.0, shift=-1.0, flat=True)),
}


# ================================================================ 组装
def make_materials():
    mats, imgs = {}, {}
    for name, (tex, _) in MATERIALS.items():
        if tex not in imgs:
            imgs[tex] = bpy.data.images.load(os.path.join(TEX_DIR, tex))
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        m.use_backface_culling = True
        nt = m.node_tree
        bsdf = next((n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED'), None)
        if bsdf is None:
            bsdf = nt.nodes.new('ShaderNodeBsdfPrincipled')
            outn = nt.nodes.new('ShaderNodeOutputMaterial')
            nt.links.new(bsdf.outputs['BSDF'], outn.inputs['Surface'])
        tn = nt.nodes.new('ShaderNodeTexImage')
        tn.image = imgs[tex]
        nt.links.new(tn.outputs['Color'], bsdf.inputs['Base Color'])
        bsdf.inputs['Roughness'].default_value = 1.0
        mats[name] = m
    return mats


def make_armature():
    arm = bpy.data.armatures.new('Armature')
    ob = bpy.data.objects.new('Armature', arm)
    bpy.context.scene.collection.objects.link(ob)
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.mode_set(mode='EDIT')
    eb = {}
    for name, (h, t, par) in BONES.items():
        b = arm.edit_bones.new(name)
        b.head, b.tail = h, t
        eb[name] = b
    for name, (h, t, par) in BONES.items():
        if par:
            eb[name].parent = eb[par]
            eb[name].use_connect = False
    bpy.ops.object.mode_set(mode='OBJECT')
    return ob


def join(objs, name):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    ob = bpy.context.view_layer.objects.active
    ob.name = name
    ob.data.name = name
    return ob


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    build_head()
    build_eyes()
    build_brows()
    build_mouth()
    build_hair_cap()
    build_bangs()
    build_long_hair()
    build_ahoge()
    build_headdress()
    build_hair_bows()
    build_ears()
    build_torso()
    build_skirt()
    build_apron()
    build_arms()
    build_legs()
    build_tail()
    define_bones()

    mats = make_materials()
    arm = make_armature()
    bone_index = {n: i for i, n in enumerate(BONES)}
    groups = {}
    for p in PARTS:
        groups.setdefault(p.obj, []).append(part_to_object(p, bone_index, mats[p.mat]))
    meshes = []
    for name, objs in groups.items():
        ob = join(objs, name) if len(objs) > 1 else objs[0]
        ob.name = name
        ob.parent = arm
        mod = ob.modifiers.new('Armature', 'ARMATURE')
        mod.object = arm
        meshes.append(ob)
        print('MESH', name, 'verts', len(ob.data.vertices), 'tris', sum(len(p.vertices) - 2 for p in ob.data.polygons),
              'shape keys', len(ob.data.shape_keys.key_blocks) if ob.data.shape_keys else 0)

    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.export_scene.gltf(filepath=OUT_GLB, export_format='GLB', export_yup=True, export_apply=False,
                              export_skins=True, export_morph=True, export_morph_normal=False,
                              export_animations=False, export_materials='EXPORT', export_image_format='AUTO',
                              export_tangents=False, export_all_influences=False)
    humanoid = ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head']
    for side in ('left', 'right'):
        humanoid += [side + s for s in ('Shoulder', 'UpperArm', 'LowerArm', 'Hand', 'UpperLeg', 'LowerLeg', 'Foot',
                                        'Toes')]
    rig = dict(humanoid={n: n for n in humanoid}, springs=SPRINGS, colliders=COLLIDERS, expressions=EXPRESSIONS,
               materials={k: v[1] for k, v in MATERIALS.items()}, face_mesh='Face', eye_material='Eye',
               look_at_pos=[0, -0.17, 0.668])
    with open(RIG_JSON, 'w') as f:
        json.dump(rig, f, ensure_ascii=False, indent=1)
    print('EXPORTED', OUT_GLB)


main()
