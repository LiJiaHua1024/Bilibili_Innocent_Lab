# 局域网投屏与安全接力实验

本分支基于 main 独立开发。2026-10-23 的开发与本地验证期间未使用测试手机；本地测试不代表实机兼容性验收。2026-10-24 用户单独授权安装到 `192.168.1.78:5555`，另一台测试手机仍未获准使用。

## 协议边界

- 接收端为 DLNA MediaRenderer，无需安装电视客户端。
- UPnP AVTransport 并未规定弹幕渲染；必须由发送端合成视频，不能把弹幕开关写入 SOAP 就宣称电视已支持。
- UID 为公开标识；没有可验证的账号证明时，禁止据此自动建立信任。

参考：[UPnP AVTransport](https://upnp.org/specs/av/UPnP-av-AVTransport-v1-Service.pdf)。

## 实现与边界

代码位于 `app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/lan/`，由 `LanContinuityFeatureInstaller` 在宿主主进程安装。默认关闭。在「视频增强」启用后重启宿主，点击播放器原有的 TV 投屏按钮，打开可关闭的面板并开启首次局域网会话。没有常驻悬浮入口。依照用户确认的方案，所有设备首次配对一次；之后打开宿主时，有已保存信任设备即自动恢复发现与接力同步。「停止本次会话」抑制当前进程的自动恢复，手动开启可恢复。

| 需求 | 当前实现 | 尚需完成或验证 |
| --- | --- | --- |
| 免电视客户端发现与主动推送 | 原生 TV 按钮进入；SSDP 搜索 MediaRenderer；SetAVTransportURI / Play；电视拉取手机的随机媒体地址 | 2026-10-24 客厅电视已实测播放指定视频，用户确认画面和声音正常；其他电视与编码规格仍待验证 |
| 高画质音视频 | 验证当前 IJK 媒体 CID 与资源实例，选择当前清晰度、编码及音轨；保持宿主签名 URL、User-Agent / Referer 与同轨备用 CDN；正确处理 HTTP 200 / 206；原画模式不重新编码，合并音视频 | 长视频需要完整准备，尚非即时串流；宿主不提供的清晰度不会凭空获得 |
| 同步弹幕画面 | CID 弹幕 XML；Surface 解码 → GLES 叠加 → AVC 编码，音频不重压；没有 HDR 色调映射时明确拒绝合成；2026-10-24 客厅电视实测有滚动弹幕且画面声音正常 | 尚无新增弹幕的持续订阅；旋转视频合成暂不支持；其他分辨率、硬件与 4K 性能未实机验收 |
| 手机遥控 | 播放、暂停、REL_TIME 跳转、分数倍速、按电视声明范围换算音量、停止 | TV 可返回 701 / 717 等拒绝；不能保证全部 DLNA 设备支持倍速 |
| 弹幕开关与样式 | 开关、字号、不透明度、覆盖区域 | 当前需重新生成和推送，不能即时改变已编码的画面 |
| 分 P | 手机面板读取当前普通视频的公开 pages 元数据，选定后按 BV/CID/P 路由切换，等待宿主目标播放器和轨道就绪后自动推送至已选电视 | 本次外部读取遇到 412，失败时回退原播放器选集；实际宿主路由、合集和番剧选集、自动播放下一集仍待验证或实现 |
| 一次性配对、管理与解绑 | 256 位随机邀请码，两分钟有效，一次使用；列表、改名、本机解绑 | 43 字符码需要复制或输入；短码 PAKE / 二维码体验尚未实现 |
| 配对后自动互认 | 用户已确认「首次配对一次，之后自动互认与接力」；已保存配对设备在宿主后续启动时自动恢复 | 不使用自报 UID、相同 UID 的散列或登录令牌替代配对证明 |
| 接力状态同步 | 每对设备独立密钥、AES-256-GCM、用途与双方身份及接收方启动挑战绑定；请求随机数、防重放与过期校验；在已认证信封内换算状态年龄以容忍小幅时钟偏差；后台继续采样仍绑定的真实播放器，页面销毁即清除 | 设备休眠、网络恢复及后台生存需要实机验收 |
| 点击接力续播 | 已互信设备的短期提示，锚定原生投屏图标并在 6 秒后自动消失；固定 BV/CID 路由，匹配目标播放器后设置进度与倍速并读回 | 宿主版本、异步路由、分 P 与播放器状态切换尚未实机验收 |

这些边界意味着本分支目前属于实验实现，不能称为需求全部完成，也不能仅凭构建和 JVM 测试宣称电视播放或无缝接力验收通过。

宿主结构覆盖已按归档样本逐版核对并补齐：8.84.0–8.96.0 走 Repository 内静态回调，8.97.0–9.17.0 走合并 lambda 的 `onClick(View)`，清晰度读取在 8.98.0 起改读 `getCurVideoId`。原始缺口记录见「2026-10-27 全版本宿主结构核查（无设备阶段）」，实现与复核见「2026-10-27 全版本适配（结构层）」。

## 信任与网络纪律

1. DNS-SD 只公开随机设备标识、协议类型与端口，SSDP 只搜索电视。不广播 BV/CID、标题、进度、倍速、账号 UID 或令牌。
2. 同 UID 是公开标识，不是认证。用户已选择首次配对后自动互认；信任属于这一对安装实例，切换账号不会暗中把信任转移给新设备。双方不交换 Cookie、access_key 或 refresh token，用户可从设备列表解绑。
3. 配对码为高熵密钥；双方通过加密请求与对应请求的加密回执证明持有该码。接力密钥按双方设备标识和接收方启动挑战派生。短数字码不能直接套用该协议，否则会形成离线猜测风险。
4. 密钥保存在宿主 `noBackupFilesDir/lan-trust-v1.bin`，由 Android Keystore 包装；模块设置备份不包含设备密钥、配对码、观看状态或媒体地址。损坏的存储拒绝开启会话。
5. 接力只有已配对设备之间的加密 HTTP 请求。重启后的挑战更换使旧请求失效，随机请求号与时间窗口防止当前进程内重放。解绑清除本机密钥及提示；不承诺远端设备也立即删除自己的副本。
6. 控制地址使用字面量私网 IPv4。SSDP LOCATION 必须与 UDP 响应来源一致；设备描述中的 URLBase / controlURL / SCPDURL 必须同源。禁止 DNS 地址、重定向、用户信息与跨源服务地址。XML 拒绝 DTD 和实体声明，HTTP 输入、读取时间与并发有界。
7. DLNA 视频链路为协议规定的 HTTP 明文；随机路径和接收端 IP 限制不等于链路加密，无法抵御可窃听或伪造局域网流量的攻击者。接力加密与 DLNA 媒体传输是两条不同通道。
8. 局域网会话绑定启动时的 Wi-Fi 地址；网络丢失或地址变化时停止，不切换到蜂窝网络。宿主下次进入前台时可以为已配对设备建立新会话，但不会自动重放投屏指令。宿主需要原有 INTERNET / ACCESS_NETWORK_STATE 权限。Android 17 还可能要求宿主取得 ACCESS_LOCAL_NETWORK；模块不能替第三方宿主静默授予权限。参考：[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。
9. 防重放缓存额外保留 30 秒时钟回退余量；系统时钟大幅倒退时拒绝当前会话的请求，直至时钟恢复或用户重新开启会话取得新的启动挑战，避免已淘汰请求重新变得有效。

## 本地构建

应用托管 worktree：`C:/Users/李家华/.codex/worktrees/lan-continuity-cast/Bilibili_Innocent_Lab`。

该目录含非 ASCII 字符，AGP 在 Windows 拒绝构建。仅本地创建目录联接 `G:/src/Bilibili_Innocent_Lab/captures/lan-cast-worktree` 指向上述 worktree，从该联接下的 Android 工程运行项目 wrapper。JDK 17、SDK ASCII 路径、Gradle / Android 缓存继续使用根仓库已有配置，不修改系统环境变量、依赖版本或 lint baseline。

首轮 `lintFast` 与 `--tests` 参数组合被现有任务名检查拒绝，改用其明确允许的 `assembleDebug testDebugUnitTest lintFast` 组合。检查输出需以最终执行结果为准。

新增本地测试覆盖 AES 篡改与身份/启动挑战绑定、单次配对、过期和重放、私网地址与服务同源、XML 实体拒绝、媒体 Range、暂停提示保活、时钟偏差及分 P 元数据拒绝路径。另有真实 loopback HTTP 双设备配对/状态交换/解绑和 mock DLNA 遥控测试。媒体下载新增 7 项测试，验证原始 URL 和请求头、403 后同轨备用、206 分段续取、重定向限制、错误 Range、空间预算、取消以及错误信息不泄露签名。它们没有调用手机、外部 B 站 API、硬件编解码器或真实电视。

本机 AdGuard 会拦截格式错误的 HTTP 请求并返回自己的 400 页面，因此异常帧与长度校验直接测试同一生产请求解析器；有效协议交互继续使用真实 loopback socket。未关闭或修改 AdGuard 的系统设置。

### 2026-10-23 最终本地结果

- 分支：`experiment/lan-continuity-cast`，从 `main` 的 `bae163a8` 切出；未提交、未推送。
- 同一轮 `:app:assembleDebug :app:testDebugUnitTest :app:lintFast --offline --no-daemon` 成功。
- JVM：3976 项，3975 通过、1 项原有跳过、0 失败；新增局域网测试 24 项全部通过。
- 快速 Lint：0 个未过滤错误，265 个警告；原有 3 项错误基线未改变。没有执行完整 Lint，也没有改动规则、排除列表或 baseline。
- 新文件的 8 个 Lint 警告：5 个实际可用磁盘空间建议，保留保守预算以避免驱逐宿主其他缓存；3 个宿主面板中文文案建议，面板国际化仍待完善。未新增 suppress。
- 人工复查了宿主反射边界与 BetterAndroid 1.1.6 实际 API，采用统一 Lookup、hostActivity、toast、updatePadding、toPx、removeSelf；未升级依赖。
- APK：`Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk`；快速报告：`Bilibili_Innocent_Lab/app/build/reports/lint/fast/lint-results-debug.html`。
- 本次 Gradle wrapper 已退出并运行 `--stop`。结束时另一工作区仍有新构建，保留其共享 Kotlin 守护进程，未强制停止其他任务。
- 未执行任何 adb 或手机操作；未验证电视播放、GPU 合成画面或宿主实机续播。

## 设备释放后的验收清单

2026-10-24 安装记录：已将前述通过本地验证的 Debug APK 使用 `install -r` 覆盖安装到 `192.168.1.78:5555`，保留数据；包版本 `1.2.1`、versionCode `22`。APK SHA-256 为 `BD6162B935C026C52ABA22B8B70DB80625CF069C65DE288F0B768B6ACCB22D65`。已停止旧宿主进程并按主 Activity 冷启动，确认宿主进入前台且主进程加载模块。未修改功能开关、用户设置或旋转状态；投屏与接力效果由用户自行测试。本次安装不构成功能实机验收通过。

### 2026-10-24 媒体获取与原生入口修复

用户反馈 `media fetch rejected`，要求移除常驻悬浮入口并只投当前指定视频。排查并修复了以下问题：

- 不再把宿主签名 HTTP 地址改写为 HTTPS，不再手编 User-Agent / Referer；使用当前播放器的实际请求配置与同轨备用地址，不转发 Cookie 或账号令牌。
- 只取当前选择的清晰度、编码与音轨；读取前后核对真实媒体 CID 和同一资源实例，推送前再次核对正在观看的 BV/CID，视频变化时拒绝推送。
- 支持完整及有界分段 HTTP 206，严格验证 Content-Range、长度、预算和重定向，避免将部分文件当成完整媒体。
- 删除永久悬浮按钮，接管 8.90.2 原生投屏工具栏的实际点击回调；按参数与返回值唯一定位并排除 synthetic 转发方法，不全局拦截 View 点击。

实机使用 `192.168.1.78:5555`、哔哩哔哩 8.90.2（8902100）。原生 TV 按钮已打开模块面板，日志验证当前源为 `BV1kS8H6VERt` / CID `41074626031`，2 条当前媒体轨道、共 4 个宿主 CDN 候选。仅选择唯一发现的「客厅电视 DLNA投屏」，没有投送其他视频。

《黑神话：钟馗》15分钟实机演示已完成原画合轨，生成 93,482,354 字节 MP4；电视接受 SetAVTransportURI / Play / Seek，面板显示已推送。用户随后明确确认「已播放这个视频，画面和声音正常」。这次验证没有开启弹幕，没有覆盖各项遥控、其他画质、双设备配对或接力；不能据此宣称这些能力也已实机通过。界面整体美化依用户要求后续处理。

使用：打开当前视频 → 点击播放器原有 TV 图标 → 开启本次局域网会话 → 搜索 DLNA 电视 → 选择客厅电视。当前仍需等待文件完整准备后推送；可在同一面板进入「遥控当前电视」。关闭面板保留会话，停止会话会停止电视播放。

修复包已覆盖安装并冷启动宿主，包版本仍为 `1.2.1` / `22`，保留数据。已直接核对手机安装的 base.apk 与本地 APK，SHA-256 均为 `BCB02A3D9BAA84A6DABB2576584DBBAFD0485C6FE9D21DC7C3DB7FB7421FD800`。实测后关闭面板，手机仍停在指定视频页面，截图确认没有常驻悬浮入口；未修改方向锁、用户设置或操作另一台手机。

该轮源码统一通过 Debug 构建、3983 项 JVM 测试（3982 通过、1 项原有跳过）及快速 Lint（267 个警告、0 个未过滤错误，原有 3 项错误基线保持）。这份检查结果对应尚未修复弹幕压缩的版本。

### 2026-10-24 弹幕实机补测

用户追问弹幕后，继续只用同一 BV/CID 和客厅电视实测。首次开启弹幕即发现 `SAXParseException`，未进入合成，原画投屏成功不能代表弹幕通过。实际弹幕响应为 HTTP 200 / `Content-Type: text/xml` / `Content-Encoding: deflate`，110,376 字节原始 DEFLATE 解压后为 336,265 字节 XML。原实现只处理 gzip，将压缩字节误当成 XML。

已补充原始 DEFLATE 与 zlib 包装格式识别，保留 gzip、无压缩处理，未知编码拒绝；解压后仍执行 8 MiB 上限与原 XML 实体拒绝。新增 7 项回归验证各压缩格式、中文及时间保真、解压预算、截断数据和 HTTP 错误关闭连接，专项全部通过。修复包 SHA-256 为 `3CCF5EC2B0D2BD404205081A76618C3DB8A1A4CF9F884D00A04262026027ABDA`，已覆盖安装到同一手机并冷启动宿主。

新版原生投屏入口继续核对 `BV1kS8H6VERt`；开启弹幕后已通过解析并进入真实硬件合成。该手机播放器控件需等待约 500–700 毫秒显示动画后再点击，连续立即点击会错过控件；没有为此改动系统输入或方向设置。

全片约 5 分钟完成准备，19:36 生成 436,989,010 字节 MP4 并向同一客厅电视推送，电视接受媒体、播放和 seek 指令。取回生成文件用 ffprobe 核对为 H.264 1280×532 / 30 fps、AAC 48 kHz，时长 952.064 秒；第 90 秒与第 600 秒实际编码帧可见正常中文与彩色弹幕，视频未倒置。手机临时导出副本已删除，投屏仍使用宿主缓存文件。

用户随后明确确认「有滚动弹幕，画面声音正常」，因此该指定视频、当前轨道、默认弹幕样式在这台客厅电视上的实际播放通过。本次没有逐项测量音画延迟或尝试所有弹幕样式与遥控操作；仍不代表新增实时弹幕、即时开关、4K 或跨设备接力验收通过。

最终同一轮 `assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过：3990 项 JVM 测试，3989 通过、1 项原有跳过、0 失败；快速 Lint 267 个警告、0 个未过滤错误，3 项既有错误基线未变，未运行完整 Lint。手机安装包 SHA-256 已再次直接核对，与上述弹幕修复包一致。wrapper 已结束，已执行 `--stop`，结束检查未发现 Gradle / Kotlin 守护进程。未提交、未推送。

### 2026-10-24 Lumen 投屏界面重做

用户先授权提交既有实现，已生成唯一提交 `69f1d4a9`（`feat: add secure LAN casting and trusted device continuity`），没有 push。以下 UI 重做发生在该提交之后，保持未提交，等待人工 review。

- 继续从原生 TV 按钮进入，不增加常驻悬浮。主面板分为「投屏」「设备接力」，当前 BV、进度、目标电视和准备状态集中展示；连接后提供播放、暂停、跳转、倍速、音量与选集入口。
- 重做附近设备、弹幕、倍速、音量、输入、配对码、信任管理和说明页面，移除原先的大块系统灰色按钮与层层 AlertDialog 列表。危险操作保留独立强调和解绑确认；音量显示目标值，不伪装成电视当前读数。
- 使用现有 Lumen 1.5.1 的 `LumenPalette`、`LumenSurfaceSession` / `staticPanel`、`LumenSlidingSelection`、`LumenAnimator`、`LumenEasing` 和现有触控柔光接入；未升级引擎、依赖或修改缓存。小型线性图标只绘制业务符号，不重写材质算法。
- `LanPanelUi` / `LanSheet` 每个窗口持有有界局部表面会话，跟随既有宿主主题，正文独立于材质。没有对第三方 Activity 调用 `LumenActivityDelegate.prepare` 或 `bindRoot`；窗口关闭及 owner 暂停时释放表面与回调。返回复用现有 `PredictiveBackApi33` 隔离层，未在业务代码散落新反射。
- 分段选项带滑动高亮，弹幕字号、不透明度和区域提供本地预览；明确标记「重新推送后生效」。搜索时显示忙碌状态并抑制重复点击，媒体准备提供进度与取消入口。

手机 `192.168.1.78:5555` 实测原生入口、两页切换、弹幕模式/字号预览、设备列表、配对码排版及返回、主面板关闭与再次打开。生成的临时配对码随关闭作废，没有新增信任设备，未操作另一台手机。当前实机为浅色主题，深色及其他窗口尺寸遵循既有主题与限高实现，本轮没有修改用户主题或方向设置进行强制切换测试。

UI 回归曾用同一 BV/CID 连接原先客厅电视，使用原画模式，电视接受播放和 seek，用户确认有画面和声音、没有弹幕；这不是带弹幕模式的回归结果。用户随后要求将电视用于其他用途，约 20:26 已结束本次会话，手机再次打开面板确认无已选电视、局域网会话关闭。此后只检查手机 UI 与本地文件，未经用户再次释放不得继续电视搜索、投送或遥控测试。

最终 UI 包已覆盖安装、冷启动宿主，并再次通过原生入口检查。手机 base.apk 与本地文件 SHA-256 均为 `98721C839ABD234323AEECA93558F50BE3666B739E78106BAE468998D7B9C686`。同一轮 Debug 构建、3990 项 JVM 测试（3989 通过、1 项原有跳过）和快速 Lint（267 个警告、0 个未过滤错误，原有 3 项错误基线未变）通过。未运行完整 Lint，未扩大 baseline 或增加 suppress。wrapper 已退出，已执行 `--stop`，结束检查无 Gradle / Kotlin 守护进程。

截图位于忽略目录 `captures/`：`lan-ui-final-main.png`、`lan-ui-final-danmaku.png`、`lan-ui-continuity-first.png`、`lan-ui-devices-first.png` 和 `lan-ui-remote-first.png`。配对码截图包含已作废的临时邀请，不作为对外展示图片。

- 同一 Wi-Fi 搜索电视，验证随机 URL 拉流、媒体编码、画质、音画同步、暂停、seek、音量、各档倍速与拒绝提示。
- 开关弹幕及调整样式，验证横竖视频、中文字幕、颜色、密度、长视频磁盘预算、取消准备和会话关闭。
- 两台设备配对；抓包确认发现报文无观看元数据，未配对请求无法取得状态；验证码过期、重复使用、篡改、重放、网络切换和解绑。
- 分别验证正在播放、刚暂停、超过提示期限、分 P 切换、切账号、冷启动、退出详情页、熄屏和后台存活。
- 点击接力后逐项检查实际 BV/CID、进度、倍速和播放状态；失败时不显示成功。

测试手机未经用户释放前不得连接、安装、截图、启动应用或执行任何设备操作。

### 2026-10-25 合成性能改造（无设备阶段）

用户要求将约 5 分钟的准备耗时大幅降低，当前没有可用手机，电视也未释放。本轮只在 `experiment/lan-continuity-cast` 内开发和做本地检查，没有 ADB、设备搜索或投送。既有约 5 分钟记录包含下载、编码和封装，未记录分项耗时，不能据此断言编码单独占用 5 分钟。

- 原实现每帧清空视频尺寸 ARGB 位图、CPU 重画所有弹幕，再 `texImage2D` 上传整张位图。以 1280×532、30 fps、952 秒估算，单这条路径的逻辑像素上传量约 77.8 GB；这是代码工作量估算，不是总线实测值。
- 改为有界 GLES 文字纹理图集：仅首次使用某文字瓦片时栅格化并上传，之后只批量绘制移动的四边形。图集最多 2048×2048 RGBA（16 MiB），低堆内存或低纹理精度设备进一步缩小；缓存换页前提交旧绘制命令，长文字按像素切片，保留整条 Unicode 文本的字体整形和边缘采样。
- 弹幕布局仅遍历当前活跃条目，支持回退/跳跃和超过 60 秒寿命的长弹幕，保留原有字号、透明度、颜色、描边及移动/顶部/底部模式。旧 CPU 绘制器已移入 Android 图像对照测试作为独立参考。
- 编解码改为非阻塞批量填充输入、优先排空编码输出，仅全部阶段空闲时做短等待；SurfaceTexture 到达回调使用独立线程，不排队到宿主 UI 线程。未降低分辨率、源帧率和码率，也未通过主动丢帧提速。
- 真正没有可用弹幕时直接复用原画合轨路径，避免无意义重编码。新增 `BIL-LAN` 的 `cast-prepare` 分项计时和计数：下载、视频处理、音频复制、封装收尾、帧等待、GL 提交、编码背压、文字上传次数/字节量、图集换页和实际 codec 名称；日志不包含媒体 URL、token、BV/CID 或弹幕内容。各子耗时可能包含在视频处理总时间中，不能重复相加。

本轮仍为全片预合成，流式下载和边合成边播放尚未实现。JVM 工作量模型和图像对照测试不能代替手机实测，不将纹理上传工作量的下降倍数当作整片加速倍数。手机可用后需固定源文件、分辨率、码率、帧数及弹幕样式，对照旧包与新包的准备耗时，分别报告冷下载与本地媒体条件，至少验证整体 2 倍提升目标、连续长片温升后的吞吐、音画同步、中文字形/方向/透明度、取消后的资源回收。未达到目标时根据新增分项计时继续定位，不以降画质或删弹幕冒充性能提升。

本地新增 10 项 JVM 测试验证布局/回退/长寿命弹幕、图集命中/换页/越界及失败上传恢复。952 秒、30 fps、每秒 10 条合成弹幕的工作量模型共遍历 28,560 帧，绘制 2,062,284 次可见条目，缓存上传 8,693 次、换页 17 次；按模型瓦片尺寸计算，逻辑像素上传量由 77,792,870,400 字节变为 262,876,320 字节（约 296 倍减少）。这个模型没有执行 Android Paint、GLES 或 MediaCodec，不能换算为实际耗时。

`CastDanmakuRendererInstrumentedTest` 提供两个离屏图像对照场景：中文/emoji/颜色/透明度/四种弹幕模式/回退，以及长文本切片/强制小图集换页；对照旧 Canvas 绘制器，并检查重复帧不反复上传文字。测试 APK 已编译，当前无手机，因此这两个测试尚未执行，编码管线和电视画面也未重新实测。

最终源码统一通过 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintFast --offline --no-daemon`：4000 项 JVM 测试（3999 通过、1 项原有跳过、0 失败），快速 Lint 269 个警告、0 个未过滤错误，原有 3 项错误 baseline 未改；没有执行完整 Lint。最后一轮 5 分 44 秒完成，随后 `--stop`，结束时无 Gradle / Kotlin 守护进程。改动未 commit、未 push，待人工 review。

### 2026-10-26 手机性能与清晰度改造

本轮在原 worktree 的 `experiment/lan-continuity-cast` 上继续开发，用户已释放 `192.168.1.78:5555`（Mi 10、Android API 36）并授权模块安装与测试。只使用这台手机，没有搜索、连接或向电视投送；另一台手机没有操作。模块和测试包均通过 `install -r` 更新，未卸载、未清用户数据、未修改旋转设置。

#### 实现变化

- 输入读取、Surface 解码/GLES 合成、编码输出改为三阶段并行。输入和输出各有单独 owner；codec 缓冲提供有界背压。输出线程交织复制 AAC，不再等全片画面结束后才处理音频。取消和失败先等待工作线程退出，再释放 extractor、muxer 和 codec。
- 最多同时下载两条媒体轨道，共享总字节预算；失败会断开连接、取消并等待所有写入退出。失败残片只有成功删除后才释放预算，删除失败立即停止备用尝试。空闲磁盘检查改为约 1 MiB 或 250 ms 一次，仍保留 64 MiB 加窗口余量。
- 文字图集命中时复用字形边界、瓦片键和描述，减少逐帧对象与闭包分配。默认字号按适配 16:9 大屏的视觉比例计算，1280×532 从约 18.47 px 增至 25 px；固定纵向基线对齐像素，横向仍保留亚像素滚动。
- 保持源宽高、源帧率和呈现时间戳，沿用原 AVC 码率规则，没有用降分辨率、主动丢帧或削减弹幕数据换速度。HDR、旋转源等原有明确拒绝边界保留。本机使用 `OMX.qcom.video.decoder.avc` / `OMX.qcom.video.encoder.avc`。
- 双侧 `KEY_OPERATING_RATE` 做了有界提示实验，但同一短片未观察到收益（提示版 40.989 s、关闭提示复测 39.708 s，属于早期串行版本）。生产默认关闭，只保留显式 instrumentation 入口；不把资源规划提示当作输出帧率或速度承诺。
- 修复输出线程改造中发现的 AAC 负起始 PTS 边界：编码延迟样本不能被当成 EOF；只有真正到达 extractor 末尾才停止复制。最终 1080p 素材全部 704 个可播放音频样本保留。

#### 手机实测

以下本地测试使用固定文件、同一手机和硬件 codec。受控对照期间停止宿主播放，避免第二路解码争抢资源；不代表宿主界面同时播放或电视拉流时的完整用户耗时。旧 CPU 逐帧位图基线冻结自 `2b9e02fb`，只放在 `androidTest`；与最终结果的倍数包含上一轮图集优化及本轮并行改造的累计收益，不能表述为相对 `8f807e24` 又提升 4.35 倍。每项为单次记录，不是多轮统计均值。

| 素材与条件 | 旧 CPU 基线 | 本轮结果 | 完整性检查 |
| --- | ---: | ---: | --- |
| 120 s，1280×532，30 fps，匹配旧字号 | 62.947 s | 14.475 s，约 4.35 倍 | 3,600 个视频、5,625 个音频样本及末尾 PTS 保留 |
| 同一短片，最终默认新字号 | 不作同字号倍数比较 | 14.197 s | 同上，尺寸保持 1280×532 |
| 952.064 s，854×356，默认新字号，本地干净源 | 未取得可比的纯合成计时 | 94.405 s | 28,563 个视频、44,628 个音频样本保留 |
| 同一完整视频，经生产 `CastMedia.prepare` 冷下载 | 不与历史 720p 五分钟作倍数对比 | 99.163 s；两轨下载 5.247 s | 同上，视频末尾 951,983,311 μs，音频末尾 952,042,666 μs |
| 15 s，1920×1080，30 fps 测试图与 AAC 音频 | 14.227 s | 3.990 s | 450 个视频、704 个音频样本，尺寸保持 1920×1080 |

素材来源与限制：

- 同一指定视频为《黑神话：钟馗》实机演示（`BV1kS8H6VERt` / CID `41074626031`）。公开 API 在本轮返回的所选干净 AVC 轨道实际为 **854×356**；请求的清晰度编号不能当作实际分辨率。完整 94.4 / 99.2 秒结果不能证明之前 720p 场景也会在同一时间完成。
- 120 秒素材从此前已有成功成品截取，已经烧有旧弹幕，仅用于固定输入的吞吐对照，不用来评价新字形或比较画质；固定文件校验值见下。
- 完整干净源没有旧烧字，用于新合成效果与样本保留验证；1080p 素材由 `testsrc2` 与正弦音生成，覆盖高分辨率及 AAC 编码延迟边界，不冒充实际 B 站高画质轨道。
- 冷准备首次使用测试文件中的非标准端口主地址被原有来源校验拒绝；选择 API 返回的 HTTPS 443 备用地址后，旧签名又返回 403。刷新同一公开视频链接后成功，未放宽来源/端口规则，也未读取宿主 Cookie 或账号凭据。该测试的弹幕 XML 已在本地，99.163 s 不含弹幕接口获取、设备发现和 DLNA 起播。
- 冷准备下载 124,578,653 bytes；`cast-prepare` 记录视频阶段 93.728 s、AAC 工作 22.183 s、输入工作 27.996 s、GL 合成 32.756 s、Surface 背压 20.284 s。这些阶段并行且部分嵌套，**不能相加**。完整视频文字图集 16 MiB、814 次上传、14,877,024 bytes、0 次换页；这不等于进程峰值内存。

固定文件的完整 SHA-256：

```text
short720.mp4  232BD4DF1900660E30BBC19DF90C18A17CDBF9CBB73E63D7CF5C37871C73B27F
clean480.mp4  BE463CEE7192F670E75F5CB45EE0BC54D4F1FD7B5CE0E53F4694A7C099E43A32
```

#### 清晰度与回归

三项手机离屏 Canvas/GLES 图像测试通过，覆盖中文、Unicode、颜色、透明度、四种模式、字号、长文本切片、图集换页和回退。修复参考 Canvas 同时刻弹幕漏绘，图像误差按文字覆盖区域归一。人工检查手机生成的离屏 PNG、干净 480p 第 90 秒编码帧及最终 1080p 编码帧，中文方向、描边和色彩正常；低分辨率源本身的细节不足仍存在。不能把增大字号解释为补回源画质，也没有本轮电视放大后的观感结论。

最终编码器的短片、完整冷准备、1080p 音频回归及取消测试通过。取消测试确认返回后没有仍存活的 `BIL-Cast-Input`、`BIL-Cast-Output`、`BIL-Cast-Frames`；帧数/音频样本数精确相等，末尾时间戳允许 100 μs 容器舍入差（曾实际观察到 1 μs），远小于一帧。1080p 中间版本曾漏音频，已修复并复测，失败版本的 4.415 s 不计入成功结果。

Debug APK 与测试 APK 构建完成；最终全量 JVM 检查共 4,018 项、1 项原有跳过，其中原有 `SponsorSegmentRepositoryTest.hardDeadlineReportsFailureAndLateCompletionCannotReplaceANewFlight` 出现一次超时失败。源码未改，单独复测该类 5 项全部通过；首次 XML 与复测 XML 均保存，没有将首次全量结果写成零失败。快速 Lint 通过（269 个警告、0 个未过滤错误、原有 3 个错误 baseline 未改），未执行完整 Lint。已执行 `--stop`，检查时无 Gradle / Kotlin 守护进程。最终安装 APK 的 SHA-256 与本地一致：`C20A9CAC7ED8760024C44D9A467D5A9C56393C6C80ED051CEA6DD9CE7CBD09ED`。

本地证据位于忽略目录 `captures/device-fix/`：`benchmark-legacy720-isolated.log`、`benchmark-pipeline720-isolated.log`、`benchmark-default720-final.log`、`benchmark-pipeline-full480.log`、`benchmark-cold-final.log`、`benchmark-cold-metrics-final.log`、`benchmark-quality1080-final.log`、`benchmark-cancel-final.log`、`quality-encoded-clean480.png` 和 `quality-encoded-1080-final.png`。测试总运行时间还包含逐样本扫描、截图导出，不能替代单独记录的合成/准备耗时。

收尾已归档 52 张离屏对照图，并删除手机上本轮自建的两个 `cast-benchmark` 目录（包括大视频与临时签名地址），不涉及模块设置和宿主缓存。宿主已冷启动回到 `MainActivityV2`，主进程 PID 3531 的本模块新 Hook 加载记录显示 `installed=125, failed=0`；记录保存在 `final-host-hook.log`。其他模块的既有日志异常不作为本次投屏验收结果。

#### 实时合成结论

详见 [DLNA 实时弹幕合成方案评估](lan-cast-realtime-options.md)。建议共用输入缓存、解码、GPU 弹幕、硬件编码和时间轴，保留完整 MP4，优先增加 HLS-TS 与连续 HTTP TS；fMP4 按明确的设备兼容收益启用。面向用户提供「自动 / 边合成边播 / 完整准备后播放」，协议选择放设备高级设置。无需为本轮 DLNA 同时实现 Google Cast、AirPlay、DASH、WebRTC 等不同接收协议。

当前实现仍需完整预合成，没有实时分片、新增弹幕订阅或手机侧音频倍速。此次吞吐结果只说明流式试验具备基础，持续高分辨率温升、后台运行和电视各协议兼容性仍需后续验收。本轮改动未 commit、未 push，保留在原分支供人工 review。

### 2026-10-26 流式管线与 HLS-TS / 连续 TS（无设备阶段）

用户要求按方案评估先完成第 1、2 步，且不得使用测试机。本轮没有执行 ADB、设备搜索或投送。实现细节与边界见 [实施进度](lan-cast-realtime-options.md#2026-10-26-实施进度第-12-步无设备阶段)。默认投屏仍为完整 MP4，界面暂无流式入口，因此现有用户路径不变。完整 MP4 合成改为经过 `CastMuxerSink` 与共用选轨；输出参数与零起点的帧/音频处理保持原样，但这次重构没有在手机上复测。

同一轮 `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintFast --offline --no-daemon` 通过：4,039 项 JVM 测试，1 项原有跳过、0 失败；快速 Lint 273 个警告、0 个未过滤错误，3 项错误 baseline 未改。新增 4 个警告均为 `UsableSpace`，保留与完整准备相同的保守空间预算，不驱逐宿主其他缓存。没有运行完整 Lint。已执行 `--stop`，未发现残留 Gradle / Kotlin 守护进程。未 commit、未 push。

### 2026-10-27 全版本宿主结构核查（无设备阶段）

本轮只做云端静态核查，没有使用任何手机，没有改动功能代码、依赖或构建配置。目标是确认投屏依赖的宿主结构是否覆盖项目归档的全部准确 APK。

#### 判定口径

该分支没有按宿主版本号分支的逻辑，投屏是否可用完全取决于安装期还能否按结构定位到下列成员。

| 依赖点 | 代码位置 | 核对内容 |
| --- | --- | --- |
| 原生投屏点击接管 | `LanContinuityFeatureInstaller` | `…page.toolbar.actionview.projection.ToolbarCastScreenRepository` 中唯一非 synthetic 静态 `(Repository, View)V` |
| 当前媒体读取 | `LanHostMedia`、`PlayerBufferLocator` | `IjkMediaPlayerItem` 的 `getCid`/`getCurQn`/`getScheme` 与 `mContext`/`mMediaAsset`/`mVodType`/`mIsStop`/`mIsReleased`/`mIjkMediaConfigParams`/`mCurAudioId`/`mVideoCodecType`；`IjkMediaConfigParams.mUserAgent`/`mReferer`；`IjkMediaAsset`、`…$MediaAssetStream`、`…$MediaAssertSegment` 的轨道与分段方法 |
| 播放器与接力 | `SponsorPlayerAccess`、`HostAgentRouter` | `IPlayerCoreService` 的 `getCurrentMediaItem`/`seekTo(IZ)`/`getPlaySpeed(Z)`/`setPlaySpeed(F)`/`play` 等运行 ABI，以及 `BLRouter.routeTo` 静态入口 |

#### 输入与证据

- 版本矩阵 `f8ca1825fae74b1eb232501fe46b1dc0`（`execution_policy=adaptive`，4 个物理 runner 组作业 40476328914 / 40476333195 / 40476338305 / 40476342183）。
- 38 个样本，覆盖 8.84.0–9.17.0，同版本不同构建分别核对；样本 revision `1c744580ec53f13d57339e162246945e5004e577`，分析工具提交 `8782f43d537c442905123e35af325c412caa5188`，请求定义 SHA-256 `135cc1f0f1d810390128a4641b2b7d2ce5094e484f1d272779b86105482ff1fe`。
- 全部成员结果 `complete`，`collection_errors` 为空；每项绑定准确 APK SHA-256，没有把截断、跳过或失败当作通过。
- 判定脚本、逐版本结果与原始产物保留在本机 `runtime/hostlab/`，未随代码提交。

#### 结论

| 版本组 | 样本数 | 结果 |
| --- | ---: | --- |
| 8.84.0、8.86.0、8.87.0、8.88.0、8.89.0、8.90.2、8.91.0、8.92.1、8.93.0、8.94.0、8.95.0、8.96.0 | 12 | 全部锚点满足：静态 `(Repository, View)V` 唯一命中（`f`，flags `0x1a`），媒体字段与分段方法、`getCurQn`、core 播放接口、`BLRouter` 均通过 |
| 8.97.0 | 1 | 入口失效：Repository 只剩 `<init>` 与 `c`/`d`/`e`/`f`/`g` 六个方法，没有任何 `(Repository, View)V`；媒体链仍完整 |
| 8.98.0、8.99.0、9.1.0、9.1.1、9.2.0、9.3.0、9.4.0、9.5.0、9.6.0、9.7.0、9.8.0、9.9.0、9.10.0、9.11.0（两个构建）、9.12.0、9.13.0、9.14.0（两个构建）、9.15.0（三个构建）、9.16.0、9.16.1、9.17.0 | 25 | 入口同样失效，且 `IjkMediaPlayerItem.getCurQn()I` 不再存在，`LanHostMedia.resolve` 返回空，读不到当前清晰度与媒体地址 |

#### 三个断裂点

1. 8.97.0 起，原生 TV 按钮的点击回调不再是 Repository 上的静态方法，而是同包 `projection.b#onClick(android.view.View)V`（实例 `OnClickListener`）。8.84.0–8.96.0 的形态是 Repository 内一个非 synthetic 静态 `(Repository, View)V` 加一个 synthetic 转发方法，本分支只按前者定位。
2. 8.98.0 起，`IjkMediaPlayerItem.getCurQn()I` 从宿主消失（全样本范围内同名方法只剩无关的 `LiveRtcReporter$ReportHLSSWInfo#getCurQn()`），因此当前清晰度无从读取，媒体读取路径在入口修好后仍会失败。
3. 9.17.0 的 `projection` 包内已不存在任何 `(Landroid/view/View;)V` 方法，`projection.b` 也变成 `FrameLayout` 子类；点击入口已离开该包，不能靠放宽签名或改名候选修复。

#### 影响与边界

- 8.97.0 及以上：`install()` 在注册任何 Hook 之前返回 `Skipped("ambiguous-native-cast-entry")`，宿主保持原生行为、不会崩溃；代价是投屏面板永远不会出现，日志只会在主进程安装记录里体现。
- 媒体断点当前被入口失败掩盖；只修入口不足以让 8.98+ 可用。
- 其余锚点在 38/38 样本均满足：媒体字段、asset 与 segment 方法、`IjkMediaConfigParams` 的 UA/Referer、`IPlayerCoreService` 的播放/跳转/倍速 API、`BLRouter.routeTo` 静态入口。
- 以上全部是静态结构证据，不代表运行期 Hook、电视画面或接力续播验收；本分支现有真机记录仍只覆盖 8.90.2（属通过组）。本轮没有构建、没有运行测试、没有连接任何设备。
- README 中「已验证客户端 8.84.0–9.14.0」指模块整体历史实机范围，不构成投屏特性的版本覆盖结论。

#### 后续可选方向（均未实施）

- 入口：在静态方法之外接受同包 `OnClickListener#onClick(View)`，并回证其与该 Repository 的绑定关系；9.x 需要重新定位点击入口，不能只放宽签名条件。
- 媒体：为 8.98+ 寻找替代清晰度来源（当前 asset/stream 选择或新版 core 接口），保留现有 CID 与资源实例双读校验。
- 在 `install()` 日志里区分「宿主结构不支持」与「适配失败」，避免把模块整体支持范围误当成投屏支持范围。

### 2026-10-27 全版本适配（结构层）

承接上一条核查，本轮把投屏的两处版本断点补齐。没有可用测试机，仍然只用云端静态证据与本地构建/测试；未连接、未安装、未启动任何设备。

#### 改动

| 位置 | 改动 |
| --- | --- |
| `LanHostMedia` | 当前视频质量改为 `getCurQn()I` → `getCurVideoId()I` 回退。两版方法体逐字相同（native 属性 `0x4f1a`，回退字段 `mCurVideoId`），因此可互换读取；两者都不存在时明确失败，不回退到"猜一个清晰度" |
| `CastToolbarClickLocator`（新增） | 统一投屏入口定位：直连静态回调（8.84.0–8.96.0）与合并 lambda（8.97.0 起）两种形态；缓存只保存 owner 名称，使用前重新验真 |
| `DexAssistQuery.CAST_TOOLBAR_CLICK`（新增） | 只在直连缺失且投屏启用时并入既有后台适配批次；判据只有"返回 void + 方法体包含 `player.player.screencast.click.player`"，owner 名不参与定位 |
| `VersionAdapter` / `HookEntry` | 适配结果新增可选键 `cast_click`（owner + 是否 lambda）；投屏启用时参与计划、合并与后台补装，投屏关闭时不查询 |
| `LanContinuityFeatureInstaller` | 使用统一入口；合并 lambda 在 `before` 里先验 payload 是否为该 Repository 实例，不是就放行给宿主，避免拦截同类里的其它点击分支 |

payload 判据按实际宿主形态分两种，都只在字段实值确实是 Repository 实例时才拦截：合并分支是 `Object` 字段加 `int` 选择子（8.97.0、9.17.0 等），单用途 lambda 直接捕获 Repository 类型（8.99.0、9.12.0）。

#### 复核

矩阵 `dd86888825f04d7aa175d77a14d28bd2`（38 个样本 8.84.0–9.17.0，样本 revision `1c744580…`，工具提交 `8782f43d…`，4 个 runner 组作业，`collection_errors` 为空）按**生产规则本身**重算，而不是只查类是否存在：

- 入口：静态形态要求 Repository 内唯一的非 synthetic 静态 `(Repository, View)V`；lambda 形态要求 `onClick(Landroid/view/View;)V` 非静态、非 synthetic、owner 实现 `View.OnClickListener` 且持有可容纳 Repository 的实例字段。**38/38 唯一命中**：8.84.0–8.96.0 为 `static`，8.97.0–9.17.0 为 `lambda`。
- 清晰度：`getCurQn()I` 与 `getCurVideoId()I` 恰好存在其一，**38/38** 通过。
- 这两条规则由新增的 8 项 JVM 测试锁住（静态唯一/歧义拒绝、两种 lambda 形态、payload 身份判别、缓存复核、JSON 往返）。

本地门禁：`assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过，4,047 项 JVM 测试（1 项原有跳过、0 失败、0 错误），快速 Lint 273 个警告、0 个未过滤错误，既有 3 条错误基线未改；随后执行 `--stop`，结束时无 Gradle / Kotlin 守护进程。未运行完整 Lint。

#### 仍未覆盖

- 本轮的适配是**静态结构层**结论：没有真机验证，DexKit 查询在真实宿主上的实际命中、Hook 注册与面板交互都未执行；现有真机记录仍只有 8.90.2。
- 9.17.0 另有一套 `com.bilibili.ship.theseus2` 页面架构（`ToolbarCastScreenRepository$action$1`、`VertexContext`），其播放器绑定与投屏入口都不在本模块的经典页适配范围内，本轮没有把它算作已适配。
- 合并 lambda 的 payload 判别只保证"拦截到投屏分支"，不保证宿主在同类里没有其它持有同一 Repository 实例的分支；出现时表现为多拦一次面板打开，需要真机确认后再收紧。
- 新清晰的恢复路径没有改变既有拒绝边界：HDR、旋转源、分段媒体、DRM 仍然按原样拒绝。

### 2026-10-27 全版本适配复核：清晰度语义与新页面栈边界

继续在无设备条件下核对上一条适配的两处假设，并把归档范围重新对齐一次。

#### 清晰度访问器不是简单改名

逐版解码方法体后发现了比"改名"更细的差别：

| 版本 | `getCurQn()` / `getCurVideoId()` 方法体 |
| --- | --- |
| 8.90.2（已实机验证） | 只读 `mCurVideoId` 字段 |
| 8.97.0 | 先读 native 属性 `0x4f1a`，大于 0 就返回，否则读 `mCurVideoId` |
| 9.17.0 `getCurVideoId()` | 与 8.97.0 的 `getCurQn()` 逐字相同 |

因此"直接换成 `getCurVideoId()`"并不保证与已实机验证的语义一致：属性值未必等于清晰度编号。现在的规则是**两个值都只用于唯一选中同编码的视频轨道**——先用访问器的值，选不中再退回 `mCurVideoId` 字段，两者都选不中才失败，绝不把属性值直接当成清晰度。矩阵 `2854327d97354c56a68380781296a6ae` 确认 `mCurVideoId`、`mCurAudioId`、`mVideoCodecType` 三个字段与"每版恰好一个质量访问器"在 38/38 样本全部成立。

#### 归档范围没有新增

重新拉取样本清单：仍是 revision `1c744580ec53f13d57339e162246945e5004e577`、38 个 APK，最高 9.17.0。没有需要补适配的新版本，本轮结论覆盖归档全集。

#### 入口判定只保留可全量复核的特征

合并 lambda 的形态判定最初还要求 owner 实现 `View.OnClickListener`。本轮尝试逐版取证该接口归属时，宿主索引里的 `class_interfaces` 无法与类定义稳定关联（多表关联在该后端直接报错），只能逐样例确认 8.97.0 与 9.17.0。与其保留一条无法在全部样本上复核的硬条件，不如把它去掉：判定只保留三条都能全量验证的特征——方法体埋点常量、`onClick(Landroid/view/View;)V` 完整 ABI、以及能被 Repository 填充的捕获字段。宿主会不会真的把它当作点击回调调用，本来就取决于点击链路自身；多要求一层接口归属只会把可用宿主误判成缺失，而且运行时 payload 身份复核仍然是拦截前的最后一道门。

去掉该条件后按生产规则重算同一批证据：仍为 38/38 唯一命中，没有因为放宽而出现歧义候选。

#### 9.17.0 的 theseus2 新页面栈（明确不在范围内）

9.17.0 里出现的 `com.bilibili.ship.theseus2.*` 不是投屏入口改名，而是一套并行的完整页面架构：`adaption`（含 `AndroidKmpTheseusAdaptionModule`）、`cheese` 播放域、独立的 `ToolbarRepository` / `ToolbarCastScreenRepository`（构造参数是 `kntr.common.trio.vertex.VertexContext`），以及独立的点击入口 `ToolbarCastScreenRepository$action$1#invoke()V`。它在本归档里只出现于 9.17.0，而同一版本的经典 `ship.theseus` 工具栏与解析器仍然完整存在。

本模块的播放器绑定（`SponsorPlayerAccess` 的 `TheseusKeelPlayer$runPlayable$1`、`playercontainer` 等）只针对经典栈，因此 theseus2 的投屏入口即使接上也无法工作。本轮按"另一套页面架构"处理，不把它计入已适配；真要支持需要先补齐 theseus2 的播放器与页面绑定，属于独立议题。宿主的实验分流决定用户实际进入哪套页面，静态分析无法观测，这点同样不能由本轮的静态证据替代。

#### 本轮门禁

`assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过：4,047 项 JVM 测试（1 项原有跳过、0 失败、0 错误），快速 Lint 273 个警告、0 个未过滤错误，既有 3 条错误基线未改。已执行 `--stop`，结束时无 Gradle / Kotlin 守护进程。仍未连接任何设备。

### 2026-10-27 全量依赖面核对（含播放器绑定）

前两轮只逐版证过"入口 + 清晰度"两处断点，投屏依赖的其余宿主成员（播放器绑定、观察者接口、容器、续体字段）一直只有类注释里的历史结论。本轮把它们一并按生产判据核对。

#### 核对面

| 组 | 判据 | 结果 |
| --- | --- | --- |
| 绑定类 | 20 个必需类（续体、params、核心服务、包装器、容器作用域、四个观察者接口、播放处理器、资产类族等） | 38/38 存在 |
| 观察者 ABI | `onPlayerProgressChange(II)V`、`onSeekStart(J)V`、`onSeekComplete(J)V`、`onPlayerStateChanged(I)V`、`onPlayerWillRelease()V` | 38/38 |
| 核心注册链 | `registerPlayerProgressObserver` / `unregister…` / `registerSeekObserver` / `unregister…` / `registerState(…,[I)` / `unregisterState` / `addPlayerReleaseObserver` / `remove…` | 38/38 |
| params 链 | `getBizType`（返回枚举）在 params 类或其父类 `Video$PlayableParams` 上、`getBvId/getCid/getAvid`、枚举上的静态 `UGC` | 38/38 |
| 容器 | `getPlayerCoreService()IPlayerCoreService` | 38/38 |
| 续体 | `label:I` 与 `this$0` 捕获字段 | 38/38 |
| 包装器构造 | 同时含核心服务、owner、`CoroutineScope`（9.12.0 的参数顺序为 owner/scope/core，判据不看顺序） | 38/38 |
| 执行边界 | owner 上唯一的非静态 `(playable, Continuation|ContinuationImpl) -> Object`；owner 的 0 参 getter 返回该 playable；playable 声明 `GeminiCommonPlayableParams` 的 0 参 getter | 38/38 |

矩阵：`d1926e9752124d85810fd258722f9e61`、`4b839384e52d4c49aaf3492e7f9feda5`、`b0d1ce337b1144abb08b224d340447e3`、`777357d937f44a7fac66153088064881`（38 样本，`collection_errors` 全空）。

#### 两个容易判错的细节

- **续体形参有两种**：8.84.0–8.96.0 的执行边界是 `(playable, kotlin.coroutines.Continuation)`，8.97.0 起变成 `(playable, kotlin.coroutines.jvm.internal.ContinuationImpl)`（8.97.0 实测 `h.q(keel.player.j, ContinuationImpl)`）。只按 `Continuation)` 匹配会把 8.97+ 全部误判成"缺少执行边界"，这也是我本轮第一版核对脚本 38/38 报错的原因；模块自身的判据本来就同时接受两种续体。
- **owner 被混淆但结构不变**：8.84.0–8.96.0 的 owner 是 `TheseusKeelPlayer`，8.97.0 起变成 `keel.player.h`；playable 接口仍是 `keel.player.j`，而 `tangram.basic.*` 只是内部实现接口。模块从续体的 `this$0` 推导 owner，所以不受混淆影响；但若有人把 `keel.player` 前缀判据放宽成"任意接口"，反而会把 `tangram` 的实现接口误选进来。

#### 结论

投屏依赖的宿主面在本归档 38 个样本上全部成立：入口（静态/合并 lambda）、当前清晰度、媒体轨道链、播放器绑定、观察者注册链、路由、容器。本轮没有发现需要再改代码的版本，也没有新增改动；上一轮的构建与测试结论对应当前源码仍然有效（本轮未改源码，未重跑门禁）。

边界不变：全部为静态结构证据，没有真机；9.17.0 的 `theseus2` 并行页面栈不在范围内。

### 2026-10-27 编码枚举与选轨兜底

继续核查媒体侧最后一类"字符串/数字常量"依赖。

#### 宿主枚举常量（38/38 稳定）

矩阵 `b7bf8ab7f4084b02b61d315d8d6c36c2`：`IjkMediaAsset$StreamType` 始终是 `DASH_VIDEO`/`NORMAL`/`DASH_AUDIO`，`IjkMediaAsset$VideoCodecType` 始终是 `H264`/`H265`/`AV1`（另有 `H266`/`UNKNOWN`）。模块按名字比较、再按名字挑轨道，全版本成立。

#### 选轨不再硬依赖编码数字

`mVideoCodecType` 的 7/12/13 是 DASH 协议里的 codecid（AVC/HEVC/AV1），不是可逐版静态取证的宿主结构；8.90.2 的 `<clinit>` 已被 R8 拆分，逐版读常量值也不现实。原先"认不出的编码值直接失败"会把这些不确定性放大成整条链路不可用，现在的规则是：

1. 先按"清晰度 + 编码名"精确匹配；
2. 认不出编码，或该清晰度在该编码下没有唯一轨道时，退回"该清晰度下的唯一视频轨道"；
3. 仍然多于一条就继续失败（`current-video-track-ambiguous`），不猜。

也就是把"不知道编码"从硬错误降级为"只要没有歧义就继续"，歧义依旧拒绝。这不会让模块凭空选择别的清晰度：兜底只在同一清晰度内发生。

#### 门禁

`assembleDebug testDebugUnitTest lintFast --offline --no-daemon` 通过：4,047 项 JVM 测试（1 项原有跳过、0 失败、0 错误），快速 Lint 273 个警告、0 个未过滤错误，既有 3 条错误基线未改；已 `--stop`。仍未连接任何设备，选轨兜底没有真机用例。

### 2026-10-27 可复用的全量校验脚本

前几轮的判定分散在多个一次性脚本里，宿主再出新版本时无法直接复用。现在合并为 `runtime/hostlab/verify_lan_cast.py`（本机脚本，不随代码提交）：

```text
python tools/hostlab/.venv-remote/Scripts/python.exe runtime/hostlab/verify_lan_cast.py <manifest.json>
```

manifest 是一组 hostlab 结果 `result.txt` 路径（可混合多个矩阵）。脚本按 `version_code` 合并证据，缺任务时跳过该项而不是判失败，最后逐版本输出：

- 入口形态（静态回调 / 合并 lambda）与当前清晰度访问器名；
- `mCurVideoId`/`mCurAudioId`/`mVideoCodecType` 字段、`StreamType`/`VideoCodecType` 常量名；
- 20 个绑定类、item/player/bundle 代理/包装器/处理器成员、asset/stream/segment 成员、core 播放成员、路由静态入口；
- 观察者 ABI 与核心注册链；
- 续体 `label`/`this$0`、唯一的执行边界、`active` getter、playable 的 params getter、包装器与作用域构造。

本轮用 8 个矩阵共 304 份样本结果跑过一遍：

```text
versions=38 fail=0
8.84.0–8.96.0  entry=static quality=getCurQn
8.97.0         entry=lambda quality=getCurQn
8.98.0–9.17.0  entry=lambda quality=getCurVideoId
```

宿主再发新版本时的流程固定为：更新 `runtime/hostlab/inventory.json` → 对新增样本跑 entry/quality/media/binding 四类查询 → `verify_lan_cast.py` 出结论。新版本若只在"清晰度访问器"或"执行边界"上改名，通常无需改代码；若入口或绑定结构变化，脚本会直接指出缺哪一项。

### 2026-10-27 9.17.0 双栈并存的边界（静态证据）

9.17.0 同时存在两套投屏入口，需要说清"经典栈已适配"到什么程度：

| 证据 | 含义 |
| --- | --- |
| `com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.ToolbarCastScreenRepository`（经典）仍有完整点击链路：合并 lambda `Bc/c#onClick` 持有它并上报 `player.player.screencast.click.player` | 经典工具栏在 9.17 仍然存活，模块的 lambda 定位在 9.17 有效 |
| `com.bilibili.ship.theseus2.united.page.toolbar.actionview.projection.ToolbarCastScreenRepository` 与 `…$action$1#invoke()V` 同时存在 | 新页面栈有自己的投屏入口 |
| `theseus2` 下有 `adaption.AndroidNativePageModule`、`uistyle.TheseusPageUIStyleRepository`、`cheese`、`ogv` 等，**没有** `TheseusIntroductionFragment` 或独立 Activity | `theseus2` 不是独立详情页，而是装在同一个 `UnitedBizDetailsActivity` 里的 Compose 页面模块 |
| `UnitedBizDetailsActivity` 仍由 BRouter 路由进入（`…detail/a;->onContextAvailable` 等） | 详情页外壳没变，变的是页面内容与工具栏实现 |

因此 9.17.0 上用户实际拿到哪套工具栏取决于宿主的页面实现分流，静态分析无法判定；模块目前只接管经典栈。若把 `theseus2` 的 `action$1#invoke()` 也挂上，需要先解决两件事，都不能靠静态证据拍板：

1. `invoke()` 没有 View 参数，也没有 payload、Activity 只能取自模块自己的前台 Activity 记录，无法像经典栈那样用"点击的 View 属于哪个 Activity"复核，存在误开面板的风险；
2. `theseus2` 页面的播放器是否仍走 `keel.player` 绑定未知，若不走，面板即使打开也读不到当前媒体。

结论保持不变：经典栈在归档全部 38 个版本上结构可适配；`theseus2` 是新页面栈，需要真机确认后再决定是否接入，不在本轮范围。

#### 审计留档

重新对 8 个矩阵、304 份样本结果跑 `verify_lan_cast.py`：`versions=38 fail=0`，与前几轮一致（本轮未改源码）。

### 2026-10-27 theseus2 属于独立播放栈（结论性证据）

上一节只说"不清楚 theseus2 的播放器归属"，本轮补上了决定性数据：

| 证据 | 结论 |
| --- | --- |
| `…theseus2…ToolbarCastScreenRepository$action$1#invoke()V` 的方法体是 `CallableReference.receiver` 取出 theseus2 仓库后，读 `h:HashSet`（已发现设备）→ 有设备走提示、否则发出流并上报 | `action` 就是点击动作本身，不是重组副作用；要接只能挂它 |
| theseus2 的方法里对 `Ltv/danmaku/biliplayerv2/service/IPlayerCoreService;` 的引用数为 **0** | 模块的播放器绑定（`IPlayerCoreService` + `keel.player` owner/续体）在 theseus2 页面不成立 |
| theseus2 的方法里对 `Lcom/bilibili/app/gemini/base/player/GeminiCommonPlayableParams;` 的引用数为 **0** | 同上，读不到 BV/CID/进度 |
| theseus2 的方法里对 `Lcom/bilibili/ship/theseus/keel/player/*` 的引用数为 **0**；`theseus2/united/player/` 自带 823 个类 | theseus2 是自带播放域的独立页面栈 |

因此 theseus2 不是"漏了一个版本"，而是**另一套播放器与页面实现**：接它需要新的播放器访问层（BV/CID/进度/倍速/轨道），不是给现有定位器加一个候选类就能解决。该栈只出现在归档的 9.17.0 一个样本上。

这也解释了为什么不能只挂 `action$1#invoke()`：没有 View 可复核、Activity 只能取前台记录，且即便挂上，面板也没有可用的播放器读取路径——误开面板的概率大于收益。结论：在模块继续以 `biliplayerv2` + `keel.player` 为播放边界的阶段，theseus2 明确不在支持范围，需要真机确认分流后再立项。
