"""Blender 脚本建模用的小型几何工具集：部件累加器、扫掠、网格面、薄壳、荷叶边。

约定：Blender 坐标，Z 向上，角色面向 -Y，角色左手在 +X。方位角 phi 自正前方（-Y）起算，向 +X 增大。
"""
import math

import bmesh
import bpy
from mathutils import Vector


def V(x, y, z):
    return Vector((x, y, z))


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def smooth(t):
    t = clamp(t)
    return t * t * (3 - 2 * t)


def interp(tbl, x):
    """分段线性插值，tbl 为按 x 升序的 [(x, y)]。"""
    if x <= tbl[0][0]:
        return tbl[0][1]
    for (x0, y0), (x1, y1) in zip(tbl, tbl[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return tbl[-1][1]


def catmull(points, per_seg=8):
    """过全部控制点的 Catmull-Rom 样条采样。"""
    pts = [points[0]] + list(points) + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(per_seg):
            t = k / per_seg
            t2, t3 = t * t, t * t * t
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    out.append(points[-1].copy())
    return out


def path_params(pts):
    """累计弧长归一化参数。"""
    acc = [0.0]
    for a, b in zip(pts, pts[1:]):
        acc.append(acc[-1] + (b - a).length)
    total = acc[-1] or 1.0
    return [a / total for a in acc]


def polar(phi, r=1.0, z=0.0, ky=1.0):
    return V(r * math.sin(phi), -r * math.cos(phi) * ky, z)


def chain_weights(val, levels, names, parent, end=None, blend=0.3):
    """沿骨骼链分配权重。levels 为各骨骼起点的坐标值（沿链递减），val 为顶点坐标值。

    val 高于第一根骨骼起点时归 parent；每段内部归该骨骼，靠近关节处与相邻骨骼混合。
    """
    if val >= levels[0]:
        top = levels[0] + (levels[0] - levels[1]) * blend
        k = clamp((val - levels[0]) / (top - levels[0]))
        return {parent: 0.5 + 0.5 * k, names[0]: 0.5 - 0.5 * k}
    n = len(levels)
    for b in range(n):
        lo = levels[b + 1] if b + 1 < n else (end if end is not None else levels[b] - 1e3)
        if val >= lo or b == n - 1:
            s = clamp((levels[b] - val) / (levels[b] - lo)) if levels[b] != lo else 0.0
            w = {names[b]: 1.0}
            if s < blend:
                prev = names[b - 1] if b > 0 else parent
                k = 0.5 * (1 - s / blend)
                w = {names[b]: 1 - k, prev: k}
            elif s > 1 - blend and b + 1 < n:
                k = 0.5 * ((s - (1 - blend)) / blend)
                w = {names[b]: 1 - k, names[b + 1]: k}
            return w
    return {names[-1]: 1.0}


class Part:
    """一个部件：顶点、面、逐顶点 UV、逐顶点骨骼权重，可选形态键目标。"""

    def __init__(self, name, mat, obj='Body', weld=True, orient=None):
        self.name, self.mat, self.obj, self.weld, self.orient = name, mat, obj, weld, orient
        self.v, self.uv, self.w, self.f = [], [], [], []
        self.morph = {}  # 形态键名 -> {顶点序号: 目标位置}

    def vert(self, p, uv, w):
        self.v.append(Vector(p))
        self.uv.append(uv)
        self.w.append(w)
        return len(self.v) - 1

    def quad(self, a, b, c, d):
        self.f.append((a, b, c, d))

    def tri(self, a, b, c):
        self.f.append((a, b, c))

    def set_morph(self, name, vi, p):
        self.morph.setdefault(name, {})[vi] = Vector(p)


def grid_surface(part, P, uv_fn, w_fn, skip_fn=None):
    """由点阵 P[i][j] 生成单层曲面。重合的行或列（极点、接缝）交给后续焊接处理。"""
    ni, nj = len(P), len(P[0])
    idx = [[part.vert(P[i][j], uv_fn(i, j, P[i][j]), w_fn(i, j, P[i][j])) for j in range(nj)] for i in range(ni)]
    for i in range(ni - 1):
        for j in range(nj - 1):
            if skip_fn and skip_fn(i, j):
                continue
            part.quad(idx[i][j], idx[i][j + 1], idx[i + 1][j + 1], idx[i + 1][j])
    return idx


def grid_normals(P, closed_u=False):
    ni, nj = len(P), len(P[0])
    N = [[None] * nj for _ in range(ni)]
    for i in range(ni):
        for j in range(nj):
            i0, i1 = max(i - 1, 0), min(i + 1, ni - 1)
            if closed_u:
                j0, j1 = (j - 1) % (nj - 1), (j + 1) % (nj - 1)
            else:
                j0, j1 = max(j - 1, 0), min(j + 1, nj - 1)
            du = P[i][j1] - P[i][j0]
            dv = P[i1][j] - P[i0][j]
            n = du.cross(dv)
            N[i][j] = n.normalized() if n.length > 1e-12 else V(0, 0, 1)
    return N


def grid_solid(part, P, thick, uv_fn, w_fn, closed_u=False, hint_fn=None):
    """由点阵生成带厚度的闭合薄壳，使描边在薄片边缘也成立。

    uv_fn(i, j, p, layer)：layer 0 为外表面，1 为内表面。hint_fn(p) 给出大致的外法线方向。
    closed_u 为真时最后一列与第一列重合，不在该方向生成封边。
    """
    ni, nj = len(P), len(P[0])
    N = grid_normals(P, closed_u)
    if hint_fn:
        s = sum(N[i][j].dot(hint_fn(P[i][j])) for i in range(ni) for j in range(nj))
        if s < 0:
            N = [[-n for n in row] for row in N]
    top = [[part.vert(P[i][j], uv_fn(i, j, P[i][j], 0), w_fn(i, j, P[i][j])) for j in range(nj)] for i in range(ni)]
    bot = [[part.vert(P[i][j] - N[i][j] * thick, uv_fn(i, j, P[i][j], 1), w_fn(i, j, P[i][j])) for j in range(nj)]
           for i in range(ni)]
    for i in range(ni - 1):
        for j in range(nj - 1):
            part.quad(top[i][j], top[i][j + 1], top[i + 1][j + 1], top[i + 1][j])
            part.quad(bot[i][j], bot[i + 1][j], bot[i + 1][j + 1], bot[i][j + 1])
    for j in range(nj - 1):
        part.quad(top[0][j], bot[0][j], bot[0][j + 1], top[0][j + 1])
        part.quad(top[ni - 1][j], top[ni - 1][j + 1], bot[ni - 1][j + 1], bot[ni - 1][j])
    if not closed_u:
        for i in range(ni - 1):
            part.quad(top[i][0], top[i + 1][0], bot[i + 1][0], bot[i][0])
            part.quad(top[i][nj - 1], bot[i][nj - 1], bot[i + 1][nj - 1], top[i + 1][nj - 1])
    return top


def sweep(part, pts, hw, ht, uv_fn, w_fn, nseg=10, normal_fn=None, n0=None, closed=False):
    """沿路径扫掠椭圆截面。hw 为沿侧向的半宽，ht 为沿法向的半厚；端点半径为 0 时自然收成尖。

    normal_fn(i, p) 逐点给出法向参考；否则从 n0 起做平行移动。
    uv_fn(i, t, k, p, ca, sa)：t 为弧长参数，k 为截面周向比例，ca/sa 为截面角的余弦与正弦。
    """
    n = len(pts)
    ts = path_params(pts)
    T = []
    for i in range(n):
        if closed:
            a, b = pts[(i - 1) % (n - 1)], pts[(i + 1) % (n - 1)]
        else:
            a, b = pts[max(i - 1, 0)], pts[min(i + 1, n - 1)]
        d = b - a
        T.append(d.normalized() if d.length > 1e-12 else V(0, 0, 1))
    N = []
    prev = n0 or V(0, 0, 1)
    for i in range(n):
        ref = normal_fn(i, pts[i]) if normal_fn else prev
        nn = ref - T[i] * ref.dot(T[i])
        if nn.length < 1e-6:
            nn = prev - T[i] * prev.dot(T[i])
        nn.normalize()
        N.append(nn)
        prev = nn
    P = []
    meta = []
    for i in range(n):
        S = T[i].cross(N[i]).normalized()
        row, mrow = [], []
        for k in range(nseg + 1):
            a = 2 * math.pi * k / nseg
            ca, sa = math.cos(a), math.sin(a)
            row.append(pts[i] + S * (ca * hw[i]) + N[i] * (sa * ht[i]))
            mrow.append((ts[i], k / nseg, ca, sa))
        P.append(row)
        meta.append(mrow)
    return grid_surface(part, P,
                        lambda i, j, p: uv_fn(i, meta[i][j][0], meta[i][j][1], p, meta[i][j][2], meta[i][j][3]),
                        lambda i, j, p: w_fn(i, meta[i][j][0], p))


def frill_strip(part, base, out, width, amp, nwave, uv_fn, w_fn, rows=4, thick=0.005, closed=False, scallop=0.0,
                hint_fn=None, droop=None):
    """荷叶边：沿 base 路径向 out 方向伸出 width，并沿薄片法向做正弦起伏。

    closed 为真时 base 首尾重合成环。droop 为可选的附加位移向量（例如向下垂）。
    """
    n = len(base)
    P = [[None] * n for _ in range(rows)]
    for j in range(n):
        if closed:
            a, b = base[(j - 1) % (n - 1)], base[(j + 1) % (n - 1)]
        else:
            a, b = base[max(j - 1, 0)], base[min(j + 1, n - 1)]
        tan = (b - a).normalized()
        o = out[j].normalized()
        nrm = tan.cross(o)
        nrm = nrm.normalized() if nrm.length > 1e-9 else V(0, 0, 1)
        ph = 2 * math.pi * nwave * j / (n - 1)
        for i in range(rows):
            k = i / (rows - 1)
            w = width * (1 + scallop * math.cos(2 * ph))
            p = base[j] + o * (k * w) + nrm * (amp * k * math.sin(ph))
            if droop is not None:
                p = p + droop * (k * k)
            P[i][j] = p
    return grid_solid(part, P, thick, uv_fn, w_fn, closed_u=closed, hint_fn=hint_fn)


def ellipsoid(part, center, radii, uv_fn, w_fn, nu=16, nv=10, deform=None, axes=None):
    """椭球；deform(local) 可在单位球坐标上做附加变形；axes 为三个正交轴向量。"""
    ax = axes or (V(1, 0, 0), V(0, 1, 0), V(0, 0, 1))
    P = []
    for i in range(nv + 1):
        th = math.pi * i / nv
        row = []
        for j in range(nu + 1):
            ph = 2 * math.pi * j / nu
            l = V(math.sin(th) * math.cos(ph), math.sin(th) * math.sin(ph), math.cos(th))
            if deform:
                l = deform(l)
            row.append(center + ax[0] * (l.x * radii[0]) + ax[1] * (l.y * radii[1]) + ax[2] * (l.z * radii[2]))
        P.append(row)
    return grid_surface(part, P, lambda i, j, p: uv_fn(i, j, p), lambda i, j, p: w_fn(i, j, p))


# ---------------------------------------------------------------- 写入 Blender
def part_to_object(part, bone_index, mat):
    """把部件写成 Blender 网格对象：焊接重合顶点、统一法线朝外、平滑着色、顶点组。"""
    bm = bmesh.new()
    uvl = bm.loops.layers.uv.new('UVMap')
    dl = bm.verts.layers.deform.verify()
    bvs = []
    for p, w in zip(part.v, part.w):
        bv = bm.verts.new(p)
        tot = sum(w.values()) or 1.0
        for name, val in w.items():
            if val > 1e-4:
                bv[dl][bone_index[name]] = val / tot
        bvs.append(bv)
    bm.verts.ensure_lookup_table()
    for f in part.f:
        vs = [bvs[i] for i in f]
        if len(set(vs)) < 3:
            continue
        try:
            bf = bm.faces.new(vs)
        except ValueError:
            continue
        for lp, vi in zip(bf.loops, f):
            lp[uvl].uv = part.uv[vi]
    if part.weld:
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=2e-5)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.normal_update()
    if part.orient is not None:
        s = sum(f.normal.dot(part.orient) * f.calc_area() for f in bm.faces)
        if s < 0:
            bmesh.ops.reverse_faces(bm, faces=bm.faces)
    me = bpy.data.meshes.new(part.name)
    bm.to_mesh(me)
    bm.free()
    for poly in me.polygons:
        poly.use_smooth = True
    me.materials.append(mat)
    ob = bpy.data.objects.new(part.name, me)
    bpy.context.scene.collection.objects.link(ob)
    for name in sorted(bone_index, key=bone_index.get):
        ob.vertex_groups.new(name=name)
    if part.morph:
        ob.shape_key_add(name='Basis', from_mix=False)
        for mname, tg in part.morph.items():
            sk = ob.shape_key_add(name=mname, from_mix=False)
            for vi, p in tg.items():
                sk.data[vi].co = p
    return ob
