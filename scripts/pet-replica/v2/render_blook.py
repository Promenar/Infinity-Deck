"""用 B 版工程的材质、灯光与机位，分别渲染原始高模和低模，得到同条件的四视图对比素材。

用法：Blender --background --python render_blook.py -- <B.blend 副本> <工作目录> <输出目录>
B.blend 只读取、不保存。低模取自 <工作目录>/low_baked.blend，先按 normalize.json 还原到高模原始坐标。
"""
import json
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

bblend, work, out = sys.argv[sys.argv.index('--') + 1:][:3]
bpy.ops.wm.open_mainfile(filepath=bblend)
sc = bpy.context.scene
src = next(o for o in sc.objects if o.type == 'MESH')
cam = sc.camera
pts = [src.matrix_world @ Vector(c) for c in src.bound_box]
mins = Vector([min(p[i] for p in pts) for i in range(3)])
maxs = Vector([max(p[i] for p in pts) for i in range(3)])
center, size = (mins + maxs) / 2, max(maxs - mins)
VIEWS = {'front': 0, 'left': 90, 'back': 180, 'q45': 45}


def render(tag):
    for name, deg in VIEWS.items():
        a = math.radians(deg)
        cam.location = center + size * Vector((math.sin(a) * 3, -math.cos(a) * 3, 0.17))
        cam.rotation_euler = (center - cam.location).to_track_quat('-Z', 'Y').to_euler()
        sc.render.filepath = os.path.join(out, f'{tag}_{name}.png')
        bpy.ops.render.render(write_still=True)


render('bhigh')
with bpy.data.libraries.load(os.path.join(work, 'low_baked.blend')) as (data_from, data_to):
    data_to.objects = ['Low']
low = data_to.objects[0]
sc.collection.objects.link(low)
nz = json.load(open(os.path.join(work, 'normalize.json')))
s = 1.0 / nz['scale']
low.matrix_world = Matrix.Translation(Vector(nz['shift'])) @ Matrix.Scale(s, 4)
mat = src.data.materials[0].copy()
nt = mat.node_tree
base_img = bpy.data.images.load(os.path.join(work, 'base.png'))
nrm_img = bpy.data.images.load(os.path.join(work, 'normal.png'))
nrm_img.colorspace_settings.name = 'Non-Color'
orig_base = next(n for n in nt.nodes if n.type == 'TEX_IMAGE' and n.image and n.image.colorspace_settings.name == 'sRGB')
orig_base.image = base_img
# 其余贴图节点（金属度粗糙度、原法线）与新 UV 不对应，断开；法线改接烘焙出的法线贴图
diffuse = next(n for n in nt.nodes if n.type == 'BSDF_DIFFUSE')
ntex = nt.nodes.new('ShaderNodeTexImage')
ntex.image = nrm_img
nmap = nt.nodes.new('ShaderNodeNormalMap')
nt.links.new(ntex.outputs['Color'], nmap.inputs['Color'])
nt.links.new(nmap.outputs['Normal'], diffuse.inputs['Normal'])
low.data.materials.clear()
low.data.materials.append(mat)
src.hide_render = True
render('blow')
print('RENDERED', out)
