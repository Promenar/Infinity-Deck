"""阶段 1：导入底模、焊接接缝重复点、归一化尺寸与原点、采样逐顶点颜色，保存高模工程。

用法：Blender --background --python s1_prepare.py -- <输入.glb> <工作目录>
产出：<工作目录>/high.blend（对象 High，带原始 UV、材质与顶点色属性 col）
归一化：整体缩放到总高度 TARGET_TOTAL（默认 1.0465 米，含呆毛），脚底落在 z=0，原点取两脚中心；角色面向 -Y。
"""
import os
import sys

import bmesh
import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

src, work = sys.argv[sys.argv.index('--') + 1:][:2]
TARGET_TOTAL = float(os.environ.get('TARGET_TOTAL', '1.0465'))  # 归一化后的总高度（含呆毛），米
C.reset()
ob = C.import_glb(src)
ob.name = 'High'
me = ob.data
bm = bmesh.new()
bm.from_mesh(me)
bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
bm.to_mesh(me)
bm.free()
v = C.np_verts(me)
zmin = v[:, 2].min()
feet = v[v[:, 2] < zmin + 0.03]
cx, cy = feet[:, 0].mean(), feet[:, 1].mean()
SCALE = TARGET_TOTAL / float(v[:, 2].max() - zmin)   # 换底模后各部位高度仍落在同一套经验数值附近
v = (v - np.array([cx, cy, zmin], np.float32)) * SCALE
me.vertices.foreach_set('co', v.ravel())
me.update()
print('ORIGIN shift', cx, cy, zmin, 'new bbox', v.min(0), v.max(0))
import json
with open(os.path.join(work, 'normalize.json'), 'w') as f:
    json.dump({'scale': SCALE, 'shift': [float(cx), float(cy), float(zmin)]}, f)

# 逐顶点颜色：取任一面角的 UV 采样基础色贴图
img = next(n.image for n in me.materials[0].node_tree.nodes if n.type == 'TEX_IMAGE' and n.outputs[0].links
           and n.outputs[0].links[0].to_socket.name == 'Base Color')
w, h = img.size
px = np.empty(w * h * 4, np.float32)
img.pixels.foreach_get(px)
px = px.reshape(h, w, 4)
nl = len(me.loops)
lv = np.empty(nl, np.int32)
me.loops.foreach_get('vertex_index', lv)
uv = np.empty(nl * 2, np.float32)
me.uv_layers[0].data.foreach_get('uv', uv)
uv = uv.reshape(-1, 2)
xi = np.clip((uv[:, 0] * w).astype(int), 0, w - 1)
yi = np.clip((uv[:, 1] * h).astype(int), 0, h - 1)
lc = px[yi, xi, :3]
vc = np.zeros((len(me.vertices), 3), np.float32)
vc[lv] = lc
attr = me.color_attributes.new('col', 'FLOAT_COLOR', 'POINT')
buf = np.ones((len(me.vertices), 4), np.float32)
buf[:, :3] = vc
attr.data.foreach_set('color', buf.ravel())
np.save(os.path.join(work, 'high_pos.npy'), v)
np.save(os.path.join(work, 'high_col.npy'), vc)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(work, 'high.blend'))
print('HIGH verts', len(me.vertices), 'faces', len(me.polygons))
