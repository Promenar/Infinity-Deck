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
- **截图与录屏**：素材来自 App 视觉实验室的宣传片工程 `footage/` 目录（不在本仓库）。页面里的媒体地址带内容哈希版本号（`src/lib/asset.mjs`），替换文件后重新发布即可，CDN 与浏览器不会继续用旧文件。

```bash
website/scripts/prepare-media.sh <footage 目录>
```

## 部署：Cloudflare Pages

本仓库的 GitHub Pages 已用于托管 App 的签名模型目录（`model-catalog-publish.yml` 整站部署，App 固定读取 `promenar.github.io/Infinity-Workbench/model-catalog/`）。官网不能部署到这里，也不能给它绑定自定义域名，否则模型目录地址会被重定向而失效。官网由 Cloudflare Pages 托管。

| 项目 | 值 |
| --- | --- |
| Pages 项目 | `infinity-workbench`（直接上传方式，生产分支 `main`） |
| 默认地址 | <https://infinity-workbench.pages.dev> |
| 自定义域名 | <https://infinityware.work>，解析为代理的 CNAME → `infinity-workbench.pages.dev` |

### 发布

在本机构建后用 wrangler 上传。令牌需要账户级 Cloudflare Pages 编辑权限，经环境变量提供给 wrangler，不写进脚本或提交：

```bash
cd website
npm run build
CLOUDFLARE_API_TOKEN=… CLOUDFLARE_ACCOUNT_ID=… \
  npx wrangler@4.152.0 pages deploy dist --project-name infinity-workbench --branch main \
  --commit-hash "$(git rev-parse HEAD)" --commit-message "<说明>"
```

`--branch main` 发布到生产域名；换成其它分支名会生成独立的预览地址，不影响线上。新 Release 发布后，下载区会在浏览器端自动显示新版本；重新发布一次即可把新版本写进页面与更新日志。

### 改为推送自动构建

直接上传方式的项目不能改成连接 Git。需要自动构建时，二选一：

- 在仓库加 GitHub Actions 工作流，`main` 上 `website/` 或 `CHANGELOG.md` 变化时执行上面的构建与上传，令牌存为仓库密钥。
- 在 Cloudflare 后台新建连接 Git 的 Pages 项目（Root directory `website`，Build command `npm run build`，输出目录 `dist`，Build watch paths 只含 `website/*` 与 `CHANGELOG.md`），再把 `infinityware.work` 从旧项目移到新项目。
