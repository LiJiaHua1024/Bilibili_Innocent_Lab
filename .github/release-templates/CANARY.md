# Canary 与 Telegram 配置

Canary 每次 `main` 推送通过检查后，使用现有 Alpha 固定签名环境自动构建 Release APK，产物保存在 Actions 30 天，不创建 Git Tag 或 GitHub Release。版本名采用下一补丁的 `X.Y.Z-canary.<run_number>`，`versionCode` 仍由源码控制。

模块 Canary 渠道读取公开的成功 main/push 工作流和产物元数据，验证版本、源码、归属与过期状态。安装包可在构建页下载；Telegram 配置完成后，频道同时提供 APK 文件。模块不保存 GitHub/Bot 令牌。Stable 和 Alpha 保留原流程。

## 配置 Bot

1. 在 Telegram [@BotFather](https://t.me/BotFather) 创建 Bot，或使用已有 Bot。
2. 将 Bot 加入 [@Bilibili_Innocent_LabRelease](https://t.me/Bilibili_Innocent_LabRelease)，设为管理员并授予“发布消息”权限。
3. 在 [仓库 Actions Secrets](https://github.com/jichuo1/Bilibili_Innocent_Lab/settings/secrets/actions) 添加 `TELEGRAM_BOT_TOKEN`。
4. 可选变量 `TELEGRAM_RELEASE_CHAT_ID`，默认 `@Bilibili_Innocent_LabRelease`；也支持私有频道的 `-100...` chat ID。

令牌仅存储在 GitHub Secrets。以下本地辅助脚本提供隐藏输入，并通过 stdin 写入 GitHub；令牌不会进入命令行参数或仓库文件：

```powershell
.\.github\release-templates\setup-telegram.ps1
```

工作流上传并回读产物后，Bot 先核对身份、目标频道和管理员权限，再直接上传匹配 SHA-256 的非 Debug 签名 APK。消息包含版本、简短变化、源码、文件哈希和 Actions 链接。只有 APK 发到频道，R8 mapping 单独保留在 Actions。

没有令牌时 Summary 提示配置；Telegram 失败不撤销已验证的 Actions 安装包。发送超时可能已经投递，因此不自动重发，重跑前先检查频道。

来源：[GitHub artifacts API](https://docs.github.com/en/rest/actions/artifacts)、[Telegram sendDocument](https://core.telegram.org/bots/api#senddocument)。Bot API 当前支持上传不超过 50 MB 的普通文件，按 URL 发送 document 仅保证 PDF/ZIP；APK 使用 multipart。
