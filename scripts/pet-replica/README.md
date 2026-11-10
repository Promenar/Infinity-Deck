# 会话宠物原型：鲸鱼女仆模型工具

开发测试用脚本。模型、贴图等角色形象资产不进仓库，产物放在仓库外的共享笔记目录。

## 两条路线

- **手工脚本建模**（本目录）：`build.sh <构建目录> <产物目录> [Python]`
  - `make_textures.py` 程序绘制贴图（需要 Pillow、numpy）；
  - `build_model.py`、`meshkit.py` 在 Blender 里按设定图脚本建模，含骨骼与形态键表情；
  - 这条路线已停止，保留作参考。
- **图生 3D 网格转 VRM**（`v2/`）：`v2/build.sh <输入.glb> <工作目录> <产物目录> [起始阶段]`
  - `s1_prepare.py` 焊接、归一化、采样顶点色；
  - `s2_lowpoly.py` 体素重建、剔除里层、减面、部位分区、切口封口；
  - `s3_bake.py` 分组展 UV、最近可见点颜色传递；
  - `s3b_flat.py` 生成最终基础色并给每个面标材质类别：默认保留手绘笔触并烘入 B 版调色，`TEX=flat` 时量化成平涂色；
  - `make_face_atlas.py` 用 numpy 绘制眼睛、嘴的表情图集和围裙鲸鱼图案（没有重绘图集时的后备）；
  - `atlas_from_sheet.py` 把图像模型画的表情素材表整理成图集，放到 `<工作目录>/assets/` 后构建时自动采用（需要 Pillow）；
  - `s4_rig.py` 骨骼、权重、材质拆分与法线整理、面部贴花、脚本手臂与胸前部件、导出 glb；
  - `render_blook.py` 用 B 版工程渲染高低模对比；`render_dbg.py`、`inspect_src.py` 为检查用；
  - 只用 Blender 自带的 Python。

## 共用

- `glb_to_vrm.py`：向 glb 写入 `VRMC_vrm`、`VRMC_springBone`、`VRMC_materials_mtoon`，不依赖 Blender 的 VRM 插件，只需 numpy。
- `viewer.html`、`serve.py`：three-vrm 多视角检查器与本地服务（含截图保存端点）。
- `patch_prototype.py`：从 3D 原型 `pet-3d.html` 生成加载复刻模型的副本。
