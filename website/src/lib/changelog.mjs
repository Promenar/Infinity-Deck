// 从仓库根 CHANGELOG.md 取出已发行版本：只认 `## [主.次.补丁(-后缀)] - YYYY-MM-DD` 形式的小节。
// [Unreleased] 和旧的日期标题小节不进入官网更新日志。

const VERSION_HEADING = /^## \[(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)\] - (\d{4}-\d{2}-\d{2})\s*$/;

/**
 * @param {string} markdown
 * @returns {{ version: string, date: string, body: string }[]} 按文件顺序排列（最新在前）
 */
export function parseChangelog(markdown) {
  const releases = [];
  let current = null;
  for (const line of markdown.split(/\r?\n/)) {
    if (line.startsWith('## ')) {
      if (current) releases.push(finish(current));
      const match = VERSION_HEADING.exec(line);
      current = match ? { version: match[1], date: match[2], lines: [] } : null;
      continue;
    }
    if (current) current.lines.push(line);
  }
  if (current) releases.push(finish(current));
  return releases;
}

function finish({ version, date, lines }) {
  return { version, date, body: lines.join('\n').trim() };
}

/** 把小节正文里的相对链接改成指向 GitHub 仓库的绝对链接；绝对地址与页内锚点保持不变。 */
export function absolutizeLink(href, repoBlobBase) {
  if (!href || /^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('#') || href.startsWith('//')) return href;
  return repoBlobBase.replace(/\/$/, '') + '/' + href.replace(/^\.?\//, '');
}

/** 版本号转成可做锚点的 id，例如 0.34.0-beta → v0-34-0-beta。 */
export function versionAnchor(version) {
  return 'v' + version.replace(/[^0-9A-Za-z]+/g, '-');
}
