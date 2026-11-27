# 模型目录维护与客户端更新

## 使用方式

模型管理页的“更新目录”刷新公共元数据，“同步模型”从当前供应商读取可用模型与供应商字段。已有用户名称、类型、能力与额度编辑继续保留；目录更新不自动启用、删除模型或发起推理。

App 恢复完成后检查更新，成功检查间隔二十四小时；手动操作可提前检查。关闭 App 时不要求后台联网。更新失败保留当前目录，首次离线启动使用内置 models.dev 快照；有效缓存位于 noBackupFilesDir，重启重新验签。

云端每六小时执行“发布独立模型目录到 GitHub Pages”，使用标准 GitHub Linux Runner 和 Pages 静态托管。公开仓库的免费配额适合当前目录规模，无需购买服务器或域名。定时事件可能延迟或因仓库长期无活动停用，应以工作流结果和目录时间判断新鲜度。[Actions 定时说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

## 身份与来源

LiteLLM 的 `alias_of` 条目作为目标模型的精确别名发布，沿用目标模型的供应商作用域和元数据。目标必须是同批数据中的普通模型条目，普通模型必须声明有效的 `litellm_provider`。

规范模型来自 models.dev；供应商条目来自 models.dev provider 数据、LiteLLM 和 OpenRouter。采集器由一手官方作用域、OpenRouter 与 LiteLLM 对全局记录缺失字段执行安全回填（含跨源共识结构化输出、提示缓存、联网搜索、电脑操控、PDF 输入与废弃日，并对知识截止日期施加一手白名单与发布日一致性校验），将无歧义一手独占模型同步晋升至全局层，且音频模型直接细分为 `SPEECH`、`MUSIC` 与 `REALTIME` 发布。记录总量包含供应商销售条目，不等于基础模型数量。每批记录附带来源、抓取时间、原文摘要和许可说明。主流新模型或重排（`RERANK`）模型缺席规范目录时，可加入带官方证据的受控补充（`scripts/model-catalog/official-model-overrides.json`）；普通条目在上游出现同一规范 ID 后自动停用，重排补充条目需待上游完整接管 `RERANK` 工作负载、上下文窗口及全部别名后再自动退让，避免上游仅收录残缺信息时提前覆盖。

供应商实例 ID、规范模型 ID、目录作用域与请求模型 ID 分别保存。客户端根据供应商主机自动推断一手目录作用域链（`inferCatalogSourceProviderScopes`），优先匹配对应一手作用域并与全局规范条目合并补齐发布日期、废弃日期与能力位，中转站与自定义兼容网关保持全局视图。比如 `newapi/deepseek-v4-flash` 只有在列表明确返回对应网关 owned_by 时才允许用后缀查找；SiliconFlow 等网关的 `Pro/<org>/<model>` / `Free/<org>/<model>` 计费层级前缀在后接两段式 `<org>/<model>` 时按大小写不敏感剥离前缀查表，发送时仍保留完整字符串。未知路由前缀与冲突保持未匹配。来源声明的能力和额度不代表当前账号或聚合通道一定支持。

## 信任与发布

固定入口是 `https://promenar.github.io/Infinity-Deck/model-catalog/v1/manifest.json`。Base64 payload 使用独立 P-256 密钥签名，App 固定公钥验证 SHA256withECDSA DER 签名。客户端拒绝重定向、任意路径、签名/哈希错误、超限内容、无效 schema、旧版本回放和同版本内容替换。

仓库 Secret `INFINITY_MODEL_CATALOG_SIGNING_KEY` 保存目录私钥，keyId 为 `infinity-catalog-p256-20260922`。该私钥与 Android 签名分开，禁止提交、复制到公共产物或在日志打印。只有经过测试与归一化的公开数据进入 Pages。

密钥轮换需要先发布包含新公钥的兼容 App，再切换签名 keyId；仅改变 Secret 会使旧客户端拒绝目录。不能通过远端下发公钥绕过固定信任锚。

## 故障与纠错

1. 检查工作流中的来源下载、Python 测试、归一化、签名和部署步骤。必需来源失败时保留原部署，不将空目录或部分来源标为成功。
2. 修复来源或映射错误后，以更高 catalogVersion 发布纠正数据。不可复用版本修改内容或接受低版本回放。
3. App 下载或校验失败时现有目录仍可用；若目录已缓存但模型投影失败，界面单独提示，重试会重新应用缓存。不能通过清理用户模型配置或覆盖手动编辑来规避错误。
4. 内置快照由 `Model Catalog Refresh` 手动工作流维护；独立数据更新无需合并快照 PR 或发布 APK。

## 验证记录

入口：[实施计划](superpowers/plans/2026-09-22-independent-model-catalog.md)、[协议规范](superpowers/specs/2026-09-22-independent-model-catalog.md)、[ADR-020](ADR/ADR-020-layered-model-metadata-registry.md)。已上线目录、源码绑定、测试统计和签名 APK 见[验收记录](release/2026-09-22-model-catalog-validation.md)。
