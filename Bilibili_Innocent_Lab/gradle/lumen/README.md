# 凝光线性渐隐公共扩展

基线固定为上游 `91e31dce23dda35d2fd6d2411d0aac3079e3c46a`。该提交及核对时的 main `8839f2e90b5d7ff89a49f74d034bbe5ebe2a9af3` 都只提供固定 smoothstep 渐隐，不能满足关注页顶部等速消失的要求。

`linear-fade.patch` 是引擎原包名下的小范围公共扩展，增加 `LumenSurfaceSampling.fadeCurve` / `LumenSurfaceFadeCurve.LINEAR`；默认 `SMOOTH` 保持原行为。GPU、软件采样与静态承托共用同一策略。补丁包含引擎 API 文档、策略与后端接线测试，以及 sample 的 GPU 实际像素检查，不包含宿主业务代码。

Android settings 使用正规复合构建，同时替换 catalog 中两个同提交的 JitPack 模块，避免新旧类共存。源码从固定提交下载，SHA-256 为 `300be73940e04854b2a893af1aea57f711c6ea18c590c556c261e6d79b8c67c6`；补丁内容的 SHA-256 作为源码缓存键及诊断版本后缀，局部 `.gitattributes` 固定补丁为 LF，防止跨平台换行改变该版本标识。首次构建需要联网取得源码，此后支持 `--offline`。没有更换包名、发布依赖或修改底栏会话。

源码缓存在 Android 工程的 `.gradle/lumen-source/`，不提交缓存。用项目原有 Gradle wrapper 执行 `:lumen:lumen-engine:testDebugUnitTest`、`:lumen:sample:testReleaseUnitTest` 和引擎构建/Lint；sample 的 `linearGpuFadeHasUniformPixelCoverage` 检查真实 GPU 输出。

上游提供等效公共能力后，删除补丁与 settings 中复合构建入口，再统一更新 catalog 的固定版本。业务页面仅需调整 `FollowFeedStyle.statusBand` 的公开配置，不维护自绘渐隐实现。
