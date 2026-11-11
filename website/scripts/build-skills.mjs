import { createHash } from 'node:crypto';
import { readdir, readFile, mkdir, writeFile, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { title, categories, skills } from '../src/data/skills.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(root, 'public/skills');
const crcTable = Array.from({ length: 256 }, (_, i) => {
  let n = i;
  for (let j = 0; j < 8; j++) n = n & 1 ? 0xedb88320 ^ (n >>> 1) : n >>> 1;
  return n >>> 0;
});
function crc32(data) {
  let crc = 0xffffffff;
  for (const b of data) crc = crcTable[(crc ^ b) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
async function collect(dir, prefix = '') {
  const result = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = prefix + entry.name;
    if (entry.isDirectory()) result.push(...await collect(join(dir, entry.name), path + '/'));
    else if (entry.isFile()) result.push({ path, data: await readFile(join(dir, entry.name)) });
    else throw new Error(`不支持的文件类型：${path}`);
  }
  return result;
}
function zip(files) {
  const local = [], central = [];
  let offset = 0;
  for (const { path, data } of files) {
    const name = Buffer.from(path), compressed = deflateRawSync(data), crc = crc32(data);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x800, 6);
    h.writeUInt16LE(8, 8); h.writeUInt16LE(33, 12); h.writeUInt32LE(crc, 14);
    h.writeUInt32LE(compressed.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(name.length, 26);
    local.push(h, name, compressed);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6);
    c.writeUInt16LE(0x800, 8); c.writeUInt16LE(8, 10); c.writeUInt16LE(33, 14);
    c.writeUInt32LE(crc, 16); c.writeUInt32LE(compressed.length, 20); c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(name.length, 28); c.writeUInt32LE(offset, 42); central.push(c, name);
    offset += h.length + name.length + compressed.length;
  }
  const directory = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}
export async function buildSkills() {
  await mkdir(output, { recursive: true });
  const entries = [];
  const names = new Set();
  for (const skill of skills) {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(skill.name) || names.has(skill.name) || !/^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$/.test(skill.version)) throw new Error('技能名称或版本无效');
    names.add(skill.name);
    const files = (await collect(join(root, 'skills', skill.name))).sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)));
    if (!files.some(f => f.path === 'SKILL.md')) throw new Error(`技能缺少 SKILL.md：${skill.name}`);
    const archive = `${skill.name}-${skill.version}.zip`, bytes = zip(files);
    await writeFile(join(output, archive), bytes);
    entries.push({ ...skill, archive, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), files: files.map(f => ({ path: f.path, size: f.data.length })) });
  }
  for (const file of await readdir(output)) if (file.endsWith('.zip') && !entries.some(e => e.archive === file)) await rm(join(output, file));
  await writeFile(join(output, 'index.json'), JSON.stringify({ schema: 1, title, categories, skills: entries }, null, 2) + '\n');
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await buildSkills();
