// 各段动效共用的工具：减少动态效果偏好、数学函数、逐字点亮。

export const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
export const TAU = Math.PI * 2;
export const DPR = Math.min(2, window.devicePixelRatio || 1);

export const clamp01 = (x: number) => (x < 0 ? 0 : x > 1 ? 1 : x);
const fract = (x: number) => x - Math.floor(x);
/** 与 App 的 motionHash 相同：按 (a, b) 得到 0..1 的伪随机数。 */
export const hash = (a: number, b: number) => fract(Math.sin(a * 127.1 + b * 311.7) * 43758.5453);
export const easeInOutCubic = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);
export const easeOutCubic = (x: number) => 1 - Math.pow(1 - x, 3);

/** 把元素文字拆成逐字的 span；拉丁词整体不断行，`\n` 变成换行。 */
export function splitForSweep(el: HTMLElement) {
  if (el.dataset.split) return;
  el.dataset.split = '1';
  const text = (el.textContent || '').replace(/[ \t]*\n\s*/g, '\n').trim();
  el.textContent = '';
  el.setAttribute('aria-label', text.replace(/\n/g, ' '));
  let i = 0;
  for (const tok of text.match(/[A-Za-z0-9’'][A-Za-z0-9’'.+-]*|\n|./gu) || []) {
    if (tok === '\n') { el.appendChild(document.createElement('br')); continue; }
    const host = tok.length > 1 ? el.appendChild(Object.assign(document.createElement('span'), { className: 'w' })) : el;
    for (const ch of tok) {
      const s = document.createElement('span');
      s.className = 'ch';
      s.style.setProperty('--i', String(i++));
      s.textContent = ch;
      s.setAttribute('aria-hidden', 'true');
      host.appendChild(s);
    }
  }
}

/** 重新播放逐字点亮。 */
export function playSweep(el: Element | null) {
  if (!el || reduceMotion) return;
  el.classList.remove('pre', 'play');
  void (el as HTMLElement).offsetWidth;
  el.classList.add('play');
}
