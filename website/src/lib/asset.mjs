// 给 public/ 下的静态资源地址加内容哈希版本号：文件内容变化时地址随之变化，CDN 与浏览器缓存不会继续使用旧文件。
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const memo = new Map();

/** `/media/a.mp4` → `/media/a.mp4?v=<sha256 前 10 位>`；文件不存在时抛错，让构建失败。 */
export function versioned(path, publicDir = resolve(process.cwd(), 'public')) {
  const key = `${publicDir}\0${path}`;
  if (!memo.has(key)) {
    const hash = createHash('sha256').update(readFileSync(resolve(publicDir, path.replace(/^\//, '')))).digest('hex').slice(0, 10);
    memo.set(key, `${path}?v=${hash}`);
  }
  return memo.get(key);
}
