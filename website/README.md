# Infinity Workbench 官网

产品官网，静态站点，线上地址 <https://infinityware.work>。中文为默认语言（`/`），英文在 `/en/`。

## 技术栈

- [Astro](https://astro.build) 静态生成，输出纯 HTML、CSS 与少量原生 TypeScript 脚本。
- [marked](https://marked.js.org) 在构建时把仓库根的 `CHANGELOG.md` 渲染为更新日志页。
- 只用系统字体，不加载任何第三方字体或 CDN 资源。
- 开场灯珠动效移植自 App 的 `MobiusProjector` 与 `LED_MORPH` 时间轴（`src/scripts/ring.ts`）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/i18n.mjs` | 全站中英文文案、导航与语言路径 |
| `src/views/` | 各页面的实际内容；`src/pages/` 只按语言包装 |
| `src/components/DownloadPanel.astro` | 下载台（首页与下载页共用） |
| `src/lib/` | 更新日志解析、Release 数据读取与格式化 |
| `src/scripts/` | 客户端动效：星空、开场灯珠、钉住叙事、502 排障实演、下载区 |
| `src/data/release-snapshot.json` | 构建时读不到 GitHub API 时使用的 Release 快照 |
| `public/media/` | 叙事区截图与录屏，由 `scripts/prepare-media.sh` 生成 |
| `public/_headers` | Cloudflare Pages 缓存与安全响应头 |

## 本地开发

需要 Node.js 22.23.4 或更高版本（Cloudflare Pages 按 `.node-version` 使用 Node 24）。

```bash
cd website
npm ci
npm run dev       # http://localhost:4321
npm test          # 更新日志与 Release 数据单测
npm run build     # 输出到 dist/
npm run preview   # 预览 dist/
```

构建时会请求 GitHub API 读取最新 Release；设置 `GITHUB_TOKEN` 可避开匿名限流。请求失败时回退到 `src/data/release-snapshot.json`，并在构建日志里打印原因。页面加载后，下载区会在浏览器里再查一次最新 Release，有新版本时就地换成新版本的下载地址。

## 更新内容

- **新版本发布**：更新日志与下载区随下一次构建自动更新。发版后顺手把 `release-snapshot.json` 改成新版本（版本、发布时间、APK 文件名、大小、SHA-256），保证 API 不可用时的回退值不过期。
- **文案**：只改 `src/i18n.mjs`，中英文同步修改。
- **截图与录屏**：素材来自 App 视觉实验室的宣传片工程 `footage/` 目录（不在本仓库）。

```bash
website/scripts/prepare-media.sh <footage 目录>
```

## 部署：Cloudflare Pages

本仓库的 GitHub Pages 已用于托管 App 的签名模型目录（`model-catalog-publish.yml` 整站部署，App 固定读取 `promenar.github.io/Infinity-Workbench/model-catalog/`）。官网不能部署到这里，也不能给它绑定自定义域名，否则模型目录地址会被重定向而失效。官网改由 Cloudflare Pages 托管。

首次配置（Cloudflare 后台，Workers & Pages → Create → Pages → Connect to Git）：

| 设置项 | 值 |
| --- | --- |
| 仓库 | `Promenar/Infinity-Workbench` |
| 生产分支 | `main` |
| Root directory | `website` |
| Build command | `npm run build` |
| Build output directory | `dist` |
| 环境变量（可选） | `GITHUB_TOKEN`：只读公开仓库的细粒度令牌，避开 API 限流 |

建议在 Build watch paths 里只包含 `website/*` 与 `CHANGELOG.md`，其它目录的提交不触发官网构建。

绑定域名：项目 → Custom domains → 添加 `infinityware.work`。域名已在同一 Cloudflare 账户解析，Pages 会自动创建记录并签发证书。如需 `www.infinityware.work`，再添加一次并在 Rules 里重定向到根域名。

发布后：每次推送到 `main` 自动构建；新 Release 不会触发构建，但浏览器端会显示最新版本，下一次构建再写入页面。
