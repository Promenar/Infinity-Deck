// 全站：三层视差星空、导航栏底色、标题逐字点亮。
import { reduceMotion, TAU, DPR, splitForSweep, playSweep } from './motion';

type Star = { x: number; y: number; layer: number; r: number; phase: number; rate: number; warm: boolean; base: number };

const sky = document.getElementById('sky') as HTMLCanvasElement;
const ctx = sky.getContext('2d')!;
let stars: Star[] = [];

function buildSky() {
  sky.width = innerWidth * DPR;
  sky.height = innerHeight * DPR;
  const n = Math.round(Math.min(1100, (innerWidth * innerHeight) / 1500));
  stars = Array.from({ length: n }, (_, i) => {
    const layer = i % 7 === 0 ? 0 : i % 7 < 3 ? 1 : i % 7 < 5 ? 2 : 3;
    return {
      x: Math.random(), y: Math.random(), layer,
      r: [1.6, 1.1, 0.8, 0.6][layer] * DPR * (0.6 + Math.random() * 0.7),
      phase: Math.random() * TAU, rate: 0.3 + Math.random() * 1.6, warm: Math.random() < 0.5,
      base: [0.8, 0.5, 0.3, 0.18][layer],
    };
  });
}

const SPEED = [0.12, 0.066, 0.03, 0.012];
function drawSky(t: number) {
  const w = sky.width, h = sky.height, sy = scrollY * DPR;
  ctx.clearRect(0, 0, w, h);
  for (const s of stars) {
    const y = (((s.y * h - sy * SPEED[s.layer]) % h) + h) % h;
    const tw = Math.pow(0.5 + 0.5 * Math.sin(t * s.rate + s.phase), 1.6);
    ctx.globalAlpha = s.base * (reduceMotion ? 0.7 : tw);
    ctx.fillStyle = s.warm ? '#94CDF7' : '#DDEBF7';
    ctx.fillRect(s.x * w, y, s.r, s.r);
  }
  ctx.globalAlpha = 1;
}

let skyTimer = 0;
addEventListener('resize', () => { clearTimeout(skyTimer); skyTimer = window.setTimeout(() => { buildSky(); drawSky(performance.now() / 1000); }, 150); });
buildSky();
if (reduceMotion) {
  drawSky(0);
  addEventListener('scroll', () => drawSky(0), { passive: true });
} else {
  const loop = (ms: number) => { drawSky(ms / 1000); requestAnimationFrame(loop); };
  requestAnimationFrame(loop);
}

const nav = document.getElementById('nav')!;
const onScroll = () => nav.classList.toggle('scrolled', scrollY > 8);
addEventListener('scroll', onScroll, { passive: true });
onScroll();

document.querySelectorAll<HTMLElement>('.sweep').forEach(splitForSweep);
if (!reduceMotion) {
  const io = new IntersectionObserver(entries => entries.forEach(e => {
    if (e.isIntersecting) { playSweep(e.target); io.unobserve(e.target); }
  }), { rootMargin: '0px 0px -12% 0px' });
  document.querySelectorAll<HTMLElement>('[data-sweep]').forEach(el => {
    if (el.getBoundingClientRect().top > innerHeight * 0.88) el.classList.add('pre');
    io.observe(el);
  });
}
