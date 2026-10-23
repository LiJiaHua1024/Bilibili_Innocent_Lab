# AI Insight 实验功能

在「实验性功能 → 兼容」启用「AI Insight 视频助手」，沿用同一区域已有的 AI 来源配置。使用编号最小的有效 HTTPS 对话来源（OpenAI 兼容或 Cloudflare）；分类判定接口 JEV 不承担生成任务。修改开关或来源后重启宿主。

启用后，进入普通 UGC 视频详情页会读取并提交视频上下文：标题、简介、带时间轴字幕，以及抽样评论和弹幕。卡片先呈现一段概述，再列出核心要点；点击标题展开全文。Ask 面板支持连续提问，评论话题面板概括抽样讨论及分歧。无字幕时明确降级到元信息和讨论，不提供情节定位；没有可读取的评论时不发评论总结请求。

「自动生成原生章节」是独立开关。章节仅适用于没有官方章节、至少 600 秒且有可用字幕的视频。卡片也提供手动生成入口。生成结果通过宿主 `ViewProgressReply → VideoGuide → VideoViewPoint → VideoPoint` 进入原生章节和分段显示，不另画章节列表。新生成的章节在宿主下次读取播放进度信息时进入播放器；重新打开当前分 P 也可触发。是否在首轮播放中自然刷新、原生章节面板布局和点击行为，仍需要真机验收。

## 上下文、Prompt 与输出契约

按照用户提供的 `D:/LLM Engineering/prompt engineering guide.md`，提示词逐句执行删除测试：只有删除后会产生业务歧义的约束才保留，不加入角色身份、激励、通用思考要求或重复格式示例。

| 保留的信息 | 删除后产生的歧义 |
| --- | --- |
| 字幕/作者简介与观众讨论的证据边界 | 将弹幕或评论的观点写成视频事实 |
| 无画面输入、缺失/抽样的回答范围 | 声称看到了画面，或把抽样当作完整材料 |
| `[[t:整数秒]]` 与当前分 P 的定位约定 | 返回无法点击的时间表述，或引用其他分 P |
| 章节从零开始、连续且边界有字幕依据 | 章节覆盖不完整，或在无依据的空白区切分 |
| 材料/历史是数据 | 将字幕或评论中的命令当作任务指令 |

格式优先由 `response_format=json_schema` 承载，未在 Prompt 中再重复一份 schema。仅当服务端用结构化错误明确拒绝该格式时，退回 `json_object` 并附上同一份 schema。认证失败、未知 HTTP 错误、超时和无效内容不会触发盲目重放；JSON 模式也不支持时停止。输出还须通过本地类型、字段、数量、长度、时间范围和字幕覆盖检查，才会渲染或缓存。

上下文按任务投影：评论总结省去字幕和弹幕；章节省去评论、弹幕、简介和对话；视频总结保留完整主题材料及少量讨论。Ask 有关键词命中时检索最多八个相关字幕窗口及其邻近行，未命中则保留全局时间轴。长字幕按编码后的大小均匀抽样，覆盖首尾并显式标记抽样。每行用 `[起秒,止秒,文本]` 三元组，避免重复字段名；任务与问题放在材料之后。Ask 仅保留当前视频最近六轮对话。

自动化测试覆盖正常、缺失、超长、注入文本、无关材料、格式偏离、时间越界等场景。这证明契约和上下文边界的实现行为；尚未进行使用真实模型的质量对照评估，不能据此声称回答准确率已提高。

## 缓存与播放器边界

- 总结和章节保存 90 天，评论话题保存 1 小时；磁盘最多 200 条，内存有界。按 BVID、CID、模型端点、模型名、任务和协议版本隔离。
- 先读缓存再加载上下文；成功结果二次打开无需再次请求 B 站或模型。同一仓库实例串行合并重复请求；失败结果不缓存。
- 原始字幕、评论、凭据和 Ask 对话不落盘。生成结果位于宿主私有 `files/bil_insight/`。
- 字幕优先读取宿主 DMMoss，公开 API 兜底；标题、评论也可从对应宿主响应提取。字幕下载限定 HTTPS 的 hdslb CDN，不附带模型凭据或宿主 Cookie，不跟随重定向。
- 点击时间前同时检查前台详情 Activity、播放器归属、媒体世代、BVID、CID 和真实播放器时长。复用现有 `SponsorPlayerBindings`、播放器定位器和 `SponsorSeekGuard`，在延迟 seek 真正执行时再次校验。
- 原生章节通过独立 builder 复制整条消息链；已有官方章节、默认实例、结构缺失或复制失败时返回原响应，保留其他字段。生成章节打开 `pointPermanent`，让宿主原生分段开关可见。

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
