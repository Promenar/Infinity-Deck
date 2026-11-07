import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { versioned } from '../src/lib/asset.mjs';

test('versioned 按文件内容生成版本号，内容不同则地址不同', () => {
  const dir = mkdtempSync(join(tmpdir(), 'asset-'));
  writeFileSync(join(dir, 'a.mp4'), 'one');
  writeFileSync(join(dir, 'b.mp4'), 'two');
  const a = versioned('/a.mp4', dir);
  assert.match(a, /^\/a\.mp4\?v=[0-9a-f]{10}$/);
  assert.equal(versioned('/a.mp4', dir), a);
  assert.notEqual(versioned('/b.mp4', dir).split('?v=')[1], a.split('?v=')[1]);
});

test('versioned 在文件缺失时抛错', () => {
  const dir = mkdtempSync(join(tmpdir(), 'asset-'));
  assert.throws(() => versioned('/missing.webp', dir));
});
