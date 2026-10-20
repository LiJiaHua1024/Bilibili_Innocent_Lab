# NPatch 专项 Bug 清单（实机）

- 日期：2026-10-20
- 设备：adb serial `13a7a291`（OnePlus PJZ110，Android 16，arm64-v8a，1080x2160）
- 环境：patched 哔哩哔哩 8.90.2（8902100，NPatch Manager 模式，sigBypassLevel=2）+ NPatch Manager 1.0.9（833）+ 模块 1.2.0（21, debug）
- 结论口径：每条都给了实机复现步骤、原始日志/截图证据，以及与源码对应的可疑位置。**标注“已确证”的条目有直接证据；“待定”的是现象确认但根因未完全定位。**
- 条目数：**12 条**（B1–B12），另附“已确认正常、无需处理”清单以避免误报。
- **分类**：真正的 NPatch 专项 **9 条**（B1、B2、B3、B4、B6、B9、B10、B11、B12）；另有 3 条经复核**不属于 NPatch 问题**（B5、B7、B8），已在各条目内标注。
- 最严重：**B12**（Manager 进程被回收后模块静默完全失效且无任何提示）；其次 **B1**（关闭免 Root 后仍照装 Hook）。
- 已量化影响：B11 使每次冷启动多等约 2 秒、首帧多约 3 秒。
- **修复决策已完成**，见下方「修复决策记录」。
- **实施进度（2026-10-20）**：B10 的作用域探测、B12 的存活探测/状态降级、B1 的“关闭即全停用”
  均已实现并在测试机 `13a7a291` 验证（见各节末「状态」）；B11、B3、B4、B2、B9 已完成；B6 待继续。

---

## B1. 关闭「免 Root 支持」后宿主仍然拿到完整配置并安装全部 Hook（已确证，三次独立复现）

### 现象
关闭开关后，UI 明确承诺“仅在此开关开启时连接 NPatch”，但宿主重启后仍然：

- `[BIL] 已获得新鲜启动许可(source=manager, generation=...)`
- `[BIL] Hook 安装链完成耗时=1960ms`（93 个 Hook 点安装成功）

也就是说，“关闭”只影响模块设置页的状态显示，**没有真正阻止宿主继续接收配置和安装 Hook**。

### 复现步骤
1. 设置 → 模块 → 兼容 → 打开「免 Root 支持」，确认状态“已激活，已收到已修补哔哩哔哩的有效回执”。
2. 关闭「免 Root 支持」，状态变为“免 Root 支持已关闭，但当前哔哩哔哩进程中的 Hook 可能仍在运行；请强制停止并重新打开以完成关闭。”，prefs 落为 `sync_state=DISABLE_RESTART_REQUIRED`。
3. `am force-stop tv.danmaku.bili` 后重新启动宿主。

### 证据
- `captures/npatch-bughunt-20261020/coldstart-noroot-off.txt`
  ```
  10-20 09:54:25.999 ... [BIL] 已获得新鲜启动许可(source=manager, generation=1792461260177)
  10-20 09:54:27.959 ... [BIL] Hook 安装链完成耗时=1960ms
  ```
- 同期 `files/no_root_hook_config.json` 已是禁用态：
  ```json
  {"revision":1792461210758,"enabled":false,"settingCount":0,"settings":{}}
  ```
- 该次启动**没有**发送心跳（与“已关闭”一致），但 Hook 照装——这正是“关不掉”的直接证据。

第三次独立复现（`captures/npatch-bughunt-20261020/loadtest-manager-alive.txt`，2026-10-20 11:20）：
```
prefs: desired_enabled=false, sync_state=DISABLE_RESTART_REQUIRED, disable_was_active=true
10-20 11:20:46.656 [BIL] 已获得新鲜启动许可(source=manager, generation=1792466412258)
10-20 11:20:48.901 [BIL] Hook 安装链完成耗时=2246ms
免 Root 宿主回执 行数 = 0        ← 决定性判据
```
`reportNoRootHeartbeatIfSynced` 仅在 `config.noRootRevision > 0L` 时发送心跳
（`hook/HookEntry.kt:4880-4912`）。本次心跳为 0 行，说明宿主读到的 `noRootRevision <= 0`，
即配置来自**标准发布器文档**（`PublicationAuthorityStore.kt:26-28` 写死 `noRootRevision = 0L`）
而非 NPatch 通道文档——与上面的根因分析完全一致。

### 根因（源码）
- 关闭路径只做“尽力而为”的 remote 清理，失败被完全静默：
  `runtime/noroot/NoRootSupportController.kt:133-147` —— `enqueueRemoteSync(... resultHandler = {}, listener = null)`。
- 授权来源选择在关闭态下仍可落到 NORMAL：
  `runtime/HostAdmissionSourcePolicy.kt:4-9` —— 判据是 `noRootSelected == (normalNoRootRevision > 0L)`，而标准发布器在关闭态会把
  `settings/remote/PublicationAuthorityStore.kt:26-28` 的 `deliveryEnabled = true, noRootRevision = 0L` 写回 Remote Store，
  于是 `noRootSelected=false` 与 `normalNoRootRevision=0` 再次“自洽”，NORMAL 通过。
- 另外 `RemoteHookConfigStore.kt:244-245` 在开关开启时短路为 `Failure("NPatch selected")`，关闭后标准发布恢复，进一步巩固了上面这条自洽路径。

### 影响
用户以为已停用 NPatch 通道，实际宿主仍持续加载模块配置并安装 Hook；在 NPatch 下这是“无法关闭模块”的功能性缺陷。

### 建议
关闭时要么（a）把 tombstone 写入 Remote Store 并**校验**结果，失败必须显式报错而不是静默；要么（b）在 `HostAdmissionEndpoint` 侧对 `deliveryEnabled=false` 的文档直接 `denied`，不允许 NORMAL 路径回退重新授权。

### 状态（2026-10-20）：已实现

按「关闭 = 真的全停用」落地三层：

1. **授权门禁**（`HostAdmissionSourcePolicy`）：`!noRootSelected && (nPatchChannel || normalNoRootRevision > 0)`
   直接拒绝，NORMAL 与 DIRECT 都不发。`nPatchChannel` 由 `isNpatchConfigChannel(框架状态)` 判定
   （名字 + `PROP_CAP_SYSTEM` 能力位 + 排除 LSPatch），元数据未就绪时退回文档判据，保证 LSPosed 不受影响。
2. **文档卫生**（`RemoteHookConfigCommitter`）：NPatch 通道下、开关关闭时发布 `deliveryEnabled=false`
   的不可授权文档——既不给宿主留复活口子，也清掉旧版本写下的可授权标准文档。
3. **可观测性**（`NoRootSupportController`）：禁用路径的 tombstone 同步不再静默，失败记 `Log.w`。

实机验证（`captures/npatch-b12-design/`）：

| 场景 | 结果 |
|---|---|
| 关闭免 Root 后冷启动宿主 | `启动授权广播已处理(status=manager_sync_required)`，`Hook 安装链完成` **0 次**（修复前同场景为 `source=manager` + 93 个 Hook 点） |
| 重新开启后冷启动 | `source=manager` + Hook 安装链 + 心跳正常，反向无回归 |

日志证据：`captures/npatch-b12-design/b1-disabled-host.log`。

**与原建议的差异**：原回归用例写的是「关闭态 + NORMAL 文档 `noRootRevision=0` 不得返回 `NORMAL`」，
但那会误伤 LSPosed——它的正常路径正是「关闭态 + `noRootRevision=0`」（现有测试
`modeOffRejectsDirectButKeepsCurrentNormalDocument` 也钉着这条）。改为按通道判定：只有 NPatch
通道（或文档确实来自免 Root 通道）才被总开关门禁拦住，标准通道语义不变。

**已知边界**：模块 App 进程完全不可达（B11 场景）且 Remote Store 里还留着旧版本写入的可授权标准
文档时，宿主的 `remote_config_transport_fallback` 仍可能为那一次启动装上 Hook；层 2 的清理会在
模块 App 下次运行时修正。完整兜底要宿主侧接入 `NoRootRuntimeDetector`（运行模式探测），留作后续。

---

## B2. 主界面“统一功能诊断”入口徽章在 NPatch 下稳定误报“需要留意”（已确证）

### 现象
在完全健康的状态下（模块已激活 / 由 NPatch 激活（API 102）/ NPatch 通道：已激活 / 宿主安装链完成），主界面入口徽章显示 **“统一功能诊断 · 需要留意”**；点进去详情页却显示 **“暂未发现需处理问题”**。两者自相矛盾。

### 复现步骤
1. 免 Root 支持开启并确认 `sync_state=ACTIVE`（宿主心跳已回）。
2. 打开模块设置 → 模块页签，观察右上角“统一功能诊断”徽章文案。
3. 点进诊断页，观察顶部总评文案。

### 证据
- `captures/npatch-bughunt-20261020/badge-active.png`（徽章“需要留意”）
- `captures/npatch-bughunt-20261020/diag-final.png`、`diag-final2.png`（详情页“暂未发现需处理问题”，且 NPatch 通道=已激活）
- UI dump 同时可见：`模块已激活` / `统一功能诊断. 需要留意`。

### 根因（源码）
- 入口徽章判据：`ui/activity/MainActivity.kt:1050-1069`，其中
  ```kotlin
  publishFailed = publishState == RemoteHookConfigPublishState.FAILED
  ```
  而 NPatch 下 `settings/remote/RemoteHookConfigStore.kt:244-245` 会**按设计**短路返回
  `RemoteHookConfigPublishResult.Failure("NPatch selected")`，并把状态置为 `FAILED`
  （`RemoteHookConfigStore.kt:273-281`）。
- 详情页有专门的豁免入口：`diagnostics/ModuleDiagnostics.kt:185-199`
  （`standardPublisherBypassed` / `isIdleUnderStandardPublisherBypass`），**入口徽章没有用这套判据**。
- `ui/activity/ActivationCardVisualSpec.kt:32-46` 的 `diagnosticsSummaryState` 也不区分该旁路场景。

### 影响
所有 NPatch 用户、任何状态下都会看到“需要留意”，属于噪声型误报，会让人忽略真正的故障。

### 建议
入口徽章的 `publishFailed` 必须与详情页共用 `standardPublisherBypassed` 判据；同时把 `"NPatch selected"` 这个“按设计短路”的失败码从 FAILED 中区分出来（例如单独的 `BYPASSED` 状态）。

### 状态（2026-10-20）：已实现

标准发布器新增 `BYPASSED` 诊断状态，主动旁路不再记 `FAILED` 或 `publish_failed`。发布器、诊断详情与入口徽章共用 `StandardPublisherPolicy`；入口忽略免 Root 通道下的标准发布失败，但保留作用域/存活故障、Manager 错误、需要重启和皮肤回退的优先级。健康态使用现有 `READY` 文案「状态正常」。

实机 `13a7a291`：修复前「模块已激活 + 需要留意」，修复后「模块已激活 + 状态正常」，详情同步显示「暂未发现需处理问题」。证据 `captures/npatch-remaining/b2-before.png`、`b2-after.png`、`b2-diagnostics.png` 及同名视图树。Debug 构建、JVM 回归（3645 项，0 失败、1 跳过）和快速 Lint 通过；新增用例保证标准框架的真实失败与免 Root 的其他异常仍会提示。

---

## B3. `DISABLE_RESTART_REQUIRED` 状态无法被清除，“免 Root 已关闭，但 Hook 可能仍在运行”会一直留着（已确证）

### 现象
关闭免 Root 后，状态行长期停在
“免 Root 支持已关闭，但当前哔哩哔哩进程中的 Hook 可能仍在运行；请强制停止并重新打开以完成关闭。”
即使已经强制停止并重启宿主、也已确认 Hook 未再安装，该文案依然存在。

### 复现步骤
1. 开启免 Root 直到 ACTIVE。
2. 关闭免 Root（`sync_state` 落为 `DISABLE_RESTART_REQUIRED`，`disable_was_active=true`）。
3. 强制停止并重启宿主多次。

### 证据
- prefs 长期保持：
  ```xml
  <string name="sync_state">DISABLE_RESTART_REQUIRED</string>
  <boolean name="disable_was_active" value="true" />
  ```
- 读取侧逻辑：`runtime/noroot/NoRootSupportStore.kt:180-184`
  ```kotlin
  syncState = if (desired || storedState == SyncState.DISABLE_RESTART_REQUIRED) { ... }
  ```
  即关闭态下 `DISABLE_RESTART_REQUIRED` 被无条件保留。
- 唯一的清除点 `recordDisabledAck`（`NoRootSupportStore.kt:291-315`）只被
  `receiver/RoamingOpenReceiver.kt:219` 调用，且要求 `EXTRA_NO_ROOT_ACTIVE == false`；
  而宿主侧唯一的发送点 `hook/HookEntry.kt:4895-4903` 把 `active = true` **写死**，
  全仓库没有任何地方发送 `active=false`。

### 影响
状态永久卡住，用户无法判断“关闭是否真的生效”；同时也会持续点亮 B2 的“需要留意”（`MainActivity.kt:1053-1061` 把 `DISABLE_RESTART_REQUIRED*` 归为需关注）。

### 建议
要么实现 `active=false` 的发送路径（宿主在未安装 Hook 时回执“已停用”），要么在宿主重启且本地确认无 Hook 后由模块侧主动清除该状态。

### 状态（2026-10-20）：已实现

关闭文档保留本次 tombstone revision（包括标准发布器的清理写入）。宿主读取当前版本关闭文档后放弃安装闭包及前台重试，发送 `active=false` 回执；模块仅在开关仍关闭、revision/模块版本/当前宿主版本与更新时间匹配时清除提示。子进程不能确认停用。

返回设置页时也探测尚待确认的关闭态；宿主仅在安装已进入停用终态时附带关闭 revision。此回复受现有身份、nonce 和当前快照校验保护，不把启动途中 `hooksInstalled=false` 当成停用证明。

实机 `13a7a291` 验证：关闭后冷启动，`DISABLE_RESTART_REQUIRED → DISABLED`，文案变为「已关闭，不会连接 NPatch」；设置进程被停止时保持待确认，重新打开设置后也能清除；重新开启后 Hook 安装链与心跳正常。证据在 `captures/npatch-remaining/b3-*`（含修复前后 prefs、截图、停用/启用及探测日志）。Debug 构建、3639 个 JVM 测试及快速 Lint 通过。

---

## B4. 打开免 Root 时偶发误判为 `MODULE_NOT_REGISTERED`，文案误导为用户配置问题（已复现，间歇）

### 现象
在 Manager 进程刚被回收/未就绪时切换免 Root 开关，状态变成：
“NPatch Manager 未接受当前模块。请在管理器中添加并启用模块、确认哔哩哔哩在其作用域内，并将 NPatch 升级到 1.0.7 或更新版本。”
但此时 **Manager 作用域配置完全正常**：
`content query --uri 'content://top.nkbe.npatch.manager.provider.config?package=tv.danmaku.bili'`
→ `Row: 0 packageName=com.Bilibili_Innocent_Lab.xposedmodule`

### 复现步骤（稳定触发）
1. `adb shell am force-stop top.nkbe.npatch`（Manager 进程不在）。
2. 设置里把「免 Root 支持」关一次、再开一次。
3. 观察状态行变成 `MODULE_NOT_REGISTERED`。
4. 启动 Manager（`am start -n top.nkbe.npatch/.ui.activity.MainActivity`）后再关/开一次，状态恢复正常。

另有一次未停 Manager 也偶发出现（`toggle2-logcat.txt` 记录的第二次开关），说明存在竞态。

### 证据
- prefs：`<string name="sync_state">MODULE_NOT_REGISTERED</string>`（desired_enabled 仍为 true）
- `captures/npatch-bughunt-20261020/manager-stopped-toggle.txt`、`toggle2-logcat.txt`
- 同期日志里 Manager 进程确实不在（`ps -A | grep npatch` 为空）。

### 根因（源码）
- `runtime/noroot/NPatchRemoteGateway.kt:118-134`：任何 `SecurityException` 都被映射成 `ModuleNotRegistered`；
  `:216-218` 中 `connectServiceBlocking` 在 **拿不到 binder** 时直接
  `throw SecurityException("NPatch rejected module identity")`——
  这把“Manager 进程暂时不可达”也归类成了“模块未被接受”。
- `:102` 的 `isAvailable()` 只检查 Provider 能否解析到 Manager 包名，进程死亡时仍可能返回 true，于是走进写路径再被误分类。

### 影响
用户会去反复检查 Manager 配置（实际上没错），真正的“Manager 未运行/连接失败”反而不显示 `MANAGER_MISSING`。

### 建议
区分“binder 为空/进程不可达”与“真实身份拒绝”：前者应映射为 `ManagerMissing` 或 `ConnectionTimeout`，只有 `RemoteException` 里的显式拒绝才映射为 `ModuleNotRegistered`。

### 状态（2026-10-20）：已实现

空 Binder、失活 Binder 和 Provider 被平台拒绝访问改为连接不可达，不再伪造 `SecurityException`；保留远端事务显式身份拒绝的分类。连接失败最多自动重试一次（间隔 200ms），重试共享原超时和用户意图检查，只重试连接，不重放写入；已阻塞的调用仍由既有超时和熔断保护。

本地 NPatch `RemoteApiProvider` 源码显示，未配置模块也可能只返回 null，并无拒绝码；因此单凭空返回无法区分配置问题与暂时不可达，保守显示连接问题，不能宣称已确认模块未注册。三种语言的提示改为先打开 Manager 后重试，同时保留仅支持管理器模式的说明。

实机 `13a7a291`：Manager 停止时由修复前 `MODULE_NOT_REGISTERED` 变为 `MANAGER_MISSING`，最终文案明确引导打开 Manager 重试；恢复 Manager 后同步及冷启动心跳恢复正常。证据在 `captures/npatch-remaining/b4-*`。8 个网关策略测试（含新增 4 个重试边界用例）、全部 3643 个 JVM 测试、Debug 构建及最终资源的快速 Lint 通过。

---

## B5. 冷启动长期 `am start -W` 超时（10s+），首帧约 14s（性能观察，**主因不在模块**，待对照）

### 现象
连续 6 次冷启动全部 `Status: timeout`，`WaitTime` 稳定在 10.1–10.3 秒；`ActivityTaskManager` 记录首帧 14–15 秒。

### 证据
- 3 轮测量：`WaitTime: 10215 / 10142 / 10159`，全部 `Status: timeout`
- `captures/npatch-bughunt-20261020/coldstart-detail.txt`：
  ```
  10:00:22.115 NPatch: createLoadedApkWithContext cost: 882ms
  10:00:22.468 NPatch: Manager connected within startup deadline
  10:00:29.232 [BIL] Hook 安装链完成耗时=1938ms
  10:00:34.671 ActivityTaskManager: Displayed tv.danmaku.bili/.MainActivityV2: +14s111ms
  ```
- 关闭免 Root（B1 状态下 Hook 仍会安装）时同样是 `Displayed ... +15s372ms`。

### 分析
模块 Hook 链只占约 1.9–2.2 秒；其余时间在 NPatch loader（`createLoadedApkWithContext` 0.88–1.25s）、模块 APK 加载与宿主自身初始化。
**但请注意**：目前没有“未修补官方版”的同机基线，因此不能把 14s 全部归因于模块。要定性，需要在同一台机器上对比一份不注入模块的 patched 包。

### 建议
先补一份对照基线再定责；如确认模块侧有可观占比，优先看 `HookEntry` 里三方 Hook（顶栏/底栏视觉、视频卡片等）在主线程上的初始化顺序。

---

## B6. 每次开关/启动都产生 `receipt stage=push reason=SESSION_MISMATCH` 警告，且回执被真正丢弃（已确证，可诊断性）

### 现象
几乎每次宿主启动或开关免 Root 后，都会出现：
```
W/BilibiliInnocentLab: receipt stage=push reason=SESSION_MISMATCH
```
`SESSION_MISMATCH` 是**真实拒收**（`accepted=false`），不是纯日志噪声。

### 证据
- `baseline-logcat.txt`（`reason=SEND_FAILED`）、`noroot-enabled-start.txt`、`noroot-disabled-start.txt`、
  `state-disabled-remote.txt`、`final-verify.txt` 均为 `SESSION_MISMATCH`。

### 根因（源码）
- `runtime/HostReceiptRegistry.kt:57`：在进入 session 校验前先把 `failure` 预置为 `SESSION_MISMATCH`；
  只要后续任一 `return@runCatching false`（如 `:68-71` 的 `gate.accept` 失败、`:86` 序号不新、`:76-79` binder 已死、
  甚至 `:42` 源信息不匹配）都会以 `SESSION_MISMATCH` 出栈。
- 输出点 `:107` → `runtime/ReceiptQueryLog.kt:9-16`（60 秒限流，所以同一时段只看到一条）。

### 影响
- 回执被丢弃 → 诊断/组件快照数据可能缺失（表现为“尚无安装回执”）。
- 日志把多种不同的失败原因压成同一个码，实机排查时无法定位。

### 建议
在每条 `return false` 前分别赋具体 `ReceiptQueryFailure`（`SOURCE_MISMATCH`/`SURFACE_MISMATCH`/`STORE_FAILED` 等），
并复核 `ReceiptSessionGate.accept` 的 `started == latestStart && current != Session(...)` 分支是否在正常重启下被误判。

---

## B7. 视频详情页“活动”标签净不掉（**已查清：不属于 NPatch 问题，是模块适配覆盖缺口**）

### 现象
详情页标题前仍显示“活动”标记：

```
[活动] 2026年iPhone13mini到底还能不能当主机…
```

### 证据
- `captures/npatch-bughunt-20261020/detail-open2.png`、`player-controls.png`、`ctl-now.png`（三张均可见“活动”标签）
- 诊断页“详细项”里“详情推广·广告推荐视图：尚无独立的子能力证据 · 无可信回执”

### 归因（2026-10-20 追查结论）
负责该设置项的功能是 `detail_app_promotion`（设置项文案 `hide_video_detail_app_promotion`，
「隐藏视频详细页话题推广」→「开启后隐藏视频播放器下方的话题、活动等推广」，
映射见 `ui/activity/DiagnosticsActivity.kt:965`）。宿主回执显示：

- 父能力 `detail_app_promotion` = **INSTALLED，3 个 Hook**；
- 宿主日志确认 `[BIL] 视频详细页广告门面拦截器已就绪(underPlayer=true,relate=true)`；
- 但它声明的 5 个子能力中有 4 个处于 `UNKNOWN / CAPABILITY_UNVERIFIED`
  （`detail_promotion_nest`、`detail_promotion_list`、`detail_promotion_hd`、`detail_promotion_related_ad`），
  即“已声明但拿不到任何安装证据”（产生点 `hook/feature/FeatureInstaller.kt:177-194`，
  `runtime/HostRuntimeDiagnosticsBridge.kt:235-237`）。

**结论：配置下发与 Hook 安装链路在 NPatch 下是通的，Hook 确实装上了**；问题在于装上之后没能匹配当前
B站 8.90.2 的界面结构。这些 Hook 与适配逻辑全部运行在宿主进程内部，与“配置从哪条通道传来”无关，
换 LSPosed 也会装同一批 Hook、跑同一套适配。

> 因此本条**不计入 NPatch 专项**，作为模块对当前宿主版本的适配覆盖问题单独跟踪。
> 唯一无法 100% 排除的原因是本机没有 LSPosed 环境可对照。
> 与 `docs/npatch-compatibility-plan.md` 19.2 节记录的“详情页仍有广告卡片”属同类问题，本次是第三次独立复现。

---

## B8. 子进程（`:download` / `:ijkservice`）也加载了模块并各自拉配置、装 Hook（**属于 NPatch 注入行为，非模块缺陷**，仅需更正文档）

### 现象
未使用 `--injectdex` 的情况下，NPatch loader 仍然注入子进程：

```
10:00:36.412 NPatch: createLoadedApkWithContext cost: 1252ms   (pid 18157)
10:00:36.766 NPatch: createLoadedApkWithContext cost: 1112ms   (pid 18187)
[BIL] Modern 模块入口已加载(api=102, process=tv.danmaku.bili:ijkservice)
[BIL] Modern 模块入口已加载(api=102, process=tv.danmaku.bili:download)
[BIL] 已获得新鲜启动许可(source=manager, generation=...)   ← 子进程也走了一次授权
[BIL] Hook 点诊断: resolved=3, installed=35                ← 子进程也装了 35 个 Hook
```

### 结论
- 心跳有正确的门控（`hook/HookEntry.kt:4882` 的 `TargetProcess.isMainProcess`），子进程不会抢心跳——这点是对的。
- 但 `docs/npatch-compatibility-plan.md` 中“子进程需要 `--injectdex` 才会加载模块”的表述与实测不符，建议更正。
- 副作用是所有子进程都要付出一次 NPatch `createLoadedApkWithContext`（1.1–1.25s）+ 配置读取的开销。

---

## B9. NPatch 通道在设置页的 `"NPatch selected"` 警告每次设置变更都刷日志（低）

```
W/BilibiliInnocentLab: publish remote hook config failed: NPatch selected
```
单次冷启动可见 6–10 条。这是 B2 的同一个根因（把“按设计短路”当成失败来 `Log.w`），
但它同时污染了用户日志，会让真正的发布失败难以发现。

位置：`settings/remote/RemoteHookConfigStore.kt:373-376`（`logFailure`）+ `:244-245`（短路点）。

### 状态（2026-10-20）：已实现

只把已识别的标准发布器主动旁路降为 `Log.d`（`standard remote hook config publisher bypassed`）；其余失败仍走原 `Log.w`，保留异常信息。沿用 B2 的旁路分类，避免按模糊文本吞掉真实故障。

实机同一操作（关闭再恢复「捕捉日志」，最终值保持开启）：修复前产生两条 `W ... publish remote hook config failed: NPatch selected`，修复后为两条 `D ... publisher bypassed: NPatch selected`。证据 `captures/npatch-remaining/b9-before.log`、`b9-after.log`。

Debug 构建及快速 Lint 通过；日志级别修改复用 B2 已通过的旁路逻辑回归，未重复运行 JVM 全量测试。

---

## B10. 把模块移出 NPatch 作用域后，设置页仍宣称“模块已激活（回执已验证）”（已确证）

### 现象
在 NPatch Manager 里把模块从哔哩哔哩的模块作用域中移除后，模块**已经完全不再被注入**，但模块设置页依然显示：

- 标题：`模块已激活`
- 副标题：`由 NPatch 激活（回执已验证）`

而且文案在模块停止运行后**反而升级**成了更强的断言：移除作用域前显示的是
`由 NPatch 激活（API 102）`（即 `ACTIVE_LSPATCH`，表示宿主回执 `ACCEPTED + COMPLETED`），
移除后变成 `由 NPatch 激活（回执已验证）`（`ACTIVE_NPATCH`）——一个“回执已验证”的现在时断言。

### 复现步骤
1. 确认模块处于 ACTIVE、作用域正常（`content query` 返回模块包名）。
2. 打开 NPatch Manager → 管理 → 哔哩哔哩 → 模块作用域，取消勾选模块 → 添加。
3. 确认 `模块作用域 = 0 个模块`，且 `content query` 返回 `No result found`。
4. 强制停止并重启哔哩哔哩，确认日志里**没有任何** `BilibiliInnocentLab` 行，且
   `NPatch: Loading modules from local cache: []`。
5. 打开模块设置 → 模块页签。

### 证据
- `captures/npatch-bughunt-20261020/scope-removed-host.txt`（作用域移除后宿主启动）
  ```
  10:22:28.925 NPatch: Loading modules from local cache: [{...com.Bilibili_Innocent_Lab.xposedmodule...}]  ← 移除生效前的残留进程
  10:22:41.649 NPatch: Loading modules from local cache: []      ← 模块已不在作用域
  10:22:41.996 NPatch: Loading modules from local cache: []
  ```
  整份日志中 `BilibiliInnocentLab` 出现 0 次（正则同时匹配两者时仅命中上面 3 行缓存行）。
- `captures/npatch-bughunt-20261020/bug10-fresh2.png` + UI dump：
  ```
  [377,604] 模块已激活
  [458,673] 由 NPatch 激活（回执已验证）
  ```
- 对照：`scope-removed-module.png`（移除后早期仍显示 `API 102`）

### 根因（源码）
- 激活判定完全由**本地持久化的心跳**推导，没有任何时效或存活校验：
  `runtime/noroot/NoRootSupportState.kt:67-78`
  ```kotlin
  val heartbeatMatches = currentSnapshot?.let { snapshot ->
      snapshot.enabled && status.heartbeatRevision == snapshot.revision && ... &&
          status.heartbeatReceivedAt > 0L     // 只判断“收到过”，不判断“多久以前”
  } == true
  if (heartbeatMatches) return NoRootDisplayState.ACTIVE
  ```
  作用域被撤销不会改变这些持久化字段，因此状态永久停留在 ACTIVE。
- 文案切换路径：`runtime/noroot/NoRootSupportState.kt:142-170`
  `ACTIVE_LSPATCH`（宿主回执 CONFIRMED）→ `ACTIVE_NPATCH`（回执不再 CONFIRMED 但本地心跳仍在）。
  即宿主停止上报回执时，界面不是降级，而是换成了另一句同样肯定的话。

### 影响
用户在 Manager 里撤销作用域后，模块设置仍然告诉他“已激活、回执已验证”，会让人误以为配置仍在生效；
排查“为什么模块没效果”时这条错误状态会直接把方向带偏。这是 NPatch 管理器模式独有的场景
（LSPosed 下作用域由框架统一管理，用户较少遇到）。

### 建议
- 给心跳加时效与存活判据：例如要求 `heartbeatReceivedAt` 在合理窗口内，或校验宿主当前是否真的在运行；
- 至少在文案上区分“最近一次已验证”与“当前正在生效”，不要用现在时断言；
- 作用域被移除时应能探测到（`NPatchRemoteGateway` 的 Provider 查询返回 `No result found` 是现成信号）。

### 状态（2026-10-20）：部分完成

- **已完成（建议第 3 条）**：作用域探测 + 状态降级。新增 `runtime/noroot/NPatchScopeProbe.kt`
  只读查询 Manager 的 `top.nkbe.npatch.manager.provider.config`，作用域移除后进入新的
  `SCOPE_MISSING` 状态，激活卡片、免 Root 状态行与诊断页均不再宣称已激活；查询失败/超时
  只返回 `UNKNOWN`，不误降级。实机验证：`captures/npatch-scope-probe/`（`IN_SCOPE →
  OUT_OF_SCOPE → IN_SCOPE` 三段日志与 `scope-out.png` / `scope-back2.png` 截图）。
- **已完成（建议第 1、2 条）**：心跳时效与“最近一次已验证”文案，由 B12 的存活探测一并
  解决——`ACTIVE` 现在要求宿主实时回执，卡片区分“已激活 / 未确认 / 未生效”，“未确认”
  副标题给出最近一次回执的相对时间。

---

## B11. 冷启动授权通道依赖模块应用进程存活，否则每次白等 2000ms 并降级回退（已确证，可量化）

### 现象
模块应用（`com.Bilibili_Innocent_Lab.xposedmodule` 设置进程）没有运行时，宿主冷启动的四路授权通道
（provider / binder / local / broadcast）**全部无响应**，模块把 2000ms 授权窗口烧满后退化为
`remote_config_transport_fallback`。模块应用进程存活时，同一操作只需约 30–200ms 并走 `source=manager`。

### 复现步骤（双向可复现）
```bash
# A) 模块应用未运行
adb -s 13a7a291 shell am force-stop com.Bilibili_Innocent_Lab.xposedmodule
adb -s 13a7a291 shell am force-stop tv.danmaku.bili
adb -s 13a7a291 shell am start -n tv.danmaku.bili/.MainActivityV2

# B) 模块应用存活
adb -s 13a7a291 shell am start -n com.Bilibili_Innocent_Lab.xposedmodule/.ui.activity.MainActivity
adb -s 13a7a291 shell am force-stop tv.danmaku.bili
adb -s 13a7a291 shell am start -n tv.danmaku.bili/.MainActivityV2
```

### 证据（连续对照）
| 场景 | 授权耗时 | 来源 | 首帧 |
|---|---|---|---|
| 模块应用未运行 | `admission=2008ms` | `source=remote_config_transport_fallback` | `Displayed +16s927ms` |
| 模块应用未运行 | `admission=2009ms` | `remote_config_transport_fallback` | — |
| 模块应用未运行 | `admission=2008ms` | `remote_config_transport_fallback` | — |
| 模块应用未运行 | `admission=2016ms` | `remote_config_transport_fallback` | — |
| 模块应用未运行 | `admission=2013ms` | `remote_config_transport_fallback` | — |
| 模块应用存活 | `admission=224ms` | `source=manager` | — |
| 模块应用存活 | `admission=192ms` | `source=manager` | — |
| 模块应用存活 | `admission=33ms` | `source=manager` | `Displayed +13s873ms` |

用户可见首帧差约 **3.0 秒**（16.9s → 13.9s）。

四路通道全部失败的完整链路（`captures/npatch-bughunt-20261020/scope-restored.txt`）：
```
receipt stage=provider reason=SEND_FAILED
[BIL] 启动授权尝试(process=tv.danmaku.bili)
[BIL] 启动授权通道结果(route=provider,  reason=admission_unavailable)
[BIL] 启动授权通道结果(route=binder,    reason=admission_unavailable)
[BIL] 启动授权通道结果(route=local,     reason=admission_unavailable)
[BIL] 启动授权广播已发送(method=prepare_admission)
[BIL] 启动授权广播回退已发送(method=prepare_admission)
[BIL] 启动授权广播用户回退已发送(method=prepare_admission)
[BIL] 启动授权 PendingIntent 回退已发送(method=prepare_admission)
[BIL] 启动授权传输不可达，采用已校验 Remote Preferences 回退
[BIL] 启动授权通道结果(route=broadcast, reason=admission_unavailable)
[BIL] 启动授权阶段耗时(remote_config=55ms, admission=2006ms, total=2062ms, result=granted)
```
对照（模块应用存活时）：
```
[BIL] 启动授权广播收到回执(method=prepare_admission)
[BIL] 启动授权广播收到回执(method=confirm_admission)
[BIL] 启动授权阶段耗时(remote_config=54ms, admission=33ms, total=87ms, result=granted)
```

### 根因（源码）
- `runtime/HostAdmissionRouteClassifier.kt:26-31`：只要 status 不是 `prepared` / `granted` /
  三个明确拒绝值之一，一律归类为 `UNAVAILABLE`（`admission_unavailable`）。
  四路全部拿不到响应时，就得到 4 条 `admission_unavailable`，随后走
  `allowRemoteConfigFallback`（`:55-65`）。
- 判定逻辑本身没错，问题在于**通道可用性与模块应用进程绑定**，而该进程在真实使用中通常不在：
  普通用户不会常驻模块设置页，因此这个 2 秒等待在绝大多数冷启动中都会发生。
- `admission_unavailable` 是四路共用的兜底码，无法区分“接收方进程不存在”“超时”“包可见性受限”，
  实机排查时看不出根因（与 B6 同类的可诊断性问题）。

### 影响
- 每次冷启动固定多等约 2 秒，并连带使首帧多约 3 秒（叠加 B5 的 14 秒基线）。
- 回退路径虽能正确授权（`result=granted`），功能不受影响，但代价是纯浪费的等待。

### 建议
- 在尝试四路通道前先探测模块应用进程/Provider 是否可达；不可达时直接跳过 2000ms 窗口走回退。
- 让 `admission_unavailable` 携带具体子原因（`peer_process_dead` / `timeout` / `visibility_denied`）。

### 状态（2026-10-20）：已实现

系统完成有序广播且无人处理时，若启动已有通过协议、版本、条款和完整性校验的 NPatch 文档，立即结束广播等待；其他路线仍须结束，任何明确拒绝仍禁止回退。无有效文档及标准框架的广播回退维持原有等待语义，避免误伤厂商普通广播保底。

- 测试机 `13a7a291`，Manager 存活、模块设置进程已强制停止：授权 **2007ms → 69ms**，Hook 安装链正常完成。
- 证据：`captures/npatch-remaining/b11-before.log`、`b11-after.log`；本轮未单独量化首帧收益。
- 模块设置进程存活时仍走 `source=manager`（236ms），反向无回归；证据 `b11-alive.log`。
- Debug 构建、37 个授权相关 JVM 回归和快速 Lint 通过。

---

## B12. NPatch Manager 进程被回收后，模块静默完全失效且无任何提示（已确证，最严重）

### 现象
NPatch Manager 进程被系统回收（本机 ROM 常见，Manager 常被冻结/杀死）后，宿主冷启动时：

1. 宿主向 Manager 的 `ModuleService` 发起绑定；
2. 本机 ROM（OnePlus/ColorOS 的 `OplusAppStartupManager`）**拦截该服务启动**；
3. NPatch 打印 `Manager not ready in time; starting with local cached modules`，退化为本地缓存加载；
4. NPatch 报告 `Loaded cached module com.Bilibili_Innocent_Lab.xposedmodule`；
5. **但模块入口从未执行** —— 日志中 `[BIL]` 与 `BilibiliInnocentLab` 标签均为 **0 行**。

结果是宿主在**完全没有模块运行**的状态下正常启动，界面没有任何异常提示；而模块设置页因为心跳持久化
（见 B10）仍然显示“模块已激活”。用户会认为净化生效了，实际上一个 Hook 都没装。

### 复现步骤
```bash
# 1. 让 Manager 进程不在（系统会自行回收；也可由系统冻结触发）
adb -s 13a7a291 shell "ps -A | grep npatch"        # 期望：无输出
# 2. 冷启动宿主
adb -s 13a7a291 shell am force-stop tv.danmaku.bili
adb -s 13a7a291 shell am start -n tv.danmaku.bili/.MainActivityV2
# 3. 观察日志
adb -s 13a7a291 logcat -d | grep -c '\[BIL\]'      # 期望：0
# 4. 恢复：启动一次 Manager，再冷启动宿主
adb -s 13a7a291 shell am start -n top.nkbe.npatch/.ui.activity.MainActivity
adb -s 13a7a291 shell am force-stop tv.danmaku.bili
adb -s 13a7a291 shell am start -n tv.danmaku.bili/.MainActivityV2
adb -s 13a7a291 logcat -d | grep -c '\[BIL\]'      # 期望：数百
```

### 证据（双向对照，各 3 次以上）
| Manager 进程 | `[BIL]` 行数 | 系统拦截次数 | NPatch 判定 |
|---|---|---|---|
| 已死 | **0** | 3 | `Manager not ready in time; starting with local cached modules` |
| 已死 | **0** | 3 | 同上 |
| 已死 | **0** | 3 | 同上 |
| 存活 | **216** | 0 | `Manager connected within startup deadline` |

关键日志（`captures/npatch-bughunt-20261020/bug1-reverify.txt`、`loadtest-1/2/3.txt`）：
```
I OplusAppStartupManager: prevent start top.nkbe.npatch,
    cmp {top.nkbe.npatch/top.nkbe.npatch.manager.ModuleService} by tv.danmaku.bili callingUid 10344, Type bs, scenePriority 0
I NPatch  : Loading modules from local cache: [{"path":".../com.Bilibili_Innocent_Lab.xposedmodule-.../base.apk","packageName":"com.Bilibili_Innocent_Lab.xposedmodule"}]
I NPatch  : Loaded cached module com.Bilibili_Innocent_Lab.xposedmodule
W NPatch  : Manager not ready in time; starting with local cached modules
W NPatch-Loader: VectorServiceClient service is not ready for native LoadedModule proxy
W NPatch-Loader: VectorServiceClient service is not ready for LoadedModule path compatibility
```
对照（Manager 存活，`loadtest-manager-alive.txt`）：
```
I NPatch  : Manager connected within startup deadline
I BilibiliInnocentLab: [BIL] Modern 模块入口已加载(api=102, process=tv.danmaku.bili)
I BilibiliInnocentLab: [BIL] 已获得新鲜启动许可(source=manager, generation=1792466412258)
I BilibiliInnocentLab: [BIL] Hook 安装链完成耗时=2246ms
```

### 责任划分
- `OplusAppStartupManager` 的拦截是 ROM 行为，NPatch 无法直接控制；
- 但 **NPatch 在退化到“本地缓存模块”后没有让模块真正跑起来**，且**模块侧完全无从感知**
  —— 这属于 NPatch / 模块两侧都可改进的部分：
  - NPatch 侧：本地缓存回退路径应真正加载并调用模块入口，或在失败时向宿主/用户暴露；
  - 模块侧：无法区分“自己没被注入”与“被注入但未执行”，也没有任何外部可见的存活信号。

### 影响
这是本轮最严重的问题：**模块可能在任何一次冷启动后静默失效**，用户看到的是“设置里显示已激活、
B 站一切正常”，但净化完全没有生效。叠加 B10（状态永不过期）后，用户几乎不可能自行发现。

### 与 B10 的叠加效应
B10 让状态停留在 ACTIVE；B12 让模块真的没跑。两者组合后：
```
设置页：模块已激活 · 由 NPatch 激活（回执已验证）
实际：  0 个 Hook 安装，模块进程内没有任何模块代码执行
```
建议在修复时把这两条一起处理：至少让状态能反映“模块本次是否真的执行过”。

---

## B12 判据设计（2026-10-20）

### 结论

普通 App 无法读取其他应用进程；NPatch Manager 1.0.9 的公开 Provider（`ConfigProvider`）只接受
`?package=` 并返回作用域模块列表，不暴露“模块是否已加载”。因此 B12 不能在模块侧“自动确认
宿主在运行但模块没加载”，只能把结论分成三档：

1. **已配置**：作用域包含模块 + 配置/心跳匹配（历史证据）。
2. **当前已确认运行**：设置页向宿主发一次带 nonce 的存活探测，宿主进程内的动态接收器在
   1.5 秒内回复。
3. **验证失败**：用户点“验证”启动哔哩哔哩后，15 秒内既没有探测回复也没有新心跳，才判定
   “模块未生效”。

`ACTIVE` 从此只由第 2 档支撑；第 1 档改为 `UNCONFIRMED`（信息态），不再宣称已激活。

### 为什么心跳不够

- heartbeat 持久化在模块 App，只证明“过去某个时刻宿主装过 Hook”，进程死亡/重启不可见。
- B12 的静默失败正是“上次心跳还在、这次宿主根本没执行模块代码”。
- 心跳改为前台续期也只能覆盖“宿主最近活跃”，不能区分“宿主没运行”和“宿主运行但模块没加载”。

### 探测通道

请求（模块 App → 宿主主进程）：

- action `com.Bilibili_Innocent_Lab.xposedmodule.PROBE_HOST_RUNTIME`，
  `setPackage("tv.danmaku.bili")`。
- extras：`nonce`、`moduleVersion`、`callerProof`（模块 App 创建的 immutable PendingIntent）。
- 宿主动态接收器（只在主进程注册，`RECEIVER_EXPORTED`）校验：
  - API 34+ `sentFromPackage == 模块包名`；
  - 所有版本 `callerProof.creatorPackage == 模块包名`（包名由系统按 UID 生成，不可伪造）；
  - nonce 合法；不写任何状态。
- 不声明 manifest receiver，因此宿主没运行时广播不会拉起 B站进程。

回复（宿主 → 模块 App）：

- 显式广播到 `RoamingOpenReceiver`，携带 `nonce`、`hostPid`、`hostStartElapsedMs`
  （`Process.getStartElapsedRealtime()`）、`hooksInstalled`、`noRootRevision`、`hostProof`
  （宿主创建的 PendingIntent）。
- 模块侧校验 `sentFromPackage` / `creatorPackage == tv.danmaku.bili` 和 nonce；只接受当前
  未过期的单次探测（TTL 30 秒，单飞）。
- 不传配置内容、用户数据或日志。

### 状态机

| 条件 | 状态 | 语气 | 用户可见 |
|---|---|---|---|
| 开关关闭 | `DISABLED` / `DISABLE_RESTART_REQUIRED*` | 原样 | 原样 |
| 作用域明确不含模块 | `SCOPE_MISSING` | ACTION_REQUIRED | 模块不在作用域；打开 Manager |
| 心跳不匹配 | 原有 `SYNCING` / `RESTART_REQUIRED` / `MANAGER_MISSING` 等 | 原样 | 原样 |
| 心跳匹配 + 探测中 | `CHECKING` | INFO | 正在确认模块运行状态… |
| 心跳匹配 + 探测回复 | `ACTIVE` | OK | 当前已确认模块正在运行 |
| 心跳匹配 + 无探测回复 + 未验证 | `UNCONFIRMED`（新） | INFO | 未确认当前是否运行；最近回执 X 前；[验证] |
| 心跳匹配 + 验证启动宿主后超时 | `NOT_RUNNING`（新） | ACTION_REQUIRED | 模块未生效：已启动哔哩哔哩但未收到回执；[打开 Manager][重试] |

- `UNCONFIRMED` 不算故障，诊断徽章显示 INFO；只有 `NOT_RUNNING` 才升级为 ACTION_REQUIRED。
- `ACTIVE` 要求 heartbeatMatches && liveProbeReplied，避免“历史心跳 = 已激活”。

### 验证流程

1. 用户在 `UNCONFIRMED` 点“验证”。
2. 记录 `attemptStartedAt`，置 `PROBING`，`getLaunchIntentForPackage("tv.danmaku.bili")` 启动宿主。
3. 发探测；同时监听新 heartbeat（`heartbeatReceivedAt > attemptStartedAt`）。
4. 15 秒内收到探测回复或新心跳 → `ACTIVE`（心跳不匹配则仍 `RESTART_REQUIRED`）。
5. 超时 → `NOT_RUNNING`，提供打开 NPatch Manager。
6. 启动失败/无入口 → 保留 `UNCONFIRMED` 并提示，不误判为未生效。

### 边界

- 宿主未运行：`UNCONFIRMED`，不是 `NOT_RUNNING`；只有用户主动验证后才可能判定失败。
- 宿主运行但模块未加载：探测无回复；验证流程在启动后仍无回复 → `NOT_RUNNING`。
- 后台广播延迟：resume 探测 1.5 秒超时只降为 `UNCONFIRMED`；验证时宿主被拉到前台，
  15 秒窗口足够。
- 子进程：只在主进程注册；子进程不响应。
- LSPosed：探测通道同样可用，但只在 `desiredEnabled` 时启用，不改变 LSPosed 状态语义。
- 安全：PendingIntent 包名 + API 34 `sentFromPackage` + nonce/TTL；不响应其他应用。

### 实施拆分（每步一个原子提交）

1. 宿主动态探测响应器 + 模块探测客户端 + 接收器分支 + 纯 JVM 测试。
2. 状态机/枚举/文案/诊断 + 纯 JVM 测试。
3. 激活卡操作行（验证 / 打开 Manager）+ 验证流程 + 实机验证。
4. 文档与 B12 复测记录。

### 状态（2026-10-20）：已实现

实际落成三个提交（探测通道 / B12 状态与界面 / 文档），要点与设计一致，另有几处实现细节：

- **通道**：请求由 signature 权限 `PROBE_HOST_RUNTIME` 把关，回执用宿主自建 PendingIntent +
  `sentFromPackage` 证明身份；`nonce` 单窗口关联，窗口内每 1 秒重发一次以覆盖宿主冷启动。
- **回执必须走显式组件**：`RoamingOpenReceiver` 的 manifest 过滤器只声明了授权查询 action，
  仅 `setPackage` 的隐式广播不会被投递（实测踩到，已修正）。
- **API 34+ 必须经 `CrossAppBroadcastCompat` 发送**：裸 `sendBroadcast` 不共享发送者身份，
  `sentFromPackage` 为 null，接收端一律拒绝（实测踩到，已修正）。
- **`NOT_RUNNING` 是粘性结论**：验证失败后，回到设置页的顺手探测超时不会把它降级成
  “未确认”，只有新的回执/心跳能推翻。
- **`UNCONFIRMED` 的徽章语气用 ATTENTION 而不是设计稿里的 INFO**：否则徽章会显示
  “检查中”，与卡片“未确认”自相矛盾。
- **操作入口放进激活卡片内部**：放在页面根布局会被 SettingsHomePresenter 留在悬浮层之外，
  用户看不到（实测踩到，已改）。

实机验证（`captures/npatch-b12-design/`）：

| 场景 | 结果 | 证据 |
|---|---|---|
| 宿主运行且 Hook 已装 | 探测回复 `hooksInstalled=true`，卡片「模块已激活」 | `b12-live.png`、`b12-final-active.png` |
| 宿主已停止 | 探测超时，卡片「未确认模块正在运行」+ 最近回执时间 + 「验证」按钮 | `b12-unconfirmed5.png` |
| 点「验证」 | 自动拉起哔哩哔哩，冷启动装完 Hook 后回执确认 | `b12-after-verify.png` |
| B12 静默失效（Manager 被回收） | `NPatch: Manager not ready in time`，模块 0 行日志；验证超时后卡片「模块未生效」+「打开 NPatch Manager」 | `b12-not-running2.png` |
| 点「打开 NPatch Manager」 | 直接打开 Manager 主界面 | `b12-mgr.png` |

**已知边界**：普通 App 读不到其他应用进程，所以“宿主没运行”和“宿主运行但模块没加载”
在自动探测里无法区分，两者都表现为探测无回执；只有用户点「验证」（主动拉起宿主）后仍
无回执，才判定 `NOT_RUNNING`。这是当前权限模型下能达到的最强结论。

## 已确认正常、无需处理的项（避免误报）

| 项 | 结论 |
|---|---|
| 免 Root 正常路径（开→同步→重启→心跳→ACTIVE） | 正常，revision 全链一致 |
| 功能开关热更新（关闭“隐藏视频暂停页广告”） | 正常，`adskip_status=disabled` 生效 |
| Manager 作用域查询 | 正常，`packageName=com.Bilibili_Innocent_Lab.xposedmodule` |
| 命中 Manager 时的授权耗时 | 正常，约 30–100ms（`remote_config=54~101ms, admission=27~224ms`） |
| **重启弹窗在 NPatch 下走免 Root 分支** | **正常**。弹窗为“手动重新启动哔哩哔哩？/ 免 Root 模式无法强制停止其他应用…”，按钮为“打开应用详情”，未误用需要 `su` 的 Root 路径（源码注释 2026-09-30 记录的旧缺陷已修复） |
| 重启前落盘（`flushBeforeRestart`） | 正常，跳转系统应用详情页且 `sync_revision == remote_synced_revision` |
| 模块 APK 覆盖安装（保留数据） | 正常，重装后冷启动仍 `source=manager`、心跳正常 |
| 兼容模式开关 | 正常。开启后 provider + binder 两路均成功（`reason=` 空），`admission=27ms`；但**对 B11 场景无帮助**（模块应用未运行时仍 2012ms + 回退） |
| 首页大卡网格占位灰块 | 加载中状态，滚动后正常加载，非 bug |
| 首页第一张大卡里的“R1/R2/Pro系列”浮层 | 视频画面内容，非模块 UI，非 bug |
| `BIL-RoamingCompat ... hookinfo.pb 缺失` | 未安装 BiliRoaming 时的提示，非 NPatch 相关缺陷 |
| 子进程心跳 | 正确门控在主进程 |
| 组件扫描快照（`host_receipt_scans.xml`） | 正常，`bottom_bar` / `home_tabs` / `home_components` 三面均有有效快照，`target_update_time` 与当前一致 |
| 备份范围说明 | 正常，UI 明确排除“激活与作用域状态”，设计正确 |
| **播放器全屏进出（NPatch + 免 Root）** | **正常**。`(540,400)` 显示控制条后 `(1040,650)` 进入全屏，截图 2160×1080、`mRotation=1`；`keyevent 4` 退出后 `mRotation=0`。AGENTS.md 记录的坐标仍然有效（此前两次失败是操作节奏问题：控制条已自动隐藏，第二次点击落在了视频区域） |

---

## 修复决策记录（2026-10-20 与用户确认）

用户授权：能在用户体验层面直接判断的由我决定，需要权衡的已逐条确认。以下为最终结论，可直接作为实施依据。

| 编号 | 决策 | 修好后的用户可见表现 |
|---|---|---|
| **B1** | **关闭「免 Root 支持」= 真的全停用** | 关掉并重启 B站 后所有净化失效。依据是 2026-08-28 引入该能力时的 README 原文：该开关本就是“在 NPatch 上启用模块”的总开关（“启用后，模块会将当前支持的功能配置同步到 NPatch，并由已修补的哔哩哔哩进程加载对应 Hook”），当前实现偏离了设计 |
| **B2** | 一切正常时徽章显示「就绪」 | 不再与详情页「暂未发现需处理问题」自相矛盾 |
| **B3** | 状态可自动清除 | 重启 B站 且确认未装 Hook 后，「Hook 可能仍在运行」提示自动消失 |
| **B4** | 区分「Manager 未运行/连接超时」与「模块未注册」，并自动重试一次 | 不再把用户引去检查其实完全正常的 Manager 作用域配置 |
| **B6** | 回执不再被丢弃，失败原因可区分 | 诊断页数据变准；日志能看出具体失败原因，不再全压成 `SESSION_MISMATCH` |
| **B9** | 「NPatch selected」降为调试信息 | 设置变更不再刷警告日志，真实错误不再被淹没 |
| **B10 + B12** | **采用 A：主动检测 + 一键修复引导** | 模块实际未运行时，设置页显示「模块未生效」并提供打开 NPatch Manager 的按钮；彻底消除“界面说已激活、实际零 Hook” |
| **B11** | 探测通道不可达即跳过 | 冷启动少等约 2 秒，首帧约快 3 秒 |
| **B5** | 暂不做对照包测量（已重新分类为环境行为） | 先修 B11 观察实际效果，仍不够再回头深挖 |
| **B7** | **移出 NPatch 清单** | 已查清为模块对当前 B站 版本的适配覆盖问题，与框架无关，单独跟踪 |
| **B8** | 保持现状（已重新分类） | 属于 NPatch 注入行为，非模块缺陷；仅更正 `docs/npatch-compatibility-plan.md` 中关于 `--injectdex` 的表述 |

分类修正后，**真正的 NPatch 专项为 B1、B2、B3、B4、B6、B9、B10、B11、B12 共 9 条**。

---

## 建议的修复顺序（已与用户确认）

仅列 9 条 NPatch 专项；B5、B7、B8 已移出。

1. **B12 + B10**（模块静默失效 + 状态永不过期）——**已实施**，见 B10/B12 节末「状态」。
2. **B1**（关闭免 Root 未真正停用）——决策为「关闭 = 真的全停用」，依据是引入时的 README 原文。**已实施**。
3. **B11**（授权通道依赖模块应用进程，每次冷启动白等 2 秒 / 首帧多 3 秒）——**已实施并实机验证**，授权 2007ms → 69ms。
4. **B3**（状态永不清除）+ **B4**（误判 MODULE_NOT_REGISTERED）——**均已实施并实机验证**。
5. **B2**（徽章误报）+ **B9**（日志噪声）——**均已实施并实机验证**。
6. **B6**（回执被丢弃）——影响诊断可信度。

## 建议补充的自动化回归

- JVM：`HostAdmissionSourcePolicy` 在“关闭态 + NORMAL 文档 `noRootRevision=0`”下不得返回 `NORMAL`。
- JVM：`ActivationCardVisualSpec.diagnosticsSummaryState(publishFailed=true, standardPublisherBypassed=true)` → `READY`。
- JVM：`ReceiptSessionGate` 在“同一进程重复注册”与“进程重启”两种场景下的预期结果。
- 契约：`NPatchRemoteGateway` 在 binder 不可达时应产出 `ManagerMissing` 而非 `ModuleNotRegistered`。
- JVM：免 Root 关闭态下，NPatch 环境不得产出可授权文档（B1 的回归防线）。

## 原始证据索引

`captures/npatch-bughunt-20261020/` 下：
- 日志：`baseline-logcat.txt`、`noroot-on-logcat.txt`、`noroot-enabled-start.txt`、`noroot-disabled-start.txt`、`state-disabled-remote.txt`、`coldstart-noroot-off.txt`、`coldstart-detail.txt`、`feature-toggle.txt`、`final-verify.txt`、`manager-stopped-toggle.txt`、`toggle2-logcat.txt`、`toggle3.txt`、`module-reinstall.txt`、`scope-removed-host.txt`、`scope-restored.txt`、`admission-with-moduleapp.txt`、`compat-on.txt`、`compat-on-modapp-dead.txt`、`bug1-reverify.txt`、`loadtest-1.txt`、`loadtest-2.txt`、`loadtest-3.txt`、`loadtest-manager-alive.txt`
- 截图：`settings-module2.png`、`noroot-on2.png`、`badge-active.png`、`diag-final.png`、`diag-final2.png`、`detail-open2.png`、`player-controls.png`、`ctl-now.png`、`mgr-main.png`、`mgr-manage.png`、`mgr-app.png`、`mgr-scope.png`、`mgr-scope-after.png`、`scope-removed-module.png`、`bug10-fresh2.png`、`restart-dialog.png`、`restart-appdetails.png`、`backup-page.png`、`fs-doc-coord.png`
- 工具：`ui.ps1`（UI dump/开关/截图辅助脚本）
