// 下载区：复制 SHA-256；页面构建后若 GitHub 已有更新的 Release，就地换成新版本的下载地址。
import { formatSize, formatDate } from '../lib/release.mjs';

for (const box of document.querySelectorAll<HTMLElement>('[data-release]')) {
  const copy = box.querySelector<HTMLButtonElement>('[data-copy]');
  const sha = box.querySelector<HTMLElement>('[data-sha]');
  if (copy && sha) {
    const label = copy.textContent || '';
    copy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(sha.textContent || '');
        copy.textContent = copy.dataset.copied || label;
      } catch {
        const range = document.createRange();
        range.selectNodeContents(sha);
        const sel = getSelection();
        sel?.removeAllRanges(); sel?.addRange(range);
        copy.textContent = copy.dataset.selected || label;
      }
      setTimeout(() => { copy.textContent = label; }, 1600);
    });
  }
}

const boxes = [...document.querySelectorAll<HTMLElement>('[data-release]')];
if (boxes.length) {
  const builtTag = boxes[0].dataset.release;
  const locale = document.body.dataset.locale === 'en' ? 'en' : 'zh';
  fetch('https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
    .then(r => (r.ok ? r.json() : null))
    .then(json => {
      if (!json || json.tag_name === builtTag || json.draft || json.prerelease) return;
      const apk = (json.assets || []).find((a: { name: string }) => a.name.endsWith('.apk'));
      if (!apk) return;
      const shaAsset = (json.assets || []).find((a: { name: string }) => a.name === `${apk.name}.sha256`);
      for (const box of boxes) {
        box.querySelectorAll<HTMLElement>('[data-version]').forEach(el => { el.textContent = json.tag_name; });
        box.querySelectorAll<HTMLElement>('[data-date]').forEach(el => { el.textContent = formatDate(json.published_at, locale); });
        box.querySelectorAll<HTMLAnchorElement>('[data-apk]').forEach(a => { a.href = apk.browser_download_url; });
        box.querySelectorAll<HTMLElement>('[data-size]').forEach(el => { el.textContent = formatSize(apk.size); });
        // 新版本的校验值不在页面里；换成 .sha256 文件链接，避免显示旧值
        const row = box.querySelector<HTMLElement>('[data-sha-row]');
        if (row && shaAsset) {
          const link = document.createElement('a');
          link.className = 'btn btn-ghost btn-sm';
          link.href = shaAsset.browser_download_url;
          link.textContent = row.dataset.shaFile || '.sha256';
          row.replaceChildren(Object.assign(document.createElement('span'), { className: 'sha-l', textContent: 'SHA-256' }), link);
        }
        box.querySelectorAll<HTMLElement>('[data-newer]').forEach(el => { el.hidden = false; });
      }
    })
    .catch(() => {});
}
