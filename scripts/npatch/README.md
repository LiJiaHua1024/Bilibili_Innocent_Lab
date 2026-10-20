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

修复需要**重新修补并覆盖安装宿主 APK**，单独更新模块 APK 不会修改旧宿主里的 loader。作用域必须已正常配置并至少成功连接过一次 Manager；没有初始缓存时仍需打开 Manager。它不绕过模块授权、免 Root 开关或作用域配置，也不修复宿主本身的基础启动开销。

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
& $java21 -cp "$bcprov;$fixedPatcher" scripts/npatch/RunPatcher.java --manager -o $apkOutput $originalApk
if ($LASTEXITCODE -ne 0) { throw 'APK patch failed' }
```

保留原修补参数和签名密钥；上例是测试机的 Manager 模式与默认签名方案。安装前分别使用 SDK `apksigner verify --print-certs` 核对旧、新 APK 的签名证书一致，再 `adb -s <设备> install -r <新 APK>`。签名不一致时停止，不通过卸载规避。

本次固定 loader 的 SHA-256 为 `7eb53e46e4d7fb31c4a7441a9478bb26f73563496547ebe82e66a39301d4efaf`。原参数生成的 patcher SHA-256 为 `fd6257b562af7f2c55776131f97ef72e7e7c772f5b2c0396c69ae1517f7e1e78`。

## 2026-10-20 实机验收

设备 `13a7a291`，Android 16、B 站 8.90.2，Manager 1.0.9 (833)，模块 1.2.0 debug；新旧宿主签名证书 SHA-256 均为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`，已覆盖安装并保留数据。

- 原包：Manager `stopped=true` 时，出现 `Loaded cached module`，没有模块 Modern 入口和功能 Hook。
- 修复包：相同停止状态出现 Modern 入口、授权 `granted`，Hook 链完成 2557ms；恢复开关后再次冷启动 2083ms。
- Manager 与设置进程均停止：有效 NPatch 文档回退成功，授权 64ms、Hook 链 2034ms。
- Manager 重启后：授权 40ms、Hook 链 2181ms；运行中再次停止 Manager，设置页仍显示“由 NPatch 激活（回执已验证）”。
- Manager 停止、免 Root 关闭：授权明确拒绝，没有功能 Hook 安装链；测试后恢复开启。
- 工具：相同输入重复生成哈希一致；未知输入和已有输出均拒绝，DEX 单方法及 JAR 其它文件内容校验通过。

原始证据在已忽略的 `captures/npatch-closure/b12-*`；生成的宿主位于 `captures/npatch-closure/b12-liveness/`。
