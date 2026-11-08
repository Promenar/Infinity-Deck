# ADR-005: InfinityPageLayout 架构重构与键盘避让策略

## 状态
已接受 (Accepted)

## 上下文 (Context)
在 Infinity Deck 的原生 Android 开发过程中，表单输入页面（如 `ProviderFormScreen`）面临两个严峻问题：
1. **键盘遮挡**: 底部输入区域在软键盘弹出时无法自动避让，导致用户无法看到输入内容或点击保存按钮。
2. **测量冲突 (IllegalStateException)**: 在根容器全局应用 `Modifier.imePadding()` 时，若内容包含 `LazyColumn` 或 `verticalScroll`，会导致滚动组件被测量为无限高度，触发 Compose 崩溃。

之前的方案试图通过手动计算 `Column` 布局来解决，但由于缺乏对 `WindowInsets` 的标准化处理，导致代码冗余且鲁棒性差。

## 决策 (Decision)
为了彻底解决这些布局痛点，我们决定对 `InfinityPageLayout` 进行架构级重构：

1. **引入 Scaffold**: 将根容器从 `Column` 迁移至 `androidx.compose.material3.Scaffold`。利用 `Scaffold` 默认的 `contentWindowInsets` 处理能力，自动管理状态栏与导航栏空间。
2. **局部按需避让**: 移除根容器的全局 `imePadding`。在 `Scaffold` 的内容回调中，显式提供一个 Column 作为内容承载器，并根据 `imePadding` 参数（默认为 true）应用避让。
3. **强制高度约束**: 在 `InfinityPageLayout` 内部的 `Scaffold` 内容区中，对可滚动的核心容器应用 `Modifier.weight(1f)`。这确保了即便处于 `Scaffold` 的布局槽位中，滚动组件也会被分配有限的最大高度，从根本上杜绝无限高度测量崩溃。
4. **参数化配置**:
    - `scrollable`: 控制 `InfinityPageLayout` 是否自带外层 `verticalScroll`。
    - `imePadding`: 控制是否在该页面应用键盘避让逻辑。

## 后果 (Consequences)
- **正面影响**:
    - 表单页面现在能完美响应键盘弹出，自动推升输入框。
    - 杜绝了 `IllegalStateException` 崩溃。
    - 代码更加符合 Material 3 最佳实践，易于维护。
- **负面影响**:
    - 现有页面需要移除冗余的 `navigationBarsPadding()` 或 `imePadding()` 以避免双重间距。
    - 开发者在自定义 `Scaffold` 内容时，需注意 `innerPadding` 的传递。

## 参与者
- Antigravity (AI Coding Assistant)
- Narcis (Lead Developer)
