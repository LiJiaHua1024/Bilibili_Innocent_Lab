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

### RELATED_COMMERCIAL

在 `BV1xx411c7mD` 详情页，关闭所有相关推荐类型及匹配增强时，截图捕获明确标为“广告·查看详情”的京东推广卡；普通《【全弹幕】av10388 武器A》作为保留对照。推广卡没有出现在默认 accessibility XML 中，因此不能用 XML 搜不到广告文案证明过滤成功。服务器下发的推荐会变化，“课堂”角标也不能单独当作商业推广证据。

通过正常设置 UI 仅勾选“过滤商业推广”，其它四种类型、匹配增强及强力模式保持关闭。旧包虽报告移除条目，但同时出现“详情页推荐响应列表写回失败”，当前组的 `runtimeError` 检查失败，不计入通过。原版 8.90.2 DEX 证明 `ViewReply.relates_` 的声明类型为 `Internal$ProtobufList`；普通 Java List 无法赋给该字段。修复使用原列表契约生成独立、同类型的不可变副本，保持保留条目的身份，并清除当前宿主已验证的序列化长度与 hash 缓存；未知消息形状、别名副本和缓存重置失败均拒绝或回滚，不原地修改共享源列表。

最终包正常前台恢复模块、冷启动宿主后，当前 `module_direct` 身份下 `video_relate_filter` 的真实 `APPLIED` 检查通过（0.319 秒），没有 runtime error；新的已加载截图显示普通对照条目保留且无京东推广卡。此前专项回归修改配置后的旧快照不匹配、未重新打开模块时的许可不可用均被检查器拒绝，没有放宽身份或许可边界、没有计入通过。随后通过 UI 取消唯一勾选并保存 0 项，再冷启动，当前组与商业推广叶项的 `quiet` 检查通过（0.390 秒）；该结果须与 UI 关闭状态配对，不能由叶项缺席推断设置关闭。

证据在 `captures/npatch-2b/related-ad-acceptance/`：`08-related-second-baseline.png`、`11-cm-selected.png`、`cm-enabled-group-evidence.log`（修复前失败）、`view-reply-members.txt`、`protobuf-list-members.txt`、`GeneratedMessageLite.java`、`writeback-candidate-group-evidence.log`、`16-writeback-warm-1.png` / `-2.png`、`final-renewed-enabled-group-evidence.log`、`21-final-enabled-settled.xml/.png`、`23-final-cm-on-before-off.xml/.png`、`24-cm-off-selected.xml`、`25-cm-off-saved.xml/.png`、`final-off-group-evidence.log`、`26-cm-off-detail.png` 与 `related-ad-verification.log`。只有最终包证据用于最终验收，前期候选不能代替最终包。

最终产物 `patched-writeback-final/verified.json`：模块 SHA-256 `7f0df1b8969c5ce4564489f2b5b893bcc5d1def458634cd5a7f85e5bb48ea896`，宿主 `ab0c1406c339f9c60619a4cb99ac4abd52b1b7a4bdfc4f66eaad27e8e5af08b3`，相同证书覆盖安装，固定双 ABI native loader 不变。`writeback-final-build.log` 中 Debug/AndroidTest APK、3680 项 JVM 测试（0 失败、1 跳过）及快速 Lint 通过，250 Warning/1 Hint、原 baseline 不变；`full-jvm-suites.json` 保留全量结果。最终宿主启停/条款/Web 与原生触摸复制回归共 2 项、137.274 秒通过，见 `final-host-regression.log`。

最终覆盖安装及完整设备回归后，登录、5.0 MB 缓存和缓存入口实际播放均保留，见同目录 `retained-account-cache.log` 与 `upgrade-*.xml/.png`；相隔两秒的两帧显示实际解码画面，没有关闭网络。关闭态另做五次重新打开，普通推荐正常变化，但未再次下发同一张明确广告；`27-cm-off-warm-1..5.png` 不能冒充“同一广告关闭后恢复”的证据。

本项仍记为 `observed`：关闭后的同类广告重现尚待稳定样本；该包仅上报父组运行计数，商业推广叶项没有独立运行归因；当时 8.90.2 报告 `partial:missing-service-layer`，后续定位修复见 `RELATED_SERVICE`。其它类型、匹配增强、强力模式、其它进程和宿主版本未在本例验收，不能把本次响应写回修复扩展为全相关推荐通过。

### RELATED_SERVICE

对照同一 8.90.2 原版 DEX，服务类包含多个 `(r2) -> RunningUIComponent` 方法。旧定位按参数包名和返回类型打分，多个专用组件构造器并列，因而拒绝定位。`C0` 的实际指令先调用 `r2.y()` 读取 `RelateCardType`，再按枚举分发；调用者包括正常列表构建 `D0(List)` / `X1(RelateTab,List)` 及撤销路径。证据 `captures/npatch-2b/related-service-acceptance/dispatcher-dex-proof.txt`、`dispatcher-callers.txt`；声明和反编译源码在上一案例的 `detail-relate-service-members.txt`、`detail-relate-item-members.txt`、`DetailRelateService.java`。

修复只在完整已核对契约成立时优先 `C0`：精确 DTO 类 `r2`、精确组件返回类型、实例字段 `a` 的枚举类型及必要常量、实例 getter `y()` 返回同一枚举。保留旧 `d` 入口和最高分并列拒绝；不随意挑选同签名专用构造器。规则代次从 72 提升到 73，使旧缺失缓存重新定位；JSON 形状和 schema 不变。三项新增 JVM 回归覆盖真实同签名干扰、未验证 DTO/并列拒绝及旧入口。

最终包当前 `module_direct` 身份检查通过（0.348 秒）：父组由 `PARTIAL / 5 Hook` 变为 `INSTALLED / 6 Hook`，`observed=1 / applied=1`，没有 runtime error；该包商业推广叶项为 `INSTALLED / 6 Hook`、`observed=0 / applied=0`。正常 UI 仅商业推广开启，普通推荐对照保留；原服务层定位错误消失。证据 `06-cm-selected.xml/.png`、`08-final-enabled-detail.xml/.png`、`final-enabled-group-evidence.log`、`final-feature-results.log` 和 `service-host.log`。父组回调不能区分响应层与服务层，也不能替代叶项命中，本例只确认服务层定位及安装缺口修复；叶项归因后续见 `RELATED_RUNTIME`，服务层独立业务仍待验。

最终目录 `patched-service-final/verified.json`：模块 SHA-256 `e80a8c17e394a7080a3ee33e1d1a85c9ef606e91cc75b1ba1ff732b3b1c9ddbe`，宿主 `b82e726559f337d6e30c12aa1b80e484f8bfe2ce8ebe96b943bb86ee8d4df269`；相同证书覆盖安装、双 ABI native loader 不变。`service-final-build.log` 中 Debug/AndroidTest APK、3683 项 JVM（0 失败、1 跳过）、快速 Lint 通过，250 Warning/1 Hint，baseline 未扩大。最终宿主启停/条款/Web 与原生触摸复制共 2 项、135.092 秒通过，见 `final-host-regression.log`。

最终 UI 取消唯一商业推广勾选并保存 0 项，冷启动后当前组/叶项的无回调检查通过（0.275 秒），普通推荐保留；证据 `14-cm-off-selected.xml`、`15-cm-off-saved.xml/.png`、`16-final-off-detail.xml/.png` 及 `final-off-group-evidence.log`。升级后的登录、5.0 MB 缓存及实际缓存播放保留，见 `retained-account-cache.log` 和两张 `upgrade-cache-player-*.png`；未修改网络或旋转设置。商业推广矩阵仍为 `observed`，不因安装缺口修复升级为完整业务通过。

### RELATED_RUNTIME

此前服务层修复包的父组有实际应用，商业推广叶项却只有安装状态。此次让响应层和服务层返回实际移除原因，沿原过滤顺序，只将每张卡首个命中条件归给对应能力；未命中条件保持无叶项阶段。未知类型与选区标签暂保留父组证据。宿主桥继续保存当前进程的有界阶段证据，不能据此推算所有可能匹配条件或全程命中总量。内部列表写回失败时，实际受影响叶项同步报告 `ERROR`，getter 仍保留过滤返回值；诊断不会仅凭 `APPLIED` 将其认作无错误成功。

七项新增 JVM 回归经真实 Modern 回调核对：混合卡片分别归因、已启用但未命中项不冒领、CM/SPECIAL 载体回退、强力模式自身归因、数值过滤不冒领类型、重复写回失败只标记受影响叶项，以及当前 `r2` 服务 DTO 被拦截后原构造方法不执行。保留既有过滤判据，公共布尔匹配和原因提取共用类型判断。

最终有线机通过正常 UI 同时开启商业推广与游戏推荐，其它三类、匹配增强和强力模式关闭。冷启动后仍保留普通推荐对照，当前 `module_direct` 身份及无运行错误检查通过（0.516 秒）：

| 当前项 | 安装 | OBSERVED | APPLIED |
|---|---|---|---|
| 父组 `video_relate_filter` | INSTALLED / 6 Hook | 1 | 1 |
| 商业推广 `video_related_commercial_removed` | INSTALLED / 6 Hook | 1 | 1 |
| 已开启的游戏项 `video_related_game_removed` | INSTALLED / 6 Hook | 0 | 0 |

证据 `captures/npatch-2b/related-runtime-acceptance/06-selected-过滤游戏推荐.xml`、`06-cm-game-selected.png`、`07-cm-game-saved.xml`、`08-final-double-enabled-detail.xml/.png`、`final-double-enabled-evidence.log`、`final-double-enabled-results.log` 与 `runtime-verification.log`。游戏项只是此次未命中的负对照，未因此将游戏过滤矩阵提升为行为通过；服务层独立实际表面和其它过滤条件仍需各自案例。

最终目录 `patched-runtime-final/verified.json`：模块 SHA-256 `465851eb985e3deb8dac8d470cd68dd78afeefbe71fd54ca308775548091a0d3`，宿主 `637b9261de1c384e86e32d9676b43657d3133a6eb9818b1c0178f60fb5c8b60b`，同证书覆盖安装，native 字节不变。`runtime-build.log` 中 Debug/AndroidTest APK、3690 项 JVM（0 失败、1 跳过）和快速 Lint 通过，250 Warning/1 Hint，原 baseline 不变；最终宿主启停/条款/Web 与触摸复制共 2 项、140.606 秒通过，见 `final-host-regression.log`。

通过正常 UI 取消两项并保存 0 项，冷启动后当前父组与两个叶项均无已报告回调，检查通过（0.410 秒），普通推荐保留；证据 `14-both-off-selected.png`、`15-both-off-saved.xml`、`16-final-off-detail.xml/.png` 与 `final-off-evidence.log`。最终更新后登录、5.0 MB 缓存和实际缓存播放保留，见 `retained-account-cache.log` 与两张不同画面的 `upgrade-cache-player-*.png`；没有修改网络或旋转设置。商业推广保留 `observed`，其它过滤条件保持逐项待验。

### RELATED_DURATION

同一普通视频详情页的推荐列表，基线同时显示 2:24（144 秒）和 0:50 两个稳定对照；其它相关推荐类型、匹配增强和播放量范围关闭。通过正常设置 UI，每次保存后冷启动，并配对当前 `module_direct` 身份及无运行错误检查：

| 时长范围（秒） | 实际普通视频对照 | 当前进程检查 |
|---|---|---|
| 两端留空 | 144 秒、50 秒均出现 | 父组/时长无回调，0.525 秒通过 |
| 最短 100，最长留空 | 144 秒保留，50 秒从原列表移除；后续可见 3:59、7:55、2:03 | 时长 `APPLIED`，0.484 秒通过 |
| 最短留空，最长 100 | 50 秒和 1:30 保留，144 秒对照移除 | 时长 `APPLIED`，0.378 秒通过 |
| 最短 144，最长 144 | 恰好 2:24 的对照仍保留，验证两端包含边界 | 时长 `APPLIED`，0.556 秒通过 |
| 再次清空两端 | 144 秒、50 秒对照均恢复 | 父组/时长无回调，0.273 秒通过 |

另将最短设为 144、最长保持 100，UI 明确提示“最长时长不能短于最短时长。”并保留输入弹窗；未保存草稿关闭后原最长 100 的摘要仍保留。随后重新输入有效相等区间完成验收。自动化只在输入法实际显示时发送返回键，最终相等区间以恢复后的 25/26 号证据为准。

证据 `captures/npatch-2b/related-duration-acceptance/05-duration-baseline-dialog.xml/.png`、`07-duration-off-baseline.xml/.png`、`10-min-configured-values.xml/.png`、`11-min-only-detail.xml/.png`、`12-min-list-lower.xml/.png`、`16-max-configured-values.xml/.png`、`17-max-only-detail.xml/.png`、`20-invalid-range-rejected.xml/.png`、`25-equal-range-configured-values.xml/.png`、`26-equal-range-detail.xml/.png`、`30-duration-disabled-values.xml/.png`、`31-duration-restored-detail.xml/.png`、各阶段 `*-evidence.log` 和 `duration-verification.log`。当前回调汇总保存在 `final-feature-results.log`。

本轮生产/测试 APK 与源码均沿用 `f45801bf` 对应的 `patched-runtime-final/`，引用见 `reused-production-build.json`；复用其 3690 项 JVM、快速 Lint、最终宿主/触摸复制 2 项回归和相同包哈希，仅重查更新后的矩阵。两项共享设置及详情页时长能力记为 `observed`；首页能力、课程/商品等其它表面、独立服务层路径和跨条件组合继续待验，不将共享设置的详情页结果外推到首页。结束时两端均留空。

### RELATED_PLAY_COUNT

沿用全部相关推荐类型关闭、时长两端留空的基线，正常设置 UI 输入播放次数整数。普通投稿对照分别显示 471.9 万和 256.5 万；每次保存后冷启动，并核对当前 `module_direct` 身份与无运行错误：

| 播放量范围 | 普通投稿对照与当前检查 |
|---|---|
| 两端留空 | 两个对照均出现，父组/播放量无回调，0.349 秒通过 |
| 最低 3,000,000 | 471.9 万对照保留，256.5 万对照移除；播放量 `APPLIED`，0.381 秒通过 |
| 最高 3,000,000 | 256.5 万对照保留，471.9 万对照移除；播放量 `APPLIED`，0.430 秒通过 |
| 3,000,000～5,000,000 | 区间内 471.9 万对照保留，原低于下限/高于上限的普通投稿移除；播放量 `APPLIED`，0.303 秒通过 |
| 该播放量区间加最长 100 秒 | 时长、播放量两个叶项均 `APPLIED`，0.361 秒通过；0:12、311.6 万播放的交集对照保留，原两个对照不再出现 |
| 再次清空两类范围 | 原两个对照恢复；父组及两个叶项无回调，0.462 秒通过 |

UI 还拒绝最低 3,000,000、最高 1,000,000 的反向区间，明确显示“最高播放量不能低于最低播放量。”并保留输入弹窗。组合案例中，原 2:24 高播放量视频超出时长上限，原 0:50 视频低于播放量下限；保留的 0:12 / 311.6 万普通投稿同时满足两类条件。当前诊断只归因实际执行的首个过滤判据，不把两个开关同时开启当作两个能力都成功。

证据 `captures/npatch-2b/related-play-count-acceptance/01-play-count-baseline-dialog.png`、`02-play-count-baseline-detail.xml/.png`、`05-min-configured-values.xml/.png`、`07-min-visible-controls.xml/.png`、`10-invalid-play-range.xml/.png`、`13-max-configured-values.xml/.png`、`14-max-only-detail.xml/.png`、`18-band-configured-values.xml/.png`、`20-band-visible-controls.xml/.png`、`23-combo-duration-configured-values.xml/.png`、`24-combined-ranges-evidence.log`、`25-combined-visible-controls.xml/.png`、`28-duration-off-values.xml/.png`、`32-play-count-off-values.xml/.png`、`33-both-ranges-restored-detail.xml/.png`、`34-restored-controls-*.xml/.png`、各阶段 `*-evidence.log`、`play-count-verification.log` 与 `final-feature-results.log`。

本案例验证上述普通投稿的有效计数及范围交集；其它卡片表面的读取覆盖、首页、直播、未知计数和独立服务层路径仍需各自案例，不根据其它卡片的可见数量文案推断其内部类型或读取成功。两项共享设置与详情页播放量能力记为 `observed`，时长上限/能力补充本组合引用，首页能力继续待验。本轮复用相同 `f45801bf` 生产/测试 APK 和已通过的 3690 项 JVM、快速 Lint、140.606 秒最终包回归，只重查矩阵；包哈希引用 `reused-production-build.json`。结束时两类范围均留空。

### RELATED_AUTHOR

2026-10-22 使用相同有线环境和公开入口 `BV1xx411c7mD`，独立验证详情页相关推荐作者名单。作者 UID 来自普通投稿跳转后的公开空间 Intent，不读取当前登录账号资料。时长、播放量、发布时间和其它相关推荐过滤保持关闭。

修复前，完整公开 UID `208259` 已保存，但《bilibili上市宣传视频》仍出现；严格当前身份检查报告 `No applied callback for video_relate_filter`。原适配只有旧 `view.v1.Relate#getAuthor → Author#getName/getMid`，新版 `RelateCard` 把作者放在 `getBasicInfo → CardBasicInfo#getAuthor → Owner`。已核对实际 APK 成员和宿主映射器 `j.t` / `AuthorRepository.b.c`：新版作者显示名使用 `Owner#getTitle`，mid 使用 `getMid`。修复补齐这两条完整成员链，保留旧链；`RULE_VERSION` 73→74 使旧定位缓存失效，JSON/schema 不变。只从作者对象读取显示名，不将投稿标题充当作者。

| 修复后名单 | 普通推荐卡对照与当前身份检查 |
|---|---|
| 完整 UID `208259` | 对应投稿移除，武器 A 等其它普通推荐保留；父组及作者叶项 `APPLIED`，0.482 秒通过 |
| 仅 UP 名 `McIDE` | 保存归一为 `mcide`，该公开 UP 的 0:17 失效投稿卡移除，UID 对照恢复；父组及作者叶项 `APPLIED`，0.412 秒通过 |
| 前缀 `20825,Mci` | 保存归一为 `20825,mci`，上述两条卡片均出现；父组已执行、作者叶项无命中，0.446 秒通过 |
| 混合 `208259,McIDE,208259` | 保存归一并去重为 `208259,mcide`，两条目标卡片移除，河蟹/三体等其它普通推荐保留；父组及作者叶项 `APPLIED`，0.506 秒通过 |
| 再次清空名单 | 两条目标卡片均恢复；父组 `DISABLED`、0 Hook、无回调，作者叶项无回调，0.459 秒通过 |

修复前的 `15-control-prefix-no-match-evidence.log` 只能作为旧包观测；最终精确匹配对照采用修复后的 `28-fixed-prefixes-no-match-evidence.log`，不能把“作者链根本不可读”当成前缀匹配正确。每次查询仍严格校验当前主进程、授权、安装完成、DIRECT 来源和完整配置身份，不因业务失败放松检查。诊断计数是有界阶段证据，不作为完整推荐命中统计。

证据位于 `captures/npatch-2b/related-author-acceptance/`：`control-public-author-identity.log`、`card-basic-info-members.txt`、`owner-members.txt`、`RelateCardMapper-j.java`、`AuthorRepository-b.java`、`18-full-author-enabled-evidence.log`、`19-full-author-failure-current.xml/.png`、`20-fixed-full-author-*.log/.xml/.png`、`21-fixed-other-controls.xml/.png`、`23-name-saved.xml/.png`、`24-name-enabled-*.log/.xml/.png`、`25-name-visible-controls.xml/.png`、`28-fixed-prefixes-no-match-*.log/.xml/.png`、`29-prefix-controls.xml/.png` 和 `author-verification.log`。公开推荐会自然变化，因此同时要求实际叶项归因和界面对照，不用单张消失截图证明成功。

混合与恢复证据另见 `31-mixed-saved.xml/.png`、`32-mixed-enabled-evidence.log`、`33-mixed-controls.xml/.png`、`35-cleared-values.xml/.png`、`36-authors-off-evidence.log`、`37-restored-controls.xml/.png`。名单最终留空。

新增 5 项 JVM 回归覆盖新旧成员链序列化、精确 UID、作者显示名、缺失/异常/非正 mid 安全放行及旧直接作者路径；最终 3695 项 JVM（0 失败、1 跳过）、两个 Debug APK、快速 Lint 通过（250 Warning / 1 Hint，baseline 不变）。模块 SHA-256 `1d36c7554ea87b131f0b7c0a7f579e76ea8e65d5e15c4db6575869205b2035d6`，宿主 `c3b6cdb55fb5f1f2048ff7735ac48a5edb17272557c755e0cd6778358f43556a`；固定 patcher/native/证书复用上一单元，见 `patched-author-final/verified.json`。作者设置及详情页能力记为 `observed`，不外推首页、其它宿主版本、服务层独立路径或所有卡片类型。

最终包再次通过真实 `NPatchEmbeddedHostInstrumentedTest` 与 `HostCopySelectionInstrumentedTest`，2 项、138.851 秒，见 `final-host-regression.log`；覆盖当前配置、启停、条款、Web 和原生触屏选取复制。同签名升级及回归后，正式会员登录态和 5.0 MB 既有缓存均保留；`retained-account-cache.log`、`upgrade-cache.xml/.png` 和已查看的 `upgrade-cache-player-1.png` / `-2.png` 证明缓存播放页继续解码。没有据此声明断网或其它资源恢复场景通过。

### EMBED_WEB

2B.1 的原生 TLS 修复、恢复原有 Web Hook、关闭/条款拒绝/重新开启、重复打开/返回/后台恢复、正文滚动及复制链接均见路线图 2B.1 验收。当前模块的自由复制开关和安装诊断已有证据；评论/简介复制的具体业务仍需独立行为案例，不因 32 个公共 Hook 安装成功而全部标记通过。

账号修复后的同一最终包再次通过 `NPatchEmbeddedHostInstrumentedTest`，126.169 秒，覆盖当前配置身份、启停、条款拒绝、Web 无 Hook/完整安装及重复打开/返回/后台恢复；日志 `embedded-regression.log`。本测试不会登出账号，之后重新确认了登录与缓存保留。

## 后续顺序

账号探测修复验收后，继续评论/简介复制、播放器选项、净化和外观组合，逐行补充矩阵。随后推进构建身份一致性、同版本码不同 APK、设置 App 与内嵌模块升级和恢复链路；未测项保持待验，最终仍需正式支持矩阵和发布门禁。
