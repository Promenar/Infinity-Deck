import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseChangelog, absolutizeLink, versionAnchor } from '../src/lib/changelog.mjs';

const sample = `# Changelog

## [2026-10-01] 旧式日期小节

- 不应出现

## [Unreleased]

### 变更

- 未发行条目

## [0.34.0-beta] - 2026-11-07

### 改进

- 条目一
  - 嵌套条目

## [0.33.4-beta] - 2026-11-06

### 修复

- 条目二

## [0.2-beta] - 2026-07-01

- 两段式版本号不收录
`;

test('只收录三段式版本小节，保持文件顺序', () => {
  const releases = parseChangelog(sample);
  assert.deepEqual(releases.map(r => [r.version, r.date]), [['0.34.0-beta', '2026-11-07'], ['0.33.4-beta', '2026-11-06']]);
});

test('小节正文保留嵌套列表，不混入下一个标题', () => {
  const [latest, previous] = parseChangelog(sample);
  assert.equal(latest.body, '### 改进\n\n- 条目一\n  - 嵌套条目');
  assert.equal(previous.body, '### 修复\n\n- 条目二');
  assert.ok(!latest.body.includes('未发行'));
});

test('Windows 换行同样解析', () => {
  const releases = parseChangelog(sample.replace(/\n/g, '\r\n'));
  assert.equal(releases.length, 2);
  assert.equal(releases[1].body, '### 修复\r\n\r\n- 条目二'.replace(/\r/g, ''));
});

test('仓库的 CHANGELOG.md 能解析出版本，且最新版本在最前', () => {
  const md = readFileSync(new URL('../../CHANGELOG.md', import.meta.url), 'utf8');
  const releases = parseChangelog(md);
  assert.ok(releases.length > 20);
  for (const r of releases) {
    assert.match(r.version, /^\d+\.\d+\.\d+/);
    assert.ok(r.body.length > 0, `${r.version} 正文为空`);
  }
  const dates = releases.map(r => r.date);
  assert.deepEqual([...dates].sort().reverse(), dates);
});

test('相对链接改写为仓库绝对地址，其它链接不变', () => {
  const base = 'https://github.com/Promenar/Infinity-Workbench/blob/main/';
  assert.equal(absolutizeLink('docs/guide/features.md', base), base + 'docs/guide/features.md');
  assert.equal(absolutizeLink('./README.md', base), base + 'README.md');
  assert.equal(absolutizeLink('https://example.com/a', base), 'https://example.com/a');
  assert.equal(absolutizeLink('#v0-34-0-beta', base), '#v0-34-0-beta');
  assert.equal(absolutizeLink('mailto:a@b.c', base), 'mailto:a@b.c');
});

test('版本锚点只含字母、数字和连字符', () => {
  assert.equal(versionAnchor('0.34.0-beta'), 'v0-34-0-beta');
  assert.equal(versionAnchor('1.0.0'), 'v1-0-0');
});
