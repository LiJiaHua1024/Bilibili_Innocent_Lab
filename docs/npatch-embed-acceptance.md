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

修复证据为 `account-fixed-logcat.txt`、`account-ready.log`、`access-revoked.xml`、`precheck-revoked-summary.xml`、`account-revoked-host.log`。当前打包证据在 `patched/verified.json`：模块 SHA-256 `6132395828fc66a10b3505e54c8d1ff66c293a6ded2a1e8d427e4f8b8a7cfde5`，宿主 `05379e63ae90d79575f8511db0637618ec7f018196ab5ad90598902bc8fd3539`，保留原有双 ABI native 修复与同证书。

### FREE_COPY_DESCRIPTION

展开公开视频 `BV1xx411c7mD` 的简介，长按正文后出现模块自由复制气泡，换行已归一；气泡可选中 BV 号，执行系统复制键后在本地输入框粘贴，内容与选区一致。截图及视图树证明实际回调，记录为 `observed`，没有把键盘复制等同于所有触摸工具栏、拖选、多行与表情组合通过。本案例在账号修复重新修补前完成，自由复制实现本轮未再修改。

证据：同目录 `description-dialog.xml/.png`、`description-selection.png`、`description-pasted.xml`、`description-copy.log`。评论复制仍需单独案例。

### EMBED_WEB

2B.1 的原生 TLS 修复、恢复原有 Web Hook、关闭/条款拒绝/重新开启、重复打开/返回/后台恢复、正文滚动及复制链接均见路线图 2B.1 验收。当前模块的自由复制开关和安装诊断已有证据；评论/简介复制的具体业务仍需独立行为案例，不因 32 个公共 Hook 安装成功而全部标记通过。

账号修复后的同一最终包再次通过 `NPatchEmbeddedHostInstrumentedTest`，126.169 秒，覆盖当前配置身份、启停、条款拒绝、Web 无 Hook/完整安装及重复打开/返回/后台恢复；日志 `embedded-regression.log`。本测试不会登出账号，之后重新确认了登录与缓存保留。

## 后续顺序

账号探测修复验收后，继续评论/简介复制、播放器选项、净化和外观组合，逐行补充矩阵。随后推进构建身份一致性、同版本码不同 APK、设置 App 与内嵌模块升级和恢复链路；未测项保持待验，最终仍需正式支持矩阵和发布门禁。
