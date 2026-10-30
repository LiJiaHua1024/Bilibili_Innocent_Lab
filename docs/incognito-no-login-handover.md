# 无痕与免登录：交接说明

更新于 2026-10-30 20:20。分支 `feature/incognito-no-login`，远端 `fork`。本文只写事实：哪些做了、在哪台机器上看到过、哪些没验证、哪些界面是官方的、哪些是模块自己画的。按时间追加的证据记录在 `docs/incognito-no-login-round3-plan.md`，其中 2026-10-28 的状态表与缺口表已过时，以本文为准。

## 1. 目标

在哔哩哔哩 8.84.0 到 9.17.0（hostlab 里共 38 个版本）上：

- **无痕**：本次会话不向服务端泄露身份，写操作和埋点被拦下。
- **免登录（游客）**：不登录也能用「我的」页的收藏、稍后再看、历史、关注等入口，数据存在本地，界面尽量复用宿主自己的页面，不另造一套。

用户的原则：**使用官方原生的操作，不要割裂**。8.90.2 上做到过（星标弹层、提示条、收藏夹编辑器都是官方的，只替换存储）。后来为了覆盖更多版本，部分界面改成了模块自己的，见第 4 节。

## 2. 状态一览

| 项目 | 状态 |
|---|---|
| 提交 | 本地 HEAD 之前的功能提交见第 3 节。已推送到 `c15fd96a`；`3eaf89b6`、`617614ce` 和本文档的提交只在本地，**推送须用户明确要求，只推 `feature/incognito-no-login`，不推 main** |
| 门禁 | 最近一次 `:app:testDebugUnitTest :app:lintFast :app:assembleDebug`（`617614ce`）：4218 个测试，0 失败，1 个无关跳过；FastLint 0 错误、262 条警告（基线未增）。**合并前必须再跑一次完整 `:app:lintDebug`**，日常只跑了 FastLint，它会跳过库 API 替换建议 |
| 真机 | 只有一台小米 10（Android 16，root，LSPosed）。此前装 9.16.1，2026-10-30 起装 arm64 的 9.0.0（versionCode 9000200）。8.90.2 在这个阶段没有设备 |
| 其余版本 | 只有 hostlab 静态证据（反编译、dex 形状查询），没有真机 |

## 3. 提交清单（时间正序，节选与本阶段相关的）

- `41a2ac2b` 游客点「我的」页收藏、稍后再看、历史三行，打开宿主原生页
- `bc99b979` 视频页星标：游客收藏写本地库（简化版）
- `b52e534a` 收藏、稍后再看列表由本地库应答
- `16cde2a7` 「我的」页不再被强制登录盖层遮住
- `02bb9377` 引导页（欢迎向导）跳过，仅 9.16.0 与 9.16.1 生效
- `492da0ca` 本地库支持改收藏、稍后再看
- `d041d941` 游客收藏后重绘视频页星标
- `c165bee1` 视频页星标弹出收藏夹选择框（模块自有）
- `bc93ea64` Story 竖屏播放器的星标
- `99b51a9a` 8.84 到 8.96 的星标重绘（只静态核对）
- `422c9923` 视频页收藏数随星标变化
- `b31c2871` Story 收藏数随星标变化
- `4f38b78b` 8.97 起收藏列表由本地库应答（按形状找转换器，不再写死 8.90.2 的名字）
- `c15fd96a` 没有 `compose/favorites/kntr` 路由的版本（8.97 到 9.11）上，「我的收藏」直接打开 `FavoritesActivityV2`
- `3eaf89b6` 8.97 起阻止本地列表写进宿主的回退缓存
- `617614ce` 点收藏夹卡片打开详情页（`BLRouter.routeTo(请求, Fragment)` 重载）

## 4. 官方原生与模块自有（重点）

### 4.1 模块自己画的界面

这些是 `AlertDialog` 等普通 Android 控件，样式不是哔哩哔哩的，**与用户「用官方的」目标不一致**：

| 界面 | 代码 | 何时出现 |
|---|---|---|
| 「收藏到」多选框（勾选收藏夹、「新建收藏夹」「取消」「完成」） | `GuestFavoriteFolderPicker`，由 `GuestVideoFavoriteHooks` 调用 | 游客点视频页星标或 Story 星标。9.0.0 与 9.16.1 实机看到。**8.90.2 除外**：那里旧钩子 `GuestNativeFavoriteHooks` 在登录门之前接管星标点击，走官方的 `FavoriteDialog`，但这个阶段没有 8.90.2 设备，没重新验证 |
| 「新建收藏夹」输入框 | 同上 | 在上面的框里点「新建收藏夹」 |
| 「已保存到本地」Toast | `GuestVideoFavoriteHooks` 的 `notify` | 找不到星标状态写入口，星标无法重绘时才出现；它是唯一的反馈 |
| 无痕下点顶部「登录」弹出的提示（请先关闭无痕再登录） | `GuestLibraryHostHooks` | 无痕开着，游客点「我的」页头部的登录文字 |
| 简易列表弹窗（历史、收藏、关注、稍后再看） | `GuestLibraryUi` | 挂在旧版「我的」页头部的点击上，原生页打不开时的备用入口。具体何时触发看 `GuestLibraryHostHooks` 里 `library.show` 的调用点，这个阶段没有逐项核对 |

另外，一些**文案**是模块给的：收藏夹详情页的创建者「本地账号」，默认夹名「默认收藏夹」（`LocalVirtualLibrary.DEFAULT_FOLDER`）。

### 4.2 复用宿主自己的界面

数据由模块在本地生成，按宿主自己的响应格式交给宿主自己的解析器，所以界面是官方的：

- 「我的收藏」页（`FavoritesActivityV2` 及其「收藏夹」「视频」两个标签）、收藏夹详情页（`MultiTypeListDetailActivityV2`）：`GuestNativeListHttpHooks` 在 `BiliCall.execute` 里短路 `/x/v3/fav/*`，`NativeFavoriteProtocol` 生成 JSON。
- 稍后再看页（KNTR Compose）、历史页（Java HistoryMoss 与 KMP KHistoryMoss）：`GuestNativeWatchLaterHooks`、`GuestNativeHistoryHooks`。
- 关注列表：`GuestNativeFollowListHooks` 复用宿主的关系列表容器和单元格，走单独标记的本地路由。
- 视频页星标颜色和收藏数：调用宿主自己的 `RelationRepository.n(boolean)`，它会改页面计数、写星标缓存并发总线消息（`GuestPageFavorite`）。Story 星标用 `StoryDetail` 的 getter 和 setter（`GuestStoryStar`）。
- 8.90.2 上的收藏夹编辑器：官方 V2 Compose 编辑器与状态机（`GuestNativeFolderEditorHooks`）。

### 4.3 为什么星标用了自己的弹窗，以及改回官方的思路（未实现、未验证）

9.16.1 的官方收藏弹层是 KMP 组件 `kntr.app.favorite.favoriteDialog`，入口 `KingPositionService.k(boolean)`，类和方法名混淆且逐版变化，当时判断接管成本最高，所以改成自己的框。8.97 起 `KingPositionService.B0/C0/V0` 也不存在，旧钩子装不上。

可能的做法，**都没试过**：

1. 在星标的登录门处放行，让宿主自己弹官方弹层（类似对菜单项 `needLogin` 的处理）。
2. 弹层读收藏夹列表和提交收藏走的是 `/x/v3/fav/folder/created/list-all` 与 `/x/v3/fav/resource/deal` 一类接口。`NativeFavoriteProtocol` 已实现列表、复制、移动，但**没有 `resource/deal`**，要补，并带上 `rid` 才能标出已勾选的夹。
3. KMP 弹层的网络大概率走 Ktor 与 ignet（`IgHttpRequest`），不走 OkHttp 的 `BiliCall`，要接到 `GuestIgnetLibraryResponses`。
4. 提交后星标和收藏数仍由现有的 `GuestPageFavorite` 处理。

风险：弹层内部状态依赖账号信息，放行登录门后可能在别处再次要求登录。先在 9.0.0 或 9.16.1 上读反编译、再试，没有证据前不要承诺。

## 5. 验证矩阵

「真机」指小米 10。

### 9.0.0（arm64，真机，2026-10-30）

已看到：

- 状态键 `guest_native_lists_http=adapted;skipped=folder_route,detail_owner`：本地列表应答通过形状找转换器装上；`fa6.b`（8.90.2 的文件夹路由）在 9.0.0 不存在。
- `guest_native_lists_cache_dropped=seen`：回退缓存的 `kotlin.Lazy` 被替换成返回 null 的代理（`3eaf89b6`）。**宿主缓存文件本身没有去查看**，「没有写入」是由缓存为 null 推出来的。
- 「我的收藏」入口：`guest_favorites_route_opened=seen`，`FavoritesActivityV2` 打开，两个标签正常渲染（`c15fd96a`）。
- 星标：点视频页星标，弹出模块自己的「收藏到」框，勾选「默认收藏夹」点「完成」，状态键 `guest_video_favorite_picker`、`_applied`、`_saved`、`_counted` 都出现。
- 收藏夹卡片：`guest_favorites_folder_route_opened=seen`，点卡片后栈顶为 `MultiTypeListDetailActivityV2`。收藏一个视频后（**不重启宿主**）进详情页，显示「默认收藏夹」「创建者: 本地账号」「1个内容」和那条视频（`617614ce`）。

没验证：

- 稍后再看、历史记录、关注数、关注列表在 9.0.0 上各自能否打开、内容是否对。
- 收藏页里那张卡片在非空时的封面和「N 个内容」文字（详情页的数量是对的）。
- 引导页：`guest_onboarding_gate=absent`，冷启动会弹「欢迎来到哔哩哔哩」性别年龄页。测试时点右上角三点菜单的「暂不选择」跳过，不改账号。
- 收藏页的「+」新建收藏夹、卡片长按编辑。接口 `folder/add`、`edit`、`add_top` 本地协议里有，但没在 9.0.0 点过。

### 9.16.1（真机，此前阶段）

星标选择框、星标变色、视频页与 Story 的收藏数、Story 星标、收藏页、稍后再看页、历史页、「我的」页不被登录盖层遮住，都看到过。引导页跳过只在 9.16.0 与 9.16.1 实机验证（连续三次冷启动）。

### 8.90.2

用户此前阶段用旧钩子做出了完整的官方体验（星标弹层、提示条、星标变色）。这个阶段没有设备，**没有重新验证**。读代码的结论：旧钩子拦截 `KingPositionService.B0` 并设 `result = null`，宿主不会走到登录门，新钩子挂在登录路由上，所以不会叠出第二个弹窗。

### 其余版本

只有静态证据：

- 旧钩子 `GuestNativeFavoriteHooks` 在 38 个版本里只有 8.90.2 一个满足全部锚点；8.84 到 8.96 其余 11 个版本缺 1 到 3 个类，8.97 起 `KingPositionService.B0/C0/V0` 一个都没有。所以 37 个版本的星标全靠 `GuestVideoFavoriteHooks`。
- 8.84 到 8.96 的星标缓存是 `FavorRepository` 单例（`99b51a9a`），没有这些版本的设备，没跑过。
- 8.97 到 9.11 的「我的收藏」入口只在 9.0.0 实机跑过，其余版本只有静态结论。

## 6. 已知问题与剩余工作（按重要性）

1. **星标弹窗不是官方的**（第 4.3 节）。要不要改、怎么改需要用户定。
2. 9.0.0 上稍后再看、历史、关注数、关注列表逐项验证，约 2 到 4 小时。
3. 引导页：`GuestOnboardingHooks` 的首页等待门只在 9.16.0/9.16.1 能结构定位，其余 24 个 V2 版本不生效，8.84 到 8.96 靠旧插件钩子，没验证。
4. 批次 2 的多项（登录弹窗、一键登录引导、登录 toast、画质钳制、海外强制登录）只有「已注册」证据，没有真机触发过。
5. 评论游客上限、动态页底部登录条：9.x 上干净跳过，没做。
6. KMP 广播隧道（在线状态）仍开放。试过掐断它会让宿主 SIGABRT 或 SIGSEGV，已移除。
7. 登录用户的行为只有单元测试，没有真机。
8. `GuestLibraryHostHooks.kt` 有一处更早提交遗留的 ` · ` 中点分隔，用户明确不喜欢，没动。
9. 合并前跑完整 `:app:lintDebug`。

## 7. 实机测试的坑（这几条每条都浪费过时间）

- **无痕下本地库只存在内存里**（`LocalVirtualLibrary.enterIncognito`、`resetIncognito`），宿主进程重启就清空，磁盘上没有 `bil_guest_library.json`。收藏后重启宿主再看，列表一定是空的，这不是缺陷。验证收藏列表时不要重启宿主。
- 普通 `adb shell input tap` 会被系统以 `INJECT_EVENTS` 拒绝，要用 `su -c 'input tap X Y'`。
- 截图在对话里可能被缩放，**真实坐标 = 看到的坐标 × 约 1.17**（原图 1080×2340）。用错比例会点到别处，比如把标签点成搜索页。点之前先读当次截图，视频页有广告行时操作栏位置会变，简介展开后也会下移。
- 平台给出的截图偶尔是旧图（时间戳和内容与设备对不上）。用设备状态栏时间核对，对不上的弃用，改看日志、栈顶 Activity（`dumpsys activity activities | grep topResumedActivity`）。
- 冷启动会先出欢迎页，约 1 到 2 秒后菜单才可点，第一下点菜单常常没反应。
- 状态键从模块日志读：`/data/adb/lspd/log/` 下取当前日志，用 `su -c` 读。日志里中文在 PowerShell 的 `Select-String` 里会丢字，判断「没有」之前先用别的方式确认。
- 每次改完无痕开关要重启宿主；脚本 `inc-on.sh`、`inc-off.sh` 只改模块偏好文件里的 `incognito_enabled`，并恢复 SELinux 标签。收尾要把无痕关掉、停掉宿主与模块。

## 8. 代码地图

主目录 `Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/privacy/`：

- 网络封口：`IncognitoNetworkHooks`、`IncognitoWebHooks`、`IncognitoTelemetryHooks`、`KmpMossBoundary`、`KmpHeaderSanitizer`、`IgnetHttpBoundary`、`IgnetHttpSanitizer`。
- 会话：`IncognitoSession`、`IncognitoIdentityBus`。
- 入口：`PrivacyFeatureRuntime`（两个安装器：`incognito_privacy`、`guest_browsing`）、`GuestBrowsingFeatureInstaller`。
- 本地库：`LocalVirtualLibrary`（数据与校验）、`GuestLibraryRuntime`（线程与持久化，无痕时内存）。
- 本地协议：`NativeFavoriteProtocol`、`NativeWatchLaterProtocol`、`NativeFavoriteFolders`（夹 ID：夹名 sha256 前 6 字节加 1，默认夹是 162385528615728，自定义存储的 ID 优先）。
- 列表短路：`GuestNativeListHttpHooks`（`BiliCall.execute`，另含回退缓存屏蔽、`findFragmentByUrl`、文件夹路由、详情页归属）、`GuestIgnetLibraryResponses`（Ktor/ignet）。
- 入口与路由：`GuestMineMenuHooks`（菜单项 `needLogin`）、`GuestFavoritesRouteHooks`（收藏页与卡片，`routeTo` 两个重载）、`GuestMineOverlayHooks`、`GuestLibraryHostHooks`。
- 收藏写入：`GuestVideoFavoriteHooks`、`GuestFavoriteFolderPicker`、`GuestStoryStar`、`GuestPageFavorite`、`GuestNativeFavoriteHooks`（仅 8.90.2）、`GuestNativeFolderEditorHooks`。
- 其他：`GuestNativeHistoryHooks`、`GuestNativeWatchLaterHooks`、`GuestNativeWatchLaterAddHooks`、`GuestNativeFollowListHooks`、`GuestPlaybackHooks`、`GuestOnboardingHooks`、`PrivacyStructuralResolver`（按形状找混淆成员，歧义不猜）。

宿主类与成员的查找统一走 `runtime/KavaMemberLookup.kt`。

## 9. 约定

- 版本适配一律先做结构定位：稳定类名、公开 API、方法形状；定位不唯一就跳过并上报状态键，不猜。
- 每个子功能有独立状态键，日志通道对同一个键只保留第一条，所以不同结果要用不同键。
- 提交信息用英文，`前缀: 内容`，小写，要点用短横线。
- hostlab 数据：38 个版本，另有 9.0.0 的近邻 9.0.1（`9010300`、`9011100`）可当参考。9.0.0 自己没有 hostlab 数据，反编译用邻版再到真机确认。
