# 凝光源码接入

当前基线固定为上游 [1.2.4 标签](https://github.com/jichuo1/LumenCoacervationEngine/tree/1.2.4)，core 与 motion 同时接入，正式源码归档 SHA-256 为 `b7fade429f42e3f7e32cf81102ad5ff9bacac511ebae1f3b2051bf7ba781681a`。构建只对齐 Android 复合工程需要的工具链版本，不再修改引擎的行为源码或诊断版本号。

引擎 1.2.3 已吸收下述三个扩展；它们作为历史记录保留，settings 不再应用。首次构建下载固定归档并核对哈希，此后支持 `--offline`。缓存键由版本与宿主工具链版本确定，缓存位于 `.gradle/lumen-source/`，不提交缓存。

宿主 motion 的兼容名称委托到 LCE；窗口只有一个弹性控制器，原生回弹、翻页、手风琴、气泡、锚点和全屏形变使用引擎实现。业务 View 树、颜色、入口坐标协议和业务回调留在宿主。源码契约先核对实际委托，再读取本次 Gradle 编译的引擎源码。

本地开发引擎时可显式传 `-PinnocentLab.lumenDevelopmentSource=<引擎目录>`，默认构建与 CI 均使用上面的正式标签和哈希。开发目录必须完成工具链对齐；正式验收不得把开发覆盖当作标签构建证据。

## 历史：1.2.2 的公共扩展

基线固定为上游 [1.2.2 发布版](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.2.2)。为保留迁移前的效果，在引擎原包名下应用小范围公共扩展；宿主适配只使用公开 API，不重新维护材质、探针或弹簧求解器。接入结构见 `app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/hostui/README.md`。

`linear-fade.patch` 是引擎原包名下的小范围公共扩展，增加 `LumenSurfaceSampling.fadeCurve` / `LumenSurfaceFadeCurve.LINEAR`；默认 `SMOOTH` 保持原行为。GPU、软件采样与静态承托共用同一策略。补丁包含引擎 API 文档、策略与后端接线测试，以及 sample 的 GPU 实际像素检查，不包含宿主业务代码。

`motion-parity.patch` 让公开 `LumenSpring` 接收刚度与阻尼比，默认底栏参数及系数保持原样；另提供 standard 加速/减速曲线。宿主顶栏传入迁移前的弹簧参数，复制气泡与回复面板使用迁移前的曲线，不受引擎默认 emphasized 风格影响。

`surface-parity.patch` 增加可选的上下缘 ARGB 渐变描边与独立采样层 `backdropOpacity`，同步接入静态、GPU、软件和预设导入导出。未传参数时保留上游行为。公共 `LumenSurfaceLegibilityController` 复用引擎原有 `GlowContentProbe` 与 `GlowLegibilityPolicy`，为保留原生前景色的深色宿主栏提供局部 8Hz 内容统计、迟滞、240ms 色罩过渡与显式资源释放。业务适配只配置基色、前景色和绑定，不触碰内部探针。

该补丁还增加可空的 `softwareBlurRadiusDp`，让同一表面分别声明 RenderEffect 半径与软件盒式模糊半径；不传时保留上游算法与取整方式，宿主传 GPU 17dp / 软件 10dp。预设 JSON 同步往返软件半径与 `fadeCurve`。FROSTED 在无折射 GPU 路径补齐原提亮，在高级形状 AGSL 路径使用原柔光 C¹ 透镜函数、边界收敛和预乘提亮；LIQUID 保留其折射与色散。普通展开胶囊的 GPU/软件效果分别由模块保留的原算法作像素参照，实机帧统计按 VSYNC 去重。

单个圆角矩形的形状动画沿用常规内容节点 GPU 管线，按当前形状更新取样区域、透镜尺寸与像素预算；软件兼容沿用已存在的柔光采样并裁剪输出。进入或清除这类形状不丢弃已有背景，避免滚动后首次收起时等待后台纹理而闪出回退底色。双形状、融合、自定义角、光照、按压、渐进模糊等高级效果仍走原 AGSL / 软件轮廓管线。`HostTopIslandTransitionInstrumentedTest` 检查柔光 GPU、软件兼容和 Liquid 的真实窗口收起第一帧及内容换色，覆盖同位置重复收起。

可读性扩展必须保留调用方的 `backdropOpacity`，只调整色罩。完整帧的柔光采样 alpha 经归一化后为 1；将其重写为 `1 - tintOpacity` 会使未模糊内容透出，浅深色不能使用不同的合成规则。外观验收同时使用完整模块 renderer 和细条纹/文字背景，不能只对照旧宿主单层透明算法或低频色块。

Android settings 使用正规复合构建，同时替换 catalog 中同版本的 `lumen-engine` 与 `lumen-motion` JitPack 模块，避免新旧类共存。源码从固定标签下载，SHA-256 为 `c736b882977584dd79fa4e320f0353ab05027fad284ee8b024af9ac2ec4fc4e6`；三个补丁按 settings 中的顺序拼接后计算 SHA-256，作为源码缓存键及 `1.2.2-host.<hash>` 诊断版本后缀。局部 `.gitattributes` 固定补丁为 LF，防止跨平台换行改变版本标识。首次构建需要联网取得源码，此后支持 `--offline`。引擎包名保持上游名称，不发布自建依赖。

源码缓存在 Android 工程的 `.gradle/lumen-source/`，不提交缓存。用项目原有 Gradle wrapper 执行 `:lumen:lumen-engine:testDebugUnitTest`、`:lumen:sample:testReleaseUnitTest` 和引擎构建/Lint；sample 的 `linearGpuFadeHasUniformPixelCoverage` 检查真实 GPU 输出。

升级时同步修改 catalog 版本、settings 中的标签与归档 SHA-256，依次确认补丁可应用，再执行引擎策略与模块回归。上游提供等效公共能力后逐项删除对应补丁，并迁移调用；三个扩展都不再需要时再删除复合构建入口。更新后检查实际顶栏收起/展开、气泡的短文限宽与独立文字层、面板描边与透明度、深色亮图补偿、GPU/软件实时采样，以及 detach/重绑定后的资源和背景归属。


以上升级说明为 1.2.2 的历史流程。1.2.3 不再应用补丁；复合构建继续保证 core/motion 来源一致，并让模块与源码契约使用同一版本。升级时同步 catalog、settings 标签和归档哈希，再检查模块与引擎的行为门禁、面板裁剪、长控件拖动、关闭第一帧、GPU/软件背景及 detach 后资源归属。
