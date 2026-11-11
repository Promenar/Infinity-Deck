import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { inflateRawSync } from 'node:zlib';
import { buildSkills } from '../scripts/build-skills.mjs';
const dir = new URL('../public/skills/', import.meta.url);
async function snapshot() {
  const result = new Map();
  for (const name of (await readdir(dir)).sort()) result.set(name, await readFile(new URL(name, dir)));
  return result;
}
function unzip(bytes) {
  const files = new Map();
  let offset = 0;
  while (bytes.readUInt32LE(offset) === 0x04034b50) {
    const size = bytes.readUInt32LE(offset + 18), nameSize = bytes.readUInt16LE(offset + 26), extraSize = bytes.readUInt16LE(offset + 28);
    const name = bytes.subarray(offset + 30, offset + 30 + nameSize).toString();
    const start = offset + 30 + nameSize + extraSize;
    assert.equal(bytes.readUInt16LE(offset + 8), 8);
    assert.equal(files.has(name), false);
    files.set(name, inflateRawSync(bytes.subarray(start, start + size)));
    offset = start + size;
  }
  assert.equal(bytes.readUInt32LE(offset), 0x02014b50);
  return files;
}
test('技能目录与压缩包一致且可重现', async () => {
  const before = await snapshot();
  const index = JSON.parse(before.get('index.json'));
  assert.equal(index.schema, 1);
  for (const item of index.skills) {
    const bytes = before.get(item.archive);
    assert.equal(bytes.length, item.size);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), item.sha256);
    assert.ok(item.channels.length && item.channels.every(c => ['sideload', 'play'].includes(c)));
    const files = unzip(bytes);
    assert.deepEqual([...files].map(([path, data]) => ({ path, size: data.length })), item.files);
    assert.match(files.get('SKILL.md').toString(), new RegExp(`^---\\s*\\nname: ${item.name}\\s*\\n`));
  }
  await buildSkills();
  assert.deepEqual(await snapshot(), before);
});
