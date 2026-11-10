#!/usr/bin/env python3
"""把 Blender 导出的 glb 加工成 VRM 1.0：写入 VRMC_vrm、VRMC_springBone、VRMC_materials_mtoon 扩展。

用法：python glb_to_vrm.py <输入.glb> <rig.json> <输出.vrm>
依赖：numpy。不依赖 Blender 的 VRM 插件。
"""
import json
import struct
import sys

import numpy as np

SRC, RIG, DST = sys.argv[1:4]


def read_glb(path):
    with open(path, 'rb') as f:
        data = f.read()
    magic, version, _ = struct.unpack_from('<4sII', data, 0)
    assert magic == b'glTF' and version == 2
    off = 12
    chunks = {}
    while off < len(data):
        n, kind = struct.unpack_from('<I4s', data, off)
        chunks[kind] = data[off + 8:off + 8 + n]
        off += 8 + n
    return json.loads(chunks[b'JSON'].decode('utf-8')), chunks.get(b'BIN\x00', b'')


def write_glb(path, gltf, binary):
    js = json.dumps(gltf, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    js += b' ' * (-len(js) % 4)
    binary += b'\x00' * (-len(binary) % 4)
    total = 12 + 8 + len(js) + 8 + len(binary)
    with open(path, 'wb') as f:
        f.write(struct.pack('<4sII', b'glTF', 2, total))
        f.write(struct.pack('<I4s', len(js), b'JSON'))
        f.write(js)
        f.write(struct.pack('<I4s', len(binary), b'BIN\x00'))
        f.write(binary)


def local_matrix(node):
    if 'matrix' in node:
        return np.array(node['matrix'], float).reshape(4, 4).T
    t = np.array(node.get('translation', [0, 0, 0]), float)
    x, y, z, w = node.get('rotation', [0, 0, 0, 1])
    s = np.array(node.get('scale', [1, 1, 1]), float)
    r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = r * s
    m[:3, 3] = t
    return m


def srgb_to_linear(c):
    return [((v + 0.055) / 1.055) ** 2.4 if v > 0.04045 else v / 12.92 for v in c]


def main():
    gltf, binary = read_glb(SRC)
    rig = json.load(open(RIG))
    nodes = gltf['nodes']
    name2node = {n.get('name'): i for i, n in enumerate(nodes)}
    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []):
            parent[c] = i
    world = {}

    def wm(i):
        if i not in world:
            m = local_matrix(nodes[i])
            world[i] = wm(parent[i]) @ m if i in parent else m
        return world[i]

    def to_local(node_idx, pos_blender):
        x, y, z = pos_blender
        p = np.array([x, z, -y, 1.0])  # Blender (x, y, z) → glTF (x, z, -y)
        return [round(float(v), 6) for v in (np.linalg.inv(wm(node_idx)) @ p)[:3]]

    # ---------------- 材质：MToon
    mat_index = {m['name']: i for i, m in enumerate(gltf['materials'])}
    for name, prm in rig['materials'].items():
        if name not in mat_index:
            continue
        m = gltf['materials'][mat_index[name]]
        pbr = m.setdefault('pbrMetallicRoughness', {})
        pbr['metallicFactor'] = 0.0
        pbr['roughnessFactor'] = 1.0
        tex = pbr.get('baseColorTexture', {}).get('index')
        mtoon = {
            'specVersion': '1.0',
            'transparentWithZWrite': False,
            'renderQueueOffsetNumber': 0,
            'shadeColorFactor': list(prm['shade']),
            'shadingShiftFactor': prm.get('shift', -0.1),
            'shadingToonyFactor': prm.get('toony', 0.9),
            'giEqualizationFactor': 0.9,
            'matcapFactor': [0, 0, 0],
            'parametricRimColorFactor': [0, 0, 0],
            'rimLightingMixFactor': 1.0,
            'outlineWidthMode': 'worldCoordinates' if prm.get('outline', 0) > 0 else 'none',
            'outlineWidthFactor': prm.get('outline', 0.0),
            'outlineColorFactor': srgb_to_linear(prm.get('outline_color', (0, 0, 0))),
            'outlineLightingMixFactor': 0.0,
        }
        if tex is not None:
            mtoon['shadeMultiplyTexture'] = {'index': tex}
        m.setdefault('extensions', {})['VRMC_materials_mtoon'] = mtoon

    # ---------------- 表情（rig.json 未给出面部网格时不写表情）
    preset = {}
    if rig.get('face_mesh'):
        # 眼睛贴图：表情会平移、缩放采样坐标，采样器改为边缘钳制
        eye_mat = mat_index[rig['eye_material']]
        eye_tex = gltf['materials'][eye_mat]['pbrMetallicRoughness']['baseColorTexture']['index']
        gltf.setdefault('samplers', []).append({'magFilter': 9729, 'minFilter': 9987, 'wrapS': 33071, 'wrapT': 33071})
        gltf['textures'][eye_tex]['sampler'] = len(gltf['samplers']) - 1
        face_node = name2node[rig['face_mesh']]
        targets = gltf['meshes'][nodes[face_node]['mesh']]['extras']['targetNames']
        for name, e in rig['expressions'].items():
            ex = {'isBinary': False, 'overrideBlink': e.get('overrideBlink', 'none'),
                  'overrideLookAt': e.get('overrideLookAt', 'none'), 'overrideMouth': e.get('overrideMouth', 'none')}
            binds = [{'node': face_node, 'index': targets.index(mn), 'weight': float(w)}
                     for mn, w in e.get('morphs', [])]
            if binds:
                ex['morphTargetBinds'] = binds
            tt = []
            if 'iris_scale' in e:  # 以贴图中心为基准缩放：虹膜看起来变小
                sc = e['iris_scale']
                tt.append({'material': eye_mat, 'scale': [sc, sc], 'offset': [(1 - sc) / 2, (1 - sc) / 2]})
            if 'iris_offset' in e:  # (向右, 向下) 的画面位移，换成采样坐标的反向偏移
                dx, dy = e['iris_offset']
                tt.append({'material': eye_mat, 'scale': [1, 1], 'offset': [-dx, -dy]})
            if tt:
                ex['textureTransformBinds'] = tt
            preset[name] = ex

    head = name2node['head']
    vrm = {
        'specVersion': '1.0',
        'meta': {
            'name': 'Whale Maid Replica (dev test)',
            'version': '0.1',
            'authors': ['Infinity Deck pet prototype'],
            'copyrightInformation': '开发测试形象，按设定图复刻，不随应用分发',
            **rig.get('meta', {}),
            'licenseUrl': 'https://vrm.dev/licenses/1.0/',
            'avatarPermission': 'onlyAuthor',
            'allowExcessivelyViolentUsage': False,
            'allowExcessivelySexualUsage': False,
            'commercialUsage': 'personalNonProfit',
            'allowPoliticalOrReligiousUsage': False,
            'allowAntisocialOrHateUsage': False,
            'creditNotation': 'required',
            'allowRedistribution': False,
            'modification': 'prohibited',
        },
        'humanoid': {'humanBones': {k: {'node': name2node[v]} for k, v in rig['humanoid'].items()}},
        'lookAt': {
            'offsetFromHeadBone': to_local(head, rig['look_at_pos']),
            'type': 'expression',
            'rangeMapHorizontalInner': {'inputMaxValue': 60, 'outputScale': 1},
            'rangeMapHorizontalOuter': {'inputMaxValue': 60, 'outputScale': 1},
            'rangeMapVerticalDown': {'inputMaxValue': 45, 'outputScale': 1},
            'rangeMapVerticalUp': {'inputMaxValue': 45, 'outputScale': 1},
        },
        'expressions': {'preset': preset, 'custom': {}},
    }

    # ---------------- 弹簧骨骼
    colliders, groups, gidx = [], [], {}
    for c in rig['colliders']:
        ni = name2node[c['node']]
        if 'tail' in c:
            shape = {'capsule': {'offset': to_local(ni, c['pos']), 'radius': c['radius'], 'tail': to_local(ni, c['tail'])}}
        else:
            shape = {'sphere': {'offset': to_local(ni, c['pos']), 'radius': c['radius']}}
        colliders.append({'node': ni, 'shape': shape})
        g = c['group']
        if g not in gidx:
            gidx[g] = len(groups)
            groups.append({'name': g, 'colliders': []})
        groups[gidx[g]]['colliders'].append(len(colliders) - 1)
    springs = []
    for s in rig['springs']:
        joints = []
        n = len(s['joints'])
        for k, jn in enumerate(s['joints']):
            st = s['stiffness'][min(k, len(s['stiffness']) - 1)]
            joints.append({'node': name2node[jn], 'hitRadius': s['radius'], 'stiffness': st,
                           'gravityPower': s['gravity'], 'gravityDir': [0, -1, 0], 'dragForce': s['drag']})
        sp = {'name': s['name'], 'joints': joints}
        cg = [gidx[g] for g in s.get('groups', []) if g in gidx]
        if cg:
            sp['colliderGroups'] = cg
        springs.append(sp)
    spring_ext = {'specVersion': '1.0', 'colliders': colliders, 'colliderGroups': groups, 'springs': springs}

    gltf.setdefault('extensions', {})['VRMC_vrm'] = vrm
    gltf['extensions']['VRMC_springBone'] = spring_ext
    used = set(gltf.get('extensionsUsed', []))
    used.update(['VRMC_vrm', 'VRMC_springBone', 'VRMC_materials_mtoon'])
    gltf['extensionsUsed'] = sorted(used)
    write_glb(DST, gltf, binary)
    tris = 0
    for mesh in gltf['meshes']:
        for prim in mesh['primitives']:
            tris += gltf['accessors'][prim['indices']]['count'] // 3
    print(f'VRM -> {DST}  nodes={len(nodes)} materials={len(gltf["materials"])} tris={tris} '
          f'springs={len(springs)} joints={sum(len(s["joints"]) for s in springs)} expressions={len(preset)} '
          f'bytes={12 + 16 + len(binary)}+json')


if __name__ == '__main__':
    main()
