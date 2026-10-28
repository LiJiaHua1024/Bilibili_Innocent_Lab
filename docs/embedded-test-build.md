# 集成模式内测构建（embedded test build）

面向内测群的构建流程：在独立的构建仓库 [Bilibili_Innocent_Builds](https://github.com/LiJiaHua1024/Bilibili_Innocent_Builds) 里产出**与正式 Release 同形态、同固定签名**、仅包名不同且已打开集成模式门控的模块 APK。它不是 Release，不创建标签、不发布说明、不发送通知；产物只以 Actions artifact 形式提供。

源码仓库（Fork）只提供 `embedded` buildType 与相关代码，不保存、也不使用长期签名私钥。

## 与正式 Release 的差异

| 项 | 正式 Release | embedded 内测 |
|---|---|---|
| buildType | `release` | `embedded`（`initWith(release)`） |
| R8 / shrink / debuggable | 开 / 开 / 否 | 完全相同 |
| 签名 | 固定发布证书 | 同一张固定发布证书 |
| 包名 | `com.Bilibili_Innocent_Lab.xposedmodule` | `com.Bilibili_Innocent_Lab.xposedmodule.embedded` |
| `EmbeddedModePolicy.SUPPORTED` | `false` | `true` |
| versionName / versionCode | `1.2.1` / `22` | 相同 |

包名后缀同时作用于 Manifest 的 `${applicationId}`（权限、Provider authority）与 `assets/npatch/modules/<applicationId>.apk`（NPatch 内嵌条目）。跨 App 的 action、authority、权限常量统一由 `BuildConfig.APPLICATION_ID` 派生；Manifest 声明的类与 activity-alias 仍按 `BuildConfig.MODULE_CLASS_PACKAGE`（namespace）构造，二者不能混用。

## 构建流程与安全边界

构建仓库的工作流 `Build signed embedded APK` 分两段：

1. **构建任务**：check out `LiJiaHua1024/Bilibili_Innocent_Lab` 的指定分支，用一次性密钥运行 `:app:assembleEmbedded`，只上传中间 APK；不接触长期私钥。
2. **签名任务**：在全新 runner 上通过 artifact ID 取回中间 APK，用 `alpha-release` 环境里的固定证书重签，校验证书指纹、单一签名者、非 debuggable、包名 `com.Bilibili_Innocent_Lab.xposedmodule.embedded`，再上传最终产物。

分支代码从不在持有长期签名私钥的 runner 上执行。

## 触发

在构建仓库的 Actions 页面选择 **Build signed embedded APK**，`source_branch` 填 `feat/npatch-compat`（或任意已推送的 Fork 分支）：

```powershell
gh workflow run embedded-build.yml --repo LiJiaHua1024/Bilibili_Innocent_Builds --ref main -f source_branch=feat/npatch-compat
gh run list --repo LiJiaHua1024/Bilibili_Innocent_Builds --workflow embedded-build.yml
```

产物 artifact 以 `EMBEDDED-` 开头，保留 30 天；R8 符号表单独保留 14 天。artifact 内包含 APK、`build-info.txt`、`apk-info.txt`、`signature.txt`、`SHA256SUMS` 与不稳定性说明。

## 本地构建

本地验证同样可以构建 `embedded` 变体，但需要一套完整签名配置（可以用任意 PKCS12，仅本地验证）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:ANDROID_HOME = 'G:/AndroidSdk'
$env:ANDROID_SDK_ROOT = 'G:/AndroidSdk'
$env:GRADLE_USER_HOME = 'G:/src/Bilibili_Innocent_Lab/.gradle-user-home'
$env:ANDROID_USER_HOME = 'G:/src/Bilibili_Innocent_Lab/.android-user-home'
$env:INNOCENT_LAB_SIGNING_STORE_FILE = 'C:/path/to/your.p12'
$env:INNOCENT_LAB_SIGNING_STORE_PASSWORD = '<store password>'
$env:INNOCENT_LAB_SIGNING_KEY_ALIAS = '<alias>'
$env:INNOCENT_LAB_SIGNING_KEY_PASSWORD = '<key password>'
Set-Location 'G:/src/Bilibili_Innocent_Lab/Bilibili_Innocent_Lab'
./gradlew.bat :app:assembleEmbedded --offline --no-daemon
```

产物在 `app/build/outputs/apk/embedded/app-embedded.apk`。缺少完整签名时 Gradle 会直接拒绝打包，不会产出未签名文件。

## 使用与验证注意

1. `.embedded` 与正式模块是**两个 App**：配置、条款、免 Root 授权、心跳历史各自独立；作用域要选 `.embedded` 这个包。
2. 重新 patch 宿主时必须使用**同一份固定发布证书**重签。宿主与模块 App 同证书时，`SET_ROAMING_COMPAT` / `SET_UI_LOCALE` 等 signature 权限通道才能通过；不同证书会在这些通道上降级。
3. 包名不同，任何版本的 `.embedded` 都无法覆盖正式模块；反之亦然。
4. 诊断页的 `NPatchEmbeddedBuild` 会比较宿主内 `assets/npatch/modules/<applicationId>.apk` 与已安装模块 APK 的字节哈希。只更新设置 App 或只重装宿主都会显示 `DIFFERENT`，需要重新 patch 后再冷启动。
5. 正式发布仍不支持集成模式：`debug` / `release` 的 `EmbeddedModePolicy.SUPPORTED` 恒为 `false`，本流程不改变 README 的对外支持声明。
6. 本地 patcher `scripts/npatch/NewPatchedApk.ps1` 从模块 APK 的 `aapt2` 输出读取包名，可直接接受 `.embedded` 产物，无需手改脚本。
7. `MeasureStartup.ps1` 仍按正式模块包名与测试包名固定查询，不适用于 `.embedded` 内测包。
