// 钉住叙事：滚动进度决定当前章节、画面与侧边灯珠刻度；最后一章把内屏右半绕铰链翻过来，背面即外屏。
import { reduceMotion, clamp01, easeOutCubic, splitForSweep, playSweep } from './motion';

const story = document.getElementById('story');
if (story) {
  const chapters = [...story.querySelectorAll<HTMLElement>('.chapter')];
  const medias = [...story.querySelectorAll<HTMLVideoElement | HTMLImageElement>('.media')];
  const panel = document.getElementById('panel')!;
  const halves = document.getElementById('halves')!;
  const flap = document.getElementById('flap')!;
  const front = flap.querySelector<HTMLElement>('.front')!;
  const left = halves.querySelector<HTMLElement>('.half.l')!;
  const device = document.getElementById('device')!;
  const rail = document.getElementById('rail')!;
  const COUNT = chapters.length, PER = 5;

  chapters.forEach(c => { const h = c.querySelector<HTMLElement>('h3'); if (h) splitForSweep(h); });

  for (let i = 0; i < COUNT; i++) {
    const g = document.createElement('button');
    g.type = 'button';
    g.className = 'rail-group';
    g.setAttribute('aria-label', chapters[i].querySelector('h3')?.getAttribute('aria-label') || String(i + 1));
    for (let k = 0; k < PER; k++) g.appendChild(document.createElement('i'));
    g.addEventListener('click', () => {
      const top = story.offsetTop + ((i + 0.35) / COUNT) * (story.offsetHeight - innerHeight);
      scrollTo({ top, behavior: reduceMotion ? 'auto' : 'smooth' });
    });
    rail.appendChild(g);
  }
  const dots = [...rail.querySelectorAll('i')];

  let current = -1;
  function setChapter(i: number) {
    if (i === current) return;
    current = i;
    chapters.forEach((c, k) => {
      const on = k === i;
      c.classList.toggle('on', on);
      c.setAttribute('aria-hidden', String(!on));
      if (on) playSweep(c.querySelector('h3'));
    });
    medias.forEach(m => {
      const on = Number(m.dataset.ch) === i;
      m.classList.toggle('on', on);
      if (m instanceof HTMLVideoElement) {
        if (on && !reduceMotion) { m.currentTime = 0; m.play().catch(() => {}); } else m.pause();
      }
    });
  }

  function update() {
    const total = story!.offsetHeight - innerHeight;
    const p = clamp01((scrollY - story!.offsetTop) / total);
    const f = p * COUNT;
    const i = Math.min(COUNT - 1, Math.floor(f));
    const local = clamp01(f - i);
    setChapter(i);
    dots.forEach((d, k) => {
      const g = (k / PER) | 0, n = k % PER;
      d.classList.toggle('lit', g < i || (g === i && n <= local * PER));
    });
    const folding = i === COUNT - 1;
    const fold = folding ? easeOutCubic(clamp01((local - 0.08) / 0.62)) : 0;
    halves.classList.toggle('on', folding);
    panel.style.visibility = folding ? 'hidden' : 'visible';
    flap.style.transform = `rotateY(${-180 * fold}deg)`;
    front.style.setProperty('--shade', (fold * 0.9).toFixed(3));
    left.style.setProperty('--shade-l', (Math.sin(Math.PI * fold) * 0.5).toFixed(3));
    left.style.opacity = (1 - clamp01((fold - 0.86) / 0.12)).toFixed(3);
    device.style.transform = `translateX(${(fold * 25).toFixed(2)}%) rotateY(${(Math.sin(Math.PI * fold) * -10).toFixed(2)}deg)`;
  }

  let queued = false;
  const schedule = () => { if (!queued) { queued = true; requestAnimationFrame(() => { queued = false; update(); }); } };
  addEventListener('scroll', schedule, { passive: true });
  addEventListener('resize', schedule);
  update();
}
