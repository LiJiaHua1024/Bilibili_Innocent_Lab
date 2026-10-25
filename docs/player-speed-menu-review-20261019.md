# 播放器倍速菜单：实现与复核记录

工作分支：`feat/player-speed-menu`。接续已有未提交实现；主工作区的插帧分支保持原状。合入 `main` 时保留最新插帧功能及其设置目录 v59、诊断目录 v27、亮点批次 24；倍速菜单顺延为设置目录 v60、诊断目录 v28、亮点批次 25。

## 配置与互斥 review

- 高级设置 → 播放器新增「倍速菜单档位」。支持 1～12 个 0.1～8 倍的值，精度沿用现有百分比整数（最多两位小数），去重后降序排列。
- UI 接受逗号、空格等分隔符；持久化协议只保存百分比整数串，拒绝混用倍率、小数百分比、空分段、越界及过长配置。
- 默认播放倍速与菜单配置互斥。二级弹窗确认后先进入编辑，保存时用同一个偏好 Editor 更新两项；取消编辑保留原设置。
- 运行时遇到导入等途径产生的冲突配置，默认播放倍速优先，菜单配置不安装。长按倍速和禁用长按的现有逻辑保持独立。
- 已补齐设置目录 v60、诊断目录 v28、备份预览、三种语言及版本亮点导航（批次 25）。

## 宿主 Hook review

- 旧版：替换专用 Adapter 实例内的 FloatArray；宿主填充失败时恢复原引用。
- 新版：替换 Float/Boolean 选项模型列表，不修改全局 Collection 或窗口服务。
- 选中状态使用 0.005 内的唯一最近档位，修复宿主原有 `< 0.1` 比较导致相邻两位小数档位同时选中、无法点击的问题。
- 兼容旧 core 将 2.0 倍回报为 1.99 的约定；显式配置 1.99 档位时保留该档位的准确匹配。
- 新版缩略窗 Compose 菜单也使用宽松误差比较，因此仅在该倍速控件的同步打开调用期间，让宿主使用已有原生列表分支。线程局部作用域支持嵌套，在异常路径也恢复；其它实例与其它窗口不受影响。
- Adapter 成功注册后才发布工厂选项；缺失 Adapter 时不单独替换工厂。
- 倍速选择继续交给宿主自己的点击回调，不额外写播放器基础/临时 Flow。

## DexKit review

- 接入既有后台 `DexAssistSession`、宿主指纹、reset、未完成扫描闸门及桥资源管理。
- 快路径仅做有界反射；关闭功能、完整定位或同一宿主已完成兜底尝试时不重复扫描。
- Adapter 查询限定倍速包、Float 或 Float/List ABI、Math.abs 调用以及 List.clear / ArrayList.clear 两种调用形态。候选还须通过 RecyclerView.Adapter、OnClickListener、构造器、条目字段与默认值复核。
- 选项工厂查询限定 MediaResource/Integer/Boolean/Int ABI 和列表构造调用；从同一工厂的构造调用提取选项模型并重新验证。
- 菜单打开入口使用专用 `player.player.speed.0.player` 事件，随后核对控件、播放器与功能菜单服务访问器。
- 结果只缓存成员描述。缓存恢复拒绝外部包、通配签名、附带 viewField、失效成员和不完整工厂模型；查询失败、歧义和超限不猜测安装。
- 适配规则升至 v73，可选 JSON 键保持旧 schema 可读。

## 云端静态复核

固定样本覆盖 8.84.0、8.90.2、8.98.0、9.5.0、9.10.0、9.15.0。

- 复用此前保存的六版本类结构与反编译结果。
- 本轮复核 9.15.0 的选项工厂、播放器 setter、Compose 展示及回调；复核 9.5.0、9.10.0 原生菜单回调。
- 六版本语义条件矩阵初轮发现旧版使用 `List.clear()`，据此补全查询并重新核验。最终矩阵：`9cdcb07f38ad4fa6b25a4079e81f291f`。
- 云端静态结果不代表 LSPosed 播放器端到端验收。

| 宿主 | 唯一 Adapter 填充入口 | 选项工厂 |
| --- | --- | --- |
| 8.84.0 | `speed.g.K0(float)` | 旧版实例 FloatArray |
| 8.90.2 | `speed.g.J0(float)` | 旧版实例 FloatArray |
| 8.98.0 | `speed.f.N(float)` | 旧版实例 FloatArray |
| 9.5.0 | `speed.f.d0(float, List)` | `utils.B.a(MediaResource, Integer, boolean, int)` |
| 9.10.0 | `speed.f.j0(float, List)` | `utils.B.a(MediaResource, Integer, boolean, int)` |
| 9.15.0 | `speed.f.l0(float, List)` | `utils.C.a(MediaResource, Integer, boolean, int)` |

表中短包名分别位于 `com.bilibili.playerbizcommonv2` 下，只用于记录样本证据；运行时不按这些方法名安装。

## 验证范围

本地门禁：完整 JVM/源码契约测试、完整 Debug Lint、正常包名 Debug APK、instrumentation APK 编译及 Release R8 混淆检查。

功能分支上次同步主线后的全量测试：3,709 项，0 失败，0 错误，1 跳过。运行命令：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:minifyReleaseWithR8 --continue --console=plain
```

行为测试覆盖全范围精度回环、损坏配置、互斥与长按独立性、真实 Hook DSL 参数替换、异常恢复、相邻档位、2 倍速别名、工厂注册顺序、作用域嵌套与清理、DexKit 重命名候选、歧义/超限/不可用结果以及缓存宿主/reset 隔离。

用户要求暂停设备访问之前，独立验证包已在设备上加载 8.90.2 的真实宿主 Adapter，完成 12 档生成、唯一选中及 1.00 → 1.05 点击回调验证（`OK (1 test)`）。此后不再访问设备。新增 DexKit 原生查询 instrumentation 测试已提供并编译，本轮未执行；LSPosed 实际播放、长按恢复及新版缩略窗交互仍留作后续设备验收。

独立验证包 `com.Bilibili_Innocent_Lab.xposedmodule.speedverify` 及其测试包因设备占用保留，未继续安装、卸载或操作设备。

## 合并 main 复核

- 基于 `dcc8c5e7` 合并 `feat/player-speed-menu`，保留主线新增插帧代码、帧率上限设置及版本亮点。
- 保留已发布的设置目录 v59 快照，倍速菜单设置顺延 v60；诊断目录 v28、亮点批次 25。目录总数校验和测试统一为 239 项。
- 合并后的完整 JVM / 源码契约测试：3,729 项，0 失败、0 错误、1 跳过。完整 Debug Lint、Debug / instrumentation APK 编译及 Release R8 全部通过（`BUILD SUCCESSFUL`）。
- 按用户要求，本轮不访问设备。
