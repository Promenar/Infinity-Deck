"""v2 公共工具：导入图生 3D 的 glb、无光照多视角渲染、写调试图。只用 Blender 自带 Python。"""
import math

import bpy
import numpy as np
from mathutils import Vector


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def import_glb(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=path)
    objs = [o for o in bpy.data.objects if o not in before and o.type == 'MESH']
    assert len(objs) == 1, [o.name for o in objs]
    return objs[0]


def np_verts(me):
    a = np.empty(len(me.vertices) * 3, np.float32)
    me.vertices.foreach_get('co', a)
    return a.reshape(-1, 3)


def unlit_material(obj):
    """把材质改成直接输出基础色贴图（无光照），用于检查。"""
    for mat in obj.data.materials:
        nt = mat.node_tree
        bsdf = next((n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED'), None)
        out = next(n for n in nt.nodes if n.type == 'OUTPUT_MATERIAL')
        if bsdf is None:
            continue
        em = nt.nodes.new('ShaderNodeEmission')
        base = bsdf.inputs['Base Color']
        if base.is_linked:
            nt.links.new(base.links[0].from_socket, em.inputs[0])
        nt.links.new(em.outputs[0], out.inputs['Surface'])
        mat.use_backface_culling = False


VIEWS = {'front': (0, 0), 'q': (45, 0.17), 'left': (90, 0), 'back': (180, 0), 'right': (270, 0), 'q2': (-45, 0.17),
         'bq': (135, 0.17)}


def setup_render(res=768, bg=(0.25, 0.27, 0.32, 1)):
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_EEVEE'
    sc.view_settings.view_transform = 'Standard'
    sc.world = bpy.data.worlds.new('bg')
    sc.world.use_nodes = True
    nt = sc.world.node_tree
    bgn = next((n for n in nt.nodes if n.type == 'BACKGROUND'), None)
    if bgn is None:
        bgn = nt.nodes.new('ShaderNodeBackground')
        outn = next((n for n in nt.nodes if n.type == 'OUTPUT_WORLD'), None) or nt.nodes.new('ShaderNodeOutputWorld')
        nt.links.new(bgn.outputs[0], outn.inputs[0])
    bgn.inputs[0].default_value = bg
    sc.render.resolution_x = sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.image_settings.file_format = 'PNG'
    cam = bpy.data.cameras.new('cam')
    cam.type = 'ORTHO'
    ob = bpy.data.objects.new('cam', cam)
    sc.collection.objects.link(ob)
    sc.camera = ob
    return ob


def render_views(cam, center, size, prefix, views=('front', 'left', 'back', 'right'), scale=1.2, up=None):
    sc = bpy.context.scene
    cam.data.ortho_scale = size * scale
    for v in views:
        if v == 'top':
            cam.location = center + Vector((0, 0, size * 3))
            cam.rotation_euler = (0, 0, 0)
        else:
            deg, lift = VIEWS[v]
            a = math.radians(deg)
            cam.location = center + size * Vector((math.sin(a) * 3, -math.cos(a) * 3, lift))
            cam.rotation_euler = (center - cam.location).to_track_quat('-Z', 'Y').to_euler()
        sc.render.filepath = f'{prefix}_{v}.png'
        bpy.ops.render.render(write_still=True)


def save_png(path, rgb):
    """rgb: HxWx3 的 0..1 浮点数组，行 0 在上。"""
    h, w = rgb.shape[:2]
    img = bpy.data.images.new('tmp', w, h, alpha=False)
    px = np.ones((h, w, 4), np.float32)
    px[..., :3] = rgb[::-1]
    img.pixels.foreach_set(px.ravel())
    img.filepath_raw = path
    img.file_format = 'PNG'
    img.save()
    bpy.data.images.remove(img)
