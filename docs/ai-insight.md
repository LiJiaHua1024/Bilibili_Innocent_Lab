# AI Insight 实验功能

在「实验性功能 → 兼容」启用「AI Insight 视频助手」，沿用同一区域已有的 AI 来源配置。使用编号最小的有效 HTTPS 对话来源（OpenAI 兼容或 Cloudflare）；分类判定接口 JEV 不承担生成任务。修改开关或来源后重启宿主。

启用后，进入普通 UGC 视频详情页会读取并提交视频上下文：标题、简介、带时间轴字幕，以及抽样评论和弹幕。卡片先呈现一段概述，再列出核心要点；点击标题展开全文。Ask 面板支持连续提问，评论话题面板概括抽样讨论及分歧。无字幕时明确降级到元信息和讨论，不提供情节定位；没有可读取的评论时不发评论总结请求。

「自动生成原生章节」是独立开关。章节仅适用于没有官方章节、至少 180 秒（3 分钟）且有可用字幕的视频。选择 3 分钟是为了覆盖有明确内容转折的 3～5 分钟教程与知识视频；更短的视频不生成章节。卡片也提供手动生成入口。生成结果通过宿主 `ViewProgressReply → VideoGuide → VideoViewPoint → VideoPoint` 进入原生章节和分段显示，不另画章节列表。章节直接使用生成标题，不附加 `AI · ` 等来源前缀，已有模块缓存也按纯标题渲染，无需重新请求模型。新生成的章节在宿主下次读取播放进度信息时进入播放器；重新打开当前分 P 也可触发。8.90.2 实机已验证原生分段开关、章节面板、当前章节高亮和点击跳转；首轮显示时机仍取决于宿主进度刷新。

## 上下文、Prompt 与输出契约

按照用户提供的 `D:/LLM Engineering/prompt engineering guide.md`，提示词逐句执行删除测试：只有删除后会产生业务歧义的约束才保留，不加入角色身份、激励、通用思考要求或重复格式示例。

| 保留的信息 | 删除后产生的歧义 |
| --- | --- |
| 字幕/作者简介与观众讨论的证据边界 | 将弹幕或评论的观点写成视频事实 |
| 无画面输入、缺失/抽样的回答范围 | 声称看到了画面，或把抽样当作完整材料 |
| `[[t:整数秒]]` 与当前分 P 的定位约定 | 返回无法点击的时间表述，或引用其他分 P |
| 章节从零开始、连续且边界有字幕依据 | 章节覆盖不完整，或在无依据的空白区切分 |
| 材料/历史是数据 | 将字幕或评论中的命令当作任务指令 |

格式优先由 `response_format=json_schema` 承载，未在 Prompt 中再重复一份 schema。仅当服务端用结构化错误明确拒绝该格式时，退回 `json_object` 并附上同一份 schema。输出须通过本地类型、字段、数量、长度、时间范围和字幕覆盖检查，才会渲染或缓存。服务端即使接受 schema 也可能不遵守约束：确定的本地校验失败最多纠正一次，只反馈固定约束和字段路径，不带入上次错误答案；两次生成共享 90 秒模型时限。认证、网络、输出截断、未知错误或无法识别的 JSON 不自动重放，失败不缓存。Insight 单次输出预算为 8192 token，不改变其他 Agent 的默认预算。

River 的 DeepSeek-V4.1-Flash-1017 在默认参数下出现过 JSON 后重复追加、自我改写并耗尽预算。用同一份 310 条合成字幕逐参数实测，`enable_thinking=false` 单独无效，`thinking: {type: "disabled"}` 正常停止；将后者加入现有 `AiChatCompat` 和模型客户端的已验证端点范围，未知端点不猜测该参数。若服务端明确拒绝可选参数，仍使用既有有界回退。该兼容处理不是 Prompt 中的“不要思考”口令。

上下文按任务投影：评论总结省去字幕和弹幕；章节省去评论、弹幕、简介和对话；字幕视频总结保留完整主题材料及少量讨论，只选最重要的 3 至 6 个论点，概述及每条要点均不超过 180 字符。无字幕任务不索取片段引用，不提供无助于回答的总时长；简介中的原片时分秒位置省略，其余事实保留，避免将外部原片位置误认为当前分 P。Ask 有关键词命中时检索最多八个相关字幕窗口及其邻近行，未命中则保留全局时间轴；代词追问继承前一问题和已验证引用附近的字幕，过滤中英文常见疑问词。长字幕按编码后的大小均匀抽样，覆盖首尾并显式标记抽样。每行用 `[起秒,止秒,文本]` 三元组，避免重复字段名；任务与问题放在材料之后。Ask 仅保留当前视频最近六轮对话。

自动化测试覆盖正常、缺失、超长、注入文本、无关材料、格式偏离、时间越界等场景。2026-10-24 另用用户提供的 River 兼容接口和 DeepSeek-V4.1-Flash-1017 做了真实模型、真实视频的端到端验收。样本验证不等同于回答准确率的统计对照评估。

## 缓存与播放器边界

- 总结和章节保存 90 天，评论话题保存 1 小时；磁盘最多 200 条，内存有界。按 BVID、CID、模型端点、模型名、任务和协议版本隔离。
- 先读缓存再加载上下文；成功结果二次打开无需再次请求 B 站或模型。同一仓库实例串行合并重复请求；失败结果不缓存。
- 原始字幕、评论、凭据和 Ask 对话不落盘。生成结果位于宿主私有 `files/bil_insight/`。
- 字幕并行观察宿主 DMMoss 与独立 SubtitleMoss，公开 API 兜底；后台最多等待原生字幕响应 2.5 秒，空响应不覆盖已捕获字幕。标题、评论也可从对应宿主响应提取；快照变化会使已加载上下文失效，避免一直使用未打开评论区时的空样本。字幕下载限定 HTTPS 的 hdslb CDN；原生 HTTP CDN 地址只在域名、端口、用户信息与 fragment 校验后升级到 HTTPS，保留签名查询。不附带模型凭据或宿主 Cookie，不跟随重定向。
- 点击时间前同时检查前台详情 Activity、播放器归属、媒体世代、BVID、CID 和真实播放器时长。复用现有 `SponsorPlayerBindings`、播放器定位器和 `SponsorSeekGuard`，在延迟 seek 真正执行时再次校验。
- 原生章节通过独立 builder 复制整条消息链；已有官方章节、默认实例、结构缺失或复制失败时返回原响应，保留其他字段。生成章节打开 `pointPermanent`，让宿主原生分段开关可见。
- 字幕起止时间可含小数，播放器跳转使用整数秒：引用与章节统一用 `floor(start)..ceil(end)` 校验覆盖，实际 seek 仍按当前播放器真实时长二次校验。章节不从零开始、间隔不足或没有字幕依据时提供固定校验反馈，仍最多纠正一次。
- 卡片预览最多七行，展示概述及首个要点；竖视频根据简介视口缩短预览，保留原生简介的可滚动空间。全文与 Ask 面板使用现有宿主配色的实色背景，避免个性皮肤使对话透明叠在视频上。

## 无手机静态证据

本次使用本地 `tools/hostlab` 的远程 MCP 实现读取 B 站 9.15.0 样本，没有运行 ADB。样本 SHA-256：`a9b70a6ffb66ed3c074ce32faa53dcbfa48d6a8cb2c80ece6921f227d178a1cd`；样本 revision：`2da9a7ef202dffc3f42ea036b95c13c2e0dd9676`。

| 证据 | 分析运行 |
| --- | --- |
| 字幕请求/回复与简介 Fragment | [39933024165](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39933024165) |
| 简介页创建/销毁视图 | [39933357100](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39933357100) |
| 章节协议字段、CID/AID 与调用者 | [39934200392](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39934200392) |
| 协议到原生播放器模型的转换 | [39934608853](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39934608853) |
| 宿主实际 `executeViewProgress` 路径 | [39935745989](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39935745989) |
| 原生分段开关由 `pointPermanent` 控制 | [39936763821](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39936763821) |
| `WatchPoint.TYPE_CHAPTER = 2`，保留章节/高光类型区分 | [39938424670](https://github.com/jichuo1/innocent-lab-host-analysis/actions/runs/39938424670) |

完整结果保存在本 worktree 已忽略的 `captures/hostlab/`。这些是单版本静态证据，不代表已完成实机验收或其他版本验收。安装时使用当前宿主 ClassLoader 复核成员签名；Kotlin Moss 通过现有序列化桥处理，并按单次请求关联响应，避免并发 CID 串用。

## 本地验收记录（2026-10-23）

在 `experiment/ai-insight` 的独立 worktree 中，使用标准 JDK 17、ASCII SDK/工程路径及原有 Gradle/Android 用户目录执行：

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintFast --offline --no-daemon
./gradlew.bat --stop
```

- Debug APK 构建成功；3992 项 JVM 测试中 3991 项通过、1 项跳过，零失败。其中 AI Insight 和结构化输出相关 40 项全部通过。
- 跳过的是依赖本地 `server/src/analytics/capability-catalog.json` 的诊断目录投影测试，该私有服务目录没有包含在此 checkout 中。
- 快速 Lint 通过：258 条警告，原有 3 个错误继续由既有 baseline 过滤；baseline 和规则配置均未修改。新增文件唯一警告为 `ViewConstructor`：`InsightCard` 仅由安装器注入仓库和回调后创建，不走 XML inflate，保留必要构造参数；未添加 suppress。
- 缓存读取独立于生成队列，回归测试确认另一视频持有生成锁时仍能读取缓存总结。失败格式、缺失评论、无字幕章节、跨分 P 和模型隔离、冷进程章节恢复均有覆盖。
- 验证前后 31 份代码/资源/测试输入的 SHA-256 一致。构建后已停止并确认项目对应 Gradle/Kotlin 守护进程退出。
- APK：`Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`，24,863,235 字节，SHA-256 `8b13b60d6ae53070106e2c5a35e9c912b907f5c4d96d82e2b40c0f50585fff45`。

日志：`captures/validation-verified.log`；快速报告：`Bilibili_Innocent_Lab/app/build/reports/lint/fast/lint-results-debug.html`。本次没有执行 ADB、安装 APK 或真机操作；没有进行真实模型的质量对照评估。所有改动保持未提交，未 push。

## 实机与真实模型验收（2026-10-24）

在用户开放的 `192.168.1.78:5555` 上覆盖安装并冷启动宿主验证，Android 16、LSPosed API 102、B 站 8.90.2（8902100）。沿用已有 AI 来源 1，测试端点 `https://api.river.ai/v1`，主要模型 `deepseek-ai/DeepSeek-V4.1-Flash-1017`。凭据仅写入模块私有设置，未写入源码、文档或诊断日志。没有卸载应用、清除应用数据、修改方向锁定或重启手机。Shizuku 未用于测试。

| 实机样本 | 已验证结果 |
| --- | --- |
| `BV1uqtpzeEb9` / CID `31572494785`，31:34，310 条字幕，无官方章节 | 概述与 6 个要点；总结引用 2:48 跳转后截图为 2:49；Ask 引用 3:33 跳转后截图为 3:34；代词追问保留攀爬与抓握力依据；原生 12 章节、进度条开关与高亮；点击 1:36 后截图为 1:37；横屏进入及返回 |
| `BV1DmnB6jEGu` / CID `42977264009`，17:35，445 条字幕 | 切视频更新上下文；打开宿主评论区后读取 14 条样本，生成主要讨论方向与分歧；自动章节真实请求成功 |
| `BV1bx411M7Zx` / CID `25368631`，19:13，无可提取字幕 | 去除本轮该样本的旧生成缓存后重新请求成功，明确只依据元信息；Ask 明确无法识别一分钟处的画面，无时间跳转；章节在上下文阶段拒绝，不调用模型。画面上的双语字幕为烧录内容，不能视作已提取字幕 |
| `BV1ZFnh6KEZ6` / CID `42984868036`，1:31，42 条字幕 | 竖屏视频保留原生简介区域；总结时间链接可见于展开全文；短视频章节在模型调用前拒绝 |

原生 ViewPager 的测量高度在竖视频中会延伸出窗口，单看测量值无法保留原简介空间。卡片复用 `HostViewVisibility` 读取布局后的可见区域，在挂载/卸载时注册/释放布局监听；实机截图与视图树同时核对。其他宿主版本没有实机验收；官方章节保护、跨分 P、退后台、延迟 seek 失效、网络/格式失败与缓存隔离由相应单测覆盖，本轮未另取含官方章节的真实样本。

冷进程重开 GDC 样本记录到 `cache mode=SUMMARY` 和 `cache mode=CHAPTERS`，未进入生成阶段；总结缓存的修改时间 `1792813027` 与 SHA-256 `4572c1ecc2d0f2de10878302900cdb30c4cad2e0ef5c1c6153cec0d1a66e383f` 前后不变。此证据验证无重复生成，不能把宿主冷启动总耗时当作缓存读取耗时。

最终验证：

- `assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过，4001 项测试中 4000 项通过、1 项上述私有目录依赖测试跳过，零失败。
- 随后仅调整可见区域布局及使用已有 KTX Drawable 扩展，重新执行 `assembleDebug lintFast --offline --no-daemon` 通过，复用已通过的业务单测。快速 Lint 为 258 条警告、1 条提示，原有 baseline 与规则不变；未执行完整 Lint。
- 每轮均执行 `--stop`，最终确认无项目 Gradle/Kotlin 守护进程残留。
- 该轮验收 APK 为 26,363,876 字节，SHA-256 `d600d98e14acbbadc1a654607a3c5b7cd4f36265739df1547bd8abe593ea1fad`，已覆盖安装到测试机。

截图索引：`captures/real-device-20261024/accepted/index.md`；运行证据、缓存前后指纹与接口实验保存在同级目录。构建日志为 `build-delivery.log` 与 `build-viewport-delivery.log`，快速报告仍在 `app/build/reports/lint/fast/`。这些样本证明四项功能及所列降级路径的实际行为，不代表所有视频或模型的回答质量保证。

此前功能提交为 `d21ae658`；本节记录同一 worktree 中的实机修复及验收结果。

收尾已复核：测试 AI 配置保留在模块私有来源 1，日志开关与等级恢复到测试前的值，本机加密测试密钥副本和明文临时文件已清理。手机停在 GDC 视频详情的 AI 总结卡片，播放器已暂停。

## 章节标题与时长调整（2026-10-24）

按用户反馈移除原生章节标题的 `AI · ` 前缀，并将自动触发、请求前置校验、输出校验和原生注入的最低时长统一为 `MIN_CHAPTER_VIDEO_SECONDS = 180`。中英繁三语说明同步为至少 3 分钟；无字幕和已有官方章节的限制保留。已有缓存只在显示时使用纯标题，不失效、不重新生成。

重新执行相关 45 项 JVM 测试全部通过，覆盖 2:59 阻止且不请求模型、3:00/4:00/5:00 放行及原生标题内容保持不变；Debug 构建和快速 Lint 通过，最后停止并确认无 Gradle/Kotlin 守护进程残留。

实机新增 `BV1Gf4y1y7wc` / CID `281031471` 的首个分 P：时长 3:47，读取 91 条字幕，无官方章节，真实模型生成 6 章，边界为 0、45、75、107、143、163 秒。重开后显示原生纯标题列表，点击 1:47 后进度为 1:48、对应章节高亮。另重开原 GDC 样本，确认既有缓存章节同样去除前缀。截图为索引中的 20～22，旧截图保留原验收时状态。

本轮 APK 已覆盖安装，为 28,667,218 字节，SHA-256 `45c7259970c06501262151a1d6da1a0c19f1cc10253b8f84b7518ccd9c64bbd8`。证据为 `build-chapter-threshold-tests.log`、`build-chapter-threshold-lint.log` 和 `chapter-adjustment-validation.json`。验收后手机留在暂停的 GDC 原生章节面板。
