// 502 排障实演：Agent 查到根因后停在审批卡，等访客点“批准当前”或“拒绝当前”。
import { reduceMotion } from './motion';

const demo = document.getElementById('demo');
const log = document.getElementById('log');
if (demo && log) {
  const s = JSON.parse(demo.dataset.strings || '{}') as Record<string, string>;
  let runId = 0;
  let timer = 0;
  const wait = (ms: number) => new Promise(r => setTimeout(r, reduceMotion ? 0 : ms));
  const esc = (x: string) => x.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

  function add(html: string, cls: string, tag = 'div') {
    const el = document.createElement(tag);
    el.className = cls;
    el.innerHTML = html;
    log!.appendChild(el);
    return el;
  }

  async function run() {
    const my = ++runId;
    clearInterval(timer);
    log!.innerHTML = '';
    const alive = () => my === runId;
    add(s.intro, 'say', 'p'); await wait(900); if (!alive()) return;
    add(`<span class="dim">$</span> curl -s -o /dev/null -w '%{http_code}' localhost:8080/health\n<span class="bad">502</span>`, 'out'); await wait(700); if (!alive()) return;
    add(s.ran1, 'run'); await wait(900); if (!alive()) return;
    add(s.cause, 'say', 'p'); await wait(900); if (!alive()) return;
    const command = `{"host":"web-01","command":"sed -i 's#/srv/starlog/data#~/starlog/data#' config.toml && systemctl --user restart starlog-api"}`;
    const card = add(`<div class="card-h"><span>${esc(s.cardLabel)}</span><span class="cmd">web-01 · systemctl --user restart starlog-api</span><span class="chip">${esc(s.chip)}</span></div>
      <div class="card-b"><span class="tool">ssh_exec</span><pre>${esc(command)}</pre><span class="note">${esc(s.note)}</span>
      <div class="choices"><button class="btn btn-reject" type="button">${esc(s.reject)}</button><button class="btn btn-approve" type="button">${esc(s.approve)}</button></div></div>`, 'card');
    const waiting = add(`<i></i><span>${esc(s.waiting)}</span><span class="clock">0:00</span>`, 'wait');
    const clock = waiting.querySelector('.clock')!;
    let sec = 0;
    timer = window.setInterval(() => { sec++; clock.textContent = `${(sec / 60) | 0}:${String(sec % 60).padStart(2, '0')}`; }, 1000);
    const approved = await new Promise<boolean>(res => {
      card.querySelector('.btn-approve')!.addEventListener('click', () => res(true), { once: true });
      card.querySelector('.btn-reject')!.addEventListener('click', () => res(false), { once: true });
    });
    if (!alive()) return;
    clearInterval(timer);
    waiting.remove();
    card.querySelector('.choices')!.remove();
    card.querySelector('.chip')!.textContent = approved ? s.approved : s.rejected;
    if (!approved) { add(esc(s.rejectedSay), 'say', 'p'); return; }
    await wait(600); if (!alive()) return;
    add(s.ran2, 'run'); await wait(500); if (!alive()) return;
    add(`<span class="dim">GET</span> :8080/health     <span class="good">200</span>\n<span class="dim">GET</span> :8080/api/stars  <span class="good">200</span>`, 'out'); await wait(800); if (!alive()) return;
    add(esc(s.done), 'say', 'p');
  }

  document.getElementById('replay')?.addEventListener('click', run);
  const io = new IntersectionObserver(es => { if (es[0].isIntersecting) { run(); io.disconnect(); } }, { threshold: 0.35 });
  io.observe(demo);
}
