# NPatch 当前实施路线图

更新时间：2026-10-21。实施分支：`feat/npatch-compat`；起点 `2ef9877f`。

本文是兼容性总览与 Feature Program 合并后的唯一当前排期入口。原文保留为[兼容性调研与验收档案](npatch-compatibility-plan.md)及[Feature 可行性研究](npatch-feature-program.md)，其中历史“现状”、勾选框、工期与阶段编号不再表示当前进度。这里的“待做”表示已纳入路线，“探索”表示尚未承诺实现。

## 已完成的基线

B1–B12 已按范围闭环，详见[专项验收结论](npatch-compatibility-plan.md#2026-10-20-专项验收结论)：

- 免 Root 关闭门控与关闭确认、重复/乱序回执、Manager 不可达分类、诊断徽章和预期日志短路已修复（覆盖 F1–F4 的主要缺陷）。
- 有序广播无人处理时结束空等；默认 Manager 补丁已验证 `:download` / `:ijkservice` 注入。
- 833 loader 的 Manager 失联问题已有固定输入哈希、单方法修补工具，需重新修补宿主，不能仅更新模块解决。
- 冷启动已做对照归因：约 11 秒宿主/环境基础耗时仍在；正常开启额外约 2 秒 Hook 链，授权约几十毫秒。归因完成不等于优化完成。
- 上轮通过 Debug/AndroidTest APK、3651 项 JVM 测试（0 失败、1 跳过）、3 项实机 IPC 回归与快速 Lint；这些是上轮证据，不冒充后续源码的检查结果。

## 当前实施顺序

| 阶段 | 状态 | 内容与原文对应 | 交付与退出条件 |
|---|---|---|---|
| 1 | 已完成（Manager 实机验收） | 运行档案 F5；配置自主化 A；最小体检展示 F7；公共能力模型 | Manager/embed/未知解析；补丁档案与 Loader/Manager 版本展示；DIRECT 不依赖旧兼容开关和 Manager 写入；当前授权、正 revision、停用及有效回执闭环；标准框架策略/回退回归。embed 运行验收仍属阶段 2 |
| 2 | 已完成（实验性范围，`:web` 明确隔离） | embed B/F10；多进程 C/F17 | 同签名 embed 产物；无 Manager 的配置/启停/回执验证；四进程加载/来源/Hook 表；`:web` 保持原生网页行为。具体故障 Hook 另行定位，隔离进程与 injectdex 扩展不作未测承诺 |
| 3 | 待做 | 补丁自省与签名矩阵 D/F7；ABI/native gating E；F15 适配缓存 | 签名等级/宿主版本矩阵明确标注实测与未知；不支持的 native 能力独立降级；核实 NPatch 原 dex 来源与缓存指纹 |
| 4 | 待做 | F8 崩溃/日志桥接、F9 耗时、N4 版本漂移、N6 诊断打包、N7 授权健康度 | 有界脱敏日志与崩溃证据；最近启动样本；升级提示；可导出的支持证据。协议扩展统一设计并同步严格校验测试 |
| 5 | 待重新设计 | F6 启动性能 | 先定位当前约 2 秒 Hook 链及子进程开销，再按 Hook 时序分组优化；当前许可取得前不安装功能 Hook，不恢复缓存授权 |
| 6 | 待做/按收益细排 | F11 重启引导、F12 热应用子集、F14 漫游、N3 宿主入口与向导 | 每项独立验收；热应用只覆盖已证明无需重装 Hook 的值；Shizuku 另行评估 |
| 7 | 探索 | F13 视觉引擎冻结、F16 模块雷达、F18 共存包名、F19 分享降级、F20 DexOpt 引导、N1 档案卡、N2 安全模式、N5 保险库 | 依赖主线能力后重新评估；F18 单独实验；F19 不承诺修复对方应用的签名校验 |

F21 仅保留只读展示/手动导出的候选，不做宿主偏好写回。F22 证书 TOFU 不作为现有 UID 授权的必要修复。N1 基础档案信息由阶段 1 复用，完整可分享卡片后排；N7 基础配置来源展示随阶段 1，统计面板在阶段 4。

## 阶段 1 实施清单

- [x] 运行档案：从宿主权威 manifest 补丁 metadata 解析模式、签名等级、debuggable、Loader API/版本；缺失/损坏/未知字段保守显示未知，不以 Manager 是否安装推断 embed；injectdex 没有权威字段时保持未知。
- [x] 配置自主化：本地当前快照/权威记录作为 DIRECT 来源，带当前免 Root revision；NPatch 优先 DIRECT，Manager Remote Store 为可选镜像。普通 LSPosed 的标准文档仍为 revision 0；已确认的标准框架优先于安装补丁标记。
- [x] 状态与落盘：Manager 缺失/超时不抹掉直达就绪状态；启用、设置变更、重启屏障均校验当前快照；停用写 tombstone，旧回调不得复活状态。
- [x] 最小诊断：现有 NO_ROOT 详情展示已安装补丁档案、Manager 版本和已获准运行配置的来源；HOST_BOOTSTRAP 展示当前授权和安装结果，当前关闭许可不再停留在“未检查”。本阶段不扩大报告 JSON 白名单。
- [x] 测试：解析/未知回退、来源优先级、revision 与关闭门控、授权期间变化、Manager 不可用、标准框架策略/回退；Debug/AndroidTest、JVM、FastLint、有线实机验证。范围见下文。
- [x] 更新 README 与中英/繁体文案；回填实际结果、证据与未验证范围。

## 过时或撤回的方案

| 原方案/判断 | 当前处理 |
|---|---|
| F6：缓存许可直接安装，后台再授权；不可达时继续采用许可快照 | 撤回。缓存无法证明用户之后未关闭；NPatch 必须取得当前端点许可，端点全部不可达时不安装功能 Hook |
| 冷启动总是被固定 2 秒 admission 阻塞 | 历史现象。B11 已解决无人处理后的空等；当前应测量真实 Hook 链，不能照搬旧性能目标 |
| NoRootRuntimeDetector 未接入生产 | 过时。已接入启动授权，阶段 1 已补齐 Manager/embed 运行档案 |
| 子进程统一要求 `--injectdex` | 已纠正。默认 Manager 已验证普通子进程注入，其它进程和功能分别验收 |
| 副用户/工作资料可隔离同包名不同签名宿主 | 不可行。包安装设备级共享，采用有线备用机 |
| F10/embed 放到所有体验功能之后 | 被本路线取代。它属于原总览的主要兼容目标，在配置自主化后实施 |
| 热重载可实现全部设置免重启；全屏冻结用于修复闪退 | 不成立。热重载与 Hook 生命周期不匹配；冻结只能作为有证据的负载优化 |
| 研究文档建议提高 NPatch 版本即可直接执行 | 版本与在线状态未核实；不根据历史猜测升级依赖或替换框架 |

## 验证环境与操作边界

- 用户本轮指定有线测试机 `13a7a291`；不使用 `192.168.1.78:5555`。每条 ADB 操作显式指定 serial。
- 用户已授权测试机上的 B 站与模块卸载、重装及配置变更，无需恢复原配置；不据此扩展为修改其他设备/系统设置的授权。
- 当前阶段优先保留现有可复现环境，不为方便无故重装。截图、日志与测试产物放忽略目录 `captures/`。
- 新鲜授权、UID/nonce/challenge/terms/fingerprint/lease 和单调 revision 边界不放宽。Root/LSPosed 语义单独回归。
- 普通迭代使用 FastLint；只有本地 AGENTS.md 明确的触发条件才执行完整 Lint。构建使用现有 JDK 17、ASCII SDK 路径与项目缓存，结束后关闭并核对守护进程。
- 不自动 commit、push 或修改 Git 历史；各动作仍需用户明确授权。

## 本轮验证记录

### 2026-10-21 阶段 1 验收

环境：唯一有线设备 `13a7a291`，Android 16、arm64-v8a，B 站 8.90.2（8902100），Manager 1.0.9（833），补丁档案为 Manager 模式、签名等级 2、Loader 833/API 102。模块为本分支 1.2.0 Debug（版本码 21）。设备原有模块版本码 22 且签名不同，按用户本轮明确授权卸载模块及测试包后安装本分支产物，并重新添加 Manager 作用域；没有卸载 B 站，也没有操作无线机。

验证结果：

- 最终一次固定源码/资源的 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintFast` 成功，3660 项 JVM 测试，0 失败、1 跳过；快速 Lint 250 条警告，原 3 条错误基线保持不变。Gradle/Kotlin 守护进程已退出。期间有一次本地化检查碰到编辑中的新旧资源名，已在资源收敛后重新运行通过，不沿用该次失败结果。
- `NPatchDirectPublicationInstrumentedTest` 6 项通过：不依赖兼容开关/远端同步的正 revision 文档、单次租约、设置变化与条款撤回使许可失效、停用门控、模块 UID 不得冒充宿主、纯本地重启落盘屏障。这是端点/持久化契约检查，不冒充真实宿主 IPC。
- `HostAdmissionFallbackInstrumentedTest` 4 项通过：NPatch 不采用旧标准文档/旧正 revision 缓存；无 Manager 文档仍须取得当前许可；标准框架保留已校验文档回退。
- `NPatchDirectHostInstrumentedTest` 真实宿主全过程通过（71.58 秒）：临时停用 Manager 包，关闭通信兼容模式；冷启动取得 `module_direct` 许可并完成 Hook 安装；修改配置后再次冷启动，精确匹配新发布身份；关闭后不安装 Hook，当前拒绝及探测回执确认停用；重新开启再次完成直达安装。该测试会改变测试配置并重启宿主，仅按专项需要显式运行。
- Manager 保持不可用时打开真实设置页，状态为 `ACTIVE`，镜像错误单独记录为 `manager_cache_unavailable`；激活卡显示“由 NPatch 激活（回执已验证）”。诊断正确显示补丁模式、签名等级、Loader/API、Manager 版本、`injectdex=未知` 与实际 `module_direct` 来源。
- 设置页首次初始化改变配置身份后，再冷启动宿主并刷新诊断，已确认当前直连配置；当前画面显示配置代次 24，Hook 点安装 43、缺失/歧义 2、失败 0。配置身份变化需要重启，未将旧进程冒充已应用新配置。

证据均在忽略目录：`captures/npatch-stage1-verified-build.log`、`npatch-stage1-final-endpoint-device.log`、`npatch-stage1-final-host-device.log`、`npatch-stage1-active-without-manager.png`、`npatch-stage1-diagnostics.xml`、`npatch-stage1-final-diagnostics.png`、`npatch-stage1-final-diagnostics.xml`、`npatch-stage1-final-logcat.txt`。

验收边界：本阶段只在上述 Manager 环境完成真实宿主验证；embed 的解析分支已有 JVM 检查，尚未制作/安装 embed 产物。没有验证其它 Android/ABI/签名等级，也没有在无线 LSPosed 手机上重跑实机；标准框架由策略测试及现有回退设备测试覆盖。833 loader 仍需具备既有的 Manager 失联修补及已配置的模块路径缓存；本阶段配置自主化不等于无需注入器加载模块。诊断中“隐藏视频暂停页广告”显示部分安装，本轮未修改该功能，不据此声称所有功能均已验收。

阶段 1 测试结束时重新启用了 Manager，免 Root 保持开启、通信兼容模式保持关闭；随后按用户授权提交为 `d1892aaa`，未 push。阶段 2 的后续改动不包含在该提交中。

### 阶段 2 实施清单

- [x] `NewEmbeddedApk.ps1`：固定 833 patcher、普通 entry-hook、embed/等级 2；拒绝未知 patcher、已修补输入和已有输出；核对内嵌模块/原版 APK 哈希及参考宿主证书，输出 `verified.json`。
- [x] embed 设置 App 不查询 Manager 作用域，不写 Manager 镜像；旧 `OUT_OF_SCOPE` 不否决内嵌模块，不凭内嵌标记声称激活。提供固定作用域、重新修补、独立设置 App 与无热重载提示。
- [x] 独立的进程只读诊断：本次随机 nonce、目标包限定、签名权限、API 34 发送方检查、宿主更新时间/模块版本及严格有界白名单；最多 16 行/12 KiB，单次等待 1.5 秒，不沿用历史、不触发授权、不抢主进程心跳、不扩大报告导出 schema。
- [x] 每个进程独立回调线程返回内存状态，主线程初始化缓慢不会拖住其它进程诊断。展示 LOADED/REJECTED/DISABLED/GATED/INSTALLED/FAILED 与 Hook 点数，数量不等同功能行为验证。
- [x] 明确 gating：实测 embed `:web` 安装原有 32 个公共 Hook 后无法绘出页面，关闭功能后网页可渲染。现只隔离 **embed + 精确 `:web`** 的功能安装；继续取得当前许可并提供诊断，显示 `GATED`/0 Hook。Manager、主进程、`:download`、`:ijkservice` 不受该策略影响。具体故障 Hook 尚待单项定位，不能将此隔离描述为根因修复。
- [x] 最终产物的无 Manager 启停/配置/多进程测试、页面复测与门禁结果回填，见下文。

此前无 gating 的对照产物为 `captures/npatch-stage2-current/`：模块 SHA-256 `49aceeb6fcc89b8b1c060cead12587413ecb44b8496b139f2c797fc7b02150e8`；Manager 已真正卸载，首次清数据设置流程可完成条款、启用、冷启动授权及 ACTIVE 回执。`NPatchEmbeddedHostInstrumentedTest` 的配置/关闭闭环 72.328 秒通过；10 项端点/回退契约测试通过；3663 项 JVM、Debug/AndroidTest、FastLint 通过。这些证据只用于对照，不冒充最终 gating 源码的验收。第一次冷启动关闭态的即时进程断言未响应，已在专项测试中改为有界等待宿主初始化，不延长产品单次查询时限。

WebView 对照：`captures/npatch-stage2-web.png` 与 `npatch-stage2-web-activity.txt` 为开启功能后的空白/Activity dump 超时；`npatch-stage2-web-enabled-two-minutes.png` 在超过两分钟后仍为空白；关闭后 `npatch-stage2-web-disabled-settled.png/.xml` 可见隐私政策。临时同证书 debuggable 产物仅用于诊断，该 ROM 未能完成 JDWP 连接且 `debuggerd` 要求 root，未取得可归因线程栈；最终不沿用 debuggable 参数。不操作 NFC 或旋转设置。

### 2026-10-21 阶段 2 验收

环境仍为唯一有线机 `13a7a291`、Android 16/arm64、B 站 8.90.2；**Manager 已卸载**。最终 APK 为 embed、833/API 102、等级 2、`debuggable=false`、普通 entry-hook，不启用 injectdex。独立模块设置 App 保留。无 Manager 的首次设置已从清空模块数据开始，正常 UI 接受条款、选择 NPatch、冷启动宿主并完成当前授权/激活。

最终产物目录 `captures/npatch-stage2-accepted/`，`verified.json` 已逐字节核对内嵌模块及原版宿主：

- 模块 SHA-256：`98b002a7d3fe5a103af9882218ee3640601b29209abea0c94e5e661edd4a0ff3`。
- 修补宿主 SHA-256：`bdb9cd4c807378e8b47c3c1a5d1d7da4adfb0cfef011b57e63f4f7f1cab8c536`。
- 参考宿主与最终 APK 证书均为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`，覆盖安装；未卸载 B 站，没有操作无线机。

最终源码门禁 `captures/npatch-stage2-web-gating-build.log`：Debug/AndroidTest APK、3664 项 JVM 测试（0 失败、1 跳过）、快速 Lint 通过；原 3 条错误基线未扩大。Gradle/Kotlin 守护进程已退出。修补脚本另有未知 patcher、已有输出、已修补输入三项拒绝检查通过，证据 `captures/npatch-stage2-script-validation.txt`。

`embedded-device.log` 中真实宿主专项测试 85.637 秒通过：无 Manager 的当前许可、配置变更后精确身份匹配、关闭后无功能安装、停用回执、重新开启，以及 Web 隔离/普通子进程安装均通过。读取进程状态仅用于验证，不代替主进程心跳。此前同轮 10 项端点/回退契约检查通过，见 `captures/npatch-stage2-endpoint-device.log`；未放宽 UID/nonce/challenge/terms/fingerprint/lease 边界。

最终设置 App 初始化后再冷启动宿主并刷新，`current-diagnostics.xml/.png` 确认“宿主已确认当前直连配置”，四进程都报告 `module_direct`，档案显示 `debuggable=false`：

| 实际进程 | 加载/当前许可 | Hook 点与作用范围 | 实机结论 |
|---|---|---|---|
| `tv.danmaku.bili` | 已加载、已获准 | INSTALLED，43；主进程专用功能保持原判据 | 首页、配置变更/启停/回执通过；视频半屏/横屏进出及评论加载已验证 |
| `:download` | 已加载、已获准 | INSTALLED，32；保留既有公共 Hook 与主进程功能跳过判据 | 注入/当前配置/安装诊断通过；下载业务未单独验收 |
| `:ijkservice` | 已加载、已获准 | INSTALLED，32；不套用 Web 隔离 | 播放场景可用，注入/当前配置/安装诊断通过；不把 Hook 数量当作每个功能回调已触发 |
| `:web` | 已加载、已获准 | GATED，0；只保留许可及诊断，功能 Hook 隔离 | 官方隐私政策可渲染，Activity 状态可读；证据 `web-gated.png/.xml`、`web-gated-activity.txt` |
| `:pushservice` / 其它未观察进程 | 未验证 | 不推断加载、授权或功能 | 不为补齐表格主动唤醒或声明成功 |
| 隔离/WebView renderer 进程 | 未验证 | 未扩大 UID/宿主包授权边界 | 不把 `com.google.android.webview` 的包加载回调等同于 B 站普通 `:web` 进程 |

普通 entry-hook 已覆盖上述实际子进程；没有发现必须启用 injectdex 的已验证场景，因此本轮不制作或推荐该参数的额外覆盖承诺。登录、账号/分享风控、其它签名等级、其它 Android/ABI、Manager 新代码实机回归仍未验证；标准框架由来源/策略及既有回退测试覆盖。

后续待做：对 embed `:web` 原有公共 Hook 逐项定位，定位后才考虑缩小/解除隔离；不得因“网页能开”宣称所有 Web 功能已支持。它与阶段 3 的签名/版本矩阵分别记录。最终免 Root 开启、兼容模式关闭、Manager 未安装，手机留在当前配置匹配的四进程诊断页；本阶段改动未 commit/push。
