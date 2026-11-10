// 发版后把 GitHub 最新 Release 写入 src/data/release-snapshot.json，作为构建时接口不可用的回退值。
// 用法：在 website/ 执行 `npm run snapshot`；设置 GITHUB_TOKEN 可避开匿名限流。
import { writeFile } from 'node:fs/promises';
import { fetchLatestRelease, toSnapshot } from '../src/lib/release.mjs';

const target = new URL('../src/data/release-snapshot.json', import.meta.url);
const snapshot = toSnapshot(await fetchLatestRelease({ token: process.env.GITHUB_TOKEN }));
await writeFile(target, `${JSON.stringify(snapshot, null, 2)}\n`);
console.log(`已写入 ${snapshot.tag}：${snapshot.apk.name}，${snapshot.apk.size} 字节，SHA-256 ${snapshot.sha256}`);
