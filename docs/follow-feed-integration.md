# 关注页凝光接入

## 依赖与边界

使用 JitPack 的 `com.github.jichuo1.LumenCoacervationEngine:lumen-engine` 和 `lumen-motion`，两个模块统一固定为提交 `91e31dce23dda35d2fd6d2411d0aac3079e3c46a`。此前发布依赖 1.0.0 不包含本次需要的局部表面与分段组件。升级没有复制引擎源码、混用旧坐标或引入浮动版本。

这是对宿主原生 RecyclerView/TabLayout 的 LSPosed 局部适配，正文及部分辅助内容仍由宿主 Compose 实现。沿用 `HOST_VIDEO_CARDS` 开关与 `HostChromeTheme` 主题源；不接管 Activity、Window、系统栏和播放器。现有 `HostBottomBarFxController` 及其历史内嵌材质会话保持不变，没有迁移到底部以外的新会话。

## 统一配置

| 区域 | 公开引擎能力 | 宿主职责 |
| --- | --- | --- |
| 页面底色、主次前景 | `LumenPalette.modern` | `FollowFeedStyle.palette` 从现有宿主主题源映射，`FollowFeedViewEdits` 同步可识别的原生文字并可逆恢复；彩色会员名、认证、点赞强调及富文本语义保留 |
| 完整动态卡片 | `LumenSurfacePresets.staticPanel`、`LumenSurfaceSession.bind` | 明确覆盖角色为 `CARD`；根据顶层动态身份与模块首尾边界合并一个背景表面；管理布局与回收 |
| 全部/视频底座、选中框 | `LumenSlidingSelection`、局部表面 `TOP_BAR` / `SELECTED_ITEM` | 显式横向；绑定公开 indicator View；转发原 TabView 点击，读取实际选中状态；初始化与恢复只同步视觉 |
| 最常访问 | 同一 palette | 保留原横向列表及条目、直播装饰，46dp 头像、单行省略、完整昵称无障碍描述与大字号尺寸调整 |
| 列表末尾 | 无新增材质或动画 | 以原底栏实际覆盖区域、原 padding、系统 inset 求并集，只更新列表 padding |

`FollowFeedStyle`、`FollowFeedController` 等是本仓库新增的薄适配层，不是引擎现成 API。材质、圆角、描边、透明度和动画分别由引擎表面和分段组件实现；业务层没有新增玻璃 Drawable、Shader、弹簧或 ValueAnimator。

卡片为 `STATIC`，`sampling.enabled=false`，不提供 source。实际静态路径使用 `fallbackTintOpacity`，所以同时将它与 `tintOpacity` 设为 1，palette.surface 本身为不透明颜色；不是只靠 `opacity=1` 推断可读性。默认 16 个表面预算中预留分段底座与选中框，卡片最多占 14 个。只对当前附着且分组正确的动态建立表面，不扩大预算、不为 holder 创建独立会话。

## 宿主模型与原背景归属

已检查测试机宿主 8.90.2（8902100）的真实 APK：`j0.M()` 返回转发归属的顶层 `q0`，`q0.f()` 是稳定身份，`j0.d0()` / `g0()` 是顶层模块首尾。不要用嵌套投稿的 `N()` 来分组。Adapter bind 只缓存标量身份和成员，既不保留正文与历史模型，也不按位置奇偶分卡。刷新插入条目可能不重绑全部 holder，因此运行时使用当前布局的相邻条目验证分组，而非旧 bind position。

宿主的 `home.z0` 继承 `gv1.c`，`k(Canvas, RecyclerView, View, j0)` 在 `onDraw` 中调用矩形模块底色绘制。该矩形会遮住背景兄弟层上的凝光卡片。适配只在目标列表、合法分组且已有附着卡片绑定时跳过这项底色绘制；整个 ItemDecoration、原 offsets、onDrawOver、内容和点击均保留。未识别条目、预算不足、页面关闭时仍走宿主原绘制。

引擎表面是原列表的非交互背景兄弟层，不新增整卡点击层，不重新挂载列表，不裁剪媒体子树。只清理识别到的直接模块承托背景；保留播放器的 TextureView、原媒体圆角、封面比例、黑边、字幕及控制层。

## 生命周期与维护

`MediatorFragment` 的视图、暂停、恢复、销毁事件驱动一个页面会话。bind、child attach、回收和 detach 同步模块样式；接入时只扫描一次已有列表，滚动帧只处理附着模块的标量元数据和几何。重新 attach 建立新绑定，detach、暂停、退出关闭绑定。退出恢复仍属于本适配器的背景、布局和文字，不覆盖宿主后续写入。滚动、回收均不调用进程级 `releaseGraphics()`。

主题变化通过现有主题源驱动 `updatePalette`、分段前景及已显示模块前景，并影响随后复用的模块。宿主 Compose 正文、富文本链接、认证装饰等继续使用宿主原主题和语义，不直接修改其 Compose 内部实现。

今后统一材质或尺寸调整集中改 `FollowFeedStyle`；生命周期、分组和原背景协调集中改 `FollowFeedController`；混淆契约集中改 `FollowFeedHostAccess` 与安装器。升级引擎时统一更新 catalog 的固定版本，并核对上述公开契约及静态透明度测试，无需逐个修改散落的自绘实现。

## 可执行验证与实际范围

在项目标准 JDK 17 / 本地 Gradle 环境执行过：

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`：通过，APK 已在默认测试机覆盖安装并冷启动宿主。
- `:app:testDebugUnitTest`：2469 项通过，包括角色、无采样、透明度配置、浅深配色、动态分组、刷新插入与底栏覆盖并集。
- 引擎依赖升级后运行 `:app:lintDebug`：通过；后续宿主适配收敛后运行 `:app:lintFast`：通过，190 条警告，现有 3 个错误基线保持不变。宿主资源按名称解析的提示及 KTX 建议仍保留，没有为它们扩大 baseline。
- 指定运行 `FollowFeedInstrumentedTest`、`HostVideoCardPlayerScopeInstrumentedTest`、`HostBottomBarArtifactInstrumentedTest`、`HostBottomBarHiddenTabsInstrumentedTest`：初版 14 项通过；修正最常访问更多入口底色后新增一项原 selector 配色与恢复测试，15 项通过。

新增实机契约检查涵盖真实宿主模型与绘制入口、静态表面像素 alpha、实际 STATIC 后端、无录制、100 次重绑/解绑计数、背景所有权、浅深配色及复用文字更新、分段业务转发与恢复、未知额外标签回退，以及 280/640/1024dp、1/2 倍字号的原生布局测量。

运行时 diagnostics 分阶段输出数值，不包含动态身份、正文、用户信息或媒体路径。实机观察到 5 个附着表面，实际后端为 STATIC；GPU/software 绘制与新增会话内容录制均为 0，暂停后表面数回到 0。此计数只属于新增关注页会话，不能推断原底栏采样为零，也不能当成性能基准。

当前宿主实际检查了全部/视频切换与内容恢复、视频和转发/引用、长正文、文章、联合创作、竖版视频比例与黑边；打开作者空间、更多菜单、视频详情、评论和转发编辑页后返回，执行刷新、快速滚动与重复进出。转发编辑页已取消，没有发布；底栏实际边界仍为 `104,2175–976,2307`。最终覆盖安装版本的画面保存在 `captures/follow-delivery-final.png`，构建、14 项实机测试与局部会话诊断分别保存在 `follow-delivery-checks.log`、`follow-delivery-instrumentation.log`、`follow-delivery-final-diagnostics.log`。

实机画面验证限于当前 8.90.2 宿主和测试机浅色竖屏。浅深主题、大字号、窄屏、横屏/平板宽度通过原生 View 参数化检查，但这些模式下的完整宿主画面、系统关闭动画、实际数据流末尾与所有网络业务的端到端结果仍须进一步复核。没有修改用户偏好、发布转发或执行真实点赞来代替检查。其他宿主版本只在契约匹配时尝试接入，不能据此声称已经通过兼容性验收或零性能回退。运行截图、视图树、构建和诊断日志保存在已忽略的 `captures/`。

纯图文样本、实际点赞和静音状态切换仍待实机补测；图文模块分组有 JVM 检查，播放器作用域有设备回归检查，但不能用这些检查代替相应业务的完整实机验收。

最常访问右侧 `dy_more_container` 使用独立的 `bg_card_selector`，初版只清理父背景，留下了与页面底色不一致的白色矩形。修正通过原 selector 的独立副本映射默认背景和按压颜色，同时更新更多文字；不替换点击监听，恢复时遵循原背景所有权。修正版 Debug/AndroidTest 构建、快速 Lint、15 项设备测试通过，实机点击仍打开“我的关注”，返回正常。画面对照保存在 `captures/follow-more-before-current.png` 与 `captures/follow-more-fixed.png`，相关日志为 `follow-more-checks.log`、`follow-more-instrumentation.log`。
