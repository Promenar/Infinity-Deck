import test from 'node:test';
import assert from 'node:assert/strict';
import { GET as sitemap } from '../src/pages/sitemap.xml.js';
import { GET as robots } from '../src/pages/robots.txt.js';

const site = new URL('https://example.com/');

test('sitemap 收录正式页面，中文和英文条目配对且不包含错误页', async () => {
  const xml = await sitemap({ site }).text();
  const urls = [...xml.matchAll(/<loc>(.*?)<\/loc>/g)].map(match => match[1]);
  assert.equal(new Set(urls).size, 12);
  assert.equal(urls.length, 12);
  assert.ok(urls.every(url => new URL(url).origin === site.origin && !url.includes('/404/')));
  for (const path of ['/', '/download/', '/docs/', '/changelog/', '/guides/android-ai-coding/', '/guides/android-ssh-ops/']) {
    const zh = new URL(path, site).href;
    const en = new URL(`/en${path}`, site).href;
    for (const canonical of [zh, en]) {
      const entry = xml.split('<url>').find(part => part.startsWith(`<loc>${canonical}</loc>`));
      assert.ok(entry, canonical);
      assert.ok(entry.includes(`hreflang="zh-CN" href="${zh}"`));
      assert.ok(entry.includes(`hreflang="en" href="${en}"`));
      assert.ok(entry.includes(`hreflang="x-default" href="${zh}"`));
    }
  }
});

test('robots 使用构建站点的 sitemap 地址', async () => {
  assert.match(await robots({ site }).text(), /^Sitemap: https:\/\/example\.com\/sitemap\.xml$/m);
});
