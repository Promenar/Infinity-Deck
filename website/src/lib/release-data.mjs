// 构建期只读取一次最新 Release，所有页面共用结果。
import { loadRelease } from './release.mjs';
import snapshot from '../data/release-snapshot.json' with { type: 'json' };

let cached;
export function getRelease() {
  cached ??= loadRelease({
    token: process.env.GITHUB_TOKEN,
    fallback: snapshot,
    onWarn: message => console.warn(`[release] ${message}`),
  });
  return cached;
}
