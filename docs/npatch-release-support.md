# NPatch 支持范围与恢复

2026-10-22，2B 已按下表限定组合完成正式准入，最终门禁见[当前路线图](npatch-implementation-roadmap.md)和 [RELEASE_ADMISSION_2B](npatch-embed-acceptance.md#release_admission_2b)。逐业务证据在[行为案例](npatch-embed-acceptance.md)和[目录矩阵](npatch-feature-matrix.tsv)，未测业务不因加载模式验收而自动变为通过。

## 固定组合

| 项目 | 本轮组合与边界 |
|---|---|
| 测试环境 | 有线备用机 `13a7a291`，Android 16，arm64-v8a，无 Root |
| 宿主 | 官方原版 B 站 8.90.2 / versionCode 8902100，单 APK；包名 `tv.danmaku.bili` |
| Loader | 833 / API 102，普通 entry-hook，签名绕过等级 2，不启用 injectdex |
| 固定修复 | Java Manager Binder 修复及 native TLS/linker 锁修复；由工具核对双 ABI 内容哈希 |
| 配置 | 独立设置 App 的当前直连配置、许可与回执；接受条款并显式开启免 Root；不要求开启通信兼容模式 |
| 模块 API | Modern API min 101 / target 102，固定作用域，不启用 autoHotReload |
| 签名 | 测试宿主使用 NPatch 测试证书；用户证书的同证书产物流程另经离线测试，不外推其账号/第三方应用兼容性 |

本轮 arm64 真机运行，x86_64 loader 仅交叉编译并核对字节。其它 Android、ROM、宿主版本、split APK、签名等级、injectdex 和第三方模块共存组合保持未验证。最低系统要求与 Manager 的最低版本是代码准入条件，不表示所有满足最低要求的组合都已实测。补丁档案未知时不猜测当前模式，也不把 Manager 已安装等同于 Manager 模式。

## 签名与版本矩阵

| Loader / 宿主 / 环境 | Manager | embed | 证据与处理 |
|---|---|---|---|
| 固定修复版 833 / 8.90.2 / Android 16 arm64 / 等级 2 | 已实测 | 已实测 | 复用 2B 正式准入和模式往返证据 |
| 833 / 相同宿主和环境 / 等级 0、1 | 未验证 | 未验证 | 不根据更低等级推断业务可用；当前产物工具固定等级 2 |
| 833 / 相同宿主和环境 / 等级 3–5 | 未验证 | 固定 patcher 不接受 | embed 上限来自固定源码；不外推其它 Loader 的行为 |
| 其它 Loader、宿主、Android 或 ABI / 任意等级 | 未验证 | 未验证 | 按具体组合补证据，不从版本号大小或设备 ABI 推导兼容性 |
| 自有证书 / 固定工具参数 | 离线产物校验通过 | 离线产物校验通过 | 同证书升级与错误证书拒绝已有工具证据，账号和第三方回调仍未验证 |

诊断显示实际安装的宿主版本、Android API、设备 ABI，以及参数是否匹配上述基线。参数匹配只是一项静态对照：相同的 `Loader=833` 不能证明已经应用 Java/native 修复，设备支持 arm64 也不能证明当前宿主运行在 64 位进程。需结合 `verified.json` 的内容哈希、当前宿主回执及实际进程观测确认。未知或矩阵外参数只提示未验证，不自动关闭整组功能或改变授权。

## 两种模式的使用流程

embed 将**同一份已签名模块 APK**放进宿主，另行安装并保留这份模块作为设置 App；无需 Manager。Manager 模式从已安装模块加载，需在 Manager 中配置模块对宿主的作用域。两者共用直连配置、条款和当前身份校验，没有 embed 专用的功能类别或 Web 整进程裁剪。

首次使用：安装设置 App 与修补宿主，打开设置 App，接受条款并选择 NPatch 免 Root 通道，再手动冷启动 B 站。只有收到当前配置的有效宿主回执，设置界面才确认激活。普通配置变更后也需冷启动宿主；本轮不承诺热应用全部设置。

代码更新：先验证并签名模块，再用原版宿主重新修补，覆盖安装设置 App 与新宿主，最后冷启动。只更新设置 App 不会替换 embed 内的代码；同版本码也可能内容不同，以诊断中的内容一致性与 `verified.json` 中模块 SHA-256 核对。不能用旧心跳或仅版本号相同替代当前内容验证。

模式切换：使用相同原版输入、当前模块及同一宿主证书生成另一模式的产物，覆盖安装并冷启动。设置 App 和宿主各自保持原有签名链。工具拒绝证书不一致的升级，不通过卸载宿主规避签名错误。

构建和自有签名的完整参数见[产物工具说明](../scripts/npatch/README.md)。固定 patcher 及 native 哈希由脚本验证；未知 patcher、已修补输入、已有输出、缺少签名参数或错误证书均不能生成通过记录。没有 `verified.json` 的中间 APK 不属于验收产物。

## 配置、授权与恢复

| 情况 | 处理与预期结果 |
|---|---|
| 设置 App 被强行停止 | 当前端点可能不可达，宿主不凭旧授权安装功能；先打开设置 App，再冷启动宿主 |
| 免 Root 关闭或条款撤回 | 当前许可被拒绝，冷启动后零功能安装；旧租约、回执或配置不能重新启用 |
| 仅覆盖安装设置 App | 保留配置与当前身份；embed 可能显示内容不同，按提示重新修补宿主 |
| 卸载重装设置 App | 配置、条款和账号授权已删除；新身份不能继承旧授权，需重新接受条款并启用，账号授权需重做 |
| 导入设置备份 | 只恢复白名单业务设置，不恢复条款、免 Root 意图、账号凭据、租约或运行回执 |
| 协议未知或数据损坏 | 拒绝该次文档/许可，不用旧缓存冒充就绪；恢复匹配版本后重新握手 |
| 内嵌内容未知/不同 | 先核对当前设置 App 与修补记录，使用当前模块重新修补并冷启动，不通过卸载猜测恢复 |

无需清除 B 站账号、缓存或全局系统设置。验收过程的覆盖安装保留了原有登录、已下载缓存及缓存入口播放；并未通过卸载宿主证明“升级成功”。

## 功能与客观约束

代表性证据覆盖净化/过滤、评论与触摸复制、普通/Story 播放器、外观、账号授权、Web、下载与缓存播放，以及原版 DEX/DexKit native 查询。主进程与适用子进程分别保留实际配置与安装证据；某进程的 Hook 数量不能代替业务效果。没有样本的通用业务项仍在目录矩阵中保持待验。

833 embed 不提供 Manager 的动态作用域管理；更新内嵌模块必须重新修补宿主。固定源码 `7723mod/NPatch` 的 `a9dc461945fbe96caf22acf80e67a72d68a6d4d4` 中，`patch/src/main/java/top/nkbe/patch/NPatch.java` 明确拒绝 `!useManager && sigbypassLevel > SIGBYPASS_HIGH`，其中 HIGH 为 2；这不能通过设置页开关绕过。源码归档及哈希固定在[构建输入清单](../scripts/npatch/native/sources-833.json)。当前普通 entry-hook 已覆盖实测进程，没有启用 injectdex 的必要证据。

模块在 [module.prop](../Bilibili_Innocent_Lab/app/src/main/resources/META-INF/xposed/module.prop) 声明 `autoHotReload=false`，功能 Hook 在 package-ready 生命周期注册。不能把 Manager 同步配置或上游 hot-reload 回调等同于卸载并重建现有 Hook；代码更新和需要重装 Hook 的设置仍通过冷启动生效。

native 能力按自身 ABI 与运行环境独立判定。原版 DEX/DexKit 通道已有真实 ART 查询证据；不把它外推为插帧模型推理或其它 ABI 全部通过。缺少某项可选 native 能力时按原有能力边界降级，不据此裁掉整个 embed 模式。

### native 能力边界

| 能力 | 当前实现与降级 | 运行验证范围 |
|---|---|---|
| DexKit 后备适配 | APK 按现有配置打包 arm64-v8a/armeabi-v7a；加载失败返回 `NATIVE_UNAVAILABLE`，不取消其它 KavaRef/候选表定位或整个 Hook 安装 | arm64 原版 DEX 真实查询已通过；其它 ABI 未实测 |
| 插帧依赖准备 | `FrameNativeLoader` 按当前进程位数和对应 ABI 列表选 ARM 依赖，有界验证归档/库哈希后加载；不支持的 ABI 单独返回 `UNSUPPORTED_ABI`，准备/链接失败仅影响本能力 | ARM64/ARM32 归档及状态机已有构建/JVM 验证；不等于模型推理或宿主播放器整合已通过 |
| x86/x86_64 | 当前模块不打包 DexKit 和插帧依赖；DexKit 尝试加载失败后降级，插帧拒绝不匹配的运行 ABI | 没有 x86 实机运行证据；833 x86_64 loader 交叉编译不能代替模块 native 验证 |

当前进程诊断使用只读查询协议 v2，分别报告 `Process.is64Bit()`、DexKit 加载状态和插帧准备状态，不为查询主动加载库或执行模型。`尚未尝试` 不代表失败，`库已加载` 不代表真实查询或推理已验证。旧协议进程不响应新查询，保留未知并要求更新/冷启动，不把设置 App 自身的位数或 native 状态当成宿主观测。此扩展不改变授权协议、主进程心跳或导出报告格式。

旧研究提案中的“所有非 arm64 一律禁用插帧”不再作为当前能力规则：实际工程已有 ARM32 依赖和进程位数检查，保留现有实现，未验证的 ABI 继续在矩阵中标为未知。

### 重新修补与适配缓存

Manager/embed 共用后台内容校验：启动仍按宿主包名、版本、APK 大小/修改时间和规则版本检查缓存；重新修补使这份安装身份变化时，不在 `Application.attach` 同步打开 APK 或直接采用旧成员。取得当前端点许可后，既有适配工作线程核对原版全部 DEX 的稳定内容指纹，以及相同宿主版本/名称、规则、缓存结构和手动重置时间；全部一致才将旧结果绑定到当前安装身份并原子保存，保留原始时间戳。

需要新功能补适配、原版 DEX 改变、来源未知/不可读、规则变化或手动重置时，不通过重绑定绕过原有刷新条件，继续正常适配。首次重修补后的启动保留原有实时定位回退和有限补适配路径，下一次冷启动可直接命中保存的缓存；不承诺第一次启动全量使用旧缓存，也不把这项优化等同于阶段 5 的 Hook 安装耗时优化。

同签名升级 embed 及 embed→Manager→embed 往返已实际观测到后台重绑定，随后冷启动 `cache=file-hit` 且没有全量适配；当前配置身份和新鲜许可另经模式检查验证。这里复用的是类/成员适配数据，不保存或恢复授权许可；端点不可达、关闭或条款撤回时仍按原边界拒绝功能安装。跨卸载、跨宿主版本或跨规则的缓存迁移不在此实现范围内。

## 准入证据索引

| 通道或类别 | 代表性案例 | 证据边界 |
|---|---|---|
| Web 与进程 | [EMBED_WEB](npatch-embed-acceptance.md#embed_web)、[MODE_ROUNDTRIP](npatch-embed-acceptance.md#mode_roundtrip) | 修复 loader 根因，恢复公共 Hook；关闭/条款拒绝时 Web 仍可用，主/Web/下载/播放服务分别核对 |
| 净化与过滤 | [RELATED_COMMERCIAL](npatch-embed-acceptance.md#related_commercial)、RELATED_RUNTIME、RELATED_DURATION、RELATED_PLAY_COUNT、RELATED_AUTHOR | 实际响应写回、当前能力归因及可见前后对照；其它规则/表面仍按矩阵保存状态 |
| 评论与复制 | [FREE_COPY_REPLIES](npatch-embed-acceptance.md#free_copy_replies)、COMMENT_SEARCH_LINKS | 折叠正文、表情与触摸选区/复制组合；未发送评论或消息 |
| 播放器 | [PLAYER_SPEED](npatch-embed-acceptance.md#player_speed)、PLAYER_QUALITY、PLAYER_STORY_QUALITY_ABI、STORY_ACTION_ICONS、PLAYER_PORTRAIT_CONTROL | 普通/Story 的真实 ABI、设置与控件；不外推所有视频类型/会员组合 |
| 外观 | [APPEARANCE_REPRESENTATIVE](npatch-embed-acceptance.md#appearance_representative) | 默认→海盐→恢复跟随，页面与当前回调一起核对 |
| 账号、下载与分享 | [ACCOUNT_GATE](npatch-embed-acceptance.md#account_gate)、LOGIN、DOWNLOAD、CACHED_PLAYBACK、SHARE_HANDOFF | 本地授权/撤销、原有登录、普通缓存播放、系统分享选择页及取消；不等于外部发送/长期风控验证 |
| native 与适配来源 | [EMBED_DEX_SOURCE](npatch-embed-acceptance.md#embed_dex_source) | Java/native 共用原版 38 DEX 快照、真实 DexKit 查询及缓存失效；插帧推理另验 |
| 安装、升级与恢复 | [EMBED_BUILD_IDENTITY](npatch-embed-acceptance.md#embed_build_identity)、EMBED_SETTINGS_LIFECYCLE、MODE_ROUNDTRIP、PATCH_R8_ENTRY | 同版本不同内容、设置 App 生命周期、新身份重新授权、模式往返、R8 真实混淆入口 |

标准框架回归覆盖来源优先级、标准文档与直连授权回退契约；本轮仅授权上述无 Root 有线机，没有新增 LSPosed 真机回归。正式发布密钥未在本机配置；Release/R8 实机包通过原测试证书签名并明确作为本地验收产物。正式分发继续使用项目既有发布签名门禁。

测试账号的已有登录、普通下载与本地账号授权/撤销均有观测；没有主动制造风控、令牌失效、支付或长期封禁实验。第三方客户端可能独立核验宿主证书，改签后的外部分享/登录回调不能靠模块伪装成官方签名；应分别记录本地分享面板、系统分享入口和实际外部接收结果。未知组合不能描述为永久不支持。

上述上游约束的版本证据及固定源码输入见[调研档案](npatch-compatibility-plan.md)、[实施记录](npatch-implementation-roadmap.md)和[可复现 loader 配方](../scripts/npatch/README.md)。后续阶段继续完善签名/ABI 矩阵、诊断、启动性能和可热应用子集，不把通用增强重新列为 2B 已发现故障。
