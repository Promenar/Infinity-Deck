"""阶段 4：建骨架、按部位分配权重、把手臂从胸前合拢摆到 T 姿势并固化为初始姿势，导出 glb 与 rig.json。

用法：Blender --background --python s4_rig.py -- <工作目录>
输入：<工作目录>/low_baked.blend（s3_bake.py 产出）
产出：<工作目录>/low_rigged.blend、whale-maid.glb、rig.json（供 ../glb_to_vrm.py 写入 VRM 扩展）

做法：骨骼先按扫描姿势摆放并绑定；随后在姿态模式下把上臂、前臂、手转到水平，
应用骨架修改器把变形烘进网格，再把当前姿态设为初始姿态。这样 VRM 的初始姿势是 T 姿势。
"""
import json
import math
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import classify as K
import common as C
from meshkit import chain_weights

WORK = sys.argv[sys.argv.index('--') + 1]
P_HEAD, P_TORSO, P_ARM_L, P_ARM_R, P_HAIR, P_TAIL = 1, 2, 3, 4, 5, 6
FLAT = os.path.exists(os.path.join(WORK, 'low_flat.blend')) and os.environ.get('FLAT', '1') == '1'
bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'low_flat.blend' if FLAT else 'low_baked.blend'))
low = bpy.data.objects['Low']
bpy.data.objects.remove(bpy.data.objects['High'])
me = low.data

# ================================================================ 骨骼（扫描姿势）
BONES = {}


def bone(name, head, tail, parent):
    BONES[name] = (Vector(head), Vector(tail), parent)


LEG_X = 0.04
bone('hips', (0, 0.01, 0.31), (0, 0.01, 0.37), None)
bone('spine', (0, 0.01, 0.37), (0, 0.01, 0.42), 'hips')
bone('chest', (0, 0.01, 0.42), (0, 0.01, 0.465), 'spine')
bone('upperChest', (0, 0.01, 0.465), (0, 0.005, 0.51), 'chest')
bone('neck', (0, 0.005, 0.51), (0, 0.0, 0.555), 'upperChest')
bone('head', (0, 0.0, 0.555), (0, 0.0, 0.76), 'neck')
bone('headEnd', (0, 0.0, 1.0), (0, 0.0, 1.03), 'head')
SCRIPT_ARMS = FLAT and os.environ.get('ARMS', 'script') == 'script'
ARM_Z, ARM_Y = 0.497, 0.005
ARM_X = [0.078, 0.168, 0.262, 0.315]   # 肩、肘、腕、指尖（T 姿势）
for sx, side, arm in ((1, 'left', K.ARM_L), (-1, 'right', K.ARM_R)):
    if SCRIPT_ARMS:
        arm = [np.array([sx * ax, ARM_Y, ARM_Z]) for ax in ARM_X]
    bone(side + 'Shoulder', (sx * 0.03, 0.005, 0.50), arm[0], 'upperChest')
    bone(side + 'UpperArm', arm[0], arm[1], side + 'Shoulder')
    bone(side + 'LowerArm', arm[1], arm[2], side + 'UpperArm')
    bone(side + 'Hand', arm[2], arm[3], side + 'LowerArm')
    bone(side + 'UpperLeg', (sx * LEG_X, 0.005, 0.30), (sx * LEG_X, 0.005, 0.19), 'hips')
    bone(side + 'LowerLeg', (sx * LEG_X, 0.005, 0.19), (sx * LEG_X, 0.005, 0.075), side + 'UpperLeg')
    bone(side + 'Foot', (sx * LEG_X, 0.005, 0.075), (sx * LEG_X, -0.055, 0.02), side + 'LowerLeg')
    bone(side + 'Toes', (sx * LEG_X, -0.055, 0.02), (sx * LEG_X, -0.095, 0.02), side + 'Foot')

SPRINGS, COLLIDERS = [], []
# 头发：8 条链，绕头发云的中心均匀分布
HAIR_C = (0.03, 0.09)
HAIR_PHI = [40, 80, 120, 160, 200, 240, 280, 320]
HAIR_LEVELS = [0.60, 0.48, 0.36, 0.24]
HAIR_END = 0.12
HAIR_R = [(0.12, 0.34), (0.24, 0.34), (0.36, 0.30), (0.48, 0.25), (0.60, 0.20)]


def hair_pt(deg, z):
    r = float(np.interp(z, [a for a, _ in HAIR_R], [b for _, b in HAIR_R]))
    a = math.radians(deg)
    return Vector((HAIR_C[0] + r * math.sin(a), HAIR_C[1] - r * math.cos(a), z))


for ci, deg in enumerate(HAIR_PHI):
    zs = HAIR_LEVELS + [HAIR_END]
    par, names = 'head', []
    for k in range(len(zs) - 1):
        nm = 'hair%d_%d' % (ci, k)
        bone(nm, hair_pt(deg, zs[k]), hair_pt(deg, zs[k + 1]), par)
        par = nm
        names.append(nm)
    bone('hair%d_end' % ci, hair_pt(deg, zs[-1]), hair_pt(deg, zs[-1]) + Vector((0, 0, -0.04)), par)
    SPRINGS.append(dict(name='hair%d' % ci, joints=names + ['hair%d_end' % ci], stiffness=[1.4, 1.0, 0.8, 0.6],
                        gravity=0.08, drag=0.5, radius=0.03, groups=['body']))
# 裙摆：6 条链
SKIRT_PHI = [0, 60, 120, 180, 240, 300]
SKIRT_LEVELS = [0.385, 0.30]
SKIRT_END = 0.19


def skirt_r(z):
    return float(np.interp(z, [a for a, _ in K.SKIRT_R], [b for _, b in K.SKIRT_R]))


for ci, deg in enumerate(SKIRT_PHI):
    a = math.radians(deg)
    zs = SKIRT_LEVELS + [SKIRT_END]
    ps = [Vector((skirt_r(z) * 0.9 * math.sin(a), -skirt_r(z) * 0.9 * math.cos(a), z)) for z in zs]
    par, names = 'hips', []
    for k in range(2):
        nm = 'skirt%d_%d' % (ci, k)
        bone(nm, ps[k], ps[k + 1], par)
        par = nm
        names.append(nm)
    bone('skirt%d_end' % ci, ps[-1], ps[-1] + Vector((0, 0, -0.03)), par)
    SPRINGS.append(dict(name='skirt%d' % ci, joints=names + ['skirt%d_end' % ci], stiffness=[2.8, 2.2], gravity=0.04,
                        drag=0.55, radius=0.03, groups=[]))
# 尾巴：尾根 + 4 节，沿主干中心线
seg = np.linalg.norm(np.diff(K.TAIL_MAIN, axis=0), axis=1)
cum = np.concatenate([[0], np.cumsum(seg)]) / seg.sum()


def tail_pt(t):
    k = min(int(np.searchsorted(cum, t, side='right')) - 1, len(seg) - 1)
    f = (t - cum[k]) / (cum[k + 1] - cum[k])
    return Vector((K.TAIL_MAIN[k] + f * (K.TAIL_MAIN[k + 1] - K.TAIL_MAIN[k])).tolist())


TAIL_T = [0.18, 0.40, 0.60, 0.80]
bone('tailRoot', (0, 0.10, 0.27), tail_pt(TAIL_T[0]), 'hips')
par = 'tailRoot'
for k in range(4):
    nm = 'tail%d' % k
    bone(nm, tail_pt(TAIL_T[k]), tail_pt(TAIL_T[k + 1] if k < 3 else 1.0), par)
    par = nm
bone('tail_end', tail_pt(1.0), tail_pt(1.0) + Vector((0.02, 0, 0.08)), par)
SPRINGS.append(dict(name='tail', joints=['tail0', 'tail1', 'tail2', 'tail3', 'tail_end'], stiffness=[2.4, 1.9, 1.5, 1.2],
                    gravity=0.0, drag=0.35, radius=0.03, groups=[]))
# 鲸鳍耳
EARS = {'L': (Vector((0.19, 0.03, 0.665)), Vector((0.36, 0.07, 0.655))),
        'R': (Vector((-0.20, 0.03, 0.655)), Vector((-0.37, 0.07, 0.60)))}
for side, (root, tip) in EARS.items():
    bone('ear' + side, root, tip, 'head')
    bone('ear%s_end' % side, tip, tip + (tip - root).normalized() * 0.03, 'ear' + side)
    SPRINGS.append(dict(name='ear' + side, joints=['ear' + side, 'ear%s_end' % side], stiffness=[3.2], gravity=0.0,
                        drag=0.4, radius=0.02, groups=[]))
COLLIDERS.append(dict(node='spine', pos=(0, 0.01, 0.37), tail=(0, 0.01, 0.50), radius=0.09, group='body'))
COLLIDERS.append(dict(node='hips', pos=(0, 0.0, 0.27), radius=0.19, group='body'))

# ================================================================ 换成脚本手臂时，先删掉生成网格里的手臂
if SCRIPT_ARMS:
    pf = np.zeros(len(me.polygons), np.int32)
    me.attributes['part'].data.foreach_get('value', pf)
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.faces.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[bm.faces[i] for i in np.nonzero((pf == P_ARM_L) | (pf == P_ARM_R))[0]], context='FACES')
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if not v.link_faces], context='VERTS')
    bm.to_mesh(me)
    bm.free()

# ================================================================ 权重
P = C.np_verts(me)
nv = len(P)
part_f = np.zeros(len(me.polygons), np.int32)
me.attributes['part'].data.foreach_get('value', part_f)
vpart = np.zeros(nv, np.int32)
votes = np.zeros((nv, 7), np.int32)
for poly in me.polygons:
    for vi in poly.vertices:
        votes[vi, part_f[poly.index]] += 1
vpart = votes.argmax(1)
names = list(BONES)
bidx = {n: i for i, n in enumerate(names)}
W = np.zeros((nv, len(names)), np.float32)


def put(vi, d):
    for k, val in d.items():
        W[vi, bidx[k]] += val


ascore, aside, aseg, atpar = K.arm_score(P)
tscore, tmain, ttpar = K.tail_score(P)
for vi in range(nv):
    x, y, z = P[vi]
    r = math.hypot(x, y)
    part = vpart[vi]
    # 鲸鳍耳：头两侧伸出的部分
    ear = None
    if part in (P_HEAD, P_HAIR) and 0.54 < z < 0.76 and abs(x) > 0.205 and -0.12 < y < 0.22:
        side = 'L' if x > 0 else 'R'
        root, tip = EARS[side]
        d, _ = K.seg_dist(P[vi:vi + 1].astype(np.float64), np.array(root), np.array(tip))
        if d[0] < 0.075:
            k = min(1.0, (abs(x) - 0.205) / 0.04)
            ear = {'ear' + side: k, 'head': 1 - k}
    if ear:
        put(vi, ear)
    elif part == P_HEAD:
        if z < 0.585 and y < 0.08 and r < 0.13:   # 下巴与脖子
            k = min(1.0, max(0.0, (z - 0.525) / 0.05))
            put(vi, {'head': k, 'neck': 1 - k})
        else:
            put(vi, {'head': 1.0})
    elif part == P_HAIR:
        ph = math.degrees(math.atan2(x - HAIR_C[0], -(y - HAIR_C[1]))) % 360
        ph = min(max(ph, HAIR_PHI[0]), HAIR_PHI[-1])
        for c in range(len(HAIR_PHI) - 1):
            if ph <= HAIR_PHI[c + 1]:
                f = (ph - HAIR_PHI[c]) / (HAIR_PHI[c + 1] - HAIR_PHI[c])
                break
        for ci, cw in ((c, 1 - f), (c + 1, f)):
            if cw > 1e-3:
                nm = ['hair%d_%d' % (ci, k) for k in range(len(HAIR_LEVELS))]
                for bn, val in chain_weights(z, HAIR_LEVELS, nm, 'head', end=HAIR_END).items():
                    put(vi, {bn: val * cw})
    elif part == P_TAIL:
        if tmain[vi]:
            nm = ['tail%d' % k for k in range(4)]
            put(vi, chain_weights(-ttpar[vi], [-t for t in TAIL_T], nm, 'tailRoot', end=-1.0))
        else:
            put(vi, {'tailRoot': 1.0})
    elif part in (P_ARM_L, P_ARM_R):
        side = 'left' if part == P_ARM_L else 'right'
        arm = K.ARM_L if part == P_ARM_L else K.ARM_R
        best, bk, bt = 9.0, 0, 0.0
        for k in range(3):
            d, t = K.seg_dist(P[vi:vi + 1].astype(np.float64), arm[k], arm[k + 1])
            d = d[0] / K.ARM_RAD[k]
            if d < best:
                best, bk, bt = d, k, t[0]
        nm = [side + 'UpperArm', side + 'LowerArm', side + 'Hand']
        w = {nm[bk]: 1.0}
        if bt < 0.25:
            prev = nm[bk - 1] if bk > 0 else side + 'Shoulder'
            kk = 0.5 * (1 - bt / 0.25)
            w = {nm[bk]: 1 - kk, prev: kk}
        elif bt > 0.75 and bk < 2:
            kk = 0.5 * (bt - 0.75) / 0.25
            w = {nm[bk]: 1 - kk, nm[bk + 1]: kk}
        put(vi, w)
    else:  # 躯干与下身
        rs = skirt_r(z)
        if 0.11 < z < 0.405 and r > max(0.105, 0.62 * rs):          # 裙摆外壳
            ph = math.degrees(math.atan2(x, -y)) % 360
            c = int(ph // 60) % 6
            f = (ph - c * 60) / 60
            for ci, cw in ((c, 1 - f), ((c + 1) % 6, f)):
                if cw > 1e-3:
                    nm = ['skirt%d_%d' % (ci, k) for k in range(2)]
                    for bn, val in chain_weights(z, SKIRT_LEVELS, nm, 'hips', end=SKIRT_END).items():
                        put(vi, {bn: val * cw})
        elif z < 0.30:                                               # 腿与鞋
            side = 'left' if x > 0 else 'right'
            w = chain_weights(z, [0.30, 0.19, 0.075], [side + 'UpperLeg', side + 'LowerLeg', side + 'Foot'], 'hips')
            if z < 0.075 and y < -0.04:
                k = min(1.0, (-0.04 - y) / 0.03)
                w = {side + 'Foot': 1 - 0.8 * k, side + 'Toes': 0.8 * k}
            put(vi, w)
        else:
            put(vi, chain_weights(-z, [-0.31, -0.37, -0.42, -0.465, -0.51],
                                  ['hips', 'spine', 'chest', 'upperChest', 'neck'], 'hips'))

# 沿网格边做几轮权重平滑，让关节处过渡柔和；切口两侧已不相连，不会互相渗透
el = np.empty(len(me.edges) * 2, np.int32)
me.edges.foreach_get('vertices', el)
el = el.reshape(-1, 2)
deg = np.bincount(el.ravel(), minlength=nv).astype(np.float32)
for _ in range(3):
    acc = np.zeros_like(W)
    np.add.at(acc, el[:, 0], W[el[:, 1]])
    np.add.at(acc, el[:, 1], W[el[:, 0]])
    W = 0.5 * W + 0.5 * acc / np.maximum(deg, 1)[:, None]
# 每个顶点最多保留 4 个影响
order = np.argsort(-W, axis=1)[:, :4]
Wk = np.zeros_like(W)
rows = np.arange(nv)[:, None]
Wk[rows, order] = W[rows, order]
Wk /= np.maximum(Wk.sum(1, keepdims=True), 1e-9)
for n in names:
    low.vertex_groups.new(name=n)
for bi, n in enumerate(names):
    col = Wk[:, bi]
    nz = np.nonzero(col > 1e-4)[0]
    vg = low.vertex_groups[n]
    for vi in nz:
        vg.add([int(vi)], float(col[vi]), 'REPLACE')
print('WEIGHTS bones', len(names), 'max influences', int((Wk > 1e-4).sum(1).max()))

# ================================================================ 材质拆分与法线整理（平涂贴图时）
MATERIALS = {'Body': dict(shade=(0.86, 0.86, 0.92), outline=0.0, toony=0.9, shift=-0.2)}
if FLAT:
    matid = np.zeros(len(me.polygons), np.int32)
    me.attributes['matid'].data.foreach_get('value', matid)
    src = me.materials[0]
    me.materials.clear()
    for nm in ('Cloth', 'Skin', 'Hair'):   # 三个材质共用一张贴图，只为阴影色和描边各不相同
        m2 = src.copy()
        m2.name = nm
        me.materials.append(m2)
    me.polygons.foreach_set('material_index', matid)
    # 阴影色向蓝紫偏；描边深海军蓝，头发最粗、皮肤最细
    MATERIALS = {
        # 与 B 版调色节点的对应：
        # - 色阶节点的暗部色 (0.70, 0.74, 0.83) → shade（阴影色乘数），这里再向蓝紫偏一点；
        # - 色阶由三档软过渡改为两档硬边 → toony 0.95；
        # - 视角边缘亮色（Facing 0.48–0.92 × 蓝色遮罩 × 0.16，滤色 (0.15, 0.34, 0.68)）→ rim，只给头发和服装；
        # - 提饱和、提亮蓝色区域 → 不在材质里做，平涂贴图直接用设定图的色值。
        'Cloth': dict(shade=(0.74, 0.72, 0.92), outline=0.0024, outline_color=(0.10, 0.12, 0.30), toony=0.95, shift=-0.05,
                      rim=(0.024, 0.054, 0.109), rim_power=3.0),
        'Skin': dict(shade=(0.98, 0.80, 0.82), outline=0.0016, outline_color=(0.36, 0.20, 0.26), toony=0.95, shift=-0.15),
        'Hair': dict(shade=(0.70, 0.72, 0.95), outline=0.0030, outline_color=(0.09, 0.11, 0.30), toony=0.95, shift=-0.05,
                     rim=(0.033, 0.075, 0.150), rim_power=3.0),
    }
    if os.environ.get('TEX', 'painted') == 'painted':
        # 手绘贴图为主：阴影色贴近主色、只略偏蓝紫，阴影范围收窄，描边比平涂版细一档
        MATERIALS = {
            'Cloth': dict(shade=(0.88, 0.88, 0.96), outline=0.0012, outline_color=(0.10, 0.12, 0.30), toony=0.95, shift=0.25,
                          rim=(0.012, 0.027, 0.054), rim_power=3.0),
            'Skin': dict(shade=(0.98, 0.90, 0.91), outline=0.0009, outline_color=(0.36, 0.20, 0.26), toony=0.95, shift=0.3),
            'Hair': dict(shade=(0.87, 0.88, 0.97), outline=0.0014, outline_color=(0.09, 0.11, 0.30), toony=0.95, shift=0.25,
                         rim=(0.016, 0.037, 0.075), rim_power=3.0),
        }
    # 顶点法线：脸取球面代理，头发取“球面/柱面代理 + 大幅平滑”的混合，其余部位轻度平滑。
    # 这样脸上不会有五官起伏投下的碎阴影，头发的阴影成大块。
    vmat = np.zeros((nv, 3), np.int32)
    for poly in me.polygons:
        for vi in poly.vertices:
            vmat[vi, matid[poly.index]] += 1
    vm = vmat.argmax(1)
    N0 = np.zeros(nv * 3, np.float32)
    me.vertices.foreach_get('normal', N0)
    N0 = N0.reshape(-1, 3)
    el0 = np.empty(len(me.edges) * 2, np.int32)
    me.edges.foreach_get('vertices', el0)
    el0 = el0.reshape(-1, 2)

    def smooth_n(N, it):
        N = N.copy()
        for _ in range(it):
            acc = N.copy()
            np.add.at(acc, el0[:, 0], N[el0[:, 1]])
            np.add.at(acc, el0[:, 1], N[el0[:, 0]])
            N = acc / np.maximum(np.linalg.norm(acc, axis=1, keepdims=True), 1e-9)
        return N
    fs = np.load(os.path.join(WORK, 'face_sphere.npy'))
    N = smooth_n(N0, 2)
    hairv = vm == 2
    up = P[:, 2] > 0.60
    proxy = np.where(up[:, None], P - np.array([0.0, 0.0, 0.74]),
                     np.c_[P[:, 0] - 0.03, P[:, 1] - 0.09, np.full(nv, 0.12)])
    proxy /= np.maximum(np.linalg.norm(proxy, axis=1, keepdims=True), 1e-9)
    Nh = 0.6 * proxy + 0.4 * smooth_n(N0, 8)
    N[hairv] = Nh[hairv]
    facev = (vm == 1) & (P[:, 2] > 0.55)
    N[facev] = (P - fs[:3])[facev]
    N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-9)
    me.normals_split_custom_set_from_vertices(N.tolist())

# ================================================================ 面部 2D 贴花（眼睛、嘴）
# 贴花片贴在脸的皮肤表面外 3 毫米处：从脸部代理球心向外发射线，取皮肤上的交点；
# 落在刘海上的点改用周围皮肤点插值，这样贴花会从刘海下面穿过，被刘海自然遮住。
FACE = None
if FLAT and os.path.exists(os.path.join(WORK, 'eyes.png')):
    from mathutils.bvhtree import BVHTree
    lo = np.empty(len(me.loops), np.int32)
    me.loops.foreach_get('vertex_index', lo)
    bvh = BVHTree.FromPolygons(P.tolist(), lo.reshape(-1, 3).tolist())
    fcv, frr = Vector(fs[:3].tolist()), float(fs[3])
    # 重绘图集的眼睛偏宽，贴花片取大一些；程序绘制的图集用 0.108
    ASSET_EYES = os.path.exists(os.path.join(WORK, 'assets', 'eyes.png'))
    EYE_Z, EYE_SIZE = 0.657, (0.128 if ASSET_EYES else 0.108)
    PATCHES = [  # (材质序号, 中心 x, 中心 z, 边长, 网格数, 图集内 u0, 每片 u 宽, 每片 v 高)
        (0, -0.098, EYE_Z, EYE_SIZE, 10, 0.0, 0.25, 1 / 3),
        (0, 0.062, EYE_Z, EYE_SIZE, 10, 0.25, 0.25, 1 / 3),
        (1, -0.017, 0.583, 0.072, 7, 0.0, 0.25, 0.5),
    ]
    fv, fuv, fnrm, ffaces, fmat = [], [], [], [], []
    for mi, cx0, cz0, size, n, u0, du, dv in PATCHES:
        pts = np.zeros((n, n, 3))
        dirs = np.zeros((n, n, 3))
        ok = np.zeros((n, n), bool)
        for i in range(n):
            for j in range(n):
                xw = cx0 + (j / (n - 1) - 0.5) * size
                zw = cz0 + (0.5 - i / (n - 1)) * size
                dy2 = frr ** 2 - (xw - fcv.x) ** 2 - (zw - fcv.z) ** 2
                tgt = Vector((xw, fcv.y - math.sqrt(max(dy2, 4e-4)), zw))
                d = (tgt - fcv).normalized()
                loc, _, idx, dist = bvh.ray_cast(fcv, d, frr * 1.6)
                dirs[i, j] = d
                if loc is not None and dist > 0.6 * frr and matid[idx] == 1:
                    pts[i, j], ok[i, j] = loc, True
                else:
                    pts[i, j] = tgt
        rad = np.linalg.norm(pts - fs[:3], axis=2)
        for _ in range(40):      # 用皮肤点的半径填补刘海下的点
            if ok.all():
                break
            acc = np.zeros_like(rad)
            cnt = np.zeros_like(rad)
            for di, dj in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                sh = np.roll(ok, (di, dj), (0, 1))
                if di == 1: sh[0, :] = False
                if di == -1: sh[-1, :] = False
                if dj == 1: sh[:, 0] = False
                if dj == -1: sh[:, -1] = False
                acc += np.roll(rad, (di, dj), (0, 1)) * sh
                cnt += sh
            fill = ~ok & (cnt > 0)
            rad[fill] = acc[fill] / cnt[fill]
            ok |= fill
        for _ in range(2):       # 轻度平滑，压掉低模表面的小起伏
            pad = np.pad(rad, 1, mode='edge')
            rad = 0.5 * rad + 0.125 * (pad[:-2, 1:-1] + pad[2:, 1:-1] + pad[1:-1, :-2] + pad[1:-1, 2:])
        base_i = len(fv)
        for i in range(n):
            for j in range(n):
                fv.append((fs[:3] + dirs[i, j] * (rad[i, j] + 0.003)).tolist())
                fnrm.append(dirs[i, j].tolist())
                fuv.append((u0 + du * j / (n - 1), 1.0 - dv * i / (n - 1)))
        for i in range(n - 1):
            for j in range(n - 1):
                a = base_i + i * n + j
                ffaces.append((a, a + n, a + n + 1, a + 1))
                fmat.append(mi)
    # 围裙小鲸鱼贴花：从正前方向围裙投射，权重取最近的身体顶点，随裙摆一起动
    DECAL_W = []
    n, size, cx0, cz0 = 6, 0.058, 0.036, 0.287
    from mathutils import kdtree as _kd
    kdt = _kd.KDTree(nv)
    for i, pp in enumerate(P):
        kdt.insert(pp, i)
    kdt.balance()
    base_i = len(fv)
    for i in range(n):
        for j in range(n):
            xw = cx0 + (j / (n - 1) - 0.5) * size
            zw = cz0 + (0.5 - i / (n - 1)) * size
            loc, nrm, idx, dist = bvh.ray_cast(Vector((xw, -0.6, zw)), Vector((0, 1, 0)))
            pos = Vector((xw, (loc.y if loc is not None else -0.2) - 0.004, zw))
            fv.append(list(pos))
            fnrm.append([0.0, -1.0, 0.0])
            fuv.append((j / (n - 1), 1.0 - i / (n - 1)))
            DECAL_W.append((len(fv) - 1, kdt.find(pos)[1]))
    for i in range(n - 1):
        for j in range(n - 1):
            a = base_i + i * n + j
            ffaces.append((a, a + n, a + n + 1, a + 1))
            fmat.append(2)
    fme = bpy.data.meshes.new('Face')
    fme.from_pydata(fv, [], ffaces)
    uvl = fme.uv_layers.new(name='UVMap')
    for poly in fme.polygons:
        poly.use_smooth = True
        for li in poly.loop_indices:
            uvl.data[li].uv = fuv[fme.loops[li].vertex_index]
    for nm, tex_name in (('FaceEyes', 'eyes.png'), ('FaceMouth', 'mouth.png'), ('Decal', 'whale.png')):
        m3 = bpy.data.materials.new(nm)
        m3.use_nodes = True
        nt3 = m3.node_tree
        b3 = next(n for n in nt3.nodes if n.type == 'BSDF_PRINCIPLED')
        t3 = nt3.nodes.new('ShaderNodeTexImage')
        t3.image = bpy.data.images.load(os.path.join(WORK, tex_name))
        nt3.links.new(t3.outputs['Color'], b3.inputs['Base Color'])
        nt3.links.new(t3.outputs['Alpha'], b3.inputs['Alpha'])
        b3.inputs['Roughness'].default_value = 1.0
        for attr, val in (('blend_method', 'BLEND'), ('surface_render_method', 'BLENDED')):
            try:
                setattr(m3, attr, val)
            except Exception:
                pass
        m3.use_backface_culling = True
        fme.materials.append(m3)
    fme.polygons.foreach_set('material_index', fmat)
    fme.normals_split_custom_set_from_vertices(fnrm)
    FACE = bpy.data.objects.new('Face', fme)
    bpy.context.scene.collection.objects.link(FACE)
    FACE.vertex_groups.new(name='head').add(list(range(base_i)), 1.0, 'REPLACE')
    FACE_DECAL_W = DECAL_W
    for nm in ('FaceEyes', 'FaceMouth', 'Decal'):
        MATERIALS[nm] = dict(shade=(1, 1, 1), outline=0.0, toony=1.0, shift=-1.0)
    for fvi, bvi in FACE_DECAL_W:
        for bi in np.nonzero(Wk[bvi] > 1e-4)[0]:
            g = FACE.vertex_groups.get(names[bi]) or FACE.vertex_groups.new(name=names[bi])
            g.add([fvi], float(Wk[bvi, bi]), 'REPLACE')
    print('FACE decal verts', len(fv), 'faces', len(ffaces))

# ================================================================ 骨架、绑定、摆 T 姿势
arm_data = bpy.data.armatures.new('Armature')
arm = bpy.data.objects.new('Armature', arm_data)
bpy.context.scene.collection.objects.link(arm)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode='EDIT')
eb = {}
for n, (h, t, par) in BONES.items():
    b = arm_data.edit_bones.new(n)
    b.head, b.tail = h, t
    eb[n] = b
for n, (h, t, par) in BONES.items():
    if par:
        eb[n].parent = eb[par]
        eb[n].use_connect = False
bpy.ops.object.mode_set(mode='OBJECT')
low.parent = arm
mod = low.modifiers.new('Armature', 'ARMATURE')
mod.object = arm

bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode='POSE')
for sx, side in (() if SCRIPT_ARMS else ((1, 'left'), (-1, 'right'))):
    for bn in (side + 'UpperArm', side + 'LowerArm', side + 'Hand'):
        pb = arm.pose.bones[bn]
        bpy.context.view_layer.update()
        cur = (pb.tail - pb.head).normalized()
        R = cur.rotation_difference(Vector((sx, 0, 0))).to_matrix().to_4x4()
        pb.matrix = Matrix.Translation(pb.head) @ R @ Matrix.Translation(-pb.head) @ pb.matrix
        bpy.context.view_layer.update()
bpy.ops.object.mode_set(mode='OBJECT')
bpy.context.view_layer.objects.active = low
bpy.ops.object.modifier_apply(modifier='Armature')
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode='POSE')
bpy.ops.pose.armature_apply(selected=False)
bpy.ops.object.mode_set(mode='OBJECT')
mod = low.modifiers.new('Armature', 'ARMATURE')
mod.object = arm
if SCRIPT_ARMS:
    # 袖子、袖口荷叶边、小圆手：直接按 T 姿势建模，颜色取贴图右下角的色块带
    from meshkit import Part, ellipsoid, frill_strip, interp, part_to_object, sweep

    def swuv(k):
        return (0.985, 0.02 + 0.04 * k)
    NAVY_UV, WHITE_UV, WSHADE_UV, SKIN_UV, LINE_UV = (swuv(k) for k in range(5))
    bone_index = {n: i for i, n in enumerate(names)}
    mats = {m.name: m for m in me.materials}
    new_objs = []
    for sx, side in ((1, 'left'), (-1, 'right')):
        nm = [side + 'UpperArm', side + 'LowerArm', side + 'Hand']

        def aw(p, nm=nm, side=side):
            w = chain_weights(-abs(p.x), [-ARM_X[0], -ARM_X[1], -ARM_X[2]], nm, side + 'Shoulder')
            if abs(p.x) < ARM_X[0]:
                k = min(1.0, (ARM_X[0] - abs(p.x)) / 0.03)
                w = {side + 'Shoulder': 1 - 0.6 * k, 'upperChest': 0.6 * k}
            return w
        sl = Part('sleeve_' + side, 'Cloth')
        xs = [0.040, 0.055, 0.072, 0.090, 0.110, 0.135, 0.165, 0.195, 0.225, 0.246]
        rr = [interp([(0.040, 0.0), (0.055, 0.036), (0.085, 0.052), (0.125, 0.049), (0.185, 0.040), (0.246, 0.034)], xv)
              for xv in xs]
        sweep(sl, [Vector((sx * xv, ARM_Y, ARM_Z)) for xv in xs], rr, rr, lambda *a: NAVY_UV, lambda i, t, p: aw(p),
              nseg=12, n0=Vector((0, 0, 1)))
        cf = Part('cuff_' + side, 'Cloth')
        xs2 = [0.236, 0.240, 0.250, 0.260, 0.264]
        r2 = [0.0, 0.038, 0.039, 0.038, 0.0]
        sweep(cf, [Vector((sx * xv, ARM_Y, ARM_Z)) for xv in xs2], r2, r2, lambda *a: WHITE_UV, lambda i, t, p: aw(p),
              nseg=12, n0=Vector((0, 0, 1)))
        n = 25
        base = [Vector((sx * 0.260, ARM_Y + 0.034 * math.sin(2 * math.pi * j / (n - 1)),
                        ARM_Z + 0.034 * math.cos(2 * math.pi * j / (n - 1)))) for j in range(n)]
        outd = [Vector((sx * 0.9, math.sin(2 * math.pi * j / (n - 1)) * 0.75, math.cos(2 * math.pi * j / (n - 1)) * 0.75))
                for j in range(n)]
        frill_strip(cf, base, outd, 0.022, 0.005, 8, lambda i, j, p, layer: WHITE_UV if layer == 0 else WSHADE_UV,
                    lambda i, j, p: aw(p), rows=3, thick=0.004, closed=True,
                    hint_fn=lambda p: Vector((0, p.y - ARM_Y, p.z - ARM_Z)))
        hd = Part('hand_' + side, 'Skin')
        ellipsoid(hd, Vector((sx * 0.288, ARM_Y, ARM_Z)), (0.034, 0.026, 0.028), lambda i, j, p: SKIN_UV,
                  lambda i, j, p: {side + 'Hand': 1.0}, nu=12, nv=8)
        ellipsoid(hd, Vector((sx * 0.278, ARM_Y - 0.021, ARM_Z + 0.006)), (0.015, 0.011, 0.011), lambda i, j, p: SKIN_UV,
                  lambda i, j, p: {side + 'Hand': 1.0}, nu=8, nv=6)
        for prt in (sl, cf, hd):
            new_objs.append(part_to_object(prt, bone_index, mats[prt.mat]))
    # 胸前：白领子、黑蝴蝶结、围裙上身。原来被双手挡住的区域没有贴图内容，用脚本部件盖住封口。
    from mathutils.bvhtree import BVHTree as _BVH
    from meshkit import grid_solid, smooth as _sm
    _lo = np.empty(len(me.loops), np.int32)
    me.loops.foreach_get('vertex_index', _lo)
    _bv = _BVH.FromPolygons(C.np_verts(me).tolist(), _lo.reshape(-1, 3).tolist())

    def chest_y(xw, zw):
        loc = _bv.ray_cast(Vector((xw, -0.6, zw)), Vector((0, 1, 0)))[0]
        return min(loc.y, -0.035) if loc is not None and abs(loc.x - xw) < 1e-3 and loc.y < 0.02 else -0.05
    CW = {'upperChest': 0.6, 'chest': 0.4}
    bib = Part('bib', 'Cloth')
    zs = [0.492, 0.475, 0.455, 0.435, 0.415]
    Pg = []
    for zw in zs:
        row = []
        for j in range(7):
            xw = -0.046 + 0.092 * j / 6
            row.append(Vector((xw, chest_y(xw * 0.6, zw) - 0.012 + 0.010 * (xw / 0.046) ** 2, zw)))
        Pg.append(row)
    grid_solid(bib, Pg, 0.004, lambda i, j, p, layer: WHITE_UV, lambda i, j, p: CW, hint_fn=lambda p: Vector((0, -1, 0)))
    for sx in (1, -1):   # 围裙上身两侧的荷叶边
        n = 13
        base = [Vector((sx * 0.046, Pg[0][0 if sx < 0 else 6].y + 0.002, 0.497 - 0.085 * j / (n - 1))) for j in range(n)]
        frill_strip(bib, base, [Vector((sx, -0.25, 0))] * n, 0.020, 0.004, 4,
                    lambda i, j, p, layer: WHITE_UV if layer == 0 else WSHADE_UV, lambda i, j, p: CW, rows=3,
                    thick=0.004, scallop=0.1, hint_fn=lambda p: Vector((0, -1, 0)))
    collar = Part('collar', 'Cloth')
    yc = chest_y(0.0, 0.50)
    n = 25
    base, outd = [], []
    for j in range(n):
        a = math.radians(-105 + 210 * j / (n - 1))
        base.append(Vector((0.040 * math.sin(a), 0.004 - 0.040 * math.cos(a), 0.522)))
        outd.append(Vector((math.sin(a), -math.cos(a), -0.75)))
    frill_strip(collar, base, outd, 0.030, 0.0, 1, lambda i, j, p, layer: WHITE_UV,
                lambda i, j, p: {'upperChest': 0.6, 'neck': 0.4}, rows=3, thick=0.004,
                hint_fn=lambda p: Vector((p.x, p.y, 0.5)))
    bow = Part('collar_bow', 'Cloth')
    bc0 = Vector((0, min(yc, -0.045) - 0.016, 0.498))
    for s_ in (-1, 1):
        def deform(l, s_=s_):
            k = 0.30 + 0.70 * _sm((l.x * s_ + 1) / 2 * 1.15)
            return Vector((l.x, l.y * (0.55 + 0.45 * k), l.z * k))
        ellipsoid(bow, bc0 + Vector((s_ * 0.017, 0, 0)), (0.018, 0.006, 0.013), lambda i, j, p: LINE_UV,
                  lambda i, j, p: CW, nu=12, nv=8, deform=deform)
    ellipsoid(bow, bc0 + Vector((0, -0.003, 0)), (0.006, 0.006, 0.007), lambda i, j, p: swuv(5), lambda i, j, p: CW,
              nu=8, nv=6)
    for prt in (bib, collar, bow):
        new_objs.append(part_to_object(prt, bone_index, mats[prt.mat]))
    bpy.ops.object.select_all(action='DESELECT')
    for o in new_objs:
        o.select_set(True)
    low.select_set(True)
    bpy.context.view_layer.objects.active = low
    bpy.ops.object.join()
    me = low.data
    print('ARMS scripted, total tris', len(me.polygons))
if FACE:
    FACE.parent = arm
    fm = FACE.modifiers.new('Armature', 'ARMATURE')
    fm.object = arm
low.name = 'Body'
me.name = 'Body'
for attr in ('partcol',):
    if attr in me.color_attributes:
        me.color_attributes.remove(me.color_attributes[attr])
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, 'low_rigged.blend'))

bpy.ops.object.select_all(action='SELECT')
bpy.ops.export_scene.gltf(filepath=os.path.join(WORK, 'whale-maid.glb'), export_format='GLB', export_yup=True,
                          export_apply=False, export_skins=True, export_morph=False, export_animations=False,
                          export_materials='EXPORT', export_image_format='AUTO', export_tangents=False,
                          export_all_influences=False)
# 表情：切换图集格子。贴图坐标偏移只能取整格，所以全部设为二值表情；
# 带眼部或嘴部画面的情绪表情会屏蔽眨眼和口型，避免两个偏移相加落到错误的格子。
EXPR = {}
if FACE:
    def EY(c, r):
        return dict(material='FaceEyes', offset=[c * 0.5, r / 3])

    def MO(c, r):
        return dict(material='FaceMouth', offset=[c * 0.25, r * 0.5])
    emo = dict(binary=True, overrideBlink='block', overrideMouth='block')
    EXPR = {
        'happy': dict(tex=[EY(0, 1), MO(3, 0)], **emo),
        'surprised': dict(tex=[EY(1, 1), MO(1, 1)], **emo),
        'sad': dict(tex=[EY(0, 2), MO(0, 1)], **emo),
        'angry': dict(tex=[EY(0, 2), MO(0, 1)], **emo),
        'relaxed': dict(tex=[], binary=True),
        'blink': dict(tex=[EY(1, 0)], binary=True),
        'aa': dict(tex=[MO(1, 0)], binary=True),
        'ih': dict(tex=[MO(2, 1)], binary=True),
        'ou': dict(tex=[MO(2, 0)], binary=True),
        'ee': dict(tex=[MO(2, 1)], binary=True),
        'oh': dict(tex=[MO(3, 1)], binary=True),
        'neutral': dict(tex=[], binary=True),
    }
humanoid = ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head']
for side in ('left', 'right'):
    humanoid += [side + s for s in ('Shoulder', 'UpperArm', 'LowerArm', 'Hand', 'UpperLeg', 'LowerLeg', 'Foot', 'Toes')]
rig = dict(
    humanoid={n: n for n in humanoid}, springs=SPRINGS, colliders=COLLIDERS, expressions=EXPR,
    look_at_pos=[-0.018, -0.2, 0.657],
    materials=MATERIALS,
    meta=dict(
        name='Whale Maid (image-to-3D, dev test)', version='0.2',
        authors=['Infinity Deck pet prototype', 'mesh generated with Pixal3D'],
        copyrightInformation='开发测试形象；网格由 Pixal3D 依据设定图生成，形象权利不属于本项目，不可再分发、不可商用',
    ),
)
with open(os.path.join(WORK, 'rig.json'), 'w') as f:
    json.dump(rig, f, ensure_ascii=False, indent=1)
print('EXPORTED tris', len(me.polygons), 'verts', len(me.vertices), 'bones', len(BONES), 'spring chains', len(SPRINGS))
