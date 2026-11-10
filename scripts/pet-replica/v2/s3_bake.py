"""阶段 3：低模展 UV，从高模烘焙基础色与法线，封口面按邻近颜色补色。

用法：Blender --background --python s3_bake.py -- <工作目录>
输入：<工作目录>/low.blend（s2_lowpoly.py 产出）、high_pos.npy、high_col.npy
产出：<工作目录>/base.png（2048×1536）、normal.png（1024×768）、low_baked.blend

贴图预算：基础色 2048×1536 + 法线 1024×768，另留 512×512 给后续的面部表情图集，三者像素合计正好等于 2048²。
基础色版面：上方两块 1024² 分给头部和身体（躯干、手臂、尾巴），下方四块 512² 分给头发（按方位分四组）。
"""
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Vector, kdtree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

WORK = sys.argv[sys.argv.index('--') + 1]
BASE_W, BASE_H = 2048, 1536
NRM_W, NRM_H = 1024, 768

bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'low.blend'))
high, low = bpy.data.objects['High'], bpy.data.objects['Low']
me = low.data

# 低模材质与烘焙目标贴图
img = bpy.data.images.new('base', BASE_W, BASE_H, alpha=True)
img.generated_color = (1, 0, 1, 0)
nimg = bpy.data.images.new('normal', NRM_W, NRM_H, alpha=False)
nimg.colorspace_settings.name = 'Non-Color'
nimg.generated_color = (0.5, 0.5, 1, 1)
mat = bpy.data.materials.new('Body')
mat.use_nodes = True
nt = mat.node_tree
bsdf = next(n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED')
tex = nt.nodes.new('ShaderNodeTexImage')
tex.image = img
ntex = nt.nodes.new('ShaderNodeTexImage')
ntex.image = nimg
me.materials.clear()
me.materials.append(mat)


def activate(node):
    for n in nt.nodes:
        n.select = False
    node.select = True
    nt.nodes.active = node


activate(tex)
bpy.context.view_layer.objects.active = low
bpy.ops.object.select_all(action='DESELECT')
low.select_set(True)
# 展 UV：各组先在正方形内各自展开，再平移缩放到贴图上的指定方块，保证像素为方形。
part = np.zeros(len(me.polygons), np.int32)
me.attributes['part'].data.foreach_get('value', part)
area = np.zeros(len(me.polygons), np.float32)
me.polygons.foreach_get('area', area)
cen = np.zeros(len(me.polygons) * 3, np.float32)
me.polygons.foreach_get('center', cen)
cen = cen.reshape(-1, 3)
quad = ((np.degrees(np.arctan2(cen[:, 0] - 0.03, -(cen[:, 1] - 0.09))) % 360) // 90).astype(int)
hair = part == 5
T = 1.0 / 3.0
groups = [(part == 1, (0.0, T, 0.5, 2 * T)), (~hair & (part != 1), (0.5, T, 0.5, 2 * T))]
groups += [(hair & (quad == k), (0.25 * k, 0.0, 0.25, T)) for k in range(4)]
print('AREA head', round(float(area[part == 1].sum()), 3), 'body', round(float(area[~hair & (part != 1)].sum()), 3),
      'hair', [round(float(area[hair & (quad == k)].sum()), 3) for k in range(4)])
me.uv_layers.new(name='UVMap')
for group, (u0, v0, us, vs) in groups:
    sel = np.zeros(len(me.polygons), bool)
    sel[group] = True
    me.polygons.foreach_set('select', sel)
    me.vertices.foreach_set('select', np.zeros(len(me.vertices), bool))
    me.edges.foreach_set('select', np.zeros(len(me.edges), bool))
    me.update()
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_mode(type='FACE')
    bpy.ops.uv.smart_project(angle_limit=math.radians(86), island_margin=0.004, area_weight=0.0,
                             correct_aspect=False, scale_to_bounds=False)
    bpy.ops.object.mode_set(mode='OBJECT')
    uv = np.empty(len(me.loops) * 2, np.float32)
    me.uv_layers[0].data.foreach_get('uv', uv)
    uv = uv.reshape(-1, 2)
    for pidx in np.nonzero(group)[0]:
        pl = me.polygons[pidx]
        sl = slice(pl.loop_start, pl.loop_start + pl.loop_total)
        uv[sl, 0] = u0 + uv[sl, 0] * us
        uv[sl, 1] = v0 + uv[sl, 1] * vs
    me.uv_layers[0].data.foreach_set('uv', uv.ravel())

sc = bpy.context.scene
sc.render.engine = 'CYCLES'
sc.cycles.device = 'CPU'
sc.cycles.samples = 4
high.hide_render = False
high.select_set(True)
low.select_set(True)
bpy.context.view_layer.objects.active = low
common = dict(use_selected_to_active=True, cage_extrusion=0.02, max_ray_distance=0.045, use_clear=True)
activate(ntex)
bpy.ops.object.bake(type='NORMAL', normal_space='TANGENT', margin=4, **common)
print('BAKED')

# ---------------------------------------------------------------- 封口面补色
HP = np.load(os.path.join(WORK, 'high_pos.npy'))
HC = np.load(os.path.join(WORK, 'high_col.npy'))
kd = kdtree.KDTree(len(HP))
for i, p in enumerate(HP):
    kd.insert(p, i)
kd.balance()
# ---------------------------------------------------------------- 基础色：最近点传递
# 低模每个贴图像素对应的表面点，取高模上离它最近的点的贴图颜色。相比射线烘焙，
# 这种做法不依赖低模法线方向，薄层（围裙、刘海）和减面产生的搭桥面不会采到背后的颜色。
from mathutils.bvhtree import BVHTree

hme = high.data
hv = C.np_verts(hme)
hloops = np.empty(len(hme.loops), np.int32)
hme.loops.foreach_get('vertex_index', hloops)
htri = hloops.reshape(-1, 3)
huv = np.empty(len(hme.loops) * 2, np.float32)
hme.uv_layers[0].data.foreach_get('uv', huv)
huv = huv.reshape(-1, 3, 2)
himg = next(n.image for n in hme.materials[0].node_tree.nodes if n.type == 'TEX_IMAGE' and n.outputs[0].links
            and n.outputs[0].links[0].to_socket.name == 'Base Color')
hw, hh = himg.size
hpx = np.empty(hw * hh * 4, np.float32)
himg.pixels.foreach_get(hpx)
hpx = hpx.reshape(hh, hw, 4)[..., :3]
full = BVHTree.FromPolygons([tuple(v) for v in hv.tolist()], [tuple(t) for t in htri.tolist()])
# 只保留从外部看得见的高模面：围裙后面的裙子、刘海下面的额头、薄壳的内侧都被别的面挡住，
# 不参与取色，否则低模上合并成一层的表面会采到里层的颜色。
fn = np.cross(hv[htri[:, 1]] - hv[htri[:, 0]], hv[htri[:, 2]] - hv[htri[:, 0]])
fn /= np.maximum(np.linalg.norm(fn, axis=1, keepdims=True), 1e-12)
fc = hv[htri].mean(1)
tilt = math.radians(55)
visible = np.zeros(len(htri), bool)
ray = full.ray_cast
for i in range(len(htri)):
    nrm = fn[i]
    ref = np.array([0.0, 0.0, 1.0]) if abs(nrm[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
    t1 = np.cross(nrm, ref)
    t1 /= np.linalg.norm(t1)
    t2 = np.cross(nrm, t1)
    # 高模局部法线朝向不可靠，正反两侧都试
    for sgn in (1.0, -1.0):
        n2 = nrm * sgn
        org = (fc[i] + n2 * 1e-4).tolist()
        dirs = [n2] + [math.cos(tilt) * n2 + math.sin(tilt) * (math.cos(a) * t1 + math.sin(a) * t2)
                       for a in (0.0, 1.57, 3.14, 4.71)]
        for d in dirs:
            if ray(org, d.tolist())[0] is None:
                visible[i] = True
                break
        if visible[i]:
            break
vis_idx = np.nonzero(visible)[0]
print('VISIBLE high faces', len(vis_idx), 'of', len(htri))
bvh = BVHTree.FromPolygons([tuple(v) for v in hv.tolist()], [tuple(t) for t in htri[vis_idx].tolist()])

lv = C.np_verts(me)
lloops = np.empty(len(me.loops), np.int32)
me.loops.foreach_get('vertex_index', lloops)
luv = np.empty(len(me.loops) * 2, np.float32)
me.uv_layers[0].data.foreach_get('uv', luv)
luv = luv.reshape(-1, 2)
PX, PY, POS = [], [], []
for pl in me.polygons:
    ls = pl.loop_start
    t = luv[ls:ls + 3] * np.array([BASE_W, BASE_H])
    w3 = lv[lloops[ls:ls + 3]]
    x0, y0 = np.floor(t.min(0)).astype(int)
    x1, y1 = np.ceil(t.max(0)).astype(int)
    x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, BASE_W - 1), min(y1, BASE_H - 1)
    if x1 < x0 or y1 < y0:
        continue
    xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    a, b, c = t
    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(den) < 1e-9:
        continue
    l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / den
    l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / den
    l2 = 1 - l0 - l1
    tol = 0.75 / max(1.0, math.sqrt(abs(den)))
    m = (l0 >= -tol) & (l1 >= -tol) & (l2 >= -tol)
    if not m.any():
        continue
    bc = np.clip(np.stack([l0[m], l1[m], l2[m]], -1), 0, 1)
    bc /= bc.sum(-1, keepdims=True)
    PX.append(xs[m].astype(np.int32))
    PY.append(ys[m].astype(np.int32))
    POS.append(bc @ w3)
PX, PY, POS = np.concatenate(PX), np.concatenate(PY), np.concatenate(POS)
n = len(PX)
hit_i = np.zeros(n, np.int32)
hit_p = np.zeros((n, 3), np.float32)
find = bvh.find_nearest
for i, p in enumerate(POS.tolist()):
    loc, _, idx, _ = find(p)
    hit_i[i] = vis_idx[idx]
    hit_p[i] = loc
ta, tb, tc = hv[htri[hit_i, 0]], hv[htri[hit_i, 1]], hv[htri[hit_i, 2]]
v0, v1, v2 = tb - ta, tc - ta, hit_p - ta
d00, d01, d11 = (v0 * v0).sum(1), (v0 * v1).sum(1), (v1 * v1).sum(1)
d20, d21 = (v2 * v0).sum(1), (v2 * v1).sum(1)
den = np.maximum(d00 * d11 - d01 * d01, 1e-20)
bv = np.clip((d11 * d20 - d01 * d21) / den, 0, 1)
bw = np.clip((d00 * d21 - d01 * d20) / den, 0, 1)
bu = np.clip(1 - bv - bw, 0, 1)
uvh = huv[hit_i, 0] * bu[:, None] + huv[hit_i, 1] * bv[:, None] + huv[hit_i, 2] * bw[:, None]
fx = np.clip(uvh[:, 0] * hw - 0.5, 0, hw - 1.001)
fy = np.clip(uvh[:, 1] * hh - 0.5, 0, hh - 1.001)
ix, iy = fx.astype(int), fy.astype(int)
ax, ay = (fx - ix)[:, None], (fy - iy)[:, None]
col = (hpx[iy, ix] * (1 - ax) * (1 - ay) + hpx[iy, ix + 1] * ax * (1 - ay)
       + hpx[iy + 1, ix] * (1 - ax) * ay + hpx[iy + 1, ix + 1] * ax * ay)
px = np.zeros((BASE_H, BASE_W, 4), np.float32)
px[PY, PX, :3] = col
px[PY, PX, 3] = 1.0
print('TRANSFER texels', n)
npx = np.empty(NRM_W * NRM_H * 4, np.float32)
nimg.pixels.foreach_get(npx)
npx = npx.reshape(NRM_H, NRM_W, 4)
miss = float((px[..., 3] < 0.5).mean())
capf = np.zeros(len(me.polygons), np.int32)
me.attributes['cap'].data.foreach_get('value', capf)
uvs = np.empty(len(me.loops) * 2, np.float32)
me.uv_layers[0].data.foreach_get('uv', uvs)
uvs = uvs.reshape(-1, 2)
vcol_cache = {}


def vcol(vi):
    if vi not in vcol_cache:
        hits = kd.find_n(me.vertices[vi].co, 12)
        vcol_cache[vi] = HC[[h[1] for h in hits]].mean(0)
    return vcol_cache[vi]


def raster(buf, w, h, tri_uv, tri_val, pad=1.5):
    """把一个三角形按重心插值写入贴图；pad 为向外扩的像素数，避免接缝露底。"""
    p = tri_uv * np.array([w, h])
    x0, y0 = np.floor(p.min(0) - pad).astype(int)
    x1, y1 = np.ceil(p.max(0) + pad).astype(int)
    x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, w - 1), min(y1, h - 1)
    if x1 < x0 or y1 < y0:
        return
    xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
    a, b, c = p
    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(den) < 1e-9:
        return
    l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / den
    l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / den
    l2 = 1 - l0 - l1
    tol = pad / max(1.0, math.sqrt(abs(den)))
    m = (l0 >= -tol) & (l1 >= -tol) & (l2 >= -tol)
    lc = np.clip(np.stack([l0, l1, l2], -1), 0, 1)
    lc /= lc.sum(-1, keepdims=True)
    val = lc @ tri_val
    sub = buf[y0:y1 + 1, x0:x1 + 1]
    sub[m, :tri_val.shape[1]] = val[m]
    if buf.shape[2] == 4:
        sub[m, 3] = 1.0


ncap = 0
for p in me.polygons:
    if not capf[p.index]:
        continue
    li = list(p.loop_indices)
    tri_uv = uvs[li]
    cols = np.array([vcol(me.loops[l].vertex_index) for l in li])
    raster(px, BASE_W, BASE_H, tri_uv, cols)
    raster(npx, NRM_W, NRM_H, tri_uv, np.tile(np.array([0.5, 0.5, 1.0]), (3, 1)))
    ncap += 1
# 仍未命中的像素（射线没打到高模）用邻近已填像素扩散
alpha = px[..., 3] >= 0.5
for _ in range(24):
    if alpha.all():
        break
    acc = np.zeros_like(px[..., :3])
    cnt = np.zeros(alpha.shape, np.float32)
    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        sh = np.roll(alpha, (dy, dx), (0, 1))
        acc += np.roll(px[..., :3], (dy, dx), (0, 1)) * sh[..., None]
        cnt += sh
    fill = (~alpha) & (cnt > 0)
    px[fill, :3] = acc[fill] / cnt[fill][:, None]
    alpha |= fill
px[..., 3] = 1.0
img.pixels.foreach_set(px.ravel())
nimg.pixels.foreach_set(npx.ravel())
for im, name in ((img, 'base.png'), (nimg, 'normal.png')):
    im.filepath_raw = os.path.join(WORK, name)
    im.file_format = 'PNG'
    im.save()
print('CAP faces recolored', ncap, '; texture coverage before fill', round((1 - miss) * 100, 1), '%')

# 低模材质接好贴图
nt.links.new(tex.outputs['Color'], bsdf.inputs['Base Color'])
nm = nt.nodes.new('ShaderNodeNormalMap')
nt.links.new(ntex.outputs['Color'], nm.inputs['Color'])
nt.links.new(nm.outputs['Normal'], bsdf.inputs['Normal'])
bsdf.inputs['Roughness'].default_value = 1.0
mat.use_backface_culling = False  # 只保留外层后网格有开放边，材质双面显示
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, 'low_baked.blend'))
print('SAVED low_baked.blend')
