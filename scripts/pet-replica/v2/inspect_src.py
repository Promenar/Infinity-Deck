"""检查底模：统计、朝向、连通分量、非流形边，并输出无光照多视角图。
用法：Blender --background --python inspect_src.py -- <输入.glb> <输出前缀>"""
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

src, prefix = sys.argv[sys.argv.index('--') + 1:][:2]
C.reset()
ob = C.import_glb(src)
me = ob.data
v = C.np_verts(me)
print('OBJ', ob.name, 'matrix', [list(r) for r in ob.matrix_world], 'parent', ob.parent)
print('VERTS', len(me.vertices), 'POLYS', len(me.polygons), 'LOOPS', len(me.loops))
print('BBOX min', v.min(0), 'max', v.max(0), 'size', v.max(0) - v.min(0))
print('UV layers', [u.name for u in me.uv_layers], 'attrs', [a.name for a in me.attributes])
for m in me.materials:
    print('MAT', m.name, 'culling', m.use_backface_culling)
    for n in m.node_tree.nodes:
        if n.type == 'TEX_IMAGE':
            print('  IMG', n.image.name, tuple(n.image.size), n.image.colorspace_settings.name,
                  [l.to_socket.name for o in n.outputs for l in o.links])
bm = bmesh.new()
bm.from_mesh(me)
nm = sum(1 for e in bm.edges if not e.is_manifold)
bd = sum(1 for e in bm.edges if e.is_boundary)
# 连通分量
seen = set()
comps = []
for vv in bm.verts:
    if vv.index in seen:
        continue
    stack = [vv]
    seen.add(vv.index)
    n = 0
    while stack:
        x = stack.pop()
        n += 1
        for e in x.link_edges:
            o = e.other_vert(x)
            if o.index not in seen:
                seen.add(o.index)
                stack.append(o)
    comps.append(n)
comps.sort(reverse=True)
print('EDGES', len(bm.edges), 'non-manifold', nm, 'boundary', bd, 'components', len(comps), comps[:12])
bm.free()
C.unlit_material(ob)
cam = C.setup_render(900)
center = Vector(((v.min(0) + v.max(0)) / 2).tolist())
size = float((v.max(0) - v.min(0)).max())
C.render_views(cam, center, size, prefix, views=('front', 'left', 'back', 'right', 'top', 'q'), scale=1.08)
