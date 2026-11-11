import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { PETS } from '../src/data/pets.mjs';

const pub = new URL('../public/pets/', import.meta.url);

/** 从 zip 中央目录取出指定文件的内容（只支持存储与 deflate）。 */
function readZipEntry(zip, name) {
  const eocd = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  let p = zip.readUInt32LE(eocd + 16);
  const count = zip.readUInt16LE(eocd + 10);
  for (let i = 0; i < count; i++) {
    const method = zip.readUInt16LE(p + 10), csize = zip.readUInt32LE(p + 20);
    const nlen = zip.readUInt16LE(p + 28), xlen = zip.readUInt16LE(p + 30), clen = zip.readUInt16LE(p + 32);
    const local = zip.readUInt32LE(p + 42);
    if (zip.toString('utf8', p + 46, p + 46 + nlen) === name) {
      const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
      const data = zip.subarray(start, start + csize);
      return method === 0 ? data : inflateRawSync(data);
    }
    p += 46 + nlen + xlen + clen;
  }
  throw new Error(`${name} 不在压缩包中`);
}

for (const pet of PETS) {
  const bytes = readFileSync(new URL(pet.file, pub));

  test(`${pet.file} 的大小与 SHA-256 与页面显示一致`, () => {
    assert.equal(bytes.length, pet.size);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), pet.sha256);
    assert.ok(readFileSync(new URL(pet.thumb, pub)).length > 0);
  });

  test(`${pet.file} 的 manifest 与登记的 id、名称、版本和许可一致`, () => {
    const m = JSON.parse(readZipEntry(bytes, 'manifest.json').toString('utf8'));
    assert.equal(m.id, pet.id);
    assert.equal(m.name, pet.name);
    assert.equal(m.version, pet.version);
    assert.equal(m.license.redistribute, true);
    assert.equal(m.license.commercial, false);
  });
}
