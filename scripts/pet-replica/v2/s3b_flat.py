"""阶段 3b：把烘焙出的手绘感贴图改成平涂。去掉贴图里烘焙的明暗，明暗交给运行时的卡通着色。

用法：Blender --background --python s3b_flat.py -- <工作目录>
输入：<工作目录>/low_baked.blend、texels.npz（s3_bake.py 产出）
产出：<工作目录>/base_flat.png（2048×1536）、low_flat.blend（面属性 matid：0 服装 1 皮肤 2 头发）
环境变量 TEX：painted（默认）保留手绘笔触并烘入 B 版调色；flat 输出平涂色块。

做法：每个贴图像素按所属部位和原颜色归到一个色类，输出该色类的平涂色；头发和尾巴按世界高度取渐变；
原贴图里明显更深的细线保留为线稿色；白色布料的灰色褶皱保留为一档阴影色。色值取自设定图。
"""
import os
import sys

import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import classify as K
import common as C

WORK = sys.argv[sys.argv.index('--') + 1]
bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'low_baked.blend'))
low = bpy.data.objects['Low']
me = low.data
T = np.load(os.path.join(WORK, 'texels.npz'))
PX, PY, POS, PF, col = T['PX'], T['PY'], T['POS'], T['PF'], T['col']
part_f = np.zeros(len(me.polygons), np.int32)
me.attributes['part'].data.foreach_get('value', part_f)
tp = part_f[PF]
x, y, z = POS[:, 0], POS[:, 1], POS[:, 2]
R, G, B = col[:, 0], col[:, 1], col[:, 2]
lum = 0.2126 * R + 0.7152 * G + 0.0722 * B
sat = col.max(1) - col.min(1)


def hx(h):
    return np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)], np.float32)


def grad(stops, v):
    zs = [a for a, _ in stops]
    cs = np.array([hx(c) for _, c in stops])
    return np.stack([np.interp(v, zs, cs[:, k]) for k in range(3)], -1).astype(np.float32)


# 色值取自设定图
SKIN, WHITE, WSHADE = hx('#fff0e6'), hx('#fbf8fa'), hx('#d6d2e8')
NAVY, SHOE, BOW, GOLD = hx('#2b3566'), hx('#262a4a'), hx('#5a8de6'), hx('#c8a868')
LINE_HAIR, LINE_CLOTH = hx('#27306b'), hx('#1c2146')
HAIR = [(0.10, '#9ccbf6'), (0.22, '#86b9ee'), (0.34, '#74a6e4'), (0.46, '#6690d6'), (0.60, '#5376bf'),
        (0.76, '#4a67ab'), (1.10, '#42599c')]
TAILC, EARC = hx('#46588f'), hx('#3e4b84')

white = (lum > 0.66) & (sat < 0.20)
grey = (lum > 0.48) & (sat < 0.17) & ~white
skin = (R > B + 0.10) & (R >= G) & (lum > 0.50)
HEAD, TORSO, ARM_L, ARM_R, HAIRP, TAIL = 1, 2, 3, 4, 5, 6
# 色类编号
C_NAVY, C_WHITE, C_WSHADE, C_SKIN, C_SHOE, C_HAIR, C_TAIL, C_EAR, C_BOW, C_WHALE, C_LHAIR, C_LCLOTH = range(12)
cid = np.full(len(col), C_NAVY, np.int8)

# ---- 头发部位与尾巴
m = tp == HAIRP
cid[m] = C_HAIR
cid[m & (lum < 0.20)] = C_LHAIR
m = tp == TAIL
cid[m] = C_TAIL
cid[m & grey] = C_WSHADE
cid[m & white] = C_WHITE
cid[m & (lum < 0.14)] = C_LCLOTH
# ---- 躯干、手臂
m = (tp == TORSO) | (tp == ARM_L) | (tp == ARM_R)
cid[m & (lum < 0.075) & (sat < 0.08)] = C_LCLOTH                       # 深色衣料上的线稿
cid[m & (sat > 0.28) & (B > R + 0.18) & (lum > 0.22)] = C_WHALE         # 围裙上的小鲸鱼
cid[m & grey] = C_WSHADE
cid[m & white] = C_WHITE
cid[m & skin] = C_SKIN
cid[m & (z < 0.085) & ~white & ~grey & ~skin] = C_SHOE
# 围裙上的小鲸鱼：贴图在这里的像素密度不够画清楚，原位置刷白，图案改由单独的贴花承担
wx, wz = (x - K.CFG['whale'][0]) / 0.024, (z - K.CFG['whale'][1]) / 0.024
stamp = (tp == TORSO) & (y < K.CFG['face_y'] + 0.07) & (np.abs(wx) < 1.6) & (np.abs(wz) < 1.4)
cid[stamp] = C_WHITE
# ---- 头部：皮肤、头发、头饰、蝴蝶结、鲸鳍耳
m = tp == HEAD
cid[m] = C_HAIR
cid[m & (lum < 0.20)] = C_LHAIR
ear = m & (np.abs(x) > K.CFG['ear_x'] + 0.01) & (z > K.CFG['ear_z'][0]) & (z < K.CFG['ear_z'][1] - 0.015)
cid[ear] = C_EAR
bow = m & (np.abs(x) > 0.13) & (z > 0.735) & (z < 0.87) & (sat > 0.30) & (G > R + 0.12) & (lum > 0.30) & (y < 0.12)
cid[bow] = C_BOW
cloth_zone = m & ((z > 0.79) | ear)                                     # 白色只出现在头饰和耳内侧
cid[cloth_zone & grey] = C_WSHADE
cid[cloth_zone & white] = C_WHITE
sk = m & skin
cid[sk] = C_SKIN
# 旧的五官画在皮肤上：眼、嘴所在的小范围内，贴着脸表面的像素一律刷成肤色，之后由 2D 贴花覆盖
sp = POS[sk & (z > 0.57)]
A = np.c_[2 * sp, np.ones(len(sp))]
sol = np.linalg.lstsq(A, (sp ** 2).sum(1), rcond=None)[0]
fc, fr = sol[:3], float(np.sqrt(sol[3] + (sol[:3] ** 2).sum()))
d = np.linalg.norm(POS - fc, axis=1) - fr
FX, EZ, MZ = K.CFG['face_x'], K.CFG['eye_z'], K.CFG['mouth_z']
FEAT = [(FX - K.CFG['eye_dx'], EZ, 0.058, 0.050), (FX + K.CFG['eye_dx'], EZ + 0.004, 0.058, 0.050), (FX, MZ, 0.058, 0.042)]
zone = np.zeros(len(col), bool)
for cx, cz, hw, hh in FEAT:
    zone |= (np.abs(x - cx) < hw) & (np.abs(z - cz) < hh)
face = m & zone & (y < K.CFG['face_y'] + 0.10) & (d < 0.006)
# 嘴周围到下巴没有刘海，范围放宽：旧嘴的口腔颜色偏深，容易被归到别的色类
face |= ((tp == HEAD) | (tp == HAIRP)) & (np.abs(x - FX) < 0.075) & (z > MZ - 0.035) & (z < MZ + 0.039) & (y < K.CFG['face_y'] + 0.07) & (d < 0.04)
cid[face] = C_SKIN
print('FACE sphere center', np.round(fc, 3), 'radius', round(fr, 3), 'repainted texels', int(face.sum()))

# ---- 去噪：一个面内某个色类占到七成以上时，整面统一成该色（线稿不参与、不被覆盖）
isline = (cid == C_LHAIR) | (cid == C_LCLOTH) | stamp
nf = len(me.polygons)
votes = np.zeros((nf, 12), np.int32)
np.add.at(votes, (PF[~isline], cid[~isline]), 1)
top = votes.argmax(1)
share = votes.max(1) / np.maximum(votes.sum(1), 1)
dom = (share >= 0.7)[PF] & ~isline
cid[dom] = top[PF][dom]

MODE = os.environ.get('TEX', 'painted')
SW_COL = [NAVY, WHITE, WSHADE, SKIN, LINE_CLOTH, GOLD, BOW, SHOE]
if MODE == 'flat':
    flat_col = np.array([NAVY, WHITE, WSHADE, SKIN, SHOE, SKIN, TAILC, EARC, BOW, hx('#4562b8'), LINE_HAIR, LINE_CLOTH])
    out = flat_col[cid]
    hm = cid == C_HAIR
    out[hm] = grad(HAIR, z[hm])
    lm = cid == C_LHAIR                      # 发丝线稿：取所在高度发色的深一档，不用近黑色
    out[lm] = grad(HAIR, z[lm]) * np.array([0.66, 0.68, 0.84], np.float32)
else:
    # 手绘贴图：保留烘焙出的笔触、发丝线和渐变，把 B 版的调色直接烘进贴图。
    # B 版节点：蓝色遮罩 = 映射(B - R, 0.015..0.14)；遮罩内色相 +0.016、饱和度 ×1.18、明度 ×1.10。
    # 节点在线性空间里算，这里先把贴图值转线性，算完再转回。
    lin = np.where(col <= 0.04045, col / 12.92, ((col + 0.055) / 1.055) ** 2.4)
    mask = np.clip((lin[:, 2] - lin[:, 0] - 0.015) / (0.14 - 0.015), 0, 1)
    mx, mn = lin.max(1), lin.min(1)
    dlt = mx - mn
    hch = np.zeros(len(lin), np.float32)
    nz = dlt > 1e-8
    rc = np.where(nz, (mx - lin[:, 0]) / np.maximum(dlt, 1e-8), 0)
    gc = np.where(nz, (mx - lin[:, 1]) / np.maximum(dlt, 1e-8), 0)
    bc = np.where(nz, (mx - lin[:, 2]) / np.maximum(dlt, 1e-8), 0)
    hch = np.where(lin[:, 0] == mx, bc - gc, np.where(lin[:, 1] == mx, 2 + rc - bc, 4 + gc - rc)) / 6.0 % 1.0
    sch = np.where(mx > 1e-8, dlt / np.maximum(mx, 1e-8), 0)
    h2 = (hch + 0.016) % 1.0
    s2 = np.clip(sch * 1.18, 0, 1)
    v2 = mx * 1.10
    i6 = np.floor(h2 * 6).astype(int) % 6
    f6 = h2 * 6 - np.floor(h2 * 6)
    pq, qq, tq = v2 * (1 - s2), v2 * (1 - s2 * f6), v2 * (1 - s2 * (1 - f6))
    adj = np.stack([np.choose(i6, [v2, qq, pq, pq, tq, v2]), np.choose(i6, [tq, v2, v2, qq, pq, pq]),
                    np.choose(i6, [pq, pq, tq, v2, v2, qq])], -1)
    if not K.CFG.get('grade', True):
        mask = np.zeros_like(mask)
    lin2 = lin * (1 - mask[:, None]) + adj * mask[:, None]
    lin2 = np.clip(lin2, 0, 1)
    out = np.where(lin2 <= 0.0031308, lin2 * 12.92, 1.055 * lin2 ** (1 / 2.4) - 0.055).astype(np.float32)
    # ---- 按设定图校正（检查点 4）
    def to_hsv(c):
        mx_, mn_ = c.max(1), c.min(1)
        dl = mx_ - mn_
        safe = np.maximum(dl, 1e-8)
        hh = np.where(c[:, 0] == mx_, (c[:, 1] - c[:, 2]) / safe,
                      np.where(c[:, 1] == mx_, 2 + (c[:, 2] - c[:, 0]) / safe, 4 + (c[:, 0] - c[:, 1]) / safe)) / 6.0 % 1.0
        return np.where(dl > 1e-8, hh, 0.0), np.where(mx_ > 1e-8, dl / np.maximum(mx_, 1e-8), 0.0), mx_

    def from_hsv(hh, ss, vv):
        i_ = np.floor(hh * 6).astype(int) % 6
        f_ = hh * 6 - np.floor(hh * 6)
        p_, q_, t_ = vv * (1 - ss), vv * (1 - ss * f_), vv * (1 - ss * (1 - f_))
        return np.stack([np.choose(i_, [vv, q_, p_, p_, t_, vv]), np.choose(i_, [t_, vv, vv, q_, p_, p_]),
                         np.choose(i_, [p_, p_, t_, vv, vv, q_])], -1).astype(np.float32)
    hh, ss, vv = to_hsv(out)
    hairish = (cid == C_HAIR) | (cid == C_LHAIR) | (cid == C_BOW)
    # 线稿：头发里比同高度发色暗很多的笔触提亮到发色的八成，只留下发束分界的浅线，去掉黑粗线和点状噪点
    zb = np.clip(((z - 0.05) / 0.04).astype(int), 0, 27)
    vref = np.ones(28, np.float32)
    for b in range(28):
        sel = hairish & (zb == b)
        if sel.sum() > 200:
            vref[b] = np.percentile(vv[sel], 60)
    for _ in range(2):   # 相邻高度平滑
        vref = np.convolve(np.pad(vref, 1, mode='edge'), [0.25, 0.5, 0.25], mode='valid')
    floor_v = 0.80 * vref[zb]
    vv = np.where(hairish & (vv < floor_v), floor_v, vv)
    # 色相：设定图的头发是宝蓝到浅天蓝，不含绿。偏青的像素拉回到目标色相（深处 222°，亮处 210°）
    ht = np.interp(vv, [0.35, 0.90], [222 / 360, 210 / 360])
    blueish = hairish & (hh > 150 / 360) & (hh < ht) & (ss > 0.12)
    hh = np.where(blueish, hh + (ht - hh) * 0.9, hh)
    # 尾巴：深蓝主体，保留笔触的明暗起伏；白色腹面不动
    tl = (tp == TAIL) & (cid == C_TAIL)
    if tl.sum() > 50:
        vm = np.median(vv[tl])
        th_, ts_, tv_ = to_hsv(TAILC[None, :])
        hh = np.where(tl, th_[0], hh)
        ss = np.where(tl, ts_[0], ss)
        vv = np.where(tl, np.clip(tv_[0] * (vv / max(vm, 1e-3)) ** 0.6, 0.30, 0.72), vv)
    # 白色布料去掉偏紫的色调，只留很浅的冷色
    wcl = (cid == C_WHITE) | (cid == C_WSHADE)
    ss = np.where(wcl, ss * 0.35, ss)
    out = from_hsv(hh, np.clip(ss, 0, 1), np.clip(vv, 0, 1))
    # 肤色：白里透粉。脸统一成一块均匀的肤色（腮红在眼睛贴花里），腿上的肤色向它靠拢
    SKIN_REF = hx('#ffeee8')
    body_skin = (cid == C_SKIN) & (tp != HEAD)
    out[body_skin] = out[body_skin] * 0.25 + SKIN_REF * 0.75
    # 旧五官所在的皮肤区域、鲸鱼图案所在的围裙区域，用各自周围的中位色盖掉，交给贴花
    skin_ok = (cid == C_SKIN) & (tp == HEAD) & ~face
    skin_ref = np.median(out[skin_ok], axis=0)
    # 旧五官区域按周围皮肤颜色平滑填充（按距离加权），保留脸上原有的明暗过渡，不留色差方块
    ref_i = np.nonzero(skin_ok)[0]
    ref_i = ref_i[np.linspace(0, len(ref_i) - 1, min(3000, len(ref_i))).astype(int)]
    rp, rc = POS[ref_i], out[ref_i]
    fi = np.nonzero(face)[0]
    for a0 in range(0, len(fi), 2000):
        idx = fi[a0:a0 + 2000]
        d2 = ((POS[idx][:, None, :] - rp[None, :, :]) ** 2).sum(-1)
        wgt = np.exp(-d2 / (0.022 ** 2)) + 1e-9
        out[idx] = (wgt @ rc) / wgt.sum(1, keepdims=True)
    out[((cid == C_SKIN) & (tp == HEAD)) | face] = SKIN_REF
    skin_ref = SKIN_REF
    white_ref = np.median(out[(cid == C_WHITE) & (tp == TORSO) & ~stamp], axis=0)
    out[stamp] = white_ref

    def med(mask_, fallback):
        return np.median(out[mask_], axis=0) if mask_.sum() > 50 else fallback
    body = (tp == TORSO)
    SW_COL = [med(body & (cid == C_NAVY), NAVY), white_ref, med(body & (cid == C_WSHADE), WSHADE), skin_ref,
              med(body & (cid == C_LCLOTH), LINE_CLOTH), GOLD, BOW, med(body & (cid == C_SHOE), SHOE)]
# 材质类别：0 服装 1 皮肤 2 头发
cls = np.zeros(len(col), np.int8)
cls[cid == C_SKIN] = 1
cls[(cid == C_HAIR) | (cid == C_LHAIR)] = 2

# ---- 写贴图，空白处向外扩散
img = bpy.data.images['base']
W, H = img.size
px = np.zeros((H, W, 4), np.float32)
px[PY, PX, :3] = out
px[PY, PX, 3] = 1.0
alpha = px[..., 3] >= 0.5
for _ in range(32):
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
# 色块带：贴图右下角竖排的纯色块，脚本生成的部件（袖子、袖口、手）把 UV 指到这里。
# 第 k 块中心的 UV 为 (0.985, 0.02 + 0.04 * k)，顺序见 SWATCHES。
SWATCHES = SW_COL
x0 = int(0.97 * W)
for k, cval in enumerate(SWATCHES):
    y1 = H - int(0.04 * k * H)
    y0 = H - int(0.04 * (k + 1) * H)
    px[H - y1:H - y0, x0:, :3] = cval      # 像素行 0 在贴图底部
flat = bpy.data.images.new('base_flat', W, H, alpha=False)
flat.pixels.foreach_set(px.ravel())
flat.filepath_raw = os.path.join(WORK, 'base_flat.png')
flat.file_format = 'PNG'
flat.save()
mat = me.materials[0]
nt = mat.node_tree
for n in list(nt.nodes):
    if n.type == 'TEX_IMAGE' and n.image and n.image.name == 'base':
        n.image = flat
    elif n.type in ('NORMAL_MAP',) or (n.type == 'TEX_IMAGE' and n.image and n.image.name == 'normal'):
        nt.nodes.remove(n)
# ---- 每个面的材质类别：取面内像素的多数
votes = np.zeros((nf, 3), np.int32)
np.add.at(votes, (PF, cls), 1)
matid = votes.argmax(1).astype(np.int32)
attr = me.attributes.new('matid', 'INT', 'FACE')
attr.data.foreach_set('value', matid)
np.save(os.path.join(WORK, 'face_sphere.npy'), np.r_[fc, fr])
print('MATID faces cloth', int((matid == 0).sum()), 'skin', int((matid == 1).sum()), 'hair', int((matid == 2).sum()))
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, 'low_flat.blend'))
