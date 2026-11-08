# 模型目录维护与客户端更新

## 使用方式

模型管理页的“更新目录”刷新公共元数据，“同步模型”从当前供应商读取可用模型与供应商字段。已有用户名称、类型、能力与额度编辑继续保留；目录更新不自动启用、删除模型或发起推理。

App 恢复完成后检查更新，成功检查间隔二十四小时；手动操作可提前检查。关闭 App 时不要求后台联网。更新失败保留当前目录，首次离线启动使用内置 models.dev 快照；有效缓存位于 noBackupFilesDir，重启重新验签。

云端每六小时执行“发布独立模型目录到 GitHub Pages”，使用标准 GitHub Linux Runner 和 Pages 静态托管。公开仓库的免费配额适合当前目录规模，无需购买服务器或域名。定时事件可能延迟或因仓库长期无活动停用，应以工作流结果和目录时间判断新鲜度。[Actions 定时说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

## 身份与来源

规范模型来自 models.dev；供应商条目来自 models.dev provider 数据、LiteLLM 和 OpenRouter。记录总量包含供应商销售条目，不等于基础模型数量。每批记录附带来源、抓取时间、原文摘要和许可说明。主流新模型缺席规范目录时，可加入带官方证据的受控补充；上游出现同一规范 ID 后自动停用该补充，避免人工条目长期覆盖新数据。

供应商实例 ID、规范模型 ID、目录作用域与请求模型 ID 分别保存。比如 `newapi/deepseek-v4-flash` 只有在列表明确返回对应网关 owned_by 时才允许用后缀查找；发送时保留完整字符串。未知路由前缀与冲突保持未匹配。来源声明的能力和额度不代表当前账号或聚合通道一定支持。

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
