# Canary 与 Telegram 配置

Canary 每次 `main` 推送通过检查后，使用现有 Alpha 固定签名环境自动构建 Release APK，产物保存在 Actions 30 天，不创建 Git Tag 或 GitHub Release。版本名采用下一补丁的 `X.Y.Z-canary.<run_number>`，`versionCode` 仍由源码控制。

模块 Canary 渠道读取公开的成功 main/push 工作流和产物元数据，验证版本、源码、归属与过期状态。最新安装包在 Actions 构建页下载；Telegram 发布频道仅同步手动选定的版本。模块不保存 GitHub/Bot 令牌。Stable 和 Alpha 保留原流程。

## 手动同步到 Telegram

日常 `Build and upload Canary` 仅生成 Actions 产物，不读取 Bot 令牌，也不自动向频道发消息。

1. 打开仓库 Actions，选择 **Manually sync Canary to Telegram** 工作流。
2. 点击 **Run workflow**，分支选择 `main`。
3. 在 `canary_run_url` 填入需要同步的 Canary 构建链接，例如：
   `https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/37497610746`。
4. 保持 `dry_run` 不勾选，运行后即同步该构建的 APK。若勾选，则只下载并核验产物、签名和发送参数，不发频道消息。

该工作流使用已经构建好的 APK，不重新编译、不增加 Canary 版本序号。它只接受本仓库成功的 main Canary 构建，并检查产物是否过期、源码和版本是否匹配、ZIP 摘要、文件哈希及实际固定签名证书。过期产物需要使用仍保留的构建；失败的构建、其他仓库或其他工作流链接不能同步。完整文件仍保存在 Actions 30 天。

## 配置 Bot

1. 在 Telegram [@BotFather](https://t.me/BotFather) 创建 Bot，或使用已有 Bot。
2. 将 Bot 加入 [@Bilibili_Innocent_LabRelease](https://t.me/Bilibili_Innocent_LabRelease)，设为管理员并授予“发布消息”权限。
3. 在 [仓库 Actions Secrets](https://github.com/jichuo1/Bilibili_Innocent_Lab/settings/secrets/actions) 添加 `TELEGRAM_BOT_TOKEN`。
4. 可选变量 `TELEGRAM_RELEASE_CHAT_ID`，默认 `@Bilibili_Innocent_LabRelease`；也支持私有频道的 `-100...` chat ID。

令牌仅存储在 GitHub Secrets。以下本地辅助脚本提供隐藏输入，并通过 stdin 写入 GitHub；令牌不会进入命令行参数或仓库文件：

```powershell
.\.github\release-templates\setup-telegram.ps1
```

手动工作流核验所选产物及其签名后，以**上一次实际成功发布到同一 Telegram 频道的源码提交**为起点，重新生成截至所选构建源码的完整提交列表。范围不受本次 push、20 个提交、12 条日志或 4 条摘要限制；每个提交（包括维护和合并）都保留短 SHA，同名提交也分别列出。APK 附言保留版本、摘要、源码、文件哈希和 Actions 链接，完整公告随后作为关联 APK 的消息发送；超过 Telegram 长度限制时自动分条，不丢弃后面的条目。R8 mapping 单独保留在 Actions。

只有 APK 和全部公告消息都成功发送后，工作流才将对应源码和消息回执保存为 `canary-telegram` GitHub Deployment，供下次发布计算范围。它使用 `GITHUB_TOKEN` 的 `deployments: write` 权限，不创建 Git Tag 或 GitHub Release；dry-run 和失败投递不会推进节点。首次采用该记录方式时，从旧手动工作流日志中识别实际投递结果，跳过 dry-run；完全没有历史投递时列出所选提交的完整历史。无法确认旧历史或完整公告超过 256 KiB 时明确失败，不退回只展示当前提交。

没有令牌或 Telegram 投递失败时，手动工作流会失败，已验证的 Actions 安装包不受影响。发送超时可能已经投递，因此不自动重发，重跑前先检查频道；重复运行相同链接会再次发送该版本。

来源：[GitHub artifacts API](https://docs.github.com/en/rest/actions/artifacts)、[Telegram sendDocument](https://core.telegram.org/bots/api#senddocument)。Bot API 当前支持上传不超过 50 MB 的普通文件，按 URL 发送 document 仅保证 PDF/ZIP；APK 使用 multipart。
