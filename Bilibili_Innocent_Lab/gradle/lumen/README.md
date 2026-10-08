# 凝光宿主视觉公共扩展

基线固定为上游 [1.2.0 发布版](https://github.com/jichuo1/LumenCoacervationEngine/releases/tag/1.2.0)。为保留迁移前的效果，在引擎原包名下应用小范围公共扩展；宿主适配只使用公开 API，不重新维护材质、探针或弹簧求解器。接入结构见 `app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/hostui/README.md`。

`linear-fade.patch` 是引擎原包名下的小范围公共扩展，增加 `LumenSurfaceSampling.fadeCurve` / `LumenSurfaceFadeCurve.LINEAR`；默认 `SMOOTH` 保持原行为。GPU、软件采样与静态承托共用同一策略。补丁包含引擎 API 文档、策略与后端接线测试，以及 sample 的 GPU 实际像素检查，不包含宿主业务代码。

`motion-parity.patch` 让公开 `LumenSpring` 接收刚度与阻尼比，默认底栏参数及系数保持原样；另提供 standard 加速/减速曲线。宿主顶栏传入迁移前的弹簧参数，复制气泡与回复面板使用迁移前的曲线，不受引擎默认 emphasized 风格影响。

`surface-parity.patch` 增加可选的上下缘 ARGB 渐变描边与独立采样层 `backdropOpacity`，同步接入静态、GPU、软件和预设导入导出。未传参数时保留上游行为。公共 `LumenSurfaceLegibilityController` 复用引擎原有 `GlowContentProbe` 与 `GlowLegibilityPolicy`，为保留原生前景色的深色宿主栏提供局部 8Hz 内容统计、迟滞、240ms 色罩过渡与显式资源释放。业务适配只配置基色、前景色和绑定，不触碰内部探针。

该补丁还增加可空的 `softwareBlurRadiusDp`，让同一表面分别声明 RenderEffect 半径与软件盒式模糊半径；不传时保留上游算法与取整方式，宿主传 GPU 17dp / 软件 10dp。预设 JSON 同步往返软件半径与 `fadeCurve`。FROSTED 在无折射 GPU 路径补齐原提亮，在自定义形状 AGSL 路径使用原柔光 C¹ 透镜函数、边界收敛和预乘提亮；LIQUID 保留其折射与色散。自定义形状仍使用引擎后台原始纹理，软件兼容回退仍是模糊加裁剪，不能视为原即时 GPU 或完整软件透镜。普通展开胶囊的 GPU/软件效果分别由模块保留的原算法作像素参照，实机帧统计按 VSYNC 去重。

Android settings 使用正规复合构建，同时替换 catalog 中同版本的 `lumen-engine` 与 `lumen-motion` JitPack 模块，避免新旧类共存。源码从固定标签下载，SHA-256 为 `fce6a90310c8de6c0b5694d12c2378e008cc59cfb28168eb213ab1fbda236fc8`；三个补丁按 settings 中的顺序拼接后计算 SHA-256，作为源码缓存键及 `1.2.0-host.<hash>` 诊断版本后缀。局部 `.gitattributes` 固定补丁为 LF，防止跨平台换行改变版本标识。首次构建需要联网取得源码，此后支持 `--offline`。引擎包名保持上游名称，不发布自建依赖。

源码缓存在 Android 工程的 `.gradle/lumen-source/`，不提交缓存。用项目原有 Gradle wrapper 执行 `:lumen:lumen-engine:testDebugUnitTest`、`:lumen:sample:testReleaseUnitTest` 和引擎构建/Lint；sample 的 `linearGpuFadeHasUniformPixelCoverage` 检查真实 GPU 输出。

升级时同步修改 catalog 版本、settings 中的标签与归档 SHA-256，依次确认补丁可应用，再执行引擎策略与模块回归。上游提供等效公共能力后逐项删除对应补丁，并迁移调用；三个扩展都不再需要时再删除复合构建入口。更新后检查实际顶栏收起/展开、气泡的短文限宽与独立文字层、面板描边与透明度、深色亮图补偿、GPU/软件实时采样，以及 detach/重绑定后的资源和背景归属。
