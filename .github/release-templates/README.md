# Release 文案规范

## Canary Telegram 投递回执与重试

完整回执只在 APK 和全部公告均获 Telegram 确认后保存。`receipt_schema=2` 的 `delivered_at`
来自最后一条已确认消息的 `Message.date`，同时绑定投递 run/attempt、源码提交、构建链接、
标签、源 artifact 摘要和 APK SHA-256；checkpoint 不再等待 Jobs API 同步发送步骤元数据。
同路径的完整匹配回执可直接复用；已有回执不完整或身份不符时拒绝再次发送。

旧回执没有 Telegram 时间时，仅从相同 run/attempt 的唯一成功发送步骤恢复 `completed_at`，
按 2、4、8、16 秒间隔有界重读元数据；不能使用当前时间、job 更新时间或其他步骤的日志代替。
同一次投递重复记录时复用完整身份一致的 deployment，status 写入失败后也不重复创建；
相同 run/attempt/频道出现冲突回执时停止处理。

历史 checkpoint 保存失败时，可只读恢复 `canary-telegram-notes-<run>-<attempt>` 中的完整回执。
恢复会验证工作流/仓库/main、artifact 归属、ZIP 摘要/大小/白名单以及原 Canary 构建身份。
旧回执还要求原产物创建/更新时间早于发送，且不会补造发送时未记录的 artifact 摘要。
回执缺失、过期或结果不明且没有合格旧版证据时，自动重试失败封闭，需人工核查。
artifact 的保留期仍由工作流的 `retention-days` 决定，历史线上 checkpoint 补录需要另行授权。

手动工作流的同一 run 重试会跳过已完整投递的先前 attempt，结果不确定或先前 job 不可见时停止。
新建的独立手动运行仍保留明确重发的原有语义；定时发布仍按每日规则和已发布构建去重。

从 `v1.0.5` 之后，GitHub Release 统一使用本目录中的固定模板：

- Stable 正式版：`stable.md`
- Alpha 测试版：`alpha.md`

## 固定规则

1. 标题只使用版本号：Stable 为 `vX.Y.Z`，Alpha 为 `vX.Y.Z-alpha.N`。
2. 正文结构固定：居中标题区（英文 Emoji 标题 Stable 🧬 / Alpha 🧪 ＋斜体副标）、一句概述
   （Stable）或 WARNING 风险提示（Alpha）、自动变更记录、分隔线后的下载与反馈两小节、
   居中小字脚注；安装说明用 `> [!TIP]` 提示框承载。不写 README 中的功能介绍与项目信条，
   不堆砌兼容性、已知问题、验证结果等长章节。
3. “版本概述”最多五句，Stable 为 2～5 句，Alpha/Canary 可为空；说明本版本解决了什么问题，不堆砌实现细节。
4. 每项更新以用户可感知的结果开头，必要时再补充技术原因。
5. Stable 必须包含版本概述与 APK SHA-256；兼容性、固定签名证书指纹与验证细节由附件
   `BUILD_INFO.txt`、`SHA256SUMS.txt` 和仓库文档承载。
6. Alpha 必须保留 WARNING 风险提示与回滚说明，并明确不会作为稳定更新推送。
7. Alpha Release 上传 APK、`BUILD_INFO.txt` 与 `SHA256SUMS.txt`；APK 文件名必须包含
   完整版本号和源码短 SHA。
8. 发布前删除模板中的填写提示，并确认没有残留 `{{TOKEN}}` 占位符。
9. Alpha/Stable 工作流通过 `generate_release_notes.py` 自动生成“更新内容”，详见下方“自动更新说明”。
10. Alpha 标签必须使用 `gradle.properties` 中稳定基础版本的下一个补丁前缀，例如
    `1.0.6` 对应 `v1.0.7-alpha.N`；工作流会把完整 Alpha 版本写入 APK，并在发布前反查
    APK 内部版本与构建源码 SHA。
11. Stable 只通过手动运行 `Build and publish Stable` 发布；标签必须与源码中的稳定
    `versionName` 完全一致，`versionCode` 必须在发布提交中完成递增，不允许由工作流临时覆盖。
12. Stable 的 `release_summary` 可以留空，由模型撰写 2～5 句概述；填写时优先使用手写内容。
13. Alpha/Stable 工作流只发布主仓库 Release，不再自动调用 LSPosed 同步。需要同步时单独
    运行 `sync-lsposed-release.yml`，目标标签固定为 `<versionCode>-<versionName>`，不得手工
    猜测 versionCode。
14. 独立同步只接受一个 APK 和 `SHA256SUMS.txt`，会复制全部附件。目标标签已存在时只做
    全量一致性校验，不自动覆盖或删除。
15. Alpha/Stable 的发布资产只能来自受保护环境中的 `assembleRelease`，必须由同一个长期
    证书签名并通过 `verify_release_apk.py`；普通 `main` 推送的 Debug 构建不得作为候选发布资产。

## 推荐流程

1. 复制对应模板到临时文件。
2. 填写各章节；未涉及的分类写“无”。
3. 使用 `render_release_template.py` 替换版本、提交、日期和哈希占位符。
4. 通读一次安装、兼容性、已知问题和校验值。
5. 创建 Release，并确认 Stable 未勾选 Pre-release、Alpha 已勾选 Pre-release。

Alpha/Stable 工作流会通过 `generate_release_notes.py` 自动生成变更记录，再传入
`--changelog-file`，无需发布者另外生成整份说明。手动渲染 `alpha.md` 时，需要准备
Markdown 变更记录文件并增加：

```bash
  --changelog-file ALPHA_CHANGELOG.md
```

`generate_alpha_changelog.py` / `generate_stable_changelog.py` 仍保留，作为规则版兜底。
`stable.md` 同时要求 `--release-summary` 和 `--changelog-file`，缺少任一内容或残留占位符
都会终止发布。

渲染示例：

```bash
python3 .github/release-templates/render_release_template.py \
  --template .github/release-templates/stable.md \
  --output RELEASE_NOTES.md \
  --version v1.0.6 \
  --commit 0123456789abcdef \
  --release-date 2026-09-01 \
  --sha256 abcdef0123456789 \
  --apk-filename Bilibili_Innocent_Lab-v1.0.6-01234567.apk \
  --release-summary "本版本完成近期功能与兼容性调整的稳定化收口。" \
  --changelog-file STABLE_CHANGELOG.md
```

## 自动更新说明

`generate_release_notes.py` 负责两个渠道的“更新内容”（Stable 还包括版本概述）：

1. **收集证据**（`release_context.py`）：两个渠道都以最近的已发布 Stable 或 Alpha 标签为基线，
   标签必须早于目标版本；同版本 Stable 晚于 Alpha。例如 `v1.2.1-alpha.1` 对比 `v1.2.0`，
   `v1.2.1` 可以对比 `v1.2.1-alpha.1`。AI 与规则兜底使用同一基线。收集基线到本次提交的记录、用户可见文案
   （`values-zh-rCN/strings.xml`）与更新亮点目录的净 diff、净文件变化、按改动量截取的源码摘录；
   Stable 还会读取本周期已发布的 Alpha 正文和上一发布正文。净 diff 代表最终状态，
   用来合并阶段性提交、剔除已被推翻的中间实现。收集范围与禁用术语见 `release_notes_config.json`。
2. **按槽位请求模型**（`llm_providers.py`）：槽位 1 → 2 → 3 依次尝试，模型只输出 JSON。
3. **分批与本地校验**：每批至多 40 个提交，保留完整索引、每个 SHA 的真实差异，
   以及该批实际修改的文案键和文件的最终净变化；不给诊断批次混入其他批次的新功能。
   提交引用必须属于本批范围、每个提交都要被归类、Stable 概述为 2～5 句、Alpha/Canary 最多五句，
   条目长度、不得含 Markdown/链接/内部术语、不得重复。不通过时保留最新草稿与具体错误，
   要求模型最小修改；仅概述不合规时只修改概述，每个槽位最多修订两轮，仍失败由下一槽位继续修订。
   归类、概述与最终归并的非法 JSON 也进入修订，全部槽位均失败后才使用兜底；请求错误则切换槽位。
   遗漏提交始终是错误，不因范围超过 60 个而降低校验要求。
   每批条数上限随提交数扩展；最终正文最多 100 条，保持原文风，尽量按具体场景与问题细分。
   超限时使用原条目 ID 做最终归并，逐一检查所有原点都有归属并自动恢复全部提交引用；
   原始细分证据完整保留在报告中，不用截掉正文末尾来满足上限。
   最终归并按类别和分组聚拢后划分为小组，各组共享总计 100 条的硬预算；
   独立功能和不同场景的问题保留，同一功能的重复或阶段描述合并，每组单独核对原点与引用。
   预览的 `reuse_details_run` 可复用已完成的原始归类报告，只重跑归并，避免重复请求整轮证据。
   最后单独生成概述，保留所有批次正文。任一批失败则整个范围回退规则记录，禁止发布部分 AI 总结。
   公告超过 120000 字符的正文预算时，`prepare_release_announcement.py` 把完整原文保存在
   `RELEASE_NOTES_FULL.md`，正文明确提供附件链接；附件与校验值一起发布。优先只将完整提交列表
   移到附件，更新条目本身也过长时再将完整正文移入附件，不丢掉任何条目或提交。
4. **本地渲染**：章节、图标、分组、提交链接、完整提交记录与差异链接都由脚本生成，
   模型文本中的 HTML 和 `@` 会被转义。
5. **兜底**：未配置凭据或槽位失败时回退规则版（按提交标题分类），两渠道均附完整提交记录，
   保留同名提交和维护提交，并在 Job Summary
   与 Actions 注解中给出警告。

### 配置槽位

在仓库 **Settings → Secrets and variables → Actions** 中配置，必须是仓库级（不要放在
Environment 里）。`<N>` 为 1、2、3，至少配置一个槽位；未配置 API Key 的槽位会被跳过。

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `RELEASE_NOTES_LLM_<N>_API_KEY` | Secret | 必填 |
| `RELEASE_NOTES_LLM_<N>_PROTOCOL` | Variable | `anthropic`（默认，Messages）、`openai`（Chat Completions）、`responses`（Responses）或 `gemini`（原生 generateContent） |
| `RELEASE_NOTES_LLM_<N>_BASE_URL` | Secret 或 Variable | 可选；默认 `https://api.anthropic.com` 或 `https://api.openai.com/v1`。同名 Secret 优先；**私有代理地址必须放 Secret** |
| `RELEASE_NOTES_LLM_<N>_MODEL` | Variable | `anthropic` 默认 `claude-opus-5`；`openai` 必填 |
| `RELEASE_NOTES_LLM_<N>_OUTPUT_FORMAT` | Variable | `auto`（默认）、`json_schema`、`json_object` 或 `text`；不支持时继续降级，Messages 不接受 `json_object` |
| `RELEASE_NOTES_LLM_<N>_HEADERS_JSON` | Secret | 可选字符串字典，支持 API-key、自定义认证、租户与代理头；大小写不敏感覆盖默认头 |
| `RELEASE_NOTES_LLM_<N>_BODY_JSON` | Secret | 可选请求体参数，如思考模式、服务等级或路由；不能覆盖模型、消息、工具、输出格式等核心字段 |

仓库公开，运行日志与 Job Summary 人人可见：Variable 会以明文出现在日志的 env 块中，只有 Secret 会被遮蔽。模型调用失败的错误信息在写入日志前会抹掉密钥、`sk-` 开头的密钥片段、Bearer/api_key 字段值与非官方端点地址；工作流契约测试保证密钥只从 Secret 读取。建议为发布说明单独创建带消费上限的密钥。

直连官方 Anthropic API 时会启用服务端拒答回退（`fallbacks: "default"`）；经代理时不附加该参数。
接口拒绝结构化参数时，脚本逐级降级为 JSON 模式、纯提示词 JSON；校验重试及后续批次复用
本次生成已确认的格式，不重复探测被拒参数。DeepSeek 官方 Chat Completions 的 `auto` 直接
选择 `json_object`，以 16000 输出 token 上限与非思考模式生成；`BODY_JSON` 可以显式调整。
上述任一模式都要经过本地结构、提交归属与完整覆盖校验，JSON 模式不等于 Schema 校验。

所有 `openai`（Chat Completions）槽位都会发送明确的输出 token 上限，默认 16000。
官方 `api.openai.com` 默认使用 `max_completion_tokens`；River、DeepSeek 与其他兼容端默认使用
`max_tokens`。若兼容端需要指定字段，可在 `BODY_JSON` 设置单一 `max_tokens` 或
`max_completion_tokens`，例如 `{"max_completion_tokens":8000}`；显式字段会替代默认字段。
两字段不能同时出现，值必须是 1–16000 的整数，布尔、空值、非正数或超上限均在请求前拒绝。
若所选模型的输出上限低于 16000，应显式设置更小值；脚本不根据模型名猜测提供商规格。
这只控制单次输出额度；模型推理 token 是否计入该额度，以提供商合同为准。

### 多提供商配置

基础地址由各提供商与所选模型的官方接口表确定，脚本不会根据模型名字猜协议。支持根路径、
带 `/v1` 的路径及完整操作路径，Messages 不重复追加 `/v1`。当前可接入的协议与参考：

| 提供商/接口 | PROTOCOL 与配置 | 官方来源 |
| --- | --- | --- |
| OpenCode Console/Zen | Claude 用 `anthropic`，GPT 用 `responses`，兼容模型用 `openai`；当前基础地址是 `https://opencode.ai/inference/v1`，历史 Zen/Go 地址按账户实际端点配置 | [模型与接口表](https://opencode.ai/v2/docs/console/models/) |
| OpenCode Go | 按模型选择协议；自动发送真实客户端 User-Agent 与同次生成稳定的 `x-opencode-session`，每次请求单独 ID；支持自定义头覆盖 | [会话头与客户端要求](https://opencode.ai/v2/docs/console/go/) |
| OpenAI 官方 Chat Completions | `openai`，基础地址 `https://api.openai.com/v1`；默认使用 `max_completion_tokens` 控制输出额度，包括推理模型 | [Chat Completions 参数](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) |
| DeepSeek | `openai`，基础地址 `https://api.deepseek.com`；自动 JSON 模式。`responses` 可用于其 Responses 接口 | [Chat Completions](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) |
| OpenRouter | `openai`，基础地址 `https://openrouter.ai/api/v1`；可在 HEADERS_JSON 设置 HTTP-Referer、X-OpenRouter-Title | [结构化输出](https://openrouter.ai/docs/guides/features/structured-outputs)、[归属头](https://openrouter.ai/docs/app-attribution) |
| Gemini OpenAI 兼容接口 | `openai`，基础地址 `https://generativelanguage.googleapis.com/v1beta/openai`，API Key 使用 Gemini 凭据 | [官方兼容说明](https://ai.google.dev/gemini-api/docs/openai) |
| Gemini 原生与 OpenCode Gemini | `gemini`，Google 基础地址 `https://generativelanguage.googleapis.com/v1beta`；OpenCode 用其模型表的基础地址；使用 x-goog-api-key、systemInstruction、generationConfig，支持 Schema/JSON/文本逐级降级 | [generateContent API](https://ai.google.dev/api/generate-content) |
| Anthropic 与 Messages 代理 | `anthropic`；可通过 HEADERS_JSON 配置 anthropic-version、anthropic-beta、代理认证等 | [Messages API](https://platform.claude.com/docs/en/api/messages/create) |
| 其他 Chat Completions/Responses 网关 | `openai` 或 `responses`，基础地址、模型、格式与请求头按接口合同配置 | [Responses 格式](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses) |

HEADERS_JSON 示例（内容存入对应 Secret，切勿写入工作流正文）：

```json
{"HTTP-Referer":"https://github.com/jichuo1/Bilibili_Innocent_Lab","X-OpenRouter-Title":"Bilibili Innocent Lab Release Notes"}
```

也可只配置 HEADERS_JSON 中的自定义认证而不设 API_KEY。禁止 CR/LF、非法头名、重复大小写头名、
Host/Content-Length 等传输头。错误日志抹掉自定义头值、请求体字符串及端点信息；不打印配置原文。
OpenCode 的真实客户端标识不伪装成官方 OpenCode；是否允许此类公告生成请求、免费或订阅模型的
账户权限，仍由提供商决定。这里列出已实现的协议合同，真实付费调用成功需分别验证。

### 手写覆盖

在 `.github/release-notes/<标签>.md` 放置文件（例如 `.github/release-notes/v1.2.0.md`），
工作流会原样使用它作为“更新内容”，不再调用模型。Stable 使用覆盖文件时必须填写
`release_summary`。

### 本地预览

```bash
python3 .github/release-templates/generate_release_notes.py   --channel stable --repo-root .   --repository-url https://github.com/jichuo1/Bilibili_Innocent_Lab   --release-tag v1.2.0 --commit HEAD   --changelog-output /tmp/CHANGELOG.md --summary-output /tmp/SUMMARY.md   --report-output /tmp/REPORT.md
```

在本地设置同名环境变量即可调用模型；加 `--no-llm` 只查看规则版结果。

### 云端验证

手动运行 `Preview release notes`（`release-notes-preview.yml`），选择 Alpha/Stable、目标标签和
可选的准确源码 SHA。它只读仓库，使用同一套提供商 Secret/Variable，生成完整变更记录与概述
并保存 7 天预览产物；不会签名 APK 或创建/更新 Release。用它验证真实认证、请求头与输出格式，
避免为了测试 AI 文案重复发布安装包。模型失败回退规则记录时，Job Summary 会明确标出来源。

## LSPosed 独立同步凭据

主仓库 Actions Secret `LSPOSED_RELEASE_TOKEN` 必须能够在
`Xposed-Modules-Repo/com.Bilibili_Innocent_Lab.xposedmodule` 创建 Release。优先使用只授权
该目标仓库且仅包含 `Contents: write` 的 Fine-grained PAT 或 GitHub App；组织不允许外部
协作者使用细粒度令牌时，才使用带 `public_repo` 的 Classic PAT。令牌值不得写入任何文件。

Alpha 和 Stable 发布工作流不再调用同步工作流，也不再传递该 Secret。需要同步时，在 Actions
中单独运行 `Sync Release to LSPosed repository` 并输入已经发布的主仓库标签；先选 `dry_run`
可只执行身份与附件校验。人工发布 Release 仍可能触发独立工作流的 `release.published` 入口，
但不会成为 Alpha 或 Stable 构建流程的一部分。
