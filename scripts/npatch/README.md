# NPatch 1.0.9 (833) Manager 存活修补

该工具修复已配置 Manager 作用域后，Manager 不可达时缓存模块完全不执行的问题。它只支持下列 SHA-256 的 **833 patcher JAR**，不会猜测适配其它版本：

```text
patcher: d7e69d464b90aa888a5d555b94d38dab94a4aab19d80f863d238b6715a0fcea3
loader:  29325bd4715bf62dcfdfeb7d1acdaa58d9bcf0040a4fa22756d615542736ea8b
```

## 原因与修复边界

833 的 `RemoteApplicationService` 已经缓存模块列表，并在 Manager 失联时提供本地回退；但其 `asBinder()` 返回远端服务 Binder，断连时返回 null。`VectorServiceClient.init()` 因而不保留这个本地服务对象，`getModules()` 得到空列表。Manager 运行中死亡也会触发同类失效。

833 发布包中的服务类只实现 `IFrameworkService`，**不继承 Binder**。不能直接把方法改成 `return this`。本工具仅把这个方法改为 `return new android.os.Binder()`，提供进程内存活令牌；Vector 仍直接调用原来的本地 Java 服务对象，配置、作用域、模块列表和重连逻辑均沿用原实现。该令牌没有远端 IPC 分发用途，不能当作跨进程 `IFrameworkService.Stub` 使用。

工具检查输入 JAR/DEX 哈希与唯一方法签名，重写后重新解析 DEX，再用原方法恢复序列化，要求与原 DEX 的规范化输出逐字节一致；此外逐项校验 JAR 其它文件内容。已有输出和未知输入均拒绝，不覆盖原文件。

修复需要**重新修补并覆盖安装宿主 APK**，单独更新模块 APK 不会修改旧宿主里的 loader。作用域必须已正常配置，宿主也必须在 Manager 在线时成功读取过当前配置；没有初始缓存，或改配置后宿主尚未读取新文档时，仍需打开 Manager 再冷启动宿主。设置页“已同步”表示写入 Manager，并不表示旧宿主缓存已更新。它不绕过模块授权、免 Root 开关或作用域配置，也不修复宿主本身的基础启动开销。

## 复现命令（PowerShell）

准备原版 833 patcher JAR、JADX 1.5.6 的 fat JAR（内含重定位后的 dexlib2）、BouncyCastle 1.80 JAR，以及**未修补的宿主 APK**。路径由调用者传入；仓库不分发第三方二进制或签名私钥。

```powershell
$java17 = 'C:/Program Files/Java/jdk-17/bin/java.exe'
$java21 = 'D:/graalvm-jdk-21.0.2+13.1/bin/java.exe' # 仅 patcher；Android Gradle 仍用标准 JDK 17
$jadx = '<jadx-1.5.6-all.jar>'
$originalPatcher = '<jar-v1.0.9-833-release.jar>'
$fixedPatcher = '<不存在的输出文件.jar>'
$bcprov = '<bcprov-jdk18on-1.80.jar>'
$originalApk = '<未修补的宿主.apk>'
$apkOutput = '<输出目录>'

& $java17 -cp $jadx scripts/npatch/FixLoader833.java $originalPatcher $fixedPatcher
if ($LASTEXITCODE -ne 0) { throw 'Loader patch failed' }
& $java21 -cp "$bcprov;$fixedPatcher" scripts/npatch/RunPatcher.java --manager --sigbypasslv 2 -o $apkOutput $originalApk
if ($LASTEXITCODE -ne 0) { throw 'APK patch failed' }
```

保留原修补参数和签名密钥；上例是测试机的 Manager 模式、签名绕过等级 2 与默认签名方案。安装前分别使用 SDK `apksigner verify --print-certs` 核对旧、新 APK 的签名证书一致，再 `adb -s <设备> install -r <新 APK>`。签名不一致时停止，不通过卸载规避。

本次固定 loader 的 SHA-256 为 `7eb53e46e4d7fb31c4a7441a9478bb26f73563496547ebe82e66a39301d4efaf`。原参数生成的 patcher SHA-256 为 `fd6257b562af7f2c55776131f97ef72e7e7c772f5b2c0396c69ae1517f7e1e78`。

## 2026-10-20 实机验收

设备 `13a7a291`，Android 16、B 站 8.90.2，Manager 1.0.9 (833)，模块 1.2.0 debug；新旧宿主签名证书 SHA-256 均为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`，已覆盖安装并保留数据。

- 原包：Manager `stopped=true` 时，出现 `Loaded cached module`，没有模块 Modern 入口和功能 Hook。
- 修复包：相同停止状态出现 Modern 入口、授权 `granted`，Hook 链完成 2557ms；恢复开关后再次冷启动 2083ms。
- 早期模块在 Manager 与设置进程均停止时可用缓存授权；最终 B1 回归发现这会复活旧关闭态，现已禁止 NPatch 只凭缓存授权，全部端点不可达时不安装功能 Hook。
- Manager 重启后：授权 40ms、Hook 链 2181ms；运行中再次停止 Manager，设置页仍显示“由 NPatch 激活（回执已验证）”。
- Manager 停止、免 Root 关闭：授权明确拒绝，没有功能 Hook 安装链；测试后恢复开启。
- 工具：相同输入重复生成哈希一致；未知输入和已有输出均拒绝，DEX 单方法及 JAR 其它文件内容校验通过。

最初复测使用等级 1；最终宿主已按原配置恢复等级 2 并同签名覆盖安装。最终模块同步后、Manager 停止时授权 28ms、Hook 链 2039ms；旧缓存且授权端点全部不可达时明确拒绝，没有功能 Hook。

最终 B3 修复包复测：Manager 停止时明确关闭许可可使设置页归位为“已关闭，不会连接 NPatch”；重新开启、在线读取当前配置后，再停止 Manager，授权 39ms、Hook 链 1898ms，设置页显示已激活且回执有效。证据 `b3-close-confirmed.*`、`final-online-enabled.log`、`final-offline-enabled.log`、`final-active.png`。

原始证据在已忽略的 `captures/npatch-closure/b12-*`、`b1-final-*`；最终生成宿主位于 `captures/npatch-closure/b12-final/`。

## embed 同签名产物

`NewEmbeddedApk.ps1` 固定上述修复后的 833 patcher、普通 entry-hook 和签名等级 2，不启用 `injectdex`。它拒绝已修补输入、已有输出目录及未知 patcher；完成后验证内嵌模块与原版宿主的 SHA-256，并要求输出证书与参考 patched APK 相同，保存 `verified.json`。仅支持 NPatch 默认测试签名；使用自有密钥的宿主不能用它覆盖，证书不一致会明确失败。脚本不安装 APK、不处理或输出私钥。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17' # apksigner 使用
./scripts/npatch/NewEmbeddedApk.ps1 `
    -OriginalApk '<未修补的宿主.apk>' `
    -ModuleApk 'Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk' `
    -ReferencePatchedApk '<当前宿主同证书的 patched.apk>' `
    -PatcherJar '<patcher-833-final.jar>' -BcprovJar '<bcprov-jdk18on-1.80.jar>' `
    -Java21 'D:/graalvm-jdk-21.0.2+13.1/bin/java.exe' `
    -ApkSigner 'G:/AndroidSdk/build-tools/37.0.0/apksigner.bat' `
    -OutputDirectory '<不存在的输出目录>'
```

安装修补后的 B 站及同版本独立模块设置 App；后者提供当前许可与配置，必须保留。首次打开设置 App，接受条款并选择 NPatch 免 Root 通道，冷启动 B 站后验证回执。embed 无需安装 Manager，作用域固定在 APK 内；独立设置 App 不写宿主 UID 的 Remote Store，也不查询 Manager 作用域。更改普通设置后重启宿主；**更新模块代码时必须用最新模块 APK 重新修补宿主**，只更新设置 App 不会替换 APK 内模块。同版本码的开发包也必须核对 `verified.json` 中的模块哈希。

诊断页只读查询当前响应的 NPatch 进程、许可/安装状态和 Hook 点数。没有响应不等于没有注入，零 Hook 也不等于功能故障；主进程专用功能继续在子进程跳过。查询最多 16 个进程、1.5 秒等待，使用各进程独立的只读回调线程，不依赖宿主主线程绘制，不唤醒未运行的进程，不抢主进程心跳。embed 的 `:web` 暂时隔离功能 Hook（`GATED`、0 Hook），仍取得当前许可并提供诊断；这是针对网页卡住的明确降级，不宣称已定位具体故障 Hook，也不影响其它进程或 Manager。登录、隔离进程和未测版本不在当前结果的承诺范围内。
