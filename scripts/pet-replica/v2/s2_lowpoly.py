"""阶段 2：减面、部位分区、切开粘连并封口，保存低模工程。

用法：Blender --background --python s2_lowpoly.py -- <工作目录> [目标三角面数]
输入：<工作目录>/high.blend、high_pos.npy、high_col.npy（s1_prepare.py 产出）
产出：<工作目录>/low.blend（对象 Low，面属性 part 与 cap；对象 High 保留作烘焙源）

部位编号（面属性 part）：1 头 2 躯干与下身 3 左臂 4 右臂 5 头发 6 尾巴
切开的部位对：手臂/躯干、左臂/右臂、头发/身体、头发/尾巴、尾巴/身体；切口两侧各自封口，封口面记在面属性 cap。
"""
import os
import sys

import bmesh
import bpy
import numpy as np
import math

from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classify as K
import common as C

argv = sys.argv[sys.argv.index('--') + 1:]
WORK = argv[0]
TARGET = int(argv[1]) if len(argv) > 1 else 17300  # 封口约一千面，脚本手臂、胸前部件和贴花约两千面
P_HEAD, P_TORSO, P_ARM_L, P_ARM_R, P_HAIR, P_TAIL = 1, 2, 3, 4, 5, 6
PART_NAMES = {1: 'head', 2: 'torso', 3: 'armL', 4: 'armR', 5: 'hair', 6: 'tail'}
CUT_PAIRS = {frozenset(p) for p in [(P_ARM_L, P_ARM_R), (P_HAIR, P_TORSO),
                                    (P_HAIR, P_ARM_L), (P_HAIR, P_ARM_R), (P_HAIR, P_TAIL), (P_TAIL, P_TORSO),
                                    (P_ARM_L, P_HEAD), (P_ARM_R, P_HEAD)]}

bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'high.blend'))
high = bpy.data.objects['High']
HP = np.load(os.path.join(WORK, 'high_pos.npy'))
HC = np.load(os.path.join(WORK, 'high_col.npy'))
hcls = K.classify(HP, HC)
print('HIGH classes', {K.NAMES[i]: int((hcls == i).sum()) for i in range(5)})


# ---------------------------------------------------------------- 减面
# 细节分配：减面前把脸、手所在的区域按径向平滑放大，减面后再逆变换回去。
# 二次误差减面与尺度相关，被放大的区域会保留更多三角面。
WARPS = [(np.array([K.CFG['face_x'], K.CFG['face_y'] + 0.02, K.CFG['eye_z'] + 0.008]), 0.17, 0.9)]  # 脸与眼睛：中心、影响半径、放大量
_hl, _hr = (K.ARM_L[2] + K.ARM_L[3]) / 2, (K.ARM_R[2] + K.ARM_R[3]) / 2
if np.linalg.norm(_hl - _hr) < 0.12:      # 双手合拢：一个放大区
    WARPS.append(((_hl + _hr) / 2, 0.085, 0.9))
else:                                      # 双手分开：各一个，范围不重叠
    WARPS += [(_hl, 0.06, 0.9), (_hr, 0.06, 0.9)]
if K.CFG['arms'] == 'script':              # 手臂要整体换掉时，才沿手臂与躯干的交界切开
    CUT_PAIRS |= {frozenset((P_ARM_L, P_TORSO)), frozenset((P_ARM_R, P_TORSO))}


def _g(r, rad):
    t = np.clip(r / rad, 0, 1)
    return (1 - t * t) ** 2


def warp(P):
    P = P.copy()
    for c, rad, k in WARPS:
        d = P - c
        r = np.linalg.norm(d, axis=1)
        P = c + d * (1 + k * _g(r, rad))[:, None]
    return P


def unwarp(P):
    P = P.copy()
    for c, rad, k in reversed(WARPS):
        d = P - c
        r2 = np.linalg.norm(d, axis=1)
        lo, hi = np.zeros_like(r2), r2.copy()
        for _ in range(40):  # 径向映射单调，二分求逆
            mid = (lo + hi) / 2
            big = mid * (1 + k * _g(mid, rad)) > r2
            hi = np.where(big, mid, hi)
            lo = np.where(big, lo, mid)
        r = (lo + hi) / 2
        P = c + d * (r / np.maximum(r2, 1e-9))[:, None]
    return P


def cull_hidden(me):
    """删除从外部任何方向都看不到的面（围裙后面的裙子、刘海下面的额头、裙内的身体等）。

    相邻很近的里外两层各自减面后会互相穿插，外层上露出里层的碎片；只留外层就不会。
    判定：从面心沿法线以及绕法线 45°、75° 的 8 个方向发射线，全部被挡住才算看不到。
    """
    v = C.np_verts(me)
    lo = np.empty(len(me.loops), np.int32)
    me.loops.foreach_get('vertex_index', lo)
    tri = lo.reshape(-1, 3)
    bvh = BVHTree.FromPolygons(v.tolist(), tri.tolist())
    fn = np.cross(v[tri[:, 1]] - v[tri[:, 0]], v[tri[:, 2]] - v[tri[:, 0]])
    fn /= np.maximum(np.linalg.norm(fn, axis=1, keepdims=True), 1e-12)
    org = v[tri].mean(1) + fn * 2e-4
    ref = np.where(np.abs(fn[:, 2:3]) < 0.9, np.array([[0.0, 0.0, 1.0]]), np.array([[1.0, 0.0, 0.0]]))
    t1 = np.cross(fn, ref)
    t1 /= np.maximum(np.linalg.norm(t1, axis=1, keepdims=True), 1e-12)
    t2 = np.cross(fn, t1)
    ray = bvh.ray_cast
    blocked = np.arange(len(tri))
    specs = [(0.0, 0.0)] + [(45.0, a) for a in (0, 90, 180, 270)] + [(75.0, a) for a in (45, 135, 225, 315)]
    for tilt, az in specs:
        ct, st = math.cos(math.radians(tilt)), math.sin(math.radians(tilt))
        ca, sa = math.cos(math.radians(az)), math.sin(math.radians(az))
        d = ct * fn[blocked] + st * (ca * t1[blocked] + sa * t2[blocked])
        still = [i for i, o, dd in zip(blocked.tolist(), org[blocked].tolist(), d.tolist()) if ray(o, dd)[0] is not None]
        blocked = np.array(still, np.int64)
    hidden = np.zeros(len(tri), bool)
    hidden[blocked] = True
    raw = int(hidden.sum())
    # 只剔除成片的隐藏区域：先收缩 3 圈去掉零星隐藏面，再在原隐藏范围内扩回 2 圈
    nvert = len(v)
    orig = hidden.copy()
    for _ in range(3):
        vall = np.ones(nvert, bool)
        np.logical_and.at(vall, tri.ravel(), np.repeat(hidden, 3))
        hidden = vall[tri].all(1)
    for _ in range(2):
        vany = np.zeros(nvert, bool)
        np.logical_or.at(vany, tri.ravel(), np.repeat(hidden, 3))
        hidden = vany[tri].any(1) & orig
    blocked = np.nonzero(hidden)[0]
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.faces.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[bm.faces[i] for i in blocked.tolist()], context='FACES')
    loose = [vv for vv in bm.verts if not vv.link_faces]
    bmesh.ops.delete(bm, geom=loose, context='VERTS')
    bm.to_mesh(me)
    bm.free()
    print('HIDDEN faces removed', int(hidden.sum()), 'of', len(tri), '(raw hidden', raw, ')')


def make_base():
    """高模副本：删除杂质与游离碎块，再做体素重建，得到只含外表面的流形网格。"""
    me = high.data.copy()
    ob = bpy.data.objects.new('Base', me)
    bpy.context.scene.collection.objects.link(ob)
    while me.uv_layers:
        me.uv_layers.remove(me.uv_layers[0])
    for a in [a for a in me.color_attributes]:
        me.color_attributes.remove(a)
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.verts.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[bm.verts[i] for i in np.nonzero(hcls == K.JUNK)[0]], context='VERTS')
    seen, small = set(), []
    for v in bm.verts:
        if v in seen:
            continue
        comp, stack = [], [v]
        seen.add(v)
        while stack:
            x = stack.pop()
            comp.append(x)
            for e in x.link_edges:
                o = e.other_vert(x)
                if o not in seen:
                    seen.add(o)
                    stack.append(o)
        if len(comp) < 600:
            small += comp
    bmesh.ops.delete(bm, geom=small, context='VERTS')
    bm.to_mesh(me)
    bm.free()
    m = ob.modifiers.new('rm', 'REMESH')
    m.mode = 'VOXEL'
    m.voxel_size = VOXEL
    m.adaptivity = 0
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.modifier_apply(modifier='rm')
    bm = bmesh.new()
    bm.from_mesh(ob.data)
    bmesh.ops.triangulate(bm, faces=bm.faces)
    bm.to_mesh(ob.data)
    bm.free()
    cull_hidden(ob.data)
    P = warp(C.np_verts(ob.data).astype(np.float64)).astype(np.float32)
    ob.data.vertices.foreach_set('co', P.ravel())
    ob.data.update()
    return ob


VOXEL = float(os.environ.get('VOXEL', '0.004'))  # 实测 2.5 毫米体素会让后续减面碎裂
base = make_base()
print('REMESHED', len(base.data.polygons), 'tris')


def make_low(ratio):
    ob = base.copy()
    ob.data = base.data.copy()
    ob.name = 'Low'
    bpy.context.scene.collection.objects.link(ob)
    m = ob.modifiers.new('dec', 'DECIMATE')
    m.ratio = ratio
    bpy.context.view_layer.objects.active = ob
    bpy.ops.object.modifier_apply(modifier='dec')
    return ob


lo, hi = 0.004, 0.08
low = None
for _ in range(9):
    mid = (lo + hi) / 2
    if low:
        bpy.data.objects.remove(low)
    low = make_low(mid)
    n = len(low.data.polygons)
    if n > TARGET:
        hi = mid
    else:
        lo = mid
    if TARGET - 500 <= n <= TARGET:
        break
print('DECIMATED', len(low.data.polygons), 'faces', len(low.data.vertices), 'verts ratio', round(mid, 4))
bpy.data.objects.remove(base)
low.name = 'Low'
me = low.data
me.name = 'Low'
P = unwarp(C.np_verts(me).astype(np.float64)).astype(np.float32)
me.vertices.foreach_set('co', P.ravel())
me.update()

# ---------------------------------------------------------------- 分类转移与分区
LP = C.np_verts(me)
kd = kdtree.KDTree(len(HP))
for i, p in enumerate(HP):
    kd.insert(p, i)
kd.balance()
lcls = np.zeros(len(LP), np.int8)
lcol = np.zeros((len(LP), 3), np.float32)
for i, p in enumerate(LP):
    hits = kd.find_n(Vector(p), 8)
    idx = [h[1] for h in hits]
    lcls[i] = np.bincount(hcls[idx], minlength=5).argmax()
    lcol[i] = HC[idx].mean(0)

vpart = np.zeros(len(LP), np.int8)
vpart[lcls == K.HEAD] = P_HEAD
vpart[lcls == K.HAIR] = P_HAIR
vpart[lcls == K.TAIL] = P_TAIL
vpart[lcls == K.JUNK] = P_HAIR
body = lcls == K.BODY
ascore, aside, aseg, _ = K.arm_score(LP)
tdist, _ = K.seg_dist(LP, np.array([0, K.CFG['torso_y'], 0.36]), np.array([0, K.CFG['torso_y'], 0.55]))
is_arm = body & (ascore < tdist / 0.078) & (ascore < 1.15) & (LP[:, 2] > min(0.37, K.ARM_L[:, 2].min() - 0.06)) & (LP[:, 2] < 0.56)
vpart[body] = P_TORSO
vpart[is_arm & (aside > 0)] = P_ARM_L
vpart[is_arm & (aside < 0)] = P_ARM_R

bm = bmesh.new()
bm.from_mesh(me)
# 删除减面后残留的游离小碎片
seen, crumbs = set(), []
for v in bm.verts:
    if v in seen:
        continue
    comp, stack = [], [v]
    seen.add(v)
    while stack:
        x = stack.pop()
        comp.append(x)
        for e in x.link_edges:
            o = e.other_vert(x)
            if o not in seen:
                seen.add(o)
                stack.append(o)
    cx = sum(v.co.x for v in comp) / len(comp)
    cz = sum(v.co.z for v in comp) / len(comp)
    if len(comp) < 45 or (K.CFG['junk'] and len(comp) < 150 and cx < -0.24 and 0.3 < cz < 0.78):  # 游离碎片；右侧残影附近放宽
        crumbs += comp
# 右侧残影的下半截：这一带的头发是浅蓝，按邻域平均色偏深来识别并删除
_lum = 0.2126 * lcol[:, 0] + 0.7152 * lcol[:, 1] + 0.0722 * lcol[:, 2]
_dark = K.CFG['junk'] & (LP[:, 0] < -0.24) & (LP[:, 2] > 0.20) & (LP[:, 2] < 0.62) & (_lum < 0.30) & (lcls != K.BODY)
_cs = set(crumbs)
crumbs += [v for v in bm.verts if _dark[v.index] and v not in _cs]
if crumbs:
    keep = np.ones(len(LP), bool)
    keep[[v.index for v in crumbs]] = False
    bmesh.ops.delete(bm, geom=crumbs, context='VERTS')
    vpart, LP, lcol = vpart[keep], LP[keep], lcol[keep]
    print('CRUMBS removed verts', len(crumbs))
bm.verts.index_update()
bm.verts.ensure_lookup_table()
# 标签平滑：每个顶点取自身与邻居的多数标签，去掉零星噪点
for _ in range(3):
    new = vpart.copy()
    for v in bm.verts:
        nb = [vpart[e.other_vert(v).index] for e in v.link_edges] + [vpart[v.index]] * 2
        new[v.index] = np.bincount(nb, minlength=7).argmax()
    vpart = new
fl_part = bm.faces.layers.int.new('part')
fl_cap = bm.faces.layers.int.new('cap')
for f in bm.faces:
    f[fl_part] = int(np.bincount([vpart[v.index] for v in f.verts], minlength=7).argmax())

# ---------------------------------------------------------------- 切开粘连并封口
cut = []
for e in bm.edges:
    ps = {f[fl_part] for f in e.link_faces}
    if len(ps) >= 2 and any(frozenset((a, b)) in CUT_PAIRS for a in ps for b in ps if a < b):
        cut.append(e)
print('CUT edges', len(cut))
pre_open = {e for e in bm.edges if e.is_boundary}  # 剔除隐藏面后留下的开放边，不封口
bmesh.ops.split_edges(bm, edges=cut)
# 封口：按部位收集切口边。闭合的一圈直接补面；两端收在未切开处的开口链，补上首尾连线后成面，
# 两侧的封口面只共用这条连线，部位之间像合页一样分开。
by_part = {}
for e in bm.edges:
    if e.is_boundary and e not in pre_open:
        by_part.setdefault(e.link_faces[0][fl_part], []).append(e)
caps, skipped = [], 0
for part_id, edges in by_part.items():
    eset = set(edges)
    while eset:
        comp, stack = [], [eset.pop()]
        while stack:
            e = stack.pop()
            comp.append(e)
            for v in e.verts:
                for e2 in v.link_edges:
                    if e2 in eset:
                        eset.remove(e2)
                        stack.append(e2)
        deg = {}
        for e in comp:
            for v in e.verts:
                deg[v] = deg.get(v, 0) + 1
        ends = [v for v, d in deg.items() if d == 1]
        if any(d > 2 for d in deg.values()) or len(ends) not in (0, 2) or len(comp) < 3:
            skipped += 1
            continue
        cset = set(comp)
        start = ends[0] if ends else comp[0].verts[0]
        order, prev_e, cur = [start], None, start
        while True:
            nxt = [e for e in cur.link_edges if e in cset and e is not prev_e]
            if not nxt:
                break
            prev_e = nxt[0]
            cur = prev_e.other_vert(cur)
            if cur is start:
                break
            order.append(cur)
        if len(order) < 3:
            skipped += 1
            continue
        try:
            f = bm.faces.new(order)
        except ValueError:
            skipped += 1
            continue
        f[fl_part] = part_id
        f[fl_cap] = 1
        caps.append(f)
print('CAP loops', len(caps), 'skipped', skipped)
# 封口面的绕向与相邻的原有面保持一致（流形网格上共边的两个面沿该边方向相反）
for f in caps:
    flip = None
    for lp in f.loops:
        for g in lp.edge.link_faces:
            if g is f or g[fl_cap]:
                continue
            gl = next(l for l in g.loops if l.edge is lp.edge)
            flip = gl.vert is lp.vert
            break
        if flip is not None:
            break
    if flip:
        f.normal_flip()
tri = bmesh.ops.triangulate(bm, faces=caps)
left = sum(1 for e in bm.edges if e.is_boundary)
cap_n = sum(1 for f in bm.faces if f[fl_cap])
cnt = np.bincount([f[fl_part] for f in bm.faces], minlength=7)
print('CAPS', cap_n, 'faces; boundary edges left', left, '; non-manifold', sum(1 for e in bm.edges if not e.is_manifold))
print('PARTS', {PART_NAMES[i]: int(cnt[i]) for i in range(1, 7)}, 'unassigned', int(cnt[0]))
bm.to_mesh(me)
bm.free()
for p in me.polygons:
    p.use_smooth = True
print('LOW final', len(me.polygons), 'tris', len(me.vertices), 'verts')

# 调试用：把部位写成顶点色
pal = {0: (1, 1, 1), 1: (1.0, 0.75, 0.4), 2: (0.85, 0.2, 0.2), 3: (0.2, 0.8, 0.3), 4: (0.9, 0.3, 0.9), 5: (0.3, 0.55, 1.0),
       6: (0.1, 0.85, 0.85)}
attr = me.color_attributes.new('partcol', 'FLOAT_COLOR', 'CORNER')
part = np.zeros(len(me.polygons), np.int32)
me.attributes['part'].data.foreach_get('value', part)
capf = np.zeros(len(me.polygons), np.int32)
me.attributes['cap'].data.foreach_get('value', capf)
buf = np.ones((len(me.loops), 4), np.float32)
for p in me.polygons:
    c = np.array(pal[int(part[p.index])]) * (0.45 if capf[p.index] else 1.0)
    buf[p.loop_start:p.loop_start + p.loop_total, :3] = c
attr.data.foreach_set('color', buf.ravel())
np.save(os.path.join(WORK, 'low_vcol.npy'), lcol)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, 'low.blend'))
