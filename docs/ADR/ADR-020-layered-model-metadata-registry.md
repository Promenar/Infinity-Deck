# ADR-020：分层模型元数据注册中心

> 状态：已实施，Pages 与本机客户端验收通过
> 日期：2026-09-22
> 范围：模型目录、Provider 持久化、身份匹配与独立更新

## 决策

1. 领域入口为 `ModelMetadataResolver.resolve(...)`，逐字段采用用户编辑、实际供应商字段、Infinity Deck 精确修正、匹配作用域的公开目录、保守回退。家族规则只补充展示信息。
2. models.dev 提供规范模型；models.dev provider、LiteLLM、OpenRouter 提供具有明确作用域的供应商条目。不同供应商的额度和能力不能合并成基础模型事实。
3. 原始请求 ID、供应商实例 ID、规范模型 ID 和目录供应商作用域分别保存。只接受精确 ID/别名匹配；未知前缀、版本变体和冲突保持未匹配。仅在列表 owned_by 对应时，newapi/openai-chatgpt 路由前缀可用于生成查找候选；wire ID 保持不变。
4. 工作负载、推理能力和 Chat endpoint 兼容性相互独立。能力使用 SUPPORTED / UNSUPPORTED / UNKNOWN，缺失不等于不支持，也不默认 chat。
5. GitHub Actions 每六小时聚合公开数据，校验后签名发布到 Pages。App 恢复完成后按二十四小时检查，也提供手动更新。APK 内置快照与验签缓存支持离线使用；关闭 App 期间不保证准点检查。
6. Provider 持久化保留供应商描述字段、三态能力、字段来源、诊断、自动指纹和用户编辑字段。目录更新重新解析现有模型，保留用户编辑、启用状态、测试结果、稳定 ID 和供应商原始字段。
7. 首次引导的真实聊天探测只更新 Chat endpoint 兼容性。会话输入区和 AI 尾注共享纯函数名称解析，历史消息优先使用持久化名称；模型名与时间位于完成回复操作行的右侧，长模型名单行截断，时间保持可见，布局遵循[会话视觉规格](../superpowers/specs/2026-10-17-nexara-settings-chat-redesign.md)。

## 信任与回滚

- 公共目录工作流只发布目录和说明。Actions 固定 commit SHA；独立目录签名私钥由 Secret 注入，产品 API Key 和 APK 私钥不进入工作流。
- P-256 / SHA256withECDSA 签署原始 payload 字节，App 固定 SPKI 公钥。验签后检查 schema、文件名、字节数、SHA-256、记录数和单调版本；同版本不同内容也拒绝。
- 版本文件落盘后原子提交缓存指针。更新失败保留现用目录；当前文件损坏可回退上一有效版本，但版本高水位不降低。缓存位于 noBackupFilesDir。
- 发布纠错以更高版本重新签名已知有效数据。网络故障或必需来源异常不发布残缺目录。内置快照刷新工作流保留手动维护入口。
- 协议与验收见[规范](../superpowers/specs/2026-09-22-independent-model-catalog.md)，操作见[目录运行说明](../model-catalog.md)，交付证据见[独立目录验收记录](../release/2026-09-22-model-catalog-validation.md)。

## 结果与边界

目录更新独立于 APK，无需常驻服务器或新后台服务。来源声明不等于实际端点测试通过，也不保证全网新模型都有完整字段；未匹配或缺失字段明确保持未知。

## 历史验证（2026-07-18，不能替代独立更新验收）

- Task 1-9 均完成独立 Terra/Sol 复审；Task 9 最终限定 diff SHA-256 为 `4414db3077a0d004f22920c25d681f72a1d7d70b65e25f74f1968d8149189020`。
- Python 更新脚本 64/64、离线 snapshot/manifest check、Kotlin catalog 26/26 通过。
- 当前工作树定点模型 JVM 89/89；全量 JVM 1945 项，0 failure/error、14 skip；Lint 0 Error/Fatal；Screenshot 61/61；AndroidTest Kotlin 编译通过。
- API 35 与 API 36 聚焦设备矩阵各 31 项，均 0 failure/error、1 个设计内 force-stop checkpoint skip。
- 真实 Provider 当前候选复验未执行，原因是六项 `INFINITY_TEST_LLM_*` 环境变量均未提供；默认测试跳过不能作为网络证据。
