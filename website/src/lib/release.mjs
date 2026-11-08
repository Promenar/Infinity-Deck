// 读取 GitHub 最新 Release 的侧载 APK 信息。构建时调用；接口不可用时回退到仓库内的快照。

export const REPO = 'Promenar/Infinity-Workbench';
export const RELEASES_URL = `https://github.com/${REPO}/releases`;
const API_LATEST = `https://api.github.com/repos/${REPO}/releases/latest`;

/**
 * 从 GitHub Release 接口的 JSON 中挑出 APK 与对应的 .sha256 资产。
 * @returns {{ tag: string, version: string, publishedAt: string, pageUrl: string,
 *   apk: { name: string, size: number, url: string }, shaUrl: string | null }}
 */
export function pickRelease(json) {
  if (!json || typeof json.tag_name !== 'string') throw new Error('Release 数据缺少 tag_name');
  const assets = Array.isArray(json.assets) ? json.assets : [];
  const apk = assets.find(a => typeof a.name === 'string' && a.name.endsWith('.apk'));
  if (!apk) throw new Error(`Release ${json.tag_name} 没有 APK 资产`);
  const sha = assets.find(a => a.name === `${apk.name}.sha256`);
  return {
    tag: json.tag_name,
    version: json.tag_name.replace(/^v/, ''),
    publishedAt: json.published_at,
    pageUrl: json.html_url,
    apk: { name: apk.name, size: apk.size, url: apk.browser_download_url },
    shaUrl: sha ? sha.browser_download_url : null,
  };
}

/** 解析 `sha256sum` 格式的校验文件，并核对文件名与 APK 一致。 */
export function parseSha256File(text, apkName) {
  const match = /^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$/m.exec(String(text).trim());
  if (!match) throw new Error('校验文件不是 sha256sum 格式');
  if (apkName && match[2] !== apkName) throw new Error(`校验文件对应 ${match[2]}，不是 ${apkName}`);
  return match[1].toLowerCase();
}

/** 以 MB（1 MB = 1024 × 1024 字节）取整显示安装包大小。 */
export function formatSize(bytes) {
  return `${Math.max(1, Math.round(bytes / 1048576))} MB`;
}

/** 发布日期按语言格式化；取 UTC 日期，与 GitHub Release 页一致。 */
export function formatDate(iso, locale) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) throw new Error(`无效日期：${iso}`);
  const y = d.getUTCFullYear(), m = d.getUTCMonth() + 1, day = d.getUTCDate();
  if (locale === 'zh') return `${y} 年 ${m} 月 ${day} 日`;
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${months[m - 1]} ${day}, ${y}`;
}

/**
 * 构建时读取最新 Release；任一步失败都回退到 fallback，并通过 onWarn 报告原因。
 * @param {{ fetchImpl?: typeof fetch, token?: string, fallback: object, onWarn?: (msg: string) => void }} options
 */
export async function loadRelease({ fetchImpl = fetch, token, fallback, onWarn = () => {} }) {
  try {
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'infinity-workbench-website-build' };
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await fetchImpl(API_LATEST, { headers });
    if (!res.ok) throw new Error(`GitHub 接口返回 ${res.status}`);
    const release = pickRelease(await res.json());
    if (!release.shaUrl) throw new Error(`${release.tag} 缺少 .sha256 资产`);
    const shaRes = await fetchImpl(release.shaUrl, { headers: { 'User-Agent': headers['User-Agent'] } });
    if (!shaRes.ok) throw new Error(`校验文件下载返回 ${shaRes.status}`);
    return { ...release, sha256: parseSha256File(await shaRes.text(), release.apk.name), source: 'github' };
  } catch (error) {
    onWarn(`读取最新 Release 失败，使用仓库快照 ${fallback.tag}：${error.message}`);
    return { ...fallback, source: 'snapshot' };
  }
}
