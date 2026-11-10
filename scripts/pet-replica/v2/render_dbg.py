"""调试渲染：按顶点色属性或贴图无光照渲染某个对象的多视角图。
用法：Blender --background --python render_dbg.py -- <工程.blend> <对象名> <模式 attr:名称|tex|shade> <输出前缀> <cx> <cy> <cz> <size> <视图...>"""
import os
import sys

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

a = sys.argv[sys.argv.index('--') + 1:]
bpy.ops.wm.open_mainfile(filepath=a[0])
ob = bpy.data.objects[a[1]]
for o in bpy.data.objects:
    if o.type == 'MESH' and o is not ob:
        o.hide_render = True
mode = a[2]
if mode.startswith('attr:') or mode == 'shade':
    mat = bpy.data.materials.new('dbg')
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new('ShaderNodeOutputMaterial')
    if mode == 'shade':
        d = nt.nodes.new('ShaderNodeBsdfDiffuse')
        d.inputs[0].default_value = (0.8, 0.8, 0.82, 1)
        nt.links.new(d.outputs[0], out.inputs[0])
        sun = bpy.data.lights.new('sun', 'SUN')
        sun.energy = 3
        so = bpy.data.objects.new('sun', sun)
        bpy.context.scene.collection.objects.link(so)
        so.rotation_euler = Vector((0.4, 0.6, -1)).to_track_quat('-Z', 'Y').to_euler()
        for p in ob.data.polygons:
            p.use_smooth = False
    else:
        em = nt.nodes.new('ShaderNodeEmission')
        at = nt.nodes.new('ShaderNodeAttribute')
        at.attribute_name = mode[5:]
        nt.links.new(at.outputs['Color'], em.inputs[0])
        nt.links.new(em.outputs[0], out.inputs[0])
    mat.use_backface_culling = False
    ob.data.materials.clear()
    ob.data.materials.append(mat)
else:
    C.unlit_material(ob)
cam = C.setup_render(int(os.environ.get('RES', '700')))
C.VIEWS.update({'b150': (150, 0.1), 'b210': (210, 0.1), 'l120': (120, 0.1), 'under': (180, -0.9), 'fl': (60, 0.1),
                'r300': (300, 0.05), 'fr': (-45, 0.1), 'top45': (0, 1.0)})
cx, cy, cz, size = [float(x) for x in a[4:8]]
C.render_views(cam, Vector((cx, cy, cz)), size, a[3], views=a[8:], scale=1.0)
