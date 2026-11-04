# Infinity Workbench 发光点阵无穷应用图标

## 采用状态和来源

用户于 2026-11-05 采用发光点阵无穷图：深蓝夜空背景上，一条扭转的丝带由多条平行点线组成无穷符号，点芯接近白色并带柔和光晕。`infinity-glow-master.png` 是用户提供的 1254 × 1254 带透明通道母版，`provenance.json` 记录谱系与采用状态。

彩色图标的前景直接取自该母版，由 `scripts/branding/GlowIcon.java` 做确定性栅格处理，不增加生成式编辑次数：

- 以不透明度 ≥ 40 的像素统计符号外接圆，低透明度光晕不参与定位。
- 符号外接圆缩放到半径 31dp，居中放进 108dp 画布，留在 66dp 安全圆（半径 33dp）内。
- 预乘透明度下逐级减半再双三次缩放，保留点阵细节，避免光晕边缘发灰。

母版更新后在仓库根运行 `java scripts/branding/GlowIcon.java` 重写下列位图产物，不要手改 PNG。背景、主题图标与通知图标仍由 `scripts/branding/infinity_icon.py` 按参数生成矢量母版，修改时改生成器参数后运行 `python3 scripts/branding/infinity_icon.py`。

## 产物

| 文件 | 画布 | 用途 |
| --- | --- | --- |
| `infinity-glow-master.png` | 1254 × 1254 | 彩色前景母版，只作 `GlowIcon.java` 的输入，不直接进入构建 |
| `launcher-foreground/{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}.png` | 108dp，依次 108 / 162 / 216 / 324 / 432 像素 | 自适应图标前景，构建时复制为各密度的 `ic_launcher_foreground.png` |
| `infinity-glow-icon.png` | 512 × 512 | README 与对外展示图：深蓝径向渐变圆角底，取中心 72dp 可见区 |
| `infinity-dots-master.svg` | 108 × 108 | 自适应图标背景：只使用其中 `background` 分组的满幅径向渐变路径；`foreground` 分组保留为生成器输出，构建不再使用 |
| `infinity-dots-monochrome.svg` | 108 × 108 | Android 13+ 主题图标：4 条点线、点更大，单条纯黑路径，由桌面着色 |
| `infinity-notification.svg` | 24 × 24 | 通知栏小图标：沿无穷中心线的 2dp 实线描边，四周留 2dp；小尺寸下点阵会糊成一团，所以不用点阵 |
| `infinity-dots-dark.svg` / `infinity-dots-light.svg` | 512 × 512 | 矢量展示稿，README 不再引用 |

`assets/branding/build/` 下的圆形遮罩预览只用于人工检查安全区，不入库。

矢量母版只用 SVG 的一个受限子集：根级 `title`/`desc`、`defs` 中的 `radialGradient`（画布坐标、带不透明度的色标）、`background`/`foreground` 分组与其中的直接 `path`。路径填充为 `#RRGGBB` 或径向渐变，可带 `fill-opacity`；主题图标与通知图标只允许纯色不透明剪影，通知图标使用描边。不含位图、文字、字体、外链、裁切、变换或嵌套视口。

彩色图标日夜一致；光效依赖深色底，不提供浅底版本作为桌面图标。

## Android 构建契约

Android 图层尺寸、安全区与单色规则依据[Android 官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。前景的光晕需要模糊，VectorDrawable 无法表达，因此前景使用按密度提供的位图；背景的径向渐变在资源中是一条带 `aapt:attr` 内联 `<gradient android:type="radial">` 的路径。

项目通过 `native-ui/buildSrc` 的 `GenerateLauncherIcons` 和 `androidComponents.onVariants`，把每个变体的生成任务接入 `variant.sources.res.addGeneratedSourceDirectory`。资源合并自动依赖生成任务；`:app:generateInfinityLauncherIcons` 可单独生成并验证所有变体。Android Studio、其它 IDE/ADE、CI 和命令行均使用这条 Gradle 链路，构建时不需要 Python、Node 或图像工具。生成任务读取前景位图目录时，校验五个密度齐全、尺寸正确且带透明通道。

每个变体拥有独立生成目录，由 Android Gradle Plugin 分配。自动生成：

- `drawable/ic_launcher_background.xml`：自适应图标背景，来自矢量母版的 `background` 分组。
- `drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png`：自适应图标前景，逐字节复制自 `launcher-foreground/`。
- `drawable/ic_launcher_monochrome.xml`：主题图标单色层。
- `drawable/ic_notification.xml`：所有通知的 `setSmallIcon`。
- `mipmap-anydpi-v26`：普通与圆形入口的自适应图标；`mipmap-anydpi-v33` 另附 `monochrome` 图层。应用 minSdk 为 31，不再生成非自适应回退。

Manifest 统一引用 `@mipmap/ic_launcher` 与 `@mipmap/ic_launcher_round`。禁止在来源目录或任一变体中放置 `ic_launcher*`、`ic_notification` 文件、旧的 `launcher_icon_colors.xml` 或同名 values 资源，也禁止另指旧图标；生成任务会报错，避免悄悄回退。

## 验证入口

- `python3 scripts/tests/test_launcher_icons.py`：在隔离 Gradle 项目中运行真实生成任务，覆盖背景渐变转换、前景位图复制与尺寸校验、单色与通知剪影、母版更新后清理旧产物、覆盖防护与非法母版；并以 `--check` 确认已提交的 SVG 与生成器当前参数一致（数字允许 0.01 的舍入差）。
- `LauncherIconDeviceTest`：在设备上解析实际资源，检查背景为不透明深色、前景四角透明、点都在安全圆内、点芯接近白色、单色层在安全圆内、通知图标为实线剪影。可通过 instrumentation 参数 `launcherIconTargetPackage` 指向已安装的正式签名 APK。

真机/OEM 桌面的遮罩形状、主题着色与缓存行为仍以对应设备显示为准。
