## Material Symbols Rounded 头像符号

- 来源：[google/material-design-icons，提交 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7)，Apache-2.0，版权 Google LLC；[完整许可](licenses/material-symbols-Apache-2.0.txt)。
- 打包形式：按 390 个码位子集化并固定轴（FILL=1、wght=500、GRAD=0、opsz=24）的 `native-ui/app/src/main/res/font/infinity_avatar_symbols.ttf`，保留 name 表版权与许可条目；许可全文另随 APK 存放于 `assets/licenses/material-symbols-LICENSE.txt`。来源哈希与重新生成方法见 `scripts/avatar/SOURCE.md`。

## QuickJS 脚本引擎

- Android 依赖：`io.github.dokar3:quickjs-kt-android:1.0.15`；JVM 测试使用同版本 `quickjs-kt-jvm`，不打入 APK。
- 绑定库来源：[dokar3/quickjs-kt v1.0.15](https://github.com/dokar3/quickjs-kt/tree/v1.0.15)，Apache-2.0；[完整许可](licenses/quickjs-kt-1.0.15-Apache-2.0.txt)。
- 上游内嵌引擎：[bellard/quickjs，提交 04be246001599f5995fa2f2d8c91a0f198d3f34c](https://github.com/bellard/quickjs/tree/04be246001599f5995fa2f2d8c91a0f198d3f34c)，MIT，版权 Fabrice Bellard、Charlie Gordon；[完整许可](licenses/quickjs-MIT.txt)。
- 上游内嵌容器：[eteran/c-vector，提交 774773d4cb1e66dd736e90e7f482d4f22af464c6](https://github.com/eteran/c-vector/tree/774773d4cb1e66dd736e90e7f482d4f22af464c6)，MIT，版权 Evan Teran；[完整许可](licenses/c-vector-MIT.txt)。子模块身份从绑定库的固定版本树核验。
- 保留各库原许可、版权及免责条款。Apache-2.0 可纳入 GPL-3.0 项目，参见 [Apache 官方兼容性说明](https://www.apache.org/licenses/GPL-compatibility.html)。Chaquopy 未纳入产品分发。

## models.dev 第三方归属与许可

### 上游项目

- 名称：models.dev
- 来源：`https://models.dev/models.json`
- 源码仓库：`https://github.com/anomalyco/models.dev`
- 许可：MIT

### 引用与快照凭据

- 真实抓取与快照元数据以离线清单为准：`native-ui/app/src/main/assets/model-catalog/manifest.json`
- 该文件字段 `fetchedAt`、`sourceSha256`、`recordCount`、`catalogSha256`、`sourceBytes` 为本次冻结来源与校验锚点。
- 快照文件：`native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json`
- 目录用途：运行时离线加载与模型能力覆盖基线，不对外提供签名后 API 语义保证。

### Copyright Notice

`models.dev` 上游文件受 MIT 协议约束，版权声明如下：

```
MIT License

Copyright (c) 2025 models.dev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 交付边界（Infinity Deck 责任说明）

- Infinity Deck 会基于 `models.dev` 上游快照叠加厂商元数据与本地修正。
- Infinity Deck 不保证该快照目录单独构成完整真实权威模型库。
- 规范数据与供应商条目经归一化、签名后通过 GitHub Pages 分发；来源与许可随目录保存，客户端保持验签与离线回退。

---

## Bettbox Material 3 界面参考与转译

### 上游项目

- 名称：Bettbox
- 上游仓库：`https://github.com/appshubcc/Bettbox`
- 本地固定来源提交：`714130a3d746d093f6278c0503dac9e2e43fd3e3`
- 许可：GNU General Public License v3.0
- Infinity Deck 许可：GNU General Public License v3.0，与上游许可兼容

### 转译范围

Infinity Deck 没有直接引入 Bettbox 的 Flutter/Dart 构建产物，而是将以下界面结构和交互语言按 Jetpack Compose 重新实现：

- `lib/widgets/list.dart`：标准 ListTile 节奏、20dp 圆角填充分组、弱化的组内分隔；
- `lib/widgets/google_bottom_nav_bar.dart`：36dp 浮动导航座、弱描边与阴影、选中容器、250ms 状态过渡；
- `lib/views/theme.dart`：三模式选择、种子色盘、纯黑模式与全局文字缩放；
- `lib/common/color.dart` 与 `lib/providers/state.dart`：种子色和纯黑表面的状态映射思路。

### 交付边界

- 业务模型、数据层、导航目的地、无障碍语义与 Android 触感仍由 Infinity Deck 自身实现负责；Flutter 特有依赖没有进入 Android 运行时。
- 固定提交只用于复现本次视觉基线；后续上游变化不会自动改变 Infinity Deck 的界面行为。
- GPLv3 完整条款见仓库根目录 `LICENSE`。


## 独立模型目录新增来源

- LiteLLM 根目录模型价格与上下文文件：MIT，Copyright (c) 2023 Berri AI；不包含 enterprise/ 内容。[上游许可](https://github.com/BerriAI/litellm/blob/main/LICENSE)。
- OpenRouter 公共模型 API：按[OpenRouter 服务条款](https://openrouter.ai/terms)使用，不能将其数据声明为 MIT。公开事实经过结构化筛选，不含账号凭据或用户请求。
- Google Gemini 官方模型文档：CC BY 4.0；补充文件记录来源与核验日期，Infinity Deck 将公开规格转换为结构化字段。[Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)。
- SenseNova 官方 API 文档：仅整理模型 ID、公开模态与推理事实并保留来源，不复制文档长段原文；仓库未声明统一再发布许可。[官方文档](https://github.com/OpenSenseNova/SenseNova6.8/blob/main/API_CN.md)。

发布产物附带 `third-party-notices.txt`，保留 models.dev 与 LiteLLM 的完整 MIT 许可及上述出处。抓取时点和摘要以该次签名目录的 sources 为准；人工补充在规范源接管后自动停用。

## 原生 SSH 与终端

- [mwiede/jsch 2.28.8](https://github.com/mwiede/jsch/tree/jsch-2.28.8)：SSH、SFTP、PTY 与本地转发。主库为三条款 BSD；内嵌 JZlib 为 BSD、jBCrypt 为 ISC。完整许可与版权归档在 [ssh/jsch-LICENSE.txt](ssh/jsch-LICENSE.txt)、[JZlib](ssh/jsch-LICENSE.JZlib.txt)、[jBCrypt](ssh/jsch-LICENSE.jBCrypt.txt)。
- [Bouncy Castle bcprov-jdk15to18 1.86](https://www.bouncycastle.org/download/bouncy-castle-java/)：SSH 仅使用 lightweight 密码学路径补足 Android API 31 的 Ed25519/X25519，不注册或替换系统 Provider；既有 PDFBox 的 bcpkix-jdk15to18、bcutil-jdk15to18 同步统一到 1.86，避免重复类及跨版本 API 混用。许可采用 Bouncy Castle/MIT 条款，完整声明见 [ssh/bouncycastle-LICENSE.txt](ssh/bouncycastle-LICENSE.txt)。
- [Termux terminal-emulator / terminal-view 0.118.0](https://github.com/termux/termux-app/tree/v0.118.0)：使用终端仿真器和渲染器连接远端 SSH PTY；侧载渠道另以其 `TerminalSession` 与 JNI 库 `libtermux.so` 驱动内置 Linux 环境的本地 PTY，Play 渠道不打包该原生库。`libtermux.so` 由仓库内原样保存的上游 `terminal-emulator/src/main/jni/termux.c`（v0.118.0，见 `native-ui/app/src/main/cpp/termux/README.md`）以 16 KB 页对齐重新编译，未修改源码。上游为 GPLv3-only，包含 Apache-2.0 来源例外，见 [ssh/termux-LICENSE.md](ssh/termux-LICENSE.md) 与[上游源码](https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator)。项目 GPL-3.0 许可允许该组合。
- [Apache MINA SSHD 2.16.0](https://github.com/apache/mina-sshd/tree/sshd-2.16.0)：Apache-2.0，仅用于 JVM 测试中的内嵌 SSH/SFTP 服务端，不进入应用运行时。

SSH 与终端依赖保持版本固定；终端原生进程桥接库只用于侧载渠道的内置 Linux 终端，不属于远端 PTY 的执行路径。许可原文按上游保留，中文说明不替代许可条款。

## 等宽字体

- [JetBrains Mono 2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304)：取官方发行包 `JetBrainsMono-2.304.zip`（SHA-256 `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`）中的无连字版 `JetBrainsMonoNL-Regular.ttf` 与 `JetBrainsMonoNL-Bold.ttf`，原样打包为 `res/font/jetbrains_mono_nl_regular.ttf`（SHA-256 `fb3b2575d7b0657359707993288f12a7360344d39387bb26050e276d61f6bd2a`）与 `jetbrains_mono_nl_bold.ttf`（SHA-256 `0198e841824025f8876e5c297f0b9b497ee8d6eb9969710a3328e1303f996ec3`），两个渠道均包含，用于终端、命令输出、代码块与标识符。SIL Open Font License 1.1，Copyright 2020 The JetBrains Mono Project Authors，无保留字体名；字体文件的 name 表保留版权与许可信息，许可原文见 [fonts/JetBrainsMono-OFL.txt](fonts/JetBrainsMono-OFL.txt)。OFL 允许字体随软件捆绑分发，字体本身仍按 OFL 授权，不并入 GPL；未做子集化或其它修改。

## 内置 Linux 环境（仅侧载渠道）

以下组件只随侧载 APK 分发，版本与摘要固定在 `native-ui/app/linux/linux-runtime.properties`，构建时从上游下载并校验 SHA-256：

- [termux/proot](https://github.com/termux/proot/tree/b27d81a68d765f3462e807f4a14e01a03f8e89ea)（提交 b27d81a6）：编译为 `libnxproot.so` 与 `libnxproot-loader.so`，以独立进程运行。GPL-2.0-or-later。
- [talloc 2.4.4](https://www.samba.org/ftp/talloc/talloc-2.4.4.tar.gz)：只编译 `talloc.c`，静态链接进 proot。LGPL-3.0-or-later。
- [Alpine Linux 3.24.3 aarch64 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/)：官方归档原样打包为 `assets/linux/alpine-minirootfs.tar.gz.bin`，安装时在应用私有目录解包。其中 BusyBox、apk-tools 为 GPL-2.0，musl 为 MIT，其余软件包按各自许可；对应源码见 [Alpine aports](https://gitlab.alpinelinux.org/alpine/aports) 中 3.24 分支与 [Alpine 源码发布](https://dl-cdn.alpinelinux.org/alpine/)。用户之后经 apk 安装的软件包直接取自所选镜像源，不随 Infinity Deck 分发。

上述 GPL-2.0 组件以独立进程或独立文件系统映像形式运行，与 Infinity Deck 主程序通过进程参数和文件交互。对外发布侧载 APK 时，应按 GPL 要求随附或书面提供对应源码（上游源码地址与固定版本见上文及属性文件），本节不构成合规结论。

## 内置 Tailscale（tsnet）

- [tailscale/tailscale v1.104.1](https://github.com/tailscale/tailscale/tree/v1.104.1)：`tsnet` 节点、本地 API 客户端、`proxymux` 与 `feature/androidbin`，编译进 `libnxtailnet.so`（App 子进程，不占用系统 VPN）。三条款 BSD，Copyright (c) 2020 Tailscale Inc & contributors；完整许可见 [tailnet/tailscale-LICENSE.txt](tailnet/tailscale-LICENSE.txt)。
- Go 1.27.1 标准库与运行时（三条款 BSD），以及经 `native-ui/app/tailnet/go.sum` 固定、实际链接的依赖：golang.org/x/crypto、net、sys、sync、term、text、time、oauth2、exp（三条款 BSD）；tailscale/wireguard-go、mdlayher/netlink 与 socket、jsimonetti/rtnetlink、fxamacker/cbor、x448/float16、gaissmai/bart、mitchellh/go-ps（MIT）；gvisor.dev/gvisor、klauspost/compress、google/btree、golang/groupcache、go4.org/mem、pires/go-proxyproto、safchain/ethtool（Apache-2.0）；coder/websocket（ISC）；godbus/dbus、huin/goupnp（两条款 BSD）；filippo.io/edwards25519、hdevalence/ed25519consensus、creachadair/msync、go-json-experiment/json、go4.org/netipx、tailscale/hujson、peercred、web-client-prebuilt（三条款 BSD）。各模块版本与许可原文汇总在 [tailnet/go-modules-LICENSES.txt](tailnet/go-modules-LICENSES.txt)。
- 上述许可均可与 GPL-3.0 组合分发（Apache-2.0 兼容 GPLv3）。升级 Go 依赖时以 `go version -m libnxtailnet.so` 重新核对链接清单并更新许可汇总。


## 录音听写与 GGUF

- [Sherpa-ONNX v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) 为 Apache-2.0，Android AAR 包含 [ONNX Runtime v1.28.2](https://github.com/microsoft/onnxruntime/tree/v1.28.2)（MIT）。原始许可和 ONNX Runtime 第三方声明随 APK 存放于 `assets/licenses/`。
- [X-ASR 480ms 中英标点流式 Zipformer2](https://github.com/Gilgamesh-J/X-ASR) 权重采用 Apache-2.0，由 [sherpa-onnx asr-models 发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)分发 `sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2`。APK 随包分发从该模型导出的 `bpe.vocab`，应用按需从官方发行包安装 `encoder.int8.onnx`、`decoder.onnx`、`joiner.int8.onnx` 与 `tokens.txt`。
- [Apache Commons Compress 1.28.0](https://commons.apache.org/proper/commons-compress/) 为 Apache-2.0，用于在本机流式解压听写模型。运行时解析到 Commons Codec 1.19.0、Commons IO 2.20.0 和 Commons Lang 3.18.0，均为 Apache-2.0。
- [llama.cpp b12076](https://github.com/ggml-org/llama.cpp/tree/36ee3caf7aa2dcb0210bedab1b61ee9f269765d3) 为 MIT，用于侧载本地推理。用户可单独选择下载 [Qwen3-0.6B Q8 GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a)（Apache-2.0）。
