"""按位置与贴图颜色给顶点粗分类：头、身体、头发、尾巴、杂质。纯 numpy，可在 Blender 内外使用。

坐标为 s1_prepare.py 归一化后的坐标（米）：脚底 z=0，原点在两脚中心，角色面向 -Y，左手在 +X。
颜色为贴图像素值（0 到 1），阈值按该取值经验设定。
"""
import numpy as np

HEAD, BODY, HAIR, TAIL, JUNK = 0, 1, 2, 3, 4
NAMES = ['head', 'body', 'hair', 'tail', 'junk']
Z_HEAD = 0.60

# 扫描姿势下的手臂折线：肩、肘、腕、指尖（左臂；右臂取 x 的相反数再加各自的修正）
ARM_L = np.array([[0.085, 0.005, 0.505], [0.118, -0.015, 0.415], [0.050, -0.118, 0.452], [0.022, -0.150, 0.492]])
ARM_R = np.array([[-0.095, 0.005, 0.505], [-0.128, -0.015, 0.415], [-0.055, -0.118, 0.452], [-0.026, -0.150, 0.492]])
ARM_RAD = [0.056, 0.048, 0.042]
SKIRT_R = [(0.11, 0.20), (0.16, 0.255), (0.22, 0.262), (0.28, 0.250), (0.34, 0.195), (0.38, 0.125), (0.41, 0.09)]


def seg_dist(P, a, b):
    ab = b - a
    t = np.clip(((P - a) @ ab) / (ab @ ab), 0, 1)
    return np.linalg.norm(P - (a + t[:, None] * ab), axis=1), t


def color_feats(C):
    R, G, B = C[:, 0], C[:, 1], C[:, 2]
    lum = 0.2126 * R + 0.7152 * G + 0.0722 * B
    sat = C.max(1) - C.min(1)
    return R, G, B, lum, sat


def arm_score(P):
    """到两条手臂折线的归一化距离（距离 / 半径）及对应的侧别、骨段。"""
    best = np.full(len(P), 9.0)
    side = np.zeros(len(P), np.int8)
    seg = np.zeros(len(P), np.int8)
    tpar = np.zeros(len(P))
    for s, arm in ((1, ARM_L), (-1, ARM_R)):
        for k in range(3):
            d, t = seg_dist(P, arm[k], arm[k + 1])
            d = d / ARM_RAD[k]
            m = d < best
            best[m], side[m], seg[m], tpar[m] = d[m], s, k, t[m]
    return best, side, seg, tpar


# 尾巴中心线（扫描姿势）。主干从身后高处绕到左前方，末端是尾鳍；另有一段伸向右后方的残段。
TAIL_MAIN = np.array([[-0.03, 0.33, 0.20], [0.05, 0.21, 0.13], [0.15, 0.06, 0.095], [0.25, -0.03, 0.125],
                      [0.33, -0.085, 0.25], [0.41, -0.10, 0.38]])
TAIL_MAIN_RAD = [0.075, 0.075, 0.07, 0.065, 0.06]
TAIL_STUB = np.array([[-0.03, 0.33, 0.20], [-0.07, 0.22, 0.115], [-0.10, 0.10, 0.08]])
TAIL_STUB_RAD = [0.07, 0.065]
# 后方的第二只尾鳍（背视图条件生成的重复部件），随尾根固定
TAIL_STUB2 = np.array([[-0.03, 0.33, 0.20], [0.13, 0.36, 0.25], [0.29, 0.41, 0.30]])
TAIL_STUB2_RAD = [0.06, 0.085]
FLUKE_C, FLUKE_R = np.array([0.40, -0.09, 0.41]), 0.115


def tail_score(P):
    """到尾巴中心线的归一化距离；返回 (分数, 是否主干, 主干弧长参数 0..1)。"""
    best = np.full(len(P), 9.0)
    main = np.zeros(len(P), bool)
    tpar = np.zeros(len(P))
    seglen = np.linalg.norm(np.diff(TAIL_MAIN, axis=0), axis=1)
    cum = np.concatenate([[0], np.cumsum(seglen)]) / seglen.sum()
    for k in range(len(TAIL_MAIN) - 1):
        d, t = seg_dist(P, TAIL_MAIN[k], TAIL_MAIN[k + 1])
        d = d / TAIL_MAIN_RAD[k]
        m = d < best
        best[m], main[m] = d[m], True
        tpar[m] = cum[k] + t[m] * (cum[k + 1] - cum[k])
    d = np.linalg.norm(P - FLUKE_C, axis=1) / FLUKE_R
    m = d < best
    best[m], main[m], tpar[m] = d[m], True, 1.0
    for line, rad in ((TAIL_STUB, TAIL_STUB_RAD), (TAIL_STUB2, TAIL_STUB2_RAD)):
        for k in range(len(line) - 1):
            d, t = seg_dist(P, line[k], line[k + 1])
            d = d / rad[k]
            m = d < best
            best[m], main[m], tpar[m] = d[m], False, 0.0
    return best, main, tpar


def classify(P, C):
    R, G, B, lum, sat = color_feats(C)
    x, y, z = P[:, 0], P[:, 1], P[:, 2]
    r = np.hypot(x, y)
    n = len(P)
    cls = np.full(n, HAIR, np.int8)
    white = (lum > 0.45) & (sat < 0.16)
    skin = (R > G) & (G > B) & (R - B > 0.08) & (lum > 0.3)
    dark = lum < 0.06
    hairlike = (B - R > 0.10) & (lum > 0.10) & ~white
    # 身体实心区
    torso = (np.hypot(x, y - 0.005) < 0.088) & (z > 0.36) & (z < 0.565)
    ascore, _, _, _ = arm_score(P)
    arm = (ascore < 1.0) & (z > 0.37) & (z < 0.56)
    rs = np.interp(z, [a for a, _ in SKIRT_R], [b for _, b in SKIRT_R])
    skirt = (z > 0.11) & (z < 0.415) & (r < rs + 0.012)
    legs = (z < 0.21) & (np.abs(x) < 0.12) & (y > -0.085) & (y < 0.08)
    core = (z < 0.40) & (r < 0.115)
    body = torso | arm | legs | core | (skirt & ~hairlike)
    cls[body] = BODY
    # 尾巴：按中心线的管状范围识别（颜色与头发接近，不能只靠颜色）
    tscore, _, _ = tail_score(P)
    fluke = np.linalg.norm(P - FLUKE_C, axis=1) < FLUKE_R
    tail = (tscore < 1.0) & ~body & (z < 0.56) & (fluke | ~(hairlike & (lum > 0.30) & (tscore > 0.55)))
    cls[tail] = TAIL
    # 右侧的深灰杂质（多视图条件冲突产生的第二条尾巴残影）
    junk = (x < -0.20) & (z > 0.15) & (z < 0.80) & (lum < 0.20) & (sat < 0.12) & ~body
    cls[junk] = JUNK
    # 头部：下巴以上整体随头骨；下巴、脖子的肤色也归头
    head = (z >= Z_HEAD) | (skin & (z > 0.535) & (y < 0.06) & ~arm)
    cls[head & (cls != JUNK)] = HEAD
    return cls
