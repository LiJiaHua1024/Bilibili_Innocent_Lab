# 宿主 UI 接入

宿主视觉使用凝光 1.2.0 的公开 API。`hook/feature` 只负责能力发现、Hook 安装和运行时诊断，具体 UI 装配放在本目录。模块自己的设置界面仍在 `ui/activity`；宿主视觉代码不再依赖那里的材质或导航实现。

| 模块 | 宿主职责 | 引擎接口 |
| --- | --- | --- |
| `common` | 读取宿主换肤、寻找独立内容层、管理注入区域生命周期、适配帧时钟 | `LumenPalette`、`LumenSurfaceSession`、`LumenSurfaceBinding`、`LumenSurfaceLegibilityController`、`LumenSpring`、`TouchGlowRenderer` |
| `top` | 原生分类布局、收岛输入与刷新/回顶、列表占位、融合带位置 | 局部采样表面、自定义形状输入、渐隐预设、弹簧与柔光 |
| `bottom` | 原生标签槽位、隐藏项、发布入口、拖动落点与点击转交 | 浮动与选中表面、弹簧与柔光 |
| `cards` | 列表识别、播放器排除、封面轮廓、信息区间距 | `LumenActivityDelegate.cardBackground` 的静态表面 |
| `follow` | 关注页标题、卡片排布、选中项、复用与状态栏位置 | 局部表面会话、`LumenSlidingSelection`、线性渐隐扩展 |
| `overlays` | 复制气泡的定位、独立文字层与选择时机、回复脉络的背景透明度和关闭释放 | `BubbleDrawable`、`LumenEasing`、静态表面会话 |

## 表面与生命周期

顶栏、底栏的视觉参数集中在 `common/HostSurfaceStyle.kt`，关注页参数在 `follow/FollowFeedStyle.kt`。新增外观优先调整公开 options/preset，不引入本地 Shader、位图模糊、NinePatch 阴影或自绘材质 Drawable，也不引用引擎 `internal` 类型。

`HostSurfaceScope` 为每个注入区域持有局部会话。每个 Drawable 只由它所绑定的 View 持有；收岛外壳使用独立的真实 View，不把 dock 的背景搬到输入层 Canvas。采样源必须是独立内容层，排除自身、祖先和注入装饰。宿主布局、padding、文字与点击行为由适配层保留。

区域 detach 时关闭会话与图形资源，重新 attach 时重建绑定；宿主覆盖背景时重新绑定，关闭时仅恢复本会话仍持有的背景。配色来自宿主 `NightTheme` 和 `ThemeUtils`，反射入口只在装配时解析。位置变化通过 `notifyPositionChanged` 通知引擎，形状输入按几何变化更新，避免逐帧重建 Drawable。

第三方 Activity 不调用 `LumenActivityDelegate.prepare` 或 `bindRoot`，局部表面不读取引擎偏好、不接管宿主窗口。复制气泡拥有自己的 Dialog，使用公开 `BubbleDrawable` 绘制外壳；适配层保留原来的按文字限宽布局、顶部箭头、底部 20% 安全区与独立文字淡入，200ms 入场、150ms 离场、120/100ms 描边淡入淡出。1.2.0 的默认 presenter 会改变宽度和文字运动，所以不用于此处。关闭开始时交还触摸，销毁 owner 时关闭 Dialog 并注销会话回调。回复脉络面板的会话随其原有 controller 释放，透明度仅影响填充色，描边保留原不透明度。

顶栏弹簧参数与原实现一致：收起刚度 115、阻尼比 0.84；展开 88 / 0.68；按压反馈 420 / 0.8。`HostSpringAxis` 只适配帧时钟和重定向，把原参数传给引擎；静止判据保留位置容差与 20 倍速度容差。60/90/120Hz 回归直接比较迁移后的轨迹与原算法，覆盖收尾、过冲和带速度反转。回复面板使用原 standard 曲线，保持 180/150ms 进退场与 160ms 回复树淡入。

深色栏通过引擎公共可读性扩展保留原 8Hz 亮度/细节统计、宿主灰色前景对比度补偿、0.92 色罩上限、迟滞和 240ms 过渡。采样区域来自绑定的实际形状，随会话、内容层与主题变化释放或重建；亮色和静态选中项不启用探针。白色渐变描边、原色罩强度、采样层独立 alpha 和采样失败时的半透明回退均通过公开参数声明。

`HostOverlaySurfaces` 还处理静态面板从零 alpha 恢复可见的绘制刷新：1.2.0 在零 alpha 时跳过背景，而 Android 属性动画可能复用该空白硬件绘制缓存。适配层只在恢复可见的那一帧 invalidate，不增加逐帧采样，并在关闭时移除观察器。更新引擎时保留真实窗口 PixelCopy 回归，确认上游修复后再去掉适配。

列表卡片不做逐卡片实时采样：使用引擎静态 CARD 表面，以 Android 轮廓提供 2dp 柔影。1.2.0 的静态局部表面未提供可配置的卡片阴影，平台轮廓同时承载原生裁切；播放器排除策略保持在 `HostVideoCardStyle`。评论关系线、刷新箭头等业务图形与系统 ripple 的遮罩仍由对应内容 View 绘制。

## 更新与验证

引擎版本、校验和与公共扩展的更新方法见根工程 `gradle/lumen/README.md`。先查看上游公开 API 和适配标准；版本变化集中处理 common/overlays 适配，再检查各功能模块的样式参数与布局契约。

仍存在的视觉差异：卡片原 NinePatch 柔影改为系统 2dp 轮廓阴影，扩散边缘会随系统渲染变化。收岛变形表面走引擎自定义形状后端：GPU 使用后台位图采样加 AGSL，保留原柔光 C¹ 透镜函数与提亮，但采样延迟、盒式模糊和原即时 RenderNode 管线仍不同；软件兼容在该路径仅模糊后裁剪，不执行完整透镜重映射与提亮。普通展开胶囊的 GPU/软件柔光已分别对照原管线，不能据此宣称收岛也逐像素一致。

使用项目 JDK 17 和本地缓存执行 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintFast --offline --daemon`。引擎执行 `:lumen:lumen-engine:testDebugUnitTest :lumen:lumen-motion:testDebugUnitTest`。宿主回归包含隐藏标签与发布外观、同页拖动、主题切换、收岛实时采样、列表位置不受视觉偏移影响、播放器排除、面板淡入后的窗口像素，以及会话释放/重绑定保留背景与 padding。最后覆盖安装并冷启动实际宿主，复测原路径；模块内测试不能代替宿主验证。

2026-10-08 初次迁移验证：默认手机针对上述范围的 27 个 Android 用例通过；宿主实测覆盖收岛/展开、关注页切换、复制气泡与系统文本选择、回复脉络面板和全屏回复树。一致性修正后的最终 JVM 回归为模块 2473 通过、1 跳过，引擎与 motion 730 通过。实机补充回归两批各 5 项（8 个不同用例）通过，覆盖 GPU/软件收岛采样、主题与背景归属、面板真实窗口像素、气泡短文限宽/独立文字层/关闭回调、深色亮图补偿和公开参数序列化。另补充并通过 GPU 绘制计数断言，确认自定义收岛外壳改变内容后继续执行 GPU 绘制，没有静默回退到软件采样。最终 Debug APK 和测试 APK 构建、快速 Lint 通过（193 条警告，原有 3 个错误基线未扩展）。

## 顶栏与底栏材质配置

`HostChromeAppearance` 管理用户选择，`HostSurfaceStyle` 声明引擎参数，`HostSurfaceScope` 统一应用材质与后端；融合带也遵循所属顶栏的渲染选择。顶栏、底栏分别支持柔光透镜（默认，映射 FROSTED）与 Liquid（映射 LIQUID）。高级渲染提供自动（GPU 优先）和软件兼容。Liquid 的完整效果需要 API 33 与可用 GPU；软件兼容在 Liquid 下不可选，并保留切换前柔光的后端选择。导入 Liquid 到旧系统会回退为柔光。

旧配置和旧备份的隐藏 `legacy` 后端保留顶栏 AUTO、已启用底栏 SOFTWARE；首次新开启的栏选择 AUTO。界面第一次保存时写入明确选择，无需启动时批量改写用户偏好。新增四项设置属于目录 v44，可备份、导入、收藏定位，并随重启宿主生效。

普通胶囊 GPU 模糊恢复为原 RenderEffect 17dp，软件三重盒式模糊为 10dp；这是两种算法的等效强度映射，不是统一设成相同数字。两个材质使用同一组模糊声明，当前不开放滑杆。

柔光采样使用 `backdropOpacity = 1`，色罩保持原 112/120：模块 `FrostedMotionSurfaceAlpha.sampleAlpha` 在完整帧上归一化为 255，不能再把采样乘上 `(1 - tintOpacity)`。旧宿主单层合成遗留的这一乘法，会让浅色约 31% 的清晰底图从模糊层下面漏出，增大模糊半径也无法消除。Liquid 保留独立的清透采样比例；深色可读性控制只调整色罩，不覆盖调用方声明的采样透明度。

`HostChromeOpacityInstrumentedTest` 直接实例化模块完整柔光 renderer，以相同细条纹和文字对照宿主，覆盖浅色、深色可读性补偿和 Liquid 区分。额外底色色罩不能替代采样透明度归一化。该测试不写用户偏好，输出保存在忽略目录 `captures/lumen-1.2.0/opacity-20261008/`。`HostChromeRenderingInstrumentedTest` 的独立算法参照也使用模块实际的 255 采样 alpha。

本次透明度修正的最终实机结果：细条纹边缘对比度浅色模块/修正后宿主/旧宿主为 0.031/0.053/6.081，深色为 0.029/0.014/1.895；指标表示相邻像素红色通道差的均值，不等同于整幅图的 RGB 误差。独立软件参照平均 RGB 差约 0.99/255、GPU 参照约 0.028/255。6 项 Android 回归、4 项相关模块 JVM 用例、401 项引擎 JVM 用例及快速 Lint 通过；Debug 覆盖安装后冷启动宿主，确认两栏 SOFT/AUTO 已加载并复测真实视频流滚动。取回的最终对照位于 `comparison-final/` 与 `rendering-final/`，色块场景的性能数据仍不代表全部宿主内容或设备。

下面的首轮数字为上述透明度修正前的记录：当时独立参照沿用了旧宿主的 143 采样 alpha，所以小 RGB 差只能证明迁移与旧宿主合成接近，不能证明与完整模块底栏一致。修正后以完整 renderer 和细节边缘检查验收，不能将旧记录当作当前外观验收结果。

2026-10-08 默认手机受控对照：同内容、同位置、确认已采样后进行窗口 PixelCopy。展开胶囊的平均 RGB 绝对差为软件柔光对原软件约 0.55/255、GPU 柔光对原 GPU 约 0.02/255；Liquid 与柔光明显不同。90Hz 下每种路径记录约 356 个不同 VSYNC、持续 4 秒，当前柔光软件 / 柔光 GPU / Liquid GPU 的 FrameMetrics TOTAL_DURATION P95 分别约 16.2 / 18.1 / 18.3ms，GPU_DURATION P95 为 3.6 / 5.0 / 6.0ms。数字仅覆盖展开胶囊与固定动态图案，不能推出复杂宿主页面、电量或所有设备的优劣，也不能把 TOTAL_DURATION 直接换算成屏幕帧率；GPU 的当帧内容节点取样和软件的节流异步取样付出的工作不同。测试输出与截图保存于忽略目录 `captures/lumen-1.2.0/parity/comparison-final/`，用例为 `HostChromeRenderingInstrumentedTest`。

本轮材质配置验证：模块 2478 项 JVM 测试通过、1 项跳过，引擎与 motion 730 项通过；Debug APK、测试 APK 和快速 Lint 通过（197 条警告，原有 3 个错误基线未扩展）。5 项相关 Android 回归通过，覆盖双管线原效果像素参照、Liquid 区分与渲染后端、GPU/软件收岛实时刷新、深色亮图可读性及新增参数序列化；系统的后台启动限制和每次测试包启动确认由当次前台恢复处理，未改长期安全策略。
