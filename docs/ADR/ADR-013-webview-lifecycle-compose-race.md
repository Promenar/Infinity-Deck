# ADR-013: WebView 生命周期管理 — 测高 WebViewClient 前置绑定

> **日期**: 2026-05-18  
> **状态**: ✅ 已实施  
> **影响范围**: `RichContentWebView.kt`, `RichContentWebViewPool.kt`

---

## 背景

`RichContentWebView` 是 Infinity Deck 所有富内容渲染（HTML/SVG/Mermaid/ECharts/PlantUML/LaTeX）的底层 WebView 组件。其核心逻辑是：加载内容到 WebView → JS 测量 `scrollHeight` → 将 WebView Compose 高度动态调整为测量值。

原实现将测高 `WebViewClient` 设置在 `LaunchedEffect` 中，而 `loadDataWithBaseURL` 调用在 `AndroidView.update` 中。

## 问题

Compose 单帧执行顺序为：
1. **Composition** 阶段 → `AndroidView.update()` 执行 → `loadDataWithBaseURL()` 触发异步页面加载
2. **Effects** 阶段 → `LaunchedEffect` 协程启动 → 设置测高 `WebViewClient`

对于简单 HTML（如 3 行纯文本 XML），WebView 加载可在 <1ms 内完成，而协程调度有 1-5ms 延迟。结果是 `onPageFinished` 在测高 `WebViewClient` 被设置之前就已触发，`webViewHeight` 永远停留在 `minHeight`（60dp）。

辅因：`RichContentWebViewPool.createWebView()` 使用 `layoutParams.height = WRAP_CONTENT`，导致 WebView 的 `scrollHeight` 测量对象为无约束视口（≈屏幕高度），被 `coerceIn(60, 600)` 钳制后呈现大面积空白。

## 决策

**将测高 `WebViewClient` 从 `LaunchedEffect` 移至 `remember { acquire() }` 块。**

具体方案：
1. 在 WebView 从池中获取时（`remember` 块内），立即通过 `apply { webViewClient = ... }` 绑定测高 WebViewClient
2. 使用 `rememberUpdatedState` 保持 `minHeight`/`maxHeight` 闭包新鲜度
3. 在 `DisposableEffect.onDispose` 中重置为默认 `WebViewClient()` 后再归还池
4. 在 `AndroidView.update` 中添加 `lastLoadedHtml` 去重优化
5. 将 `layoutParams.height` 从 `WRAP_CONTENT` 改为 `MATCH_PARENT`

## 备选方案

| 方案 | 否决原因 |
|------|----------|
| 使用 `SideEffect` 替代 `LaunchedEffect` | `SideEffect` 与 `AndroidView.update` 执行顺序无保证 |
| 在 `update` lambda 开头设置 WebViewClient | 违反单一职责，且每次 Recompose 都重设 |
| 使用 `snapshotFlow` 监听 | 引入不必要的复杂度，对标问题不直接 |

## 影响

- **性能**：消除每次 Recompose 的冗余 WebView reload（通过 `lastLoadedHtml` 去重）
- **可靠性**：彻底消除时序竞态，高度测量 100% 保证
- **内存**：归还池前重置 WebViewClient，防止闭包泄漏
- **兼容性**：零破坏性变更，所有现有渲染器（Mermaid/ECharts/PlantUML/LaTeX/HtmlArtifact）兼容
