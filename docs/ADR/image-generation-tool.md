# ADR-003：图像生成工具

**日期**：2026-05-14

**更新日期**：2026-11-04

**状态**：已采纳

## 目标与调用链

`generate_image` 实现为 `SkillDefinition`，通过 ToolExecutor 的工具调用管道生成或编辑图片，保存到会话当前工作区。未选择图像模型时，工具从模型可用列表中隐藏，设置页保留登记。

图像模型由 `ProviderManager.imageModelId` 独立选择。执行时经 `ProviderRequestRouter.requireModelRoute(...).requireOpenAiModelRoute()` 一次性解析提供商地址、凭据和远端模型 ID，再创建 `ImageGenClient`；路由错误返回明确说明。

## 参数与校验

工具使用 GPT 图像模型的参数语义，下表可选字段只在调用方明确提供时发送，服务端默认值不写入请求。审核档位例外：它不由 Agent 填写，客户端固定发送最低档（见“审核档位”）。

| 参数 | 行为与本地约束 |
|---|---|
| `prompt` | 必填非空字符串，最多 32000 字符 |
| `size` | 字符串，默认 `auto`；标准值为 `1024x1024`、`1536x1024`、`1024x1536`；自定义 `WIDTHxHEIGHT` 的宽高为正的 16 倍数，宽高比 1:3–3:1，最长边 3840，总像素最多 3840×2160。自定义尺寸支持依模型而定，超过 2560×1440 属实验性 |
| `quality` | `auto` / `low` / `medium` / `high` / `xhigh` / `max`；默认 `auto`，`xhigh` 与 `max` 仅部分模型支持 |
| `background` | `transparent` / `opaque` / `auto`；默认 `auto`，透明背景要求 PNG 或 WebP |
| `output_format` | `png` / `jpeg` / `webp`；默认 PNG |
| `output_compression` | 0–100 的整数，须同时显式指定 JPEG 或 WebP 输出 |
| `n` | 1–4 的整数；移动端每次最多 4 张，服务端默认 1 |
| `images` | 1–16 个工作区路径；提供时调用编辑接口；PNG/WebP/JPEG，每张小于 50 MiB |
| `mask` | 仅与 `images` 同用；工作区 PNG 路径，小于 4 MiB，作用于第一张图片；元数据提供尺寸时检查二者相同 |
| `markup` | 仅与 `images` 同用，不能与 `mask` 同用；工作区 PNG 路径（透明底、画有圈注与文字的标注图层），小于 4 MiB，尺寸须与第一张图片相同，无法确认尺寸时拒绝。仅 GPT Image 2.5 支持；服务端不支持时，Agent 按标注行说明去掉该参数，把修改要求写进提示词 |
| `input_fidelity` | 仅编辑可用，`high` / `low`，默认省略；仅部分模型支持，`gpt-image-2` 应省略 |

## 审核档位

官方 `moderation` 取 `low`（较宽松过滤）或 `auto`（默认）。客户端在生成与编辑请求中固定发送 `moderation=low`，不进入工具 schema，Agent 无法改动；模型 ID 含 `dall-e` 时不发送。兼容网关不认识该字段时，返回 400/422 且错误体提及 `moderation`（不含 `moderation_blocked` 与 `safety`），客户端去掉该字段重发一次；内容被安全系统拦截照常报错，不重发。

参数枚举、类型、尺寸格式、数量、格式组合和文件限制在发请求前校验，失败返回 `status="error"` 及允许值。模型对具体尺寸、质量和保真度的支持由服务商进一步校验。

## HTTP 协议

- 文本生成：`POST {base}/v1/images/generations`，JSON。
- 图片编辑：`POST {base}/v1/images/edits`，`multipart/form-data`。每张图片使用重复的 `image[]` 部分；标注图层为文件部分 `markup`（文件名 `markup.png`，审核字段降级重发时保留）；蒙版部分名为 `mask`，保真度为 `input_fidelity`，审核档位为 `moderation`。其它显式参数作为文本部分，图片部分携带由内容识别的 MIME 与对应扩展名。
- `{base}` 已以 `/v1` 结尾时直接追加 `/images/...`。
- 两种请求共用 `applyModelTimeout(ModelTimeoutKind.IMAGE)`、取消传播、HTTP 错误摘要和响应解析。
- 响应读取 `data[].b64_json`，同时兼容历史服务商返回的 `url` 与 `revised_prompt`；保留顶层 `created`、`size`、`quality`、`background`、`output_format`，以及 `usage` 的三项 token 计数与输入／输出 token 详情对象。

官方多图字段名以 [OpenAI 图像生成指南](https://developers.openai.com/api/docs/guides/image-generation#edit-images) 的 curl 示例核对；参数来源为 [生成接口参考](https://developers.openai.com/api/reference/resources/images/methods/generate) 与 [编辑接口参考](https://developers.openai.com/api/reference/resources/images/methods/edit)。

## 输入读取与输出存储

`ImageGenerationInputReader` 复用 `view_media` 的 `WorkspaceMediaResolver.resolve/open`，沿用工作区路径、挂载授权、符号链接及来源身份检查。解析失败时不打开字节流，不发送 HTTP 请求。读取后按文件头识别 PNG/WebP/JPEG，读取过程再次检查大小并响应取消，防止文件元数据陈旧导致越过上限。

生成图片经 `WorkspaceMediaStore` 保存，默认目录为 `media/<yyyy-MM-dd>/`。Base64 图片的 MIME 提示按响应 `output_format`、请求格式、字节魔数的顺序选择。存储层使用文件头复核 MIME，按实际 MIME 生成扩展名；URL 结果沿用媒体下载器与相同存储流程。

`ToolResult.media` 保存 `MediaRef` 引用（工作区路径、MIME、类型、字节数、哈希及像素尺寸）。`content` 每张成功图片一行，只给一次 Markdown 引用写法，逐项说明保存失败与修订提示词。

## 界面数据契约

`data/model/ImageGenerationResultData.kt` 定义 `@Serializable` 结构，`ImageGenerationResultData.decode(result.data)` 在内容损坏、缺少版本标记或版本未知时返回 null。`encode()` 省略所有 null 字段。

| 字段 | 含义 |
|---|---|
| `schema` | 固定 `infinity-image-generation-v1` |
| `mode` / `model` | `generate` 或 `edit`；路由解析后的远端模型 ID |
| `requested` | 调用方显式提供的 `size`、`quality`、`background`、`outputFormat`、`outputCompression`、`n` |
| `actual` | 服务端回显的 `size`、`quality`、`background`、`outputFormat` |
| `inputImages` / `mask` / `markup` | 解析后的工作区输入路径、可选蒙版路径与可选标注图层路径 |
| `usage` | 可选 `inputTokens`、`outputTokens`、`totalTokens` |
| `revisedPrompts` / `failedCount` | 去重的修订提示词、保存失败数；空图片响应计为一次失败 |

新数据的字段名避开历史图片解析器识别的 `localPath`、`url`、`b64Json`。图片展示继续使用 `media`，卡片可独立读取结构化参数和回显信息。数据库沿用已有工具结果载荷。

## 画板入参

用户在会话画板中得到三类入参，均由 `ImageAnnotationEditor` 按原图像素导出：

| 画法 | 入口 | 图层 | 交给工具的方式 |
|---|---|---|---|
| 标注 `markup` | 查看器“标注”、点输入框图片 | 透明底 PNG，与原图同尺寸 | `images=[原图]` + `markup=图层` |
| 选区 `mask` | 同上，画板内切换 | 涂抹处透明、其余不透明黑 | `images=[原图]` + `mask=图层` |
| 草图 `sketch` | 附件菜单“手绘草图” | 白底草图，比例 1:1 / 2:3 / 3:2 / 9:16 | `images=[草图]`，`size` 取画布尺寸 |

点“完成”后，`ChatViewModel.saveImageAnnotation` 把原图（来自输入框时先规整为长边不超过 2048 的 PNG）与图层存入当前会话工作区，另写一张长边不超过 1568 的合成预览放进输入框。预览的私有 PNG 块 `inAn` 记录画法、尺寸、图层与原图路径、可再次编辑的笔画快照，以及来源会话、工作区、运行模式和 worktree 的绑定。发送时读取该块，写入 `Message.userImageAnnotations` 与 `userImageAnnotationContext`（保存在既有 `files` 信封中，无需数据库迁移），预览剥离私有块后按 JPEG 内联给对话模型。`UserAttachmentModelContent` 为当前轮与历史轮输出 `[INFINITY_IMAGE_ANNOTATION …]` 行，列出图层与原图路径及用法；来源绑定与当前会话不符时只输出失效说明，需要在当前会话重新打开画板。

预览文件保存在应用私有目录 `image_annotation_previews/` 下按会话分组，七天后在后续保存时修剪，输入框、恢复草稿、提交草稿与队列仍引用的文件不删除。画板对象与笔画随界面重建（折叠屏开合）保存，快照超过 20 万字符时不保存笔画。

## 验收入口与边界

协议与校验测试位于 `ImageGenClientProtocolTest`、`ImageGenerationRequestTest`、`ImageGenerationInputReaderTest`、`ImageGenerationResultDataTest`，工作区集成与取消行为位于 `ImageGenerationWorkspaceTest` 和 `ImageGenClientCancellationTest`。主控统一执行编译、单元测试与设备验收。

编辑请求将输入图片字节保存在内存中，大量接近上限的参考图会提高峰值内存；不同兼容网关的 multipart 支持、模型参数支持和真实出图需要各自验证。
