# embed 逐功能验收

本页随[当前路线图](npatch-implementation-roadmap.md)维护，当前范围为有线机 `13a7a291`、Android 16/arm64、B 站 8.90.2、833 embed/签名等级 2。当前没有 Manager，不把 embed 结果外推为 Manager 或标准框架结果。

## 全量目录与状态

[逐项矩阵](npatch-feature-matrix.tsv)由 `SettingsCatalog`、`DiagnosticCapabilityCatalog` 和 `DiagnosticFeatureRegistry` 交叉生成，包含 236 个设置和 174 个能力。设置行的 `links` 是相关能力，能力行的 `links` 是相关设置；`installer_group` 对应 Hook 注册组，具体进程适用性还需按安装器和实际业务核对。`local_scope` 保留纯界面、诊断或宿主前置授权项，避免它们因没有独立 Hook 被遗漏。

状态含义：`pending` 待验、`installed` 已安装、`observed` 已触发、`passed` 对应行为案例通过、`blocked` 当前案例失败、`not_applicable` 有证据证明不适用。每个非待验状态必须引用本页案例；安装成功不升级为行为通过。当前仍有待验行，不宣称 2B 全量完成。

`NPatchAcceptanceMatrixTest` 校验目录版本、完整设置/能力集合、父注册组、状态值及案例引用是否存在。目录变化会使矩阵检查失败，更新模板在 `app/build/reports/npatch/feature-matrix.template.tsv`；合并新增行时保留已核验状态和证据，不整体用模板覆盖。Gradle 已把矩阵和本页计入 Test 输入，改动不会错误复用旧测试结果。矩阵只包含构建期公开元数据，不读取偏好、账号或令牌。

## 业务案例

### LOGIN

用户已在测试机完成登录。本轮个人页显示正式会员及个人空间入口；这是现有登录态的观测，不能表述为自动输入凭证完成登录。截图与视图树只保存在忽略目录，不把账号名、UID、联系人或令牌写入文档。

最终修复包同签名覆盖安装、完整启停/条款/Web 专项回归及再次冷启动后，个人页仍显示正式会员；`upgrade-my.xml` 与 `retained-account-cache.log` 记录保留结果。

### DOWNLOAD

通过已安装宿主的正常 UI，选择公开普通视频《bilibili上市宣传视频》，从播放器更多菜单进入“缓存”，使用宿主提供的 360P 免费画质。初始缓存为空；任务出现 `1.2MB/5.0MB`，随后进入“已缓存视频”，条目为 `5.0MB`。这验证真实下载完成，不能只凭 `:download` 已注入或 Hook 数量判断下载可用。

证据：`captures/npatch-2b/account-acceptance/download-before.xml`、`download-options.xml`、`download-progress.xml`、`download-finished.xml/.png`。没有测试会员付费资源、其它清晰度、存储介质或长任务断点恢复。

### CACHED_PLAYBACK

从已完成的缓存条目启动 `UnitedPlaylistActivity`，界面明确显示“离线缓存（1个视频）”、5.0 MB 条目和恢复进度，截图显示实际视频画面。没有关闭 Wi-Fi 或修改全局网络设置，因此本案例验证缓存入口播放，不声称完成断网环境验收。

最终修复包覆盖安装后再次验证：缓存条目与 5.0 MB 大小保留，重新打开后相隔两秒的截图显示不同视频帧。证据 `upgrade-cache.xml/.png`、`upgrade-cache-player.xml`、`upgrade-cache-player-1.png`、`upgrade-cache-player-2.png`。

证据：同目录 `cached-player.xml/.png`。普通在线视频播放已在选择样本时验证，未把缓存列表出现当作解码成功。

### ACCOUNT_GATE

通过设置搜索进入“获取哔哩哔哩 access_key”。取消确认后授权仍关闭；再次明确确认后开关开启。AI 总开关与强力模式中的“获取 access_key”单独启用，发布者屏蔽保持关闭，避免混淆两个分支。

旧代码冷启动拿到当前 `module_direct` 配置后，报告 `account=false, view=true` 和 `partial:missing-precheck`。检查原版 APK 的 DEX 方法声明确认：`BiliAccounts.getAccessToken()` 返回 `com.bilibili.lib.accounts.service.AccessToken` 接口，实际 model 类实现该接口。原探测器把 model 类当作声明返回类型，导致精确定位失败；修复使用 service 契约，单测保留真实接口与实现类的区别。

修复后同签名重新修补并覆盖安装，保留登录与缓存。真实宿主报告 `ai_declared_video_status=success`、`ai_declared_access_key_status=ready`。随后通过 UI 撤销授权，确认授权关闭且强力模式预检同步变为未开启；再冷启动宿主，当前进程明确报告 `precheck=false`，没有再报告账号状态。仅允许 `ready/not_logged_in/expired/unavailable` 状态离开探测边界；不导出、记录或持久化令牌，也不绕过授权开关。`ready` 证明当前账号状态可用，不能替代所有 AI 拦截效果或长期风控验收；未在实机主动制造令牌过期/登出，相关分支由单测覆盖。

证据：同目录 `access-cancelled.xml`、`access-authorized.xml`、`precheck-enabled.xml`、`account-methods.txt`、`account-logcat.txt`。初次未观测到 ready 的失败不能计入通过结果。

修复证据为 `account-fixed-logcat.txt`、`account-ready.log`、`access-revoked.xml`、`precheck-revoked-summary.xml`、`account-revoked-host.log`。账号修复当时的打包证据在 `patched/verified.json`：模块 SHA-256 `6132395828fc66a10b3505e54c8d1ff66c293a6ded2a1e8d427e4f8b8a7cfde5`，宿主 `05379e63ae90d79575f8511db0637618ec7f018196ab5ad90598902bc8fd3539`，保留原有双 ABI native 修复与同证书；后续复制修复包见下方案例。

### FREE_COPY_DESCRIPTION

展开公开视频 `BV1xx411c7mD` 的简介，长按正文后出现模块自由复制气泡，换行已归一。早期仅验证系统复制键，本轮补测真实触摸：新气泡内使用 `touchscreen` 首次长按，系统“复制/分享/全选”菜单及两个手柄正常出现；拖动手柄可改变选区，全选能覆盖两行，通过触摸菜单复制后在模块本地搜索框粘贴，两行内容完整。搜索框会把换行显示为空格，不能据此宣称原始换行字节已核对；跨行拖选、超长简介及外观组合仍待验，因此整项保留 `observed`。

早期证据：`captures/npatch-2b/account-acceptance/description-dialog.xml/.png`、`description-selection.png`、`description-pasted.xml`、`description-copy.log`。触摸证据：`captures/npatch-2b/copy-acceptance/20-description-touch-selection.png`、`21-description-dragged.png`、`23-description-all-selected.png`、`26-description-pasted.xml`。

### FREE_COPY_COMMENT

同一视频中，普通公开评论长按触发模块气泡，气泡内触摸长按显示系统菜单及选区手柄；点击“复制”后在模块本地搜索框粘贴，文字一致。关闭评论和简介两个开关并冷启动后，简介不弹模块气泡，评论恢复宿主“回复/分享/复制”等官方菜单；随后通过 UI 重新开启。未点击发送、回复、点赞、关注或外部分享。

含 `bv` 搜索链接及三个自定义表情的样本发现真实缺陷：气泡显示表情，但复制内容中只剩三个 U+200B。完整原文已捕获，失败发生在身份校验；宿主的文字搜索链接也使用 ReplacementSpan，旧逻辑将其底下的普通文字误当作 Emoji 槽位，导致可信原文被拒绝，退回可见占位符快照。

修复按底层内容区分绘制单元：普通文字保留在身份键里，仅完整表情标记、Unicode 表情或 U+200B/U+FFFC 占位区间归为 Emoji。只把确认的 Emoji Span 交给模型对齐及剪贴板语义映射；保留当前绑定、代次及防串评论判据。修复包实测 `model=3 spans=3 url=3 structural=3 applied=3`；触摸全选、复制并本地粘贴后，完整正文及三个 `[大哭]` 精确一致。并补齐正文读取器按实际 Class 缓存的回归，避免混排数据同名 getter 污染后续模型；这项缓存防护不是本例身份拒绝的根因。

最终设备回归和再次冷启动后，补测仅选中其中一个绘制表情，通过系统触摸菜单复制，本地粘贴精确为单个 `[大哭]`。证据 `85-single-emoji-selected.png`、`87-single-emoji-pasted.xml` 与 `emoji-copy-verification.log`；这验证选区语义转换，不把全文复制成功当作局部选择成功。

证据均在 `captures/npatch-2b/copy-acceptance/`：`13-comment-fresh-touch.png`、`16-comment-pasted.xml`、`58-both-off.xml`、`61-off-description-longpress.xml/.png`、`63-off-comment-longpress.xml/.png`、`66-both-on.xml`、`44-emoji-pasted.xml`（修复前失败）、`fresh-copy-host.log`、`76-identity-bubble.xml/.png`、`78-fixed-emoji-all.png`、`80-fixed-emoji-pasted.xml`、`emoji-copy-verification.log`。

`HostCopySelectionInstrumentedTest` 使用真实触摸长按、跨窗口查找系统菜单及坐标点击，再核对剪贴板选区；不以键盘快捷键替代菜单验证。无 Hook 的组件对照已通过，最终包另做宿主回归。输入源需分层：此 ROM 的宿主按钮优先 stylus，但新气泡内 stylus 长按只出现选区，touchscreen 可正常显示工具栏；不能把该输入源差异误报为产品缺陷。UI dump 默认只取活动窗口，系统浮动工具栏可能不在该树内，需结合截图或多窗口 UiAutomation。

最终包的真实 embed 宿主回归与上述触摸菜单回归合计 2 项、134.045 秒通过，日志 `final-device-regression.log`。Debug/AndroidTest APK、3670 项 JVM 测试（0 失败、1 跳过）及快速 Lint 通过；仍为 250 Warning/1 Hint，原 baseline 不变。当前产物及固定 native loader 哈希在同目录 `patched-identity/verified.json`，不沿用修复前的 `patched-fix1/` 候选。

覆盖安装及最终设备回归后再次冷启动，正式会员登录态、5.0 MB 缓存与缓存入口实际播放均保留，`upgrade-cache-player-1.png` / `-2.png` 为不同视频画面。证据同目录 `retained-account-cache.log` 与 `upgrade-*.xml/.png`；未清数据或切换全局网络状态。

本例验证当前 8.90.2 的普通评论及上述链接/表情组合；折叠长评和两类回复表面的后续证据见 `FREE_COPY_REPLIES`。纯表情、图片/推送等表面、复用压力和其它宿主版本仍待独立验收，评论整项记录为 `observed`。

### FREE_COPY_REPLIES

同一公开样本的短回复预览与专用“评论详情”回复列表能读取当前回复正文，不混入昵称或父评论。第二条折叠预览暴露独立缺陷：气泡仅有 46 字符的“昵称: 截断正文...”，实际正文为 50 字符。`NextLikableTextView` 保留完整富文本输入，但原校验使用了带昵称的截断显示文本，因此回退到了可见快照。

对照 8.90.2 的实际 DEX 和渲染源码：`C2` 的三个输入分别为完整文本、紧凑文本和点赞尾部；完整源保存在当前控件的 `d` 字段。前置昵称由 `Link.b` 生成 `f00.u` 自定义点击 Span，并非 Android `ClickableSpan`；冒号和空格由独立普通文本生成。修复仅对已核对的控件类和成员形状读取当前完整源，再以可信本次绑定正文、严格完整尾部匹配、前置点击区间及冒号空白间隔共同验证正文边界。未知形状、短公共片段、模型不匹配和表情数量不符继续回退；只缓存 Class/Member，不保留宿主实例，也不按昵称字符串或任意冒号猜测边界。

最终包再次长按折叠预览，气泡显示完整 50 字符正文；触摸长按、全选、复制，再粘贴到模块本地设置搜索框，逐字一致，昵称和截断尾部均未混入。短回复预览仍为正确的 23 字符正文；回复详情中“拉了半天总算是见了底”匹配当前回复，不是父评论。主列表多行折叠评论也保留完整八首曲目和末尾说明，共 433 字符；链接加三个 `[大哭]` 的既有样本保持正确。本轮长评案例核对的是气泡全文，未以此代替跨行剪贴板字节检查。

证据在 `captures/npatch-2b/comment-surfaces/`：`04-folded-preview-bubble.xml/.png`（修复前）、`21-final-folded-bubble.xml/.png`、`22-final-folded-selection.png`、`23-final-folded-all.png`、`25-folded-reply-pasted.xml`、`28-final-short-reply.xml/.png`、`30-final-emoji-bubble.xml/.png`、`33-final-reply-list-bubble.xml/.png`、`35-final-folded-main-bubble.xml/.png` 及 `reply-copy-verification.log`。中间候选的 Span 类型不符而保守拒绝，不计入通过结果；最终产物为 `patched-preview-link/verified.json`，仍使用固定双 ABI native 修复及相同证书覆盖安装。

源码门禁 `full-preview-link-build.log`：Debug/AndroidTest APK、3676 项 JVM 测试（0 失败、1 跳过）、快速 Lint 通过，250 Warning/1 Hint，原 baseline 不变；Gradle/Kotlin 守护进程已退出。新增回归覆盖完整源随 View 重绑更新、未知/超限源拒绝、实际点击契约、带冒号昵称、截断/错误模型和纯表情数量不匹配。当前证据只覆盖上述 8.90.2 表面，评论整项仍为 `observed`。

最终包真实宿主启停/条款/Web 与原生触摸复制合计 2 项、136.768 秒通过，见 `final-device-regression.log`。之后再次冷启动，会员登录态、5.0 MB 已完成缓存和缓存入口实际视频画面均保留，见 `retained-account-cache.log`、`upgrade-my.xml`、`upgrade-cache.xml` 及 `upgrade-cache-player-*.png`。未清数据或修改网络/旋转设置。

### PLAYER_SPEED

在同一最终宿主包上，通过模块正常设置页依次验证以下组合。使用公开视频 `BV1xx411c7mD`，新视频切换样本为正常推荐入口中的《bilibili上市宣传视频》；没有改系统方向锁定或旋转设置，横竖屏均由播放器正常进出全屏触发。

| 配置/操作 | 实际行为 |
|---|---|
| 两项跟随宿主、禁用长按关闭 | 原生长按出现“倍速播放中”；关闭态冷启动后倍速注册组为 `DISABLED`、0 Hook，真实长按后仍无模块倍速回调 |
| 默认 1.5×、长按 3.5× | 全屏控制条显示 `1.5X`；长按出现加速提示，松手恢复 `1.5X`。8 秒手势的约 9.9 秒记录窗口内进度 07:31→07:58，包含识别及截图开销，不当作精确倍率测量 |
| 播放中手动选 2.0X | 长按后恢复用户选择的 `2.0X`；切换另一公开视频后，新会话恢复默认 `1.5X` |
| 禁用长按开启，保留默认 1.5× 和保存的 3.5× | 设置明确提示保存的自定义值暂不生效；长按期间无加速提示，基础标签仍为 `1.5X`。同样 8 秒手势的约 9.8 秒窗口内进度 23:32→23:47，与基础播放一致 |

新增 `NPatchFeatureEvidenceInstrumentedTest` 配对核对当前进程的实际回调：必须显式传入公开能力 ID，校验 embed 模式、当前 `module_direct` 权威身份、已完成安装链及无运行错误。`applied` / `observed` 要求对应阶段真实出现；`quiet` 只核对无已报告回调，允许协议省略未启用叶项，不能单凭缺少记录判定开关关闭或业务成功。它不启动宿主、不操作界面或改变测试开关；身份通过既有权威记录 API 求值，协议及授权边界不变。

默认倍速和自定义长按两项的 `APPLIED` 查询通过；现有自定义长按实现完成构造参数写入和字段值回读后才报告该阶段。禁用组合的默认倍速/禁用回调均为 `APPLIED`，自定义长按无已报告回调。全部恢复跟随宿主、禁用关闭后，原生长按恢复且注册组为 `DISABLED / 0 Hook`，三叶项无已报告回调。负对照故意要求已关闭能力为 `APPLIED`，检查器按预期拒绝；无参数运行以 assumption 跳过，避免普通测试运行把未安排业务场景当成通过。早期检查器要求停用叶项必须存在，遇到协议省略而失败，已修正为明确的 `quiet` 含义，不把该失败计入通过结果。

证据在 `captures/npatch-2b/player-acceptance/`：`06-speed-baseline.xml`、`26-speed-configured.xml/.png`、`31-custom-full-controls.png`、`32-before-custom-hold.png`、`33-custom-hold-active.png`、`34-custom-hold-released.png`、`39-manual-base-restored.png`、`43-new-video-default.png`、`55-disable-saved.xml/.png`、`60-disabled-before-hold.png`、`61-disabled-hold-active.png`、`62-disabled-hold-released.png`、`71-speed-follow-host.xml/.png`、`74-follow-host-longpress.png`、`75-follow-host-release.png`、两份 `*-hold-timing.log` 及 `runtime-feature-results.log`。查询结果为 `default-and-hold-evidence.log`、`disabled-hold-final-evidence.log`、`follow-host-post-gesture.log`；负对照及无参数边界见 `negative-control-result.log`、`negative-control-evidence.log` 和 `no-arguments-guard.log`。

本轮仅新增实机检查测试和文档：AndroidTest APK 与快速 Lint 通过，250 Warning/1 Hint，主源码分析复用已通过缓存；日志 `evidence-test-final-build.log`。生产模块与宿主仍为 `FREE_COPY_REPLIES` 的相同字节，复用其 3676 项 JVM 与主 APK 门禁。Gradle/Kotlin 及本任务日志进程已停止。当前案例覆盖上述普通视频与组合，未覆盖全部倍率、PGC/Story/缓存播放器、prepared 动态发现路径和后台/拖动组合；三设置及三能力记录为 `observed`，不把安装期 `awaiting-prepared` 提示或局部证据提升为全场景支持。

### COMMENT_SEARCH_LINKS

在当前 8.90.2 原生评论列表，以 `BV1xx411c7mD` 中含 `bv` 搜索链接和三个自定义表情的公开评论完成“关→开→关”对照。关闭净化时，关键词为蓝色并带搜索图标，实际点击进入 `BiliMainSearchActivity`，查询栏显示 `bv`。

通过模块 UI 开启“关闭评论关键词搜索链接”，冷启动取得新配置后，`bv` 变为普通文字，搜索图标消失，完整正文和三个表情保留。点击相同位置按普通评论行为打开回复详情，前台仍为 `UnitedBizDetailsActivity`。现有请求侧通道报告 `search_word_disabled=true`，当前身份查询确认 `comments_search_links_removed` 为 `INSTALLED / APPLIED`；不以该日志单独替代界面对照，也不把此案例当作三道防线分别故障注入后的验收。

同一开启态继续长按，模块气泡完整保留 `bv` 和三个表情。气泡内使用真实触摸全选、复制，再仅粘贴到模块本地搜索，逐字等于原正文及三个 `[大哭]`，当前 `free_copy_comment_enabled` 也有 `APPLIED`。随后通过 UI 关闭净化并再次冷启动，蓝色关键词、搜索图标和实际 `bv` 搜索均恢复；当前净化注册组为 `DISABLED / 0 Hook`，对应能力无已报告回调。设置页在返回时可能重建折叠菜单，自动化重新核对实际控件后才修改开关；中间定位失败没有计入开启态证据。

证据在 `captures/npatch-2b/comment-search-acceptance/`：`06-search-removal-off.png`、`08-baseline-comments.xml/.png`、`09-baseline-search-opened.xml/.png`、`baseline-search-activity.txt`、`17-search-removal-enabled.xml/.png`、`19-enabled-comments.xml/.png`、`20-enabled-word-tap.xml/.png`、`enabled-word-tap-activity.txt`、`22-enabled-copy-bubble.xml`、`23-enabled-copy-selection.png`、`24-enabled-copy-all.png`、`26-combined-copy-pasted.xml`、`31-removal-off-restored.xml/.png`、`33-restored-comments.xml/.png`、`34-restored-search.xml/.png`、`restored-search-activity.txt` 和 `search-copy-verification.log`。当前回调查询两次通过，见 `enabled-feature-evidence.log`（2.004 秒）、`restored-off-feature-evidence.log` 及 `runtime-feature-results.log`。

本轮只更新验收文档与矩阵，生产/测试 APK 均沿用上一单元，复用已通过门禁，仅重新核对矩阵。未发评论、点赞、关注或外部消息，结束时本项恢复关闭。当前只覆盖上述原生列表、回复详情及自由复制组合；其它评论管线、表面和防线降级仍待验，两目录行记为 `observed`。

### EMBED_WEB

2B.1 的原生 TLS 修复、恢复原有 Web Hook、关闭/条款拒绝/重新开启、重复打开/返回/后台恢复、正文滚动及复制链接均见路线图 2B.1 验收。当前模块的自由复制开关和安装诊断已有证据；评论/简介复制的具体业务仍需独立行为案例，不因 32 个公共 Hook 安装成功而全部标记通过。

账号修复后的同一最终包再次通过 `NPatchEmbeddedHostInstrumentedTest`，126.169 秒，覆盖当前配置身份、启停、条款拒绝、Web 无 Hook/完整安装及重复打开/返回/后台恢复；日志 `embedded-regression.log`。本测试不会登出账号，之后重新确认了登录与缓存保留。

## 后续顺序

账号探测修复验收后，继续评论/简介复制、播放器选项、净化和外观组合，逐行补充矩阵。随后推进构建身份一致性、同版本码不同 APK、设置 App 与内嵌模块升级和恢复链路；未测项保持待验，最终仍需正式支持矩阵和发布门禁。
