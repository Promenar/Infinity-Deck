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
bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'low_baked.blend'))
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
for sx, side, arm in ((1, 'left', K.ARM_L), (-1, 'right', K.ARM_R)):
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
for sx, side in ((1, 'left'), (-1, 'right')):
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
humanoid = ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head']
for side in ('left', 'right'):
    humanoid += [side + s for s in ('Shoulder', 'UpperArm', 'LowerArm', 'Hand', 'UpperLeg', 'LowerLeg', 'Foot', 'Toes')]
rig = dict(
    humanoid={n: n for n in humanoid}, springs=SPRINGS, colliders=COLLIDERS, expressions={},
    look_at_pos=[0, -0.2, 0.67],
    materials={'Body': dict(shade=(0.86, 0.86, 0.92), outline=0.0, toony=0.9, shift=-0.2)},
    meta=dict(
        name='Whale Maid (image-to-3D, dev test)', version='0.2',
        authors=['Infinity Deck pet prototype', 'mesh generated with Pixal3D'],
        copyrightInformation='开发测试形象；网格由 Pixal3D 依据设定图生成，形象权利不属于本项目，不可再分发、不可商用',
    ),
)
with open(os.path.join(WORK, 'rig.json'), 'w') as f:
    json.dump(rig, f, ensure_ascii=False, indent=1)
print('EXPORTED tris', len(me.polygons), 'verts', len(me.vertices), 'bones', len(BONES), 'spring chains', len(SPRINGS))
