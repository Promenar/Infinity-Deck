"""按位置与贴图颜色给顶点粗分类：头、身体、头发、尾巴、杂质。纯 numpy，可在 Blender 内外使用。

坐标为 s1_prepare.py 归一化后的坐标（米）：脚底 z=0，原点在两脚中心，角色面向 -Y，左手在 +X。
颜色为贴图像素值（0 到 1），阈值按该取值经验设定。
"""
import json
import os

import numpy as np

HEAD, BODY, HAIR, TAIL, JUNK = 0, 1, 2, 3, 4
NAMES = ['head', 'body', 'hair', 'tail', 'junk']

# 与底模相关的位置参数。默认值对应第一版底模（双手胸前合拢、带残影和两套尾巴）；
# 换底模时用环境变量 PET_CFG 指向一份 JSON，只写需要覆盖的键。
CFG = dict(
    z_head=0.60,                 # 这个高度以上整体随头骨
    face_x=-0.018, face_y=-0.19,  # 脸的中线 x、脸最靠前的 y
    eye_dx=0.080, eye_z=0.657, mouth_z=0.583,
    whale=[0.036, 0.287],        # 围裙小鲸鱼图案的 x、z
    eye_off=0.003,               # 眼睛贴花离皮肤的距离；刘海压眼较多的底模取大一些，让眼睛画在刘海之上
    torso_y=0.005, leg_x=0.04,
    arm_l=[[0.085, 0.005, 0.505], [0.118, -0.015, 0.415], [0.050, -0.118, 0.452], [0.022, -0.150, 0.492]],
    arm_r=[[-0.095, 0.005, 0.505], [-0.128, -0.015, 0.415], [-0.055, -0.118, 0.452], [-0.026, -0.150, 0.492]],
    arm_rad=[0.056, 0.048, 0.042],
    arms='script',               # script：删掉生成的手臂换成脚本手臂；keep：保留生成的手臂并摆成 T 姿势
    skirt_r=[[0.11, 0.20], [0.16, 0.255], [0.22, 0.262], [0.28, 0.250], [0.34, 0.195], [0.38, 0.125], [0.41, 0.09]],
    tail_main=[[-0.03, 0.33, 0.20], [0.05, 0.21, 0.13], [0.15, 0.06, 0.095], [0.25, -0.03, 0.125],
               [0.33, -0.085, 0.25], [0.41, -0.10, 0.38]],
    tail_main_rad=[0.075, 0.075, 0.07, 0.065, 0.06],
    tail_stubs=[[[[-0.03, 0.33, 0.20], [-0.07, 0.22, 0.115], [-0.10, 0.10, 0.08]], [0.07, 0.065]],
                [[[-0.03, 0.33, 0.20], [0.13, 0.36, 0.25], [0.29, 0.41, 0.30]], [0.06, 0.085]]],
    fluke_c=[0.40, -0.09, 0.41], fluke_r=0.115,
    junk=True,                   # 是否有需要删除的右侧残影
    grade=True,                  # 是否把 B 版的蓝色区域调色烘进贴图（第一版底模偏青偏灰，需要）
    hair_c=[0.03, 0.09],
    hair_r=[[0.12, 0.34], [0.24, 0.34], [0.36, 0.30], [0.48, 0.25], [0.60, 0.20]],
    ear_x=0.205, ear_z=[0.54, 0.76],
    ears={'L': [[0.19, 0.03, 0.665], [0.36, 0.07, 0.655]], 'R': [[-0.20, 0.03, 0.655], [-0.37, 0.07, 0.60]]},
)
if os.environ.get('PET_CFG'):
    CFG.update(json.load(open(os.environ['PET_CFG'])))
Z_HEAD = CFG['z_head']
ARM_L, ARM_R = np.array(CFG['arm_l'], float), np.array(CFG['arm_r'], float)
ARM_RAD = CFG['arm_rad']
SKIRT_R = [tuple(v) for v in CFG['skirt_r']]


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


# 尾巴中心线（扫描姿势）。主干挂弹簧骨骼；tail_stubs 是底模里多生成的重复尾巴，按杂质删除。
TAIL_MAIN = np.array(CFG['tail_main'], float)
TAIL_MAIN_RAD = CFG['tail_main_rad']
TAIL_STUBS = [(np.array(line, float), rad) for line, rad in CFG['tail_stubs']]
FLUKE_C, FLUKE_R = np.array(CFG['fluke_c'], float), CFG['fluke_r']


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
    for line, rad in TAIL_STUBS:
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
    torso = (np.hypot(x, y - CFG['torso_y']) < 0.088) & (z > 0.36) & (z < 0.565)
    ascore, _, _, _ = arm_score(P)
    az0 = min(ARM_L[:, 2].min(), ARM_R[:, 2].min()) - 0.06
    arm = (ascore < 1.0) & (z > min(0.37, az0)) & (z < 0.56)
    rs = np.interp(z, [a for a, _ in SKIRT_R], [b for _, b in SKIRT_R])
    skirt = (z > 0.11) & (z < 0.415) & (r < rs + 0.012)
    legs = (z < 0.21) & (np.abs(x) < 0.12) & (y > -0.085) & (y < 0.08)
    core = (z < 0.40) & (r < 0.115)
    # 手臂范围内的浅蓝是搭在手边的发梢，不算手臂
    body = torso | (arm & ~(hairlike & (lum > 0.30))) | legs | core | (skirt & ~hairlike)
    cls[body] = BODY
    # 尾巴：按中心线的管状范围识别（颜色与头发接近，不能只靠颜色）
    tscore, tmain, _ = tail_score(P)
    fluke = np.linalg.norm(P - FLUKE_C, axis=1) < FLUKE_R
    light_hair = hairlike & (lum > 0.30)
    if CFG['junk']:
        tail = (tscore < 1.0) & ~body & (z < 0.56) & (fluke | ~(light_hair & (tscore > 0.55)))
    else:   # 尾巴是深蓝加白腹，旁边的发梢是浅蓝，按颜色分开
        tail = (tscore < 1.0) & ~body & (z < 0.56) & ~light_hair
    cls[tail] = TAIL
    # 只保留通向左前方尾鳍的主尾；多视图条件冲突多生成的第二只尾鳍和残段按杂质删除
    cls[tail & ~tmain] = JUNK
    if CFG['junk']:
        # 右侧的深灰杂质（多视图条件冲突产生的第二条尾巴残影）
        junk = (x < -0.20) & (z > 0.15) & (z < 1.0) & (lum < 0.22) & (sat < 0.13) & ~body
        # 残影下半截颜色偏深蓝，按“右侧外缘的深色块”补一条规则（这一带的头发是浅蓝）
        junk |= (x < -0.24) & (z > 0.20) & (z < 0.62) & (lum < 0.15) & ~body
        cls[junk] = JUNK
    # 头部：下巴以上整体随头骨；下巴、脖子的肤色也归头
    head = (z >= Z_HEAD) | (skin & (z > 0.535) & (y < CFG['face_y'] + 0.25) & ~arm)
    cls[head & (cls != JUNK)] = HEAD
    return cls
