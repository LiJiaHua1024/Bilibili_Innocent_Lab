# NPatch 专项：Feature 拆解、可实施性与实施方案

> 当前实施入口：[NPatch 当前实施路线图](npatch-implementation-roadmap.md)。本文保留可行性研究；旧阶段顺序和工期不再表示当前排期。F6 的“缓存许可直接安装、后台授权”方案已撤回，必须遵守当前端点授权边界。

- 状态：历史 Feature 调研；本轮 B1–B12 问题已闭环，扩展路线仍为提案
- 日期：2026-10-20
- 调研基线：`feat/npatch-compat`，当时 HEAD `80a21910`；以下源码行号与性能数字均为当时观察
- 调研方式：只读代码库 + 只读上游源码（`captures/npatch-src`、`captures/vector-src`、NPatch 1.0.9 build 833 产物字符串交叉验证），共 25 个并行只读调研代理；未修改任何业务代码、未构建、未连接 ADB
- 当前结果：[兼容性专项验收结论](npatch-compatibility-plan.md#2026-10-20-专项验收结论)；本文保留背景与扩展 Feature 提案，不作为未修复问题清单
- 证据口径：本文所有结论都带 `file:line`；标注「间接证据」的条目只来自源码注释与产物字符串对比

> 说明：本机 GitHub 域名解析到非公网 IP，`7723mod/NPatch` 的 issue #154/#156/#161 与 build 846 的在线状态**无法核实**，相关结论均为源码内证。

---

## 0. TL;DR

- **真正属于 NPatch 的增量价值集中在 4 条主线**：①配置通道自主化（去 Manager 单点依赖 + 冷启动不阻塞）；②环境自省/体检；③日志与崩溃桥接；④免 Root 的“重启/更新”闭环。
- **实测证据**：冷启动 `admission=2032ms`（吃满 2000ms 窗口）、`total=2153ms`，随后 `Hook 安装链完成耗时=2311ms`，全部发生在 `Application.attach` 主线程；118 条样本里 80 条 admission 打满窗口。这正是群里 `alex` 反馈的“没后台就不正常/闪退”。
- **历史优先项 B1–B4 已修复**：关闭门控、状态归位、Manager 不可达误判及诊断徽章已完成回归；后文 F1–F3 保留原调研推导，当前结果见专项验收结论。
- **热重载不可用**：BIL 结构上满足 NPatch 热重载条件（单 Java 入口、无 `native_init.list`），但 hook 在 `onPackageReady` 安装、reload 只跑 `onHotReloaded`，且 `module.prop` 是 `autoHotReload=false`。不要用它做“改设置免重启”。
- **`--injectdex` 不是子进程的必要条件**：默认修补下子进程照样加载模块，缺口在模块自己的 installer 门控。现有文档相关表述需要更正。
- **如果只挑三件**：F1+F2（关不掉/状态卡死修干净）→ F6（拿掉 2s 主线程阻塞）→ F7+F8（让“为什么不行”可诊断）。

---

## 1. 先纠正 6 个前提

排期前必须先纠正以下假设，否则会把工作量建在错误前提上。

| # | 讨论稿里的说法 | 核实结论 | 证据 |
|---|---|---|---|
| 1 | “NPatch 重签名导致模块 App 侧宿主签名校验误杀，需要 TOFU” | **不成立**。全仓没有任何证书/签名校验（`GET_SIGNING_CERTIFICATES` / `signingInfo` / `checkSignatures` / `android.content.pm.Signature` 零命中）；跨进程只校验 UID + 包名 + nonce + 条款 revision。重签名后包名不变、UID 由内核强制，天然一致 | `runtime/HostAdmissionEndpoint.kt:48-51`、`provider/RoamingCompatProvider.kt:254-264`、`runtime/HostReceiptHost.kt:40`、`runtime/HostAdmissionLocalIdentity.kt:20-32` |
| 2 | “BIL/Manager 不在后台 → 授权校验失败或 IPC 超时” | 历史测量中模块侧 `admission` 吃满 2000ms；B11 已消除确认无人处理后的空等。NPatch 自身绑定与 Provider 查询仍是另一层开销 | `captures/npatch-bughunt-20261020/baseline-logcat.txt:1575,1960`、`runtime/HostAdmissionContract.kt`、兼容性专项验收结论 B11/B12 |
| 3 | “配置只以 Manager Remote Store 为唯一来源，断了就废” | **上游已有一层离线兜底**：`FallbackModuleServiceWrapper` 把 Manager 的 remote prefs 全量镜像进宿主本地 SQLite，Manager 死后宿主读本地快照 | `captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/service/FallbackModuleServiceWrapper.java:89-131` |
| 4 | “接 hotReload 实现免重启生效” | 结构上满足条件，但**语义不成立**：hook 在 `onPackageReady` 安装，reload 只跑 `onHotReloaded`；改了会“重载成功但功能全丢” | `captures/npatch-src/manager/src/main/java/top/nkbe/npatch/manager/HotReloadRegistry.kt:87-91`、`captures/vector-src/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorModuleManager.kt:285-337`、`hook/HookEntry.kt:2292`、`app/src/main/resources/META-INF/xposed/module.prop` |
| 5 | “子进程不生效，需要 `--injectdex`” | **错**。默认修补下子进程照样加载模块（`appComponentFactory` shim 每进程实例化）；实测 `:download` / `:ijkservice` 都拿到许可并装了 35 个 Hook。缺口是模块自己的 59 个 installer 主动跳过非主进程 | `captures/npatch-src/patch/src/main/java/top/nkbe/patch/NPatch.java:286-290,508-532,556-561`、兼容性专项验收结论 B8 |
| 6 | “适配缓存改个指纹就能跨重打包继承” | 指纹确实要改（`source.length` / `lastModified` 每次安装都变），但**默认修补把原 dex 移出外层 APK**，`DexSourceFingerprint` 与 `auditDexSourceAsync` 在 NPatch 下看到的是 loader dex，安全网永久失效 | `NPatch.java:508-532`、`hook/adapter/dex/DexSourceFingerprint.kt:41-84`、`hook/VersionAdapter.kt:2830-2846,3062-3097` |

---

## 2. 上游能力基线（可依赖 / 不可依赖）

### 2.1 可依赖

| 能力 | 说明 | 证据 |
|---|---|---|
| Manager 模式 Remote Store | 模块 App 经 `IXposedService` 读写 remote prefs 与 Remote Files；宿主侧只读 | `captures/npatch-src/manager/src/main/java/top/nkbe/npatch/manager/XposedServiceBinder.kt:100-128`、`captures/npatch-src/share/android/src/main/java/top/nkbe/npatch/util/LocalInjectedModuleService.java:19,48-57` |
| 实时配置推送 | `IRemotePreferenceCallback` 真实存在，`VectorRemotePreferences.registerOnSharedPreferenceChangeListener` 可用（NPatch 用 Vector runtime） | `captures/vector-src/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt:30-71,111-121`、`captures/npatch-src/share/android/src/main/java/top/nkbe/npatch/util/NPatchRemoteStore.java:292-305` |
| Remote Files | 平铺文件名、无大小上限；规范存储在 **Manager 私有目录** `files/npatch/remote/<模块包>/` | `NPatchRemoteStore.java:233-323` |
| 补丁档案（Patch Profile） | 宿主 manifest `meta-data "npatch"` = Base64(JSON(PatchConfig))，字段含 `useManager/sigBypassLevel/debuggable/lspConfig` | `NPatch.java:409-426,774`、`captures/npatch-src/share/java/src/main/java/top/nkbe/npatch/share/PatchConfig.java:5-21` |
| Manager ConfigProvider | `content://top.nkbe.npatch.manager.provider.config/config?package=<target>`，exported 且无权限校验，可列该目标已启用模块 | `captures/npatch-src/manager/src/main/java/top/nkbe/npatch/manager/ConfigProvider.kt:21-47`、`captures/npatch-src/manager/src/main/AndroidManifest.xml:129-133` |
| Media 日志 | `/sdcard/Android/media/<宿主包>/npatch/log/yyyyMMdd.log`，按日追加、无轮转；含框架日志 + `XposedInterface.log` + 未捕获 Java 异常 | `captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/loader/XposedLogPrinter.java:100-160` |
| 宿主本地 prefs 镜像 | Manager 的 prefs 会镜像进宿主本地 SQLite，Manager 死后回退读它 | `FallbackModuleServiceWrapper.java:89-131` |

### 2.2 不可依赖

| 限制 | 说明 | 证据 |
|---|---|---|
| `--embed` 无外部配置通道 | 模块设置 App 在另一 UID，无法写宿主 store；EmbeddedXposedService 只在宿主进程内 | `captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/service/EmbeddedXposedService.java:29-33`、`captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/loader/LSPApplication.java:349-368` |
| embed 硬限制 | 签名绕过上限 High(2)、scope 不可运行期改、更新须重打补丁、`hotReloadModule` 恒 `HOT_RELOAD_UNSUPPORTED` | `EmbeddedXposedService.java:67-96`、`NPatch.java:197` |
| 833 的 EXTREME/Seccomp | `svc_bypass` 的 execute-only `.text` 防护字符串在 833 产物中缺失（源码注释指向 issue #156）→ 高等级在 XOM ROM 上仍有 SIGSEGV 风险。**建议最低基线抬到 ≥846** | 间接证据：`captures/npatch-src/patch-loader/src/main/jni/src/jni/svc_bypass.cpp:95-112` 与 833 产物字符串对比 |
| `config.json` 改 `sigBypassLevel` 无效 | 运行时被 manifest meta-data 覆盖 | `LSPApplication.java:175-198,462` |
| 热重载换 Hook | 见「前提 4」 | — |
| Manager 冻结时的新推送与 Remote Files | Manager 进程不在时，宿主只能读到本地镜像 | `FallbackModuleServiceWrapper.java:134-157` |
| Media 日志不含 `android.util.Log` 与 native crash | 官方明确不含任意 `Log.*`、native fatal、tombstone | `XposedLogPrinter.java:27-28` |

---

## 3. Feature 拆解

> 工作量口径：S ≈ 0.5–2 人日，M ≈ 3–7 人日，L ≈ 8–15 人日（含测试，不含实机排队时间）。

### P0 — 必修：已确证缺陷 + 配置通道自主化

#### F1 关闭门控失效 + 直达文档元数据修复（对应 B1，最高优先级）

- **结论**：可行，S。
- **现状**：`settings/remote/PublicationAuthorityStore.kt:26-28` 把 `deliveryEnabled=true, noRootRevision=0L` 写死 → 关闭免 Root 后 NORMAL 路径仍“自洽”通过（`runtime/HostAdmissionSourcePolicy.kt:4-9`）；`hook/HookEntry.kt:4880-4882` 因 `noRootRevision<=0` 不发心跳；关闭路径的 remote 清理失败被静默（`runtime/noroot/NoRootSupportController.kt:133-147`）。
- **实施**：`document()` 按 `NoRootSupportStore.isDesiredEnabled` 注入 `noRootRevision` 与 `deliveryEnabled`；`HostAdmissionEndpoint` 对 `deliveryEnabled=false` 的文档直接 `denied`，不允许 NORMAL 回退重新授权；关闭路径的失败必须显式报错。
- **验收**：JVM 断言「关闭态 + NORMAL 文档」不得返回 NORMAL；实机重跑 B1 步骤，宿主不得再装 Hook。

#### F2 状态机修复：`DISABLE_RESTART_REQUIRED` 永不清除（B3）+ Manager 不可达误判（B4）

- **结论**：可行，S。
- **现状**：`runtime/noroot/NoRootSupportStore.kt:180-184` 关闭态无条件保留该状态；唯一清除点 `recordDisabledAck`（:291-315）要求 `active=false`，而宿主唯一发送点 `HookEntry.kt:4895-4903` 写死 `active=true`；`runtime/noroot/NPatchRemoteGateway.kt:118-134,216-218` 把“拿不到 binder”归类成身份拒绝。
- **实施**：宿主在“未装 Hook”时回执 `active=false`（或模块侧确认无 Hook 后主动清除）；网关区分「binder 不可达」与「显式拒绝」，前者映射 `ManagerMissing` / `ConnectionTimeout`。
- **验收**：契约测试 + 实机「关→强停→重启」后状态归位；停 Manager 后开/关不再误报。

#### F3 诊断入口徽章误报 + `NPatch selected` 日志噪声（B2/B9）

- **结论**：可行，S。
- **现状**：`settings/remote/RemoteHookConfigStore.kt:244-245` 按设计短路成 `Failure("NPatch selected")` 并置 FAILED（:273-281）；详情页有豁免判据（`diagnostics/ModuleDiagnostics.kt:185-199`），入口徽章（`ui/activity/MainActivity.kt:1050-1069`）没有用。
- **实施**：徽章与详情页共用 `standardPublisherBypassed`；把「按设计短路」从 FAILED 拆成独立 `BYPASSED`；`logFailure` 不再对短路打 W。
- **验收**：JVM `ActivationCardVisualSpec.diagnosticsSummaryState(publishFailed=true, standardPublisherBypassed=true) → READY`。

#### F4 回执 `SESSION_MISMATCH` 误码与丢回执（B6）

- **结论**：可行，S。
- **现状**：`runtime/HostReceiptRegistry.kt:57` 预置 `SESSION_MISMATCH`，任何 `return false` 都以此码出栈（:42,68-71,76-79,86）。
- **实施**：每条失败分支赋具体码（`SOURCE_MISMATCH` / `SURFACE_MISMATCH` / `STORE_FAILED` 等）；复核 `ReceiptSessionGate.accept` 在正常重启下的判定。
- **验收**：JVM 覆盖「同进程重复注册」与「进程重启」两种场景。

#### F5 NPatch 模式判定 + 补丁档案（Patch Profile）

- **结论**：可行，S/M。这是 `runtime/noroot/NoRootRuntimeDetector.kt` 从未接入生产（只被测试引用）的补齐，也是体检/embed/子进程诊断的共同基础。
- **事实源**：宿主 manifest `meta-data "npatch"`（Base64 JSON）→ `useManager`；`top.nkbe.npatch.remote` / `manager.provider.config` 是否可解析；框架名 `"NPatch"` + API/能力位（`settings/remote/ModernFrameworkStatus.kt:56-58`）。
- **实施**：新增 `NPatchRuntimeMode`（MANAGER / EMBED / UNKNOWN），替换 `NoRootRuntimeDetector` 的死代码；补丁档案字段：`useManager / sigBypassLevel / debuggable / lspConfig.API_CODE,VERSION_CODE`。
- **验收**：JVM 判定矩阵；实机 manager 与 embed 各一轮。

#### F6 冷启动非阻塞化（含子进程重复 admit）

- **结论**：可行，M（本专项核心改造）。
- **现状**：`hook/HookEntry.kt:5083-5092` 在 `Application.attach` 主线程同步 `authorizeAndInstall` → 600ms 远程配置（:383-392）+ 2000ms admission；失败后首个 `onResume` 再跑一次（:5046-5064）；**每个子进程各来一遍**（bughunt B8）。
- **实施**：
  1. 宿主私有目录落盘「上次已通过 admission 的许可文档」（`runtime/TargetAppStorage.kt:22-32` + `hook/adapter/dex/AtomicJsonCache.kt:12-46` 已有范式），启动时零 IPC 读取、全量校验后直接安装 Hook；
  2. admission 移到 `HostReceiptWire` 后台线程，拿到更新 revision 只落盘、不重装 Hook；窗口从 2000ms 降到 ~300ms 仅作诊断；
  3. 失败语义：只有 `runtime/HostAdmissionRouteClassifier.kt:20-24` 的三类算拒绝，超时/不可达一律保留快照；
  4. 子进程默认只读快照、不做独立 admission（或严格限流）。
- **安全边界不放宽**：UID / nonce / challenge / terms / fingerprint / lease / 单调 revision 全部保留；本地快照只代表「上次已通过」。
- **验收**：实机双杀后冷启动 ≤300ms 装上 Hook、首帧不等待；子进程不再重复 2s。

### P1 — 可观测性与支持效率

#### F7 NPatch 体检面板（Environment Inspector）

- **结论**：可行，S（「零报告变更」路线）。
- **事实源**：宿主 `meta-data "npatch"` / `assets/npatch/config.json`（loader `lspConfig.VERSION_CODE`）、模块 App 侧 `top.nkbe.npatch` versionCode、`ModernFrameworkStatus`、ABI。
- **体检项**：①修补模式；②签名等级 vs embed 上限 2；③`debuggable` 标志；④Manager 是否安装/版本；⑤**Loader 与 Manager 版本一致性**（宿主报 loader versionCode，模块 App 比 Manager versionCode；仅 `useManager=true` 告警）；⑥配置来源；⑦ABI 与模块 native 支持；⑧`injectdex`（**无权威字段，显示「未知」**）。
- **实施**：先做「塞进现有 `HOST_BOOTSTRAP` / `NO_ROOT` 详情 + 导出报告」的 S 版，**不动** `diagnostics/DiagnosticReportCodec.kt` 的 `bootstrap.length()==11` 硬断言；确认事实源可靠后再升协议（`runtime/HostRuntimeDiagnostics.kt:102` schema 3→4 + `formatVersion` 7→8 + 同步测试）。
- **验收**：诊断页能看到「manager/embed、sig level、loader/manager 版本、配置来源」。

#### F8 崩溃与日志桥接（Crash / Media Log Bridge）

- **结论**：可行，S/M。分两半：
  - A 模块自挂 `UncaughtExceptionHandler`（链式交还 NPatch 已装的 handler，`LSPApplication.java:385-430`）→ 崩溃前写宿主私有目录（≤32KB、留 3 份、脱敏），下次启动读回进诊断。只做 A 就能回答「闪退来自模块还是宿主」。
  - B 宿主侧读自己的 `externalMediaDirs/npatch/log` 尾部 64KB（崩溃窗前后约 200 行），随回执送模块 App，做成「一键导出」。**不要**把整份 Media 日志塞进诊断 JSON。
- **注意**：`externalMediaDirs` 全仓未使用；模块 App 无全盘权限（`app/src/main/AndroidManifest.xml:59-60`），读宿主 `Android/media` 必须由宿主侧读再回传；`runtime/HostThreadGuard.kt:61-62` 已有 `failureCounts()` 但无消费者，可一并接入。
- **验收**：DEBUG 版「测试崩溃」按钮构造一次可控崩溃，验证落盘→冷启动读回→导出。

#### F9 启动耗时基线（可并入 F7）

- **结论**：可行，S/M。耗时目前只进 logcat（`HookEntry.kt:4985-5005,4944-4947`），诊断协议/页面/报告均无时长字段。
- **建议**：模块侧维护最近 8 次样本的 max/p50（单次冷启动只有一个样本），只传枚举与整型毫秒；先只进诊断页与 `module.log`，等 admission 优化收敛后再决定是否进导出报告。

### P2 — 体验

#### F10 `--embed` 支持（最小闭环）

- **结论**：有条件可行，M。真正的阻塞是 4 处口径：`noRootRevision` 恒 0、DIRECT 被「通信兼容模式」卡住（`settings/remote/PublicationAuthorityStore.kt:58` + `HostAdmissionSourcePolicy.kt:7`）、Manager 缺失被当硬失败、无 embed 模式判定；外加两个必须实机验证的未知数（宿主包可见性、签名等级 2 是否够登录/播放）。
- **实施**：F5 的模式判定 + `document()` 修 revision + `directAllowed = compat || embed` + `HostAdmissionSourcePolicy/Endpoint` 在 embed 且免 Root 开启时优先 DIRECT（并校验 revision 与 `isDesiredEnabled`）+ `runtime/noroot/NoRootSupportStore.kt:363-388` 的 `exportState` 增加 direct 变体（不再要求 `remoteSynced`）+ UI 文案拆「Manager 缺失 / 集成模式就绪」。
- **硬限制必须在 UI 写明**：签名 ≤2、更新须重打补丁、scope 固定、无热重载。
- **降级预案**：Provider/广播都被包可见性拦死 → 只留 local + 广播并标「实验性、需模块 App 常驻」。

#### F11 免 Root 一键强停/重启

- **结论**：可行，M；但**先做便宜替代**：现在 `ui/activity/MainActivity.kt:208-221` 的 `openBilibiliAppDetails` 只跳详情页，补一次 `getLaunchIntentForPackage` 自动拉起即可，零依赖（S）。
- **Shizuku 路线**：仓库零依赖，需联网加 `shizuku`（离线缓存没有制品，`--offline` 必失败）；`UserService` 与 Shizuku 同 uid（shell 2000），可直接 `am force-stop`，不必反射隐藏 API。风险：跨 ROM 杀 Shizuku、Android 12+ 后台启动限制、旋转回写无法复刻（`ui/activity/ConfirmationDialogs.kt:484-507`）。

#### F12 配置热应用（只做「改值不翻开关」子集）

- **结论**：有条件可行，M。宿主侧配置是启动期一次性快照（`hook/config/HookConfigSource.kt:19-34`、`HookEntry.kt:4923` 只 set 一次），值被 installer 构造参数固化。
- **实施**：`LiveHookConfigSource`（AtomicReference + revision）+ 复用现有「模块 App → 宿主进程」签名广播通道（`runtime/InjectedUiLocale.kt:101-111,235-257` 是现成「即时生效」范式）+ 首批三类：自由复制 4 个 volatile、弹幕权重/彩字、首页标题关键词/时长/播放量。
- **不要**做全量热改：开关翻转/布局注入本质是重挂 Hook 与重建 UI（`hook/HookPointRegistry.kt:261-269` 已有 DUPLICATE/半初始化风险）。替代：设置页按「即时生效 / 需重启」分级 + 「立即应用」按钮。
- **注意**：libxposed `service` 的 `RemotePreferences` 监听在 service jar 里是空实现，但 NPatch 用的是 Vector 实现（有真实回调）；不要依赖 service jar 行为。

#### F13 全屏视觉引擎冻结

- **结论**：有条件可行，S；但**不要按「修闪退」立项**（现有观察 20 轮全屏进出无复现、无模块异常，`docs/npatch-compatibility-plan.md:760-762,804`）。
- **实施**：`LumenSurfaceSession` 已有 `pause/resume`（`captures/lumen-source/.../LumenSurfaceSession.kt:139-151`）；建议做「冻结」而非 `pause()`（避免 STATIC tint 跳变）：命中全屏时只记录、不 apply、不 notify，退出时各调一次 `notifyContentChanged`。信号用 decor 的 `OnApplyWindowInsetsListener` + 每帧 `configuration.orientation`，不新增播放器 Hook（`VersionAdapter` 无可用点）。
- **收益定性**：省电/降负载，不是修 bug；省电优先走 `ui/skin/.../LiquidPerformancePolicy` 的采样预算降档。

#### F14 漫游「两次重启」缩短

- **结论**：有条件可行，M。机制已定位：首启删缓存 → BiliRoaming 全量分析（5–6s）产物缺 `biliAccounts.getAccessKey`、其自身校验必失败；补齐只能在 `Application.onCreate` 之后异步做 → 次启才走快速路径。
- **实施**：①过期分支在 `hasHookData` 且宿主 versionCode 未变时改走 `patchHookInfo` 原位刷新、不删（`hook/RoamingCompatHook.kt:1222-1258`）；②`reportScanResult` 时备份完整缓存到宿主 cacheDir，开启时键匹配即恢复；③首启重建完成后调用现有一键重启（`ui/activity/ConfirmationDialogs.kt:425-500`）；④改三语文案（`res/values*/strings.xml` 的 `roaming_compat_tip`）与 `README.md:320,400,472,477`。
- **降级**：不要取消「删除重建」（旧 hook 点跑新宿主风险更高）；保留两次但自动第二次。

### P3 — 探索 / 建议降级

#### F15 适配缓存稳定指纹

- **结论**：S/M。改用 `pkg|versionCode|versionName|originApkCrc|RULE_VERSION`（`assets/npatch/origin.apk` 的 ZIP entry CRC 可 O(1) 读取，NPatch 自己也用它做原包身份：`OriginApkHelper.java:310-318`）；`--injectdex` 无 origin.apk 时回退现有 DEX 指纹。
- **跨进程外置只解决「卸载重装」一次，不值得优先做**；确需跨卸载用宿主 `externalMediaDirs` 单文件，而非 Remote Files/Provider IPC。
- **顺带修正**：`hook/VersionAdapter.kt:3058-3060` 的 DEX 审计注释在 NPatch 下不再成立。

#### F16 同进程模块雷达

- **结论**：S/M。ConfigProvider 无权限校验 + 宿主有 `QUERY_ALL_PACKAGES`（manager 模式 NPatch 会补：`NPatch.java:787-789`），可列已启用模块；配合锚点类探测（`runtime/KavaMemberLookup.kt:108-118` 不触发初始化）。
- **纪律**：必须 fail-unknown、只提示不自动关停、≥2 独立信号、未知宿主版本静默。

#### F17 子进程诊断

- **结论**：S。只做「诊断页显示当前进程/是否加载/Hook 数」，**不建议**开 `--injectdex`（改变 dex 落点、丧失 entry-hook 隐蔽性，且默认修补已覆盖子进程）；同时更正 `docs/npatch-compatibility-plan.md:84,148` 的表述。

#### F18 共存包名（`-p`）

- **结论**：L，**建议放 Phase 4 / 独立 experiment**。
- **复核要点**：`staticScope/scope.list` 在 NPatch 下是死元数据（`ModuleMetadataParser.java:107,146`、`ModuleMetadataReader.kt:120,181` 均无消费者），**不是**改动点；最先撞上的硬故障是「主进程判据 `processName == TARGET_PACKAGE` 全量失效」（84 处）与 13 处 `getIdentifier`；模块侧改动约 70 文件、300–500 行机械替换（M）。
- **整体是 L**：6 个未改 permission、4 个非前缀 authority、4 个 taskAffinity、267 处 dex 常量全在宿主 APK 内，安装期 `DUPLICATE_PERMISSION` / `CONFLICTING_PROVIDER` 模块侧兜不住，必须先做宿主 Manifest/dex 后处理。
- **建议 spike 顺序**：先宿主后处理确认能装能启动 → 再改模块 target identity。

#### F19 分享到微信/QQ 降级（定稿）

- **结论**：有条件可行，M；**不是「修复分享失败」，而是「检测到 NPatch 时主动降级」**。
- **为什么修不了**：微信/QQ 的签名校验发生在**对方进程**（`validateAppSignature` / `AppUtil.getSignatureFromApk`）；NPatch 已在**本进程内**把签名伪造回原始签名（`captures/npatch-src/patch-loader/src/main/java/top/nkbe/npatch/loader/SigBypass.java:42-70`、`LSPApplication.java:484`），模块只注入 `tv.danmaku.bili`，够不到微信/QQ 进程。
- **现有净化 hook 改的是真正发出去的内容**（非仅面板）：`hook/feature/SharePurifyFeatureInstaller.kt:130-141`（`getLink/getContent` 净化并写回对象）、`:155-199`（`shareMode` 6/7 → 0）。宿主统一出口是 **`ShareHelperV2.shareTo(String, Bundle)`（未混淆）**，面板另走 `ShareDataProvider`。
- **方案取舍（推荐 C）**：
  - **C（首选）**：检测 NPatch 时提示「该分享会失败」并直接复制净化链接——零侵入、无版本风险。
  - **B（次之）**：把面板里的微信/QQ 项替换为「复制链接」（hook `ShareDataProvider.j/e`）——侵入面板，`ShareChannels` 服务端下发，版本最脆。
  - **A（默认关闭）**：拦截 `IWXAPI.sendReq` / `MMessageActV2.send` / `QQShare.shareToQQ` 改写为 `ACTION_SEND`——会丢小程序/图片。
- **落点**：新增 `ShareDegradePolicy`（纯函数：`runtimeMode == NPATCH_LEGACY` 且渠道 ∈ {wechat, wechat_moment, qq, qzone} → 降级），注册在 `HookEntry.kt:3584`，能力登记进 `diagnostics/DiagnosticCapabilityCatalog.kt:185`，默认关闭、不自动开启。
- **验收**：无微信登录也能验——分享后诊断/日志出现降级证据、系统 chooser 弹出、剪贴板=净化链接；关开关验证不降级。真实「微信拒绝→降级」需一台装并登录微信的设备，**列为待验证**。

#### F20 DexOpt（ART 编译加速）

- **结论**：建议只做「能力探测 + 引导」。
- **原因**：NPatch `disableProfile`（`LSPApplication.java:758-800`）清空 `primary.prof` 并 chmod 444 → `speed-profile` 基本空转，只有 `-m speed` 有意义；默认修补下真代码是解包出来的 `origin.apk`，PMS 只覆盖外层 loader dex（需 `--secondary-dex`，待实机确认）；补丁升级 `CacheCleaner` 会清空 cache 使优化失效；`cmd package compile` 需要 root/shell(2000)/Shizuku。
- **替代**：把 `HookEntry.kt:4944,5000` 的耗时基线显式暴露到诊断页；检测到「有 su 且宿主未 AOT」时给一次性 su/ADB 建议；NPatch Manager 自带优化入口可直接引导。

#### F21 宿主偏好快照

- **结论**：**建议不做写回**，只做「只读展示 + 手动导出」。
- **原因**：B 站播放/弹幕偏好多走服务端同步，本地收益低；`SharedPreferencesImpl` 内存副本导致外部写回会被宿主 commit 覆盖（模块已有三态「未知不干预」先例：`hook/RoamingCompatHook.kt:625-640`）；`settings/backup/SettingsBackupCodec.kt` 硬绑 `PRODUCT_ID`、`SettingValue` 类型不匹配（只有 Bool/Int/String），复用成本高；且与免 Root 无因果关系。

#### F22 跨进程证书 TOFU

- **结论**：**不需要**（见「前提 1」）。
- **可选最小版本**：如果只为保护 DIRECT 全量文档（含语义 API key），可只在 DIRECT 路径首次钉扎证书摘要 + `installTime/versionCode`，变更即待确认、不自动轮换、不自动拒绝。S。

---

## 4. 建议新增的 NPatch 专属 Feature

### N1 补丁参数推荐器 / 补丁档案卡
把「当前补丁档案」（模式、sig 等级、debuggable、injectdex、loader 版本）+「推荐参数」（manager/embed、`-l` 等级、`--outputLog`、`--injectdex` 取舍）做成一张可复制的卡片，一键复制/分享给他人或贴进 Issue。免 Root 用户最大的沟通成本就是「你的补丁怎么打的」，几乎零风险（纯读 + 文案）。S。

### N2 安全模式 / 崩溃自愈
免 Root 没有 LSPosed 管理器可以随时关模块，一旦某次启动循环崩溃，用户只能卸载重装宿主。做法：注入端在宿主私有目录维护「上次启动是否完成首帧 + 连续异常退出计数」（用 bootTime + `Application.onCreate` 完成标记区分系统杀后台与真崩溃），连续 N 次进入降级模式（只装核心净化，跳过视觉引擎/布局注入/Agent/native），并通过回执字段让设置页显示「安全模式 + 一键退出」。**这是 NPatch 专项最该有的「独属」能力**。M。

### N3 宿主内「BIL 设置」入口 + 首次使用向导
复用 `runtime/RoamingSettingsLauncher.kt:31-252` 的「前台宿主发一次性固定目标 PendingIntent」整套设施（不要由模块进程直接 `startActivity`，契约测试已钉死且受 Android 10+ 后台限制），在「我的/设置」页注入第二个入口；首启向导把 F5/F7 的检测项串成分步引导（约 80% 检测项已有实现，缺「签名等级」和「后台限制」，签名等级可零成本从宿主 manifest 解出）。S/M。

### N4 模块版本漂移检测 + 免 Root 更新闭环
模块升级后，NPatch Manager 里可能仍指向旧 APK 路径/旧版本 → 心跳的 `moduleVersionCode` 与快照不匹配（`runtime/noroot/NoRootSupportStore.kt:270-273`）→ 永远显示「需要重启」。新增诊断项明确提示「Manager 中配置的模块版本 X ≠ 已安装模块 Y，请在管理器中重新选择模块 APK」，并提供跳转 Manager 的按钮（`ui/activity/FrameworkManagerLauncher.kt:16-30`）。S。

### N5 Remote Files 三合一保险库
把「上次许可配置快照 + 适配缓存 + 崩溃日志」统一放 Manager 私有目录的 Remote Files（卸载宿主保留、重打补丁保留）。注意：Remote Files 在 **Manager 私有目录**，卸载/清数据 Manager 会丢；因此保险库要有「模块 App 私有目录」作为第二副本。M（可在 F6/F8/F15 完成后增量做）。

### N6 NPatch 诊断一键打包
把「模块诊断报告 + 补丁档案 + 启动耗时 + 崩溃片段 + Media 日志尾部 + Manager/Loader 版本」打成一个脱敏 zip 导出。免 Root 用户没有 LSPosed 管理器，这是他们唯一能自助采集完整证据的方式。S/M。

### N7 授权健康度面板
Manager 可达性、四路通道命中率（provider/binder/local/broadcast）、回退来源、admission 耗时、重试次数、配置来源。直接对应 `alex` 那类「说不清为什么慢/为什么没生效」的工单。S（依赖 F9 的打点）。

---

## 5. 建议路线图与工作量汇总

| 阶段 | 内容 | 预估 | 是否需要实机 |
|---|---|---|---|
| **Phase 0（可立即做）** | F1、F2、F3、F4、F5 | 约 1 周 | 否（JVM + 契约测试即可），F5 需一轮实机确认 |
| **Phase 1（核心）** | F6 冷启动非阻塞化 + 子进程去重 + N7 打点 | 约 1.5–2 周 | 是（manager/embed 各一轮冷启动、双杀） |
| **Phase 2（支持效率）** | F7 体检面板 + F8 崩溃/日志桥接 + F9 基线 + N6 一键打包 + N4 版本漂移 | 约 1.5–2 周 | 是（构造崩溃、导出） |
| **Phase 3（体验）** | F11（先做零依赖替代）、F12 热应用子集、F13 冻结、F14 漫游、N3 宿主内入口 + 向导 | 约 2–3 周 | 是（全屏、漫游、宿主菜单） |
| **Phase 4（可选/实验）** | F10 embed、F15 稳定指纹、F16 雷达、F17 子进程诊断、F19 分享（先做 C）、F20 DexOpt 引导、N2 安全模式、F18 共存包名 | 3 周+ | 是（多数需第二台机/模拟器） |

**如果只挑三件**：F1+F2（把「关不掉/状态卡死」修干净）→ F6（把 2s 主线程阻塞拿掉）→ F7+F8（把「为什么不行」变成可诊断）。

---

## 6. 需要更正的现有文档

- `docs/npatch-compatibility-plan.md:84,148`：子进程不需要 `--injectdex` 才会加载；默认修补已覆盖，缺口在模块 installer 门控。
- `docs/npatch-compatibility-plan.md` §C 的 `--injectdex` 定位：它是上游 advanced opt-in，会改变 dex 落点并丧失 entry-hook 隐蔽性，不建议默认开。
- `docs/npatch-compatibility-plan.md` §17.4：把 `staticScope/scope.list` 列为改动点是误判（NPatch 下无消费者）；真正的第一道硬故障是主进程判据与 `getIdentifier`。
- `docs/npatch-compatibility-plan.md` §2.4 缺口 1：仍成立，但应补一句「上游已有宿主本地 prefs 镜像兜底」，我们要做的是「快照优先 + 直达文档」，而不是从零建离线缓存。
- `hook/VersionAdapter.kt:3058-3060` 的注释在 NPatch 下不再成立（DEX 审计看的是 loader dex）。
- `README.md:76-82` 建议补：签名等级推荐（≤2 最稳）、833 的 EXTREME 风险、子进程行为、embed 边界。

---

## 7. 前置条件与风险

1. **实机资源是最大瓶颈**：Android 包管理设备级共享，官方版与 patched 版**不能同机同用户共存**（`docs/npatch-compatibility-plan.md:305-309`）；当前 ADB 手机被其他会话占用。建议第二台手机 / 模拟器（x86_64 镜像有 ARM translation 风险，只用于 UI 与通道验证）。
2. **上游版本**：建议专项最低基线抬到 ≥846（833 缺 XOM 防护）；但本机只有 833 产物，846 需要用户提供或联网（当前 GitHub 不可达）。
3. **协议硬断言**：`runtime/HostRuntimeDiagnostics.kt:102`（schema=3）与 `diagnostics/DiagnosticReportCodec.kt`（`formatVersion=7`、`bootstrap.length()==11`、`framework.length()==9`、`items.length()==entries.size`）是硬门禁，任何字段新增都要同步测试；建议 P0/P1 阶段一律走「零报告变更」路线。
4. **跨 ROM 风险**：包可见性、关联启动、后台广播、Shizuku 被杀——所有「跳转/唤起/广播」类能力都必须 fail-soft + 明确降级文案。
5. **不要做的事**（避免返工）：接 hotReload 做免重启、写回宿主 prefs、为单次排查扩诊断协议、把 `--injectdex` 当子进程前提、按「修闪退」立项全屏冻结。

---

## 8. 附录：关键代码位置索引

| 主题 | 位置 |
|---|---|
| 冷启动授权与安装链 | `hook/HookEntry.kt:4966-5040,5083-5140` |
| 四路授权客户端 | `runtime/HostAdmissionClient.kt:79-192`、`runtime/HostAdmissionRoutePlan.kt`、`runtime/HostAdmissionRouteClassifier.kt` |
| 授权端点与来源选择 | `runtime/HostAdmissionEndpoint.kt:48-107`、`runtime/HostAdmissionSourcePolicy.kt:4-9` |
| 权威发布记录 | `settings/remote/PublicationAuthorityStore.kt:26-28,46-75` |
| 免 Root 快照与心跳 | `runtime/noroot/NoRootSupportStore.kt:250-320,340-412`、`NoRootSupportState.kt:48-96` |
| NPatch 网关 | `runtime/noroot/NPatchRemoteGateway.kt:38-70,118-134,152-154,205-227` |
| 宿主私有缓存范式 | `runtime/TargetAppStorage.kt:22-32`、`hook/adapter/dex/AtomicJsonCache.kt:12-46` |
| 诊断模型与协议 | `diagnostics/ModuleDiagnostics.kt:20-33`、`diagnostics/DiagnosticReportCodec.kt:21,116-134,258`、`runtime/HostRuntimeDiagnostics.kt:34-46,101-187,222-285` |
| 诊断页 | `ui/activity/DiagnosticsActivity.kt:547-609` |
| 设置目录 | `settings/backup/SettingsCatalog.kt:34-37`、`settings/backup/SettingsBackupModels.kt:49-110` |
| 免 Root 设置卡 | `ui/activity/SettingsCardSections.kt:1460-1504` |
| 一键重启 | `ui/activity/ConfirmationDialogs.kt:430-507,528-537` |
| 宿主内设置入口范式 | `runtime/RoamingSettingsLauncher.kt:31-252`、`receiver/RoamingOpenReceiver.kt:66-97` |
| 漫游扩展 | `hook/RoamingCompatHook.kt:436-516,902-989,1222-1258` |
| 分享净化 | `hook/feature/SharePurifyFeatureInstaller.kt:130-141,155-199` |
| 版本适配 | `hook/VersionAdapter.kt:90,2830-2846,3062-3097`、`hook/adapter/dex/DexSourceFingerprint.kt:41-84` |
| 液态玻璃/视觉引擎 | `hook/hostui/common/HostSurfaceScope.kt:60-83`、`hook/hostui/background/HostDetailSurfaces.kt:99-112,168-180` |

---

## 9. 调研产物与证据索引

- 上游源码副本：`captures/npatch-src/`（NPatch）、`captures/vector-src/`（Vector runtime）
- 修补产物：`captures/npatch-out/host-current-833-npatched.apk`（含 `assets/npatch/config.json`、`assets/npatch/origin.apk`）
- 实机日志与截图：`captures/npatch-bughunt-20261020/`
- 实机问题闭环记录：[兼容性专项验收结论](npatch-compatibility-plan.md#2026-10-20-专项验收结论)（B1–B12，临时清单已移除）
- 原始计划：`docs/npatch-compatibility-plan.md`
