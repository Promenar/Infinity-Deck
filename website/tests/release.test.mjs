import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pickRelease, parseSha256File, formatSize, formatDate, loadRelease, fetchLatestRelease, toSnapshot } from '../src/lib/release.mjs';
import snapshot from '../src/data/release-snapshot.json' with { type: 'json' };

const SHA = '34b15aced87fe28ebb458bdd9df84293ef941b95e83f1fc05dc11fa8a7bc5696';
const apiJson = {
  tag_name: 'v0.35.0-beta',
  published_at: '2026-11-20T23:30:00Z',
  html_url: 'https://github.com/Promenar/Infinity-Deck/releases/tag/v0.35.0-beta',
  assets: [
    { name: 'infinity-v0.35.0-beta.apk.sha256', size: 92, browser_download_url: 'https://x/apk.sha256' },
    { name: 'infinity-v0.35.0-beta.apk', size: 56000000, browser_download_url: 'https://x/apk' },
  ],
};
const fallback = { tag: 'v0.34.0-beta', version: '0.34.0-beta', sha256: SHA };

test('挑出 APK 与同名 .sha256 资产，资产顺序不影响结果', () => {
  const r = pickRelease(apiJson);
  assert.equal(r.version, '0.35.0-beta');
  assert.deepEqual(r.apk, { name: 'infinity-v0.35.0-beta.apk', size: 56000000, url: 'https://x/apk' });
  assert.equal(r.shaUrl, 'https://x/apk.sha256');
});

test('没有 APK 的 Release 报错', () => {
  assert.throws(() => pickRelease({ tag_name: 'v1.0.0', assets: [] }), /没有 APK/);
});

test('解析 sha256sum 文件并核对文件名', () => {
  assert.equal(parseSha256File(`${SHA}  infinity-v0.34.0-beta.apk\n`, 'infinity-v0.34.0-beta.apk'), SHA);
  assert.equal(parseSha256File(`${SHA.toUpperCase()} *infinity-v0.34.0-beta.apk`, 'infinity-v0.34.0-beta.apk'), SHA);
  assert.throws(() => parseSha256File(`${SHA}  other.apk`, 'infinity-v0.34.0-beta.apk'), /不是/);
  assert.throws(() => parseSha256File('not a checksum', 'a.apk'), /格式/);
});

test('安装包大小按 MiB 取整', () => {
  assert.equal(formatSize(55769251), '53 MB');
  assert.equal(formatSize(1000), '1 MB');
});

test('发布日期按 UTC 日期格式化', () => {
  assert.equal(formatDate('2026-11-07T05:05:21Z', 'zh'), '2026 年 11 月 7 日');
  assert.equal(formatDate('2026-11-07T05:05:21Z', 'en'), 'Nov 7, 2026');
  assert.equal(formatDate('2026-11-20T23:30:00Z', 'zh'), '2026 年 11 月 20 日');
});

function fakeFetch(routes) {
  return async url => {
    const r = routes[url];
    if (!r) return { ok: false, status: 404 };
    return { ok: true, status: 200, json: async () => r, text: async () => r };
  };
}

test('接口正常时返回最新 Release 与校验值', async () => {
  const fetchImpl = fakeFetch({
    'https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest': apiJson,
    'https://x/apk.sha256': `${SHA}  infinity-v0.35.0-beta.apk\n`,
  });
  const r = await loadRelease({ fetchImpl, fallback });
  assert.equal(r.source, 'github');
  assert.equal(r.tag, 'v0.35.0-beta');
  assert.equal(r.sha256, SHA);
});

test('接口失败或校验文件不匹配时回退到快照并给出原因', async () => {
  const warnings = [];
  const down = await loadRelease({ fetchImpl: fakeFetch({}), fallback, onWarn: m => warnings.push(m) });
  assert.equal(down.source, 'snapshot');
  assert.equal(down.tag, 'v0.34.0-beta');
  const mismatch = await loadRelease({
    fetchImpl: fakeFetch({
      'https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest': apiJson,
      'https://x/apk.sha256': `${SHA}  wrong.apk`,
    }),
    fallback,
    onWarn: m => warnings.push(m),
  });
  assert.equal(mismatch.source, 'snapshot');
  assert.equal(warnings.length, 2);
  assert.match(warnings[0], /404/);
  assert.match(warnings[1], /wrong\.apk/);
});

test('快照字段与仓库内快照文件一致，不带构建来源', async () => {
  const release = await loadRelease({
    fetchImpl: fakeFetch({
      'https://api.github.com/repos/Promenar/Infinity-Deck/releases/latest': apiJson,
      'https://x/apk.sha256': `${SHA}  infinity-v0.35.0-beta.apk\n`,
    }),
    fallback,
  });
  const out = toSnapshot(release);
  assert.deepEqual(Object.keys(out), Object.keys(snapshot));
  assert.deepEqual(Object.keys(out.apk), Object.keys(snapshot.apk));
  assert.equal(out.tag, 'v0.35.0-beta');
  assert.equal(out.sha256, SHA);
});

test('刷新快照时接口失败直接报错，不写入回退值', async () => {
  await assert.rejects(fetchLatestRelease({ fetchImpl: fakeFetch({}) }), /404/);
});
