// 开场：灯珠字标与莫比乌斯环。投影、灯珠亮度与时间轴移植自 App 的 MobiusProjector、beadLevel 与 LED_MORPH；
// 网页版加密了灯珠，另加沿带面流动的星尘与十字星芒。
import { reduceMotion, TAU, DPR, clamp01, hash, easeInOutCubic } from './motion';

const stage = document.getElementById('stage');
const canvas = document.getElementById('ring') as HTMLCanvasElement | null;

if (stage && canvas) {
  const ctx = canvas.getContext('2d')!;
  const LANES = 11;
  const STAGGER = 0.55;
  const DURATION = { GATHER: 1.8, WORD: 3.2, MORPH: 1.8, RING: 5.5 } as const;
  type Phase = keyof typeof DURATION;
  const ORDER: Phase[] = ['GATHER', 'WORD', 'MORPH', 'RING'];

  type Bead = { u: number; v: number; id: number; wx: number; wy: number; rank: number; sx: number; sy: number; ang: number; lift: number };
  type Dust = { u: number; v: number; sp: number; sz: number; ph: number; rt: number; spread: number };
  type Spark = { k: number; t0: number; life: number; size: number };
  type Sprite = { glow: HTMLCanvasElement; core: string };

  let W = 0, H = 0, R = 0, N = 0;
  let beads: Bead[] = [], dust: Dust[] = [], sparks: Spark[] = [];
  let bx = new Float32Array(0), by = new Float32Array(0), blv = new Float32Array(0);
  let sprites: Sprite[] = [];
  let flare: HTMLCanvasElement;

  // ---------- 投影（同 App MobiusProjector） ----------
  const view = { a: 1, b: 0.92, c: 0.16, hw: 0.2, cyaw: 1, syaw: 0, cp: 1, sp: 0, dist: 5, dr: 0.6, cx: 0, cy: 0, burst: 0 };
  const pt = [0, 0, 0];
  function configure(radius: number, cx: number, cy: number, yaw: number, pitch: number, burst: number) {
    view.a = radius; view.b = radius * 0.92; view.c = radius * 0.16; view.hw = radius * 0.2;
    view.cyaw = Math.cos(yaw); view.syaw = Math.sin(yaw); view.cp = Math.cos(pitch); view.sp = Math.sin(pitch);
    view.dist = radius * 5; view.dr = radius * 0.6; view.cx = cx; view.cy = cy; view.burst = burst;
  }
  function project(u: number, v: number, spread: number) {
    const su = Math.sin(u), cu = Math.cos(u);
    const tx = view.a * cu, ty = view.b * (cu * cu - su * su);
    const tl = Math.hypot(tx, ty) || 1;
    const sa = Math.sin(u / 2), ca = Math.cos(u / 2);
    const px = view.a * su - (v * sa * ty) / tl;
    const py = view.b * su * cu + (v * sa * tx) / tl;
    const pz = view.c * cu + v * ca;
    const x1 = px * view.cyaw - py * view.syaw;
    const y1 = px * view.syaw + py * view.cyaw;
    const up = pz * view.cp + y1 * view.sp;
    const depth = pz * view.sp - y1 * view.cp;
    const f = view.dist / (view.dist - depth);
    const boost = 1 + view.burst * spread;
    pt[0] = view.cx + x1 * f * boost;
    pt[1] = view.cy - up * f * boost;
    pt[2] = clamp01((depth / view.dr + 1) / 2);
  }
  /** 同 App beadLevel：常暗打底，数据光带经过时高频闪，零星随机亮起。 */
  function beadLevel(id: number, t: number, band: number, base: number) {
    let level = base;
    if (band > 0.05 && hash(id, Math.floor(t * (24 + hash(id, 2) * 26))) > 0.18) level = Math.max(level, base + (1 - base) * band);
    if (hash(id, Math.floor(t * 3 + hash(id, 9) * 3)) < 0.012) level = Math.max(level, 0.75);
    return level;
  }

  // ---------- 贴图 ----------
  const mix = (a: number[], b: number[], t: number) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
  function glowSprite(rgb: string) {
    const s = 64, c = document.createElement('canvas');
    c.width = c.height = s;
    const g = c.getContext('2d')!;
    const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
    grad.addColorStop(0, `rgba(${rgb},.85)`); grad.addColorStop(0.18, `rgba(${rgb},.42)`);
    grad.addColorStop(0.5, `rgba(${rgb},.1)`); grad.addColorStop(1, `rgba(${rgb},0)`);
    g.fillStyle = grad; g.fillRect(0, 0, s, s);
    return c;
  }
  function buildSprites() {
    const blue = [120, 172, 255], ice = [148, 205, 247], violet = [190, 168, 255], white = [240, 248, 255];
    sprites = [];
    for (let i = 0; i < 6; i++) {
      const t = i / 5;
      const c = t < 0.5 ? mix(blue, ice, t * 2) : mix(ice, violet, (t - 0.5) * 2);
      sprites.push({ glow: glowSprite(c.join(',')), core: `rgb(${mix(c, white, 0.55).join(',')})` });
    }
    sprites.push({ glow: glowSprite('255,181,71'), core: 'rgb(255,214,150)' });
    const fs = 128, fc = document.createElement('canvas');
    fc.width = fc.height = fs;
    const g = fc.getContext('2d')!;
    const halo = g.createRadialGradient(fs / 2, fs / 2, 0, fs / 2, fs / 2, fs * 0.22);
    halo.addColorStop(0, 'rgba(255,255,255,1)'); halo.addColorStop(0.25, 'rgba(206,232,255,.55)'); halo.addColorStop(1, 'rgba(148,205,247,0)');
    g.fillStyle = halo; g.fillRect(0, 0, fs, fs);
    for (const [w, h, diag] of [[fs, 2, false], [2, fs, false], [fs * 0.5, 1.2, true], [1.2, fs * 0.5, true]] as const) {
      const lin = w > h ? g.createLinearGradient(-w / 2, 0, w / 2, 0) : g.createLinearGradient(0, -h / 2, 0, h / 2);
      lin.addColorStop(0, 'rgba(206,232,255,0)'); lin.addColorStop(0.5, 'rgba(240,248,255,.95)'); lin.addColorStop(1, 'rgba(206,232,255,0)');
      g.save(); g.translate(fs / 2, fs / 2); if (diag) g.rotate(Math.PI / 4);
      g.fillStyle = lin; g.fillRect(-w / 2, -h / 2, w, h); g.restore();
    }
    flare = fc;
  }

  // ---------- 字标取点（同 App sampleWordmarkStardust 的贪心间距取点） ----------
  function sampleWordmark(count: number): [number, number][] {
    const c = document.createElement('canvas');
    c.width = Math.round(W); c.height = Math.round(H);
    const g = c.getContext('2d', { willReadFrequently: true })!;
    const family = getComputedStyle(document.body).fontFamily;
    let size = R * 0.62;
    g.font = `700 ${size}px ${family}`;
    const tw = g.measureText('Infinity').width;
    size *= Math.min(1, (W * 0.86) / tw, (R * 2.1) / tw);
    g.font = `700 ${size}px ${family}`;
    g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillStyle = '#fff';
    g.fillText('Infinity', W / 2, H / 2);
    const data = g.getImageData(0, 0, c.width, c.height).data;
    const step = Math.max(1, R / 260);
    const cand: [number, number][] = [];
    for (let y = 0; y < c.height; y += step) for (let x = 0; x < c.width; x += step) {
      const jx = x + Math.random() * step, jy = y + Math.random() * step;
      const ix = Math.min(c.width - 1, jx | 0), iy = Math.min(c.height - 1, jy | 0);
      if (data[(iy * c.width + ix) * 4 + 3] > 110) cand.push([jx, jy]);
    }
    for (let i = cand.length - 1; i > 0; i--) { const j = (Math.random() * (i + 1)) | 0; [cand[i], cand[j]] = [cand[j], cand[i]]; }
    const spacing = Math.sqrt((cand.length * step * step) / count) * 0.82, lim = spacing * spacing;
    const cell = spacing / Math.SQRT2, gw = Math.ceil(W / cell) + 1;
    const grid = new Map<number, [number, number]>();
    const out: [number, number][] = [];
    for (const p of cand) {
      if (out.length >= count) break;
      const gx = (p[0] / cell) | 0, gy = (p[1] / cell) | 0;
      let ok = true;
      for (let dy = -2; dy <= 2 && ok; dy++) for (let dx = -2; dx <= 2; dx++) {
        const o = grid.get((gy + dy) * gw + gx + dx);
        if (o && (o[0] - p[0]) ** 2 + (o[1] - p[1]) ** 2 < lim) { ok = false; break; }
      }
      if (ok) { out.push(p); grid.set(gy * gw + gx, p); }
    }
    for (let k = 0; out.length < count && cand.length; k++) out.push(cand[k % cand.length]);
    return out;
  }

  function build() {
    const rect = stage!.getBoundingClientRect();
    W = rect.width; H = rect.height;
    if (W < 10 || H < 10) { N = 0; return; }
    canvas!.width = W * DPR; canvas!.height = H * DPR;
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    R = Math.min(W * 0.4, H * 0.55) * 0.92;
    const cols = W < 600 ? 170 : W < 1000 ? 250 : 340;
    N = cols * LANES;
    const word = sampleWordmark(N);
    configure(R, W / 2, H / 2, 0, 0.78, 0);
    const ring: { u: number; v: number; rx: number; id: number }[] = [];
    for (let i = 0; i < cols; i++) for (let j = 0; j < LANES; j++) {
      const u = (i / cols) * TAU, v = -1 + (2 * j) / (LANES - 1);
      project(u, v * view.hw, 0);
      ring.push({ u, v, rx: pt[0], id: i * LANES + j });
    }
    // 字标点与环上灯珠按横向次序配对，变形时从左到右依次飞散（同 App pairByHorizontalOrder）
    const wordOrder = word.map((_, i) => i).sort((a, b) => word[a][0] - word[b][0]);
    const ringOrder = ring.map((_, i) => i).sort((a, b) => ring[a].rx - ring[b].rx);
    beads = new Array(N);
    for (let r = 0; r < N; r++) {
      const rb = ring[ringOrder[r]], wp = word[wordOrder[r]];
      beads[r] = { u: rb.u, v: rb.v, id: rb.id, wx: wp[0], wy: wp[1], rank: r / (N - 1),
        sx: Math.random() * W, sy: Math.random() * H, ang: Math.random() * TAU, lift: 0.5 + Math.random() };
    }
    const dn = W < 600 ? 1300 : W < 1000 ? 2200 : 3400;
    dust = Array.from({ length: dn }, () => ({ u: Math.random() * TAU, v: (Math.random() * 2 - 1) * 1.6, sp: 0.06 + Math.random() * 0.22,
      sz: 0.5 + Math.random() * 1.2, ph: Math.random() * TAU, rt: 1 + Math.random() * 4, spread: 0.3 + Math.random() * 1.4 }));
    bx = new Float32Array(N); by = new Float32Array(N); blv = new Float32Array(N); sparks = [];
  }

  // ---------- 时间轴：首轮从星空聚成字标，之后字标 → 环 → 字标循环 ----------
  let phase = 0, phaseStart = 0, firstGather = true, lastT = 0, bandClock = 0, burstAt = -10, spawnAcc = 0;
  const ptr = { x: 0.5, y: 0.5, rx: 0, ry: 0 };
  function advance(t: number) {
    phase = (phase + 1) % ORDER.length;
    phaseStart = t;
    if (ORDER[phase] === 'WORD') firstGather = false;
  }
  const colorIndex = (x: number) => Math.max(0, Math.min(5, (((x - W / 2) / (R * 1.1)) * 0.5 + 0.5) * 5.99 | 0));

  function draw(t: number, scrollK: number) {
    if (!N) return;
    const dt = Math.min(0.05, Math.max(0, t - lastT)); lastT = t;
    let el = t - phaseStart;
    while (el >= DURATION[ORDER[phase]]) { advance(phaseStart + DURATION[ORDER[phase]]); el = t - phaseStart; }
    const name = ORDER[phase], prog = el / DURATION[name];
    ptr.ry += ((ptr.x - 0.5) * 0.7 - ptr.ry) * 0.06;
    ptr.rx += ((ptr.y - 0.5) * -0.35 - ptr.rx) * 0.06;
    const since = t - burstAt;
    const tapBurst = since < 0 ? 0 : Math.exp(-since * 2.2) * Math.min(1, since * 12);
    configure(R, W / 2, H / 2, 0.22 * Math.sin(t * 0.3) + ptr.ry, 0.78 + ptr.rx, tapBurst + scrollK * 2.2);
    bandClock += dt * (1 + 3 * Math.min(1, tapBurst));
    ctx.clearRect(0, 0, W, H);
    ctx.globalCompositeOperation = 'lighter';
    const unit = R / 200;
    const fade = 1 - scrollK * 0.85;
    let ringness = name === 'RING' ? 1 : name === 'WORD' ? 0 : name === 'MORPH' ? easeInOutCubic(prog) : 1 - easeInOutCubic(prog);
    if (firstGather && name === 'GATHER') ringness = 0;

    // 星尘：沿 ∞ 带面流动，环形态最亮，字标形态只留一层幽光
    const dustA = (0.16 + 0.84 * ringness) * fade;
    for (const d of dust) {
      d.u += d.sp * dt * (1 + 2.5 * tapBurst);
      if (d.u > TAU) { d.u -= TAU; d.v = -d.v; }
      if (dustA < 0.02) continue;
      project(d.u, d.v * view.hw, d.spread);
      const tw = 0.5 + 0.5 * Math.sin(t * d.rt + d.ph);
      const a = (0.1 + 0.7 * pt[2]) * tw * tw * dustA;
      if (a < 0.015) continue;
      const z = d.sz * unit * (0.55 + 0.8 * pt[2]);
      ctx.globalAlpha = a;
      ctx.fillStyle = sprites[colorIndex(pt[0])].core;
      ctx.fillRect(pt[0] - z / 2, pt[1] - z / 2, z, z);
    }

    // 灯珠（App 灯珠是方形灯芯）
    const spread = scrollK * 1.4;
    for (let k = 0; k < N; k++) {
      const b = beads[k];
      project(b.u, b.v * view.hw, 0.6);
      const rX = pt[0], rY = pt[1], depth = pt[2];
      const ringLv = beadLevel(b.id, t, Math.pow(0.5 + 0.5 * Math.sin(2 * b.u - bandClock * 1.6), 14), 0.09) * (0.3 + 0.7 * depth);
      const col = ((b.wx / W) * 22) | 0;
      const wx = b.wx * (1 + spread) - (W / 2) * spread;
      const wordLv = beadLevel(b.id, t, Math.pow(0.5 + 0.5 * Math.sin(col * 0.9 - bandClock * 2.2), 14), 0.3)
        * (0.62 + 0.38 * Math.sin(t * (1.5 + hash(b.id, 7) * 4) + b.id));
      let x: number, y: number, lv: number, s: number;
      if (name === 'WORD') { x = wx; y = b.wy; lv = wordLv; s = 1.2; }
      else if (name === 'RING') { x = rX; y = rY; lv = ringLv; s = 0.7 + 0.8 * depth; }
      else {
        const gathering = name === 'GATHER';
        const delay = STAGGER * (gathering ? 1 - b.rank : b.rank);
        const e = easeInOutCubic(clamp01((prog - delay) / (1 - STAGGER)));
        let fx, fy, fl, fs, tx, ty, tl, ts;
        if (gathering) {
          if (firstGather) { fx = b.sx; fy = b.sy; fl = 0.04 + 0.08 * hash(b.id, 5); fs = 0.6; }
          else { fx = rX; fy = rY; fl = ringLv; fs = 0.7 + 0.8 * depth; }
          tx = wx; ty = b.wy; tl = wordLv; ts = 1.2;
        } else { fx = wx; fy = b.wy; fl = wordLv; fs = 1.2; tx = rX; ty = rY; tl = ringLv; ts = 0.7 + 0.8 * depth; }
        const arc = Math.sin(Math.PI * e) * R * 0.22 * b.lift;
        x = fx + (tx - fx) * e + Math.cos(b.ang) * arc;
        y = fy + (ty - fy) * e + Math.sin(b.ang) * arc * 0.6;
        lv = fl + (tl - fl) * e + Math.sin(Math.PI * e) * 0.6;
        s = fs + (ts - fs) * e;
      }
      lv = Math.min(1, Math.max(0, lv)) * fade;
      bx[k] = x; by[k] = y; blv[k] = lv;
      if (lv < 0.02) continue;
      const amber = lv > 0.5 && hash(b.id, 4.4) < 0.04;
      const sp = sprites[amber ? 6 : colorIndex(x)];
      const core = s * unit * 1.55;
      if (lv > 0.24) {
        const glow = core * 8;
        ctx.globalAlpha = (lv - 0.2) * 0.9;
        ctx.drawImage(sp.glow, x - glow / 2, y - glow / 2, glow, glow);
      }
      ctx.globalAlpha = Math.min(1, lv * 1.3);
      ctx.fillStyle = lv > 0.55 ? '#F0F8FF' : sp.core;
      ctx.fillRect(x - core / 2, y - core / 2, core, core);
    }

    // 十字星芒：随机灯珠迸出，跟着灯珠移动
    spawnAcc += dt * (W < 600 ? 14 : 26) * fade;
    while (spawnAcc >= 1) {
      spawnAcc--;
      const k = (Math.random() * N) | 0;
      if (blv[k] > 0.12) sparks.push({ k, t0: t, life: 0.4 + Math.random() * 0.7, size: R * (0.05 + Math.random() * 0.1) });
    }
    sparks = sparks.filter(s => t - s.t0 < s.life);
    for (const s of sparks) {
      const env = Math.sin((Math.PI * (t - s.t0)) / s.life);
      const z = s.size * (0.5 + 0.5 * env);
      ctx.globalAlpha = env * fade;
      ctx.drawImage(flare, bx[s.k] - z / 2, by[s.k] - z / 2, z, z);
    }
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
  }

  stage.addEventListener('pointermove', e => {
    const r = stage.getBoundingClientRect();
    ptr.x = (e.clientX - r.left) / r.width; ptr.y = (e.clientY - r.top) / r.height;
  });
  stage.addEventListener('pointerleave', () => { ptr.x = 0.5; ptr.y = 0.5; });
  stage.addEventListener('click', () => { const t = performance.now() / 1000; burstAt = t; advance(t); if (reduceMotion) draw(t, 0); });

  let visible = true;
  new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(stage);
  const scrollK = () => clamp01(scrollY / (stage.offsetHeight * 1.1));

  let resizeTimer = 0, lastWidth = 0;
  const rebuild = () => {
    clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
      if (Math.abs(stage.offsetWidth - lastWidth) > 2 || !N) { lastWidth = stage.offsetWidth; build(); if (reduceMotion) draw(lastT, 0); }
    }, 150);
  };
  new ResizeObserver(rebuild).observe(stage);

  buildSprites();
  build();
  lastWidth = stage.offsetWidth;
  phaseStart = lastT = performance.now() / 1000;
  if (reduceMotion) {
    // 静止时直接停在环形态的一帧
    phase = ORDER.indexOf('RING'); firstGather = false;
    draw(lastT, 0);
  } else {
    const loop = (ms: number) => { if (visible) draw(ms / 1000, scrollK()); requestAnimationFrame(loop); };
    requestAnimationFrame(loop);
  }
}
