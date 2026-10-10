# 唐2023（DiLink 4.0）适配维护记录

这份记录对应 DiPlay 0.2.9 上游基线 `18429e737228e8d75d9b6c850af89dcca2f591b6`。用途是以后上游发布新版本时，能按功能找回补丁、重新核对并决定是否向上游提交通用改动。它不代表上游已经接受，也不代表所有实验功能已经完成。

## 功能、源码位置与验证状态

当前候选为从 test15 重新制作的 test16-r2（versionCode 44），名称仍是“BYD Carplay”。上一份 test16/version43 已撤回到 work/rejected-test16-deliverables，不再供安装。车上仍为 test15/version42，本轮未连接或安装车机。用户明确要求不改任何网络：网络/连接控制模块、Wi-Fi设置UI及其保存逻辑、网络文案和权限清单已逐字节对照test15，保持一致。以下旧版本内容为历史记录；本轮仅新增歌曲同步、车机外观与L1受限恢复。

用户后续目标是在不终止 CarPlay/iAP2 连接的情况下，窗口变化后重新协商显示参数，并由 iPhone 重新规划比例与布局。当前保持既有画布比例、连接不断并暂时接受黑边只是过渡方案；动态适配尚未实现，不能写成完成。

| 功能 | 主要源码位置 | 状态与边界 |
|---|---|---|
| 媒体/导航音频通道选择 | `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`（设置控件及应用逻辑，约 640–716 行）；`common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt`（0–20 通道及持久化） | 车上确认设置界面可选 0（自动）及 1–20；这些通道选项原项目已有，本次没有新增范围。此处仅记录 UI/设置项核对，不据此宣称所有通道的实车音频路由或自动协商均已验证。 |
| 旧款独立仪表地图窗口 | `common/src/main/java/com/shilapi/xcertplay/LegacyClusterMap.kt`、`LegacyClusterTarget.kt`、`ClusterMapActivity.kt`、`MapMirrors.kt` | 唐2023旧平台实车确认地图位于逻辑 display 1，AMS 独立窗口确认，CarPlay 主地图仍显示；native 车速与挡位保留。与 L1Mini 同时显示时，窗口层级取决于后启动的一方，不能把一次共存观察当作稳定层级保证。不同车机固件仍需单独核对。 |
| 整体地图区域调整与即时预览 | `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt`（约 873–889、909–951 行）；`LegacyClusterLayout.kt`、`LegacyClusterPreviewState.kt`、`LegacyClusterMap.kt` | 车上已验证上下左右及宽度缩放即时预览，确认保存后保留；取消、返回或退出编辑会清掉预览、不保存。 |
| 导航关键信息区域参考框 | 同上；另见 `LegacyClusterKeyArea.kt` | 车上已验证参考框随位置和缩放实时预览。已发现 iAP2 真实请求与发送历史，但现有证据不能证明 iPhone 实际采用了相应布局参数；确认保存后重连的 safeArea 效果仍需专项实车复核。 |
| Ultra 协商线索与普通地图提示 | `shared/src/main/java/com/shilapi/xcertplay/diagnostics/CarPlayNegotiationSummary.kt` 及连接流程；地图提示见 `LegacyClusterGuidance.kt` | 用户要求先暂停 Ultra 功能研发。最终源码检查确认 0x4301 未带 `clusterAsset`，没有主题包传输、缓存或 Ultra 仪表渲染，保留此实现缺口说明。普通 111 地图加本地真实导航卡已实现。 |
| 触控/渲染诊断 | `shared/src/main/java/com/shilapi/xcertplay/media/TouchLatencyProbe.kt`、`TouchSendDiagnostics.kt`、`VideoStats.kt` | 仅增加诊断观测。FruitLink 上也观察到每次切换图标约等待 2–3 秒，因此目前没有据此宣称或交付性能修复。 |
| Fruit 原生 display-panels 协议线索 | 第三方 Fruit 原生 SO 的只读 field-level 观察 | 观察到 `updateDisplayPanels` 命令；`displayPanels`/`initialVideoStreams` 完整 schema 与 iPhone 响应未知。尚未实现，也未发送该命令；不把第三方实现纳入开源包。 |
| 嘟嘟桌面右侧主屏 CarPlay 分屏 | `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`、`DiPlayActivity.kt`、`common/src/main/AndroidManifest.xml`；测试：`common/src/test/java/com/shilapi/xcertplay/CarPlayHostDisplaySizeTest.kt` | test9 已确认 launcher-split display 28 按 1284×990 显示并可打开 sidebar 应用网格。Host 保留已协商 canvas，通过等比 fit 和触控坐标重算适配窗口；同一 Host stack 327 从 display 28→0→28 移动时 PID 18847 不变、两次切换均无新 iAP2 0x4301 会话、截图持续显示。但该 ADB 验证不等于 Dudu 菜单全屏重进；test11 的用户照片显示旧画布全屏有宽黑边。test12 菜单点“全屏”已确认可直接显示全屏 CarPlay。比例不同仍会留边，低分辨率放大可能变柔和，不能通过拉伸/裁切冒充比例适配；手机原生重新规划布局尚未实现。真实 rotation 或显式 system bar 变化仍会重连；入口 reported display 0 时由 source token 默认继承，不显式强推 0，实际 display 由 AMS/Dudu 决定。 |
| Dudu Launcher 会话入口恢复 | `common/src/main/java/com/shilapi/xcertplay/DiPlayEntryRoute.kt`、`DiPlayActivity.kt`；测试：`DiPlayEntryRouteTest.kt` | 用户已确认 test12 菜单点“全屏”可直接进入 CarPlay。test13 已补 Home 键从 Dudu 右侧回到已连接 CarPlay 的修复代码，已安装并实测：从CarPlay和配置首页按Home均回到右侧CarPlay。主动打开设置保留页面；未连接分支通过单元测试，未做本轮断连实测。 |
| L1Mini 仪表地图后置启动实验 | `common/src/main/java/com/shilapi/xcertplay/LegacyL1MiniOrder.kt`、`LegacyClusterMap.kt`、`DiPlayActivity.kt`；测试：`LegacyL1MiniOrderTest.kt` | test13 候选逻辑由偏好项 `restore_l1_after_map` 控制，默认关闭。地图 surface 出现后最多等待 15 次、每次 2 秒，查找已存在的 Dashboard 任务，最多重开一次；不重复启动 service、不 force-stop、不初始化摄像头。只改善特定手动/运行期顺序的尝试，不代表稳定层级或 cold boot 修复。10101 service 的 static guard 会拒绝重复初始化，开机首次内部连接失败原因仍未定位；L1 后置日志已有 `restored_existing_dashboard`，但车辆冷启动间歇连接失败仍未解决。 |
| 仪表地图导航提示与转弯提示区域编辑 | `common/src/main/java/com/shilapi/xcertplay/ClusterMapActivity.kt`、`LegacyClusterGuidance.kt`、`LegacyClusterTurnArea.kt`、`common/src/main/res/layout/legacy_cluster_guidance.xml`；测试：`LegacyClusterGuidanceTest.kt`、`LegacyClusterTurnAreaTest.kt` | test13 候选读取 CarPlayGlance/5201/5202；内容选择变化后自动重连。MAP+提示的新会话使用 pureMAP111 与 nativeGlance card，避免使用 baked-in 提示卡造成重复。本车 `guidance_available`/`visible` 均为 true；只读 prefs 已确认两个区域设置独立保存。实车 941 图曾确认转弯提示卡显示但压住地图。现在新增“调整转弯提示区域”独立保存、实时预览；提示区与 key area 分开保存，首次迁移只按现有 key area 初始化卡片位置，之后不跟随地图 marker。key area 和提示区的位置微调步长为 0.5%，缩放仍为 5%。导航滚轮目前只完成播放跟踪器，未接入 Host，因此该操作尚未修复。仍需最后实体仪表照片确认独立调整提示区域不会移动地图/车道提示。 |

L1Mini 是唐2023 DiLink 4.0 上的闭源应用，包名 `l1tech.com.l1mini`、版本号 10101，不属于 DiPlay 源码适配。观察到约 5 秒 boot 显示、约 2 秒 Echo、约 3 秒 AVM 和内部连接等待，未见仪表窗口或首帧重试逻辑。手动启动顺序实车确认：先启动仪表地图，再执行 `am start-activity --display 1` 启动 L1 Dashboard，左右摄像头与中间 CarPlay 地图可同时显示；谁后启动谁会盖住另一方，因此窗口共存尚无稳定层级保证。已验证 L1 排序可使左右镜头与地图同时显示；车辆冷启动时的间歇连接失败仍未解决。不要把其 APK、代码或反编译内容加入源码包或公开 PR。

## 官方上游更新时怎么恢复

1. 先记录新官方版本号和提交号，保留新上游原始目录；不要直接覆盖当前源码目录。
2. 对照新上游检查上述文件及调用点。优先逐个功能移植；用小补丁或独立提交记录每项改动，避免把整份旧文件覆盖到新版本。
3. 先移植通用音频 UI/持久化，再移植仪表窗口与预览状态机。协议、显示 API 或连接生命周期若有变更，应重新审查调用逻辑，不能只处理冲突标记。
4. 嘟嘟桌面右侧主屏 CarPlay 分屏单独回归：test9 已确认 display 28 右侧 1284×990 显示及侧栏应用网格；同一 Host stack 327 从 display 28→0→28 的 ADB 连续性已验证：PID 18847 不变，无新 iAP2 0x4301 会话启动，截图持续显示。该验证未覆盖 Dudu 菜单“打开全屏”；961/971/981 旧照片显示旧版菜单会重新拉起首页，旧 1284×990 canvas 全屏出现宽黑边。test12 中菜单“全屏→CarPlay”已由用户确认可用。test13 本轮从 CarPlay 和配置首页按 Home，均自动回到右侧 CarPlay。等比 fit 不变形但留边，动态比例适配未解决，不能拉伸或裁切冒充修复。display 28（launcher-split）与 display 1（独立仪表）分别回归，不互相代替。
5. Dudu Launcher 会话入口、L1Mini 后置启动与地图导航提示均按 test13 候选逐项移植并复核。L1Mini 项默认关闭且不能视作冷启动修复；导航提示先做实体提示显示、过期隐藏及位置联动验收。test13 对应 versionCode 40，名称“BYD Carplay”，并附同名维护 patch、JSON hash 与源码 zip；不把候选描述为已发布。升级到新上游时，在干净源码树先运行 `git apply --check <patch>`，再应用 patch；有冲突按功能合并，避免直接覆盖旧文件。
6. 保留上游 `LICENSE`、`docs/THIRD_PARTY_NOTICES.md` 和各自依赖的许可证文本；不要把外部 MFi 认证资产、私钥、签名材料、车机标识或原始认证日志放进源码归档或提交。
7. 每次更新记录上游版本/提交、移植的文件、冲突处理、验证车型与固件、已知未验收项目。新上游行为不同的时候，以实测为准，不能沿用旧结论。

建议回归：先确认音频设置界面可显示 0 自动与 1–20 选项，再逐项验证实车路由；检查独立仪表 display 1、AMS 窗口、主屏投影和 native 车速挡位；分别验证整体区域预览/保存/取消和关键区域保存后重连的实际 safeArea 排版；检查应用重连、不同尺寸窗口及退出清理；Ultra 功能研发按用户要求暂停，保留缺失资源传输/缓存/仪表渲染的说明；触控诊断需与用户可见等待分开判断。分屏右侧显示、应用网格和同一 Host stack 的 display 28→0→28 ADB 连续性已验证；test12 菜单“全屏→CarPlay”已确认可用；test13 已实测从 CarPlay 和配置首页按 Home 自动回到右侧 CarPlay。旧画布全屏有宽黑边，动态比例适配未解决；L1Mini 显示顺序需分别验证并记录谁后启动谁盖住的现象；仍需最终实车图确认独立移动转弯提示区不影响车道/地图提示；车辆冷启动间歇连接失败尚未解决。

## 是否适合向上游提 PR

适合评估，建议拆成独立 PR，先提通用性较高且不绑定唐车型的部分：

- 音频通道设置或参数呈现（通道 0 自动、手动范围以现有上游能力为准）。
- 可复用的仪表区域编辑/预览 UI 与取消清理逻辑，配通用显示目标检查和测试。
- 嘟嘟桌面右侧主屏 CarPlay 分屏作为单独提案；test9 已实车验证右侧区域与 sidebar 网格；test11 只验证 Host stack 迁移时会话连续；test11 照片显示旧画布全屏出现宽黑边；test12 菜单“全屏→CarPlay”已确认可用，test13 的已连接 Home 返回已实车验证。等比 fit 会留边，动态比例适配尚未实现，rotation/显式 system bar 变化仍重连。
- Dudu Launcher 入口恢复、L1Mini 后置启动实验和地图导航提示分开提案；test13 已通过编译及14项定点测试、安装并完成已连接 Home 返回实车验收；L1Mini 顺序实验不得描述为冷启动修复，导航提示独立编辑与不重连行为需完成实车验收。

唐2023独立仪表 display 1 与嘟嘟桌面分屏 display 28 应按各自入口和窗口语义分别实现，可作为 opt-in 实验配置或设备说明讨论，不建议把任一 display 默认套用到所有车辆。Ultra 功能研发已按用户要求暂停；源码没有 0x4301 clusterAsset、主题包传输/缓存或 Ultra 渲染，此缺口说明保留，不应以“完整 Ultra 支持”提交。普通 111 地图与本地真实导航卡已实现。L1Mini 闭源应用、MFi/认证资产、密钥、签名资料、设备标识、私有日志和未经确认来源的外部资源都不应进入公开 PR。是否发布由维护者和用户另行决定；本记录没有创建或发送 PR。

## 许可与范围

源码沿用 GPL-3.0 上游基线，并保留 `LICENSE`、`docs/THIRD_PARTY_NOTICES.md` 及其中列出的第三方许可说明。第三方资源不自动因 GPL 源码许可而获得再分发授权；外部 MFi 认证资产不随本维护记录或源码包公开。

本轮收尾核验：2026-10-02 test13 名称 BYD Carplay；versionCode 40；构建通过，lint 0错误/18警告，入口9项与导航播放跟踪5项测试通过。无线安装首轮45秒超时，随后push完整APK并pm install -r成功。已连接时，从CarPlay和配置首页按Home均返回嘟嘟右侧CarPlay。位置日志本轮再次出现iPhone请求0xfffa及实际发送0xfffb；仍不能证明iPhone一定使用该位置。

test14：用户确认仪表投屏与调整（含独立转弯提示）、Dudu分屏/全屏/Home返回和L1窗口共存前四项均OK。去掉仪表启动命令已接受的成功Toast；失败仍提示，报告仍可导出。隐藏仪表设置内自动刷新的调试长文，只在主动检查ADB时显示检查结果。新增“导航播报时调节导航音量（实验）”，默认关闭；前台CarPlay拥有当前已连接会话、实际导航播放且实际legacy14时setVolumeControlStream(14)，结束/暂停/销毁/关闭恢复默认，不吞按键，不改focus/duck。16项定点测试通过；物理滚轮效果待实车验证，不能视为已修好。

test15：修复首页等待过程中连接成功只更新文字、未进入CarPlay的问题；监测未连接到已连接的状态转换，首页自动打开投影一次，不打断设置页。入口时记录连接状态以覆盖首个轮询前已连上的情形。L1窗口重新置前增加REORDER_TO_FRONT/SINGLE_TOP，避免累积Dashboard。重启失败日志显示43760 ECONNREFUSED；本轮完整退出L1并点击启动后，l1bg/l1echo进程及43760 TCP6监听恢复，Dashboard显示于仪表；未证明冷启动故障根因已修复，实体首帧仍需用户确认。

test15 本轮安装成功，版本42；冷启动自动进入仍待用户下次重启验收。导航滚轮test14实测仍调媒体，用户决定暂停这项。


## 从 test15 重做 test16-r2（2026-10-03）

用户明确要求撤销上一份test16的全部网络修改，重新以test15为基线。已实际从保留的test15源码ZIP重新展开并逐字节验证，再迁入以下三个功能；没有把上一份test16整份覆盖回来。

1. 网络完全沿用test15。
shared/network及shared/orchestration共23个原文件逐字节一致，common/DiPlayActivity.kt、AirPlayPersistence.kt及AndroidManifest.xml完整一致；原Wi-Fi连接选项、SSID/密码/安全类型/默认值与网络文案不变。不存在ManualNetworkStatus、OwnedP2pGroupCleanup及外部热点新路径。没有删除P2P组、切换模式、清网络或连接ADB。上一版现有网络/手机热点、日出日落及PHONE选项全部撤销。新外观消息通过现有会话发送，不涉及Wi-Fi配置、配对身份或连接参数。

2. 歌曲信息。
shared/hud/BydSongMetadata.kt、BydClusterSong.kt、BydOutputSettings.kt；common/CarPlayMediaKeys.kt。复用已有MediaSession同步完整title/artist与播放状态，仪表沿用原SDK写入。metadata不另抢音频焦点，断连/关闭清空；新默认开启且保留明确false。歌曲标题/歌手字段也与水果互联可确认的原生NowPlaying属性一致，但其Java发布链因SecShell不能还原，未声称完全复制水果车机发布方式。车机、桌面、仪表实际读取仍需停车实测；无歌词生成。

3. 车机外观。
common/HeadUnitAppearance.kt、CarPlayHostActivity.kt；shared/airplay/AppearanceCommands.kt、AirPlaySession.kt。旧test15只读Activity uiMode并发送setNightMode。新增读取UiModeManager选择值：1/2分别选明/暗；自动或未知使用应用系统资源的实际uiMode，再回退Activity；全部未知不擅自发浅色。连接存续期间每2秒检查，打开车机设置时也继续，变化发送最新状态且不重连。
水果原生静态调用链确认SetNightMode同时发送setNightMode、uiAppearanceUpdate、mapAppearanceUpdate。新代码独立按其可确认协议字段生成消息，使用自己/info声明的MAIN_UUID和（仅存在时）ALT_UUID。nightMode为boolean，appearanceMode=暗1/明0，appearanceSetting=手动2/自动0；没有拷入水果代码、UUID或库。主题pending只保留最新值，event channel就绪后发送，close清空不等待可能阻塞的socket写锁。
水果Java车机取值/Settings.System key因SecShell未还原，不能据字符串硬编码uimode_night/key_nightMode。新方法仍需这辆车验证。诊断报告记录selectedMode、系统/Activity uiMode、来源和发送日志，用以分清源不变、发送失败与手机不采用。bytes发出不是iPhone采用的证明。

4. L1恢复。
common/LegacyL1WakeRecovery.kt、LegacyL1WakeRecoveryPolicy.kt、LegacyL1WakeRecoveryBudget.kt及LegacyClusterMap hooks。沿用restore_l1_after_map默认false（本车原已开启）。地图就绪/屏幕ON开始最多90秒等待；真实Awake+ON稳定5秒且已有PID/Dashboard，tcp/tcp6连续30秒无43760监听、临界点复读确认，才最多一次force-stop并启动10101已导出的原生L1BootService start=boot，恢复后观察最多60秒并重新排Dashboard。任一家族监听即保护；无监听必须两份表完整合法，权限缺失/异常/截断不强退。
BOOT_COUNT+mLastWakeTime可用时私有prefs在强退前commit预算，跨BYD Carplay进程仍限制每唤醒一次；commit失败不强退。BOOT_COUNT不可读时仅当前会话限制，报告显式标注，不虚称跨进程保证。SCREEN_OFF取消pending保留监听，下一ON重检查；关地图/关开关/stopSession注销。恢复会短暂中断摄像头，不修改L1 APK、CAN或系统。

未完成的实车项：车机外观切换时是否完整被iPhone采用；车机/嘟嘟/仪表读取歌曲；L1真实休眠唤醒首帧及一次恢复；test15连接成功自动进入的冷启动回归。Ultra保持暂停，导航滚轮实测无效而暂停；全屏保持比例和连接，接受黑边；不断连重新协商比例/布局仍为后期目标。

后续上游更新：优先分别移植媒体会话、外观消息队列/状态读取及L1恢复模块与各自单测。保留test15网络路径，不套用被撤回版本的网络补丁。不得将水果APK、原生库、L1私有分析、认证/签名资产或原始日志加入源码/公开PR。

本轮最终本地验证：51项定点测试通过；APK编译通过；lint：0 errors, 18 warnings。 未安装车机，三项新增功能待停车实测。


## test55–56：高负载下的音视频调度（2026-10-06）

本轮保持网络、配对、热点、500ms媒体缓冲、声道14滚轮、H.264分屏、L1外部摄像头路径不变。集成摄像头仍停用。

test55恢复视频工作线程的普通优先级，将音乐/导航播放线程及音频接收解密线程提升为Android音频优先级。实车已确认这些音频线程nice=-16，视频nice=0；音乐和导航并行的45秒窗口内，7个音乐统计窗口和3个导航统计窗口均未出现AudioTrack欠载、队列丢包或写入错误。这只能说明这一窗口，不代表所有场景已消除声音卡顿。视频仍出现5秒内4至5次恢复，输入延迟约280至306ms就触发旧的250ms恢复阈值。

test56改为有界的积压观察：单帧延迟、静态尾帧和短暂突发不直接重建；超过250ms且后续帧跨度达到100ms时开始观察，宽限750ms后仍增长100ms或4帧才恢复；150ms/50ms低阈值清除观察，真实积压达到1.5秒保留硬保护。配置、输出Surface变化及解码器释放清除观察；等待关键帧期间先保住参考链。原60帧/8MB队列上限和500ms解码输入等待上限保留。没有输出Surface的流不再重复转换AnnexB。

数值为针对已观察故障的工程初值，实车改善仍待安装与对比。没有新增动态帧率协商、没有擅自停用原车语音/录像/L1，也没有改系统调度器或CPU频率。若持续过载，下一步单独比较60/30fps，必要时降低分辨率；实时改帧率的协议尚未验证，不能承诺不断连降帧率。

资料依据：
- https://source.android.com/docs/core/audio/latency/contrib ：调度延迟、优先级反转会造成音频欠载；增加缓冲会增加延迟。本文平台SCHED_FIFO说明不等于本应用拥有实时调度权限。
- https://developer.android.com/reference/android/media/MediaCodec ：原始视频采用Surface减少拷贝；长时间持有输入/输出缓冲可能阻塞codec。现有视频路径已使用Surface；音频释放缓冲与阻塞写入的顺序是后续独立候选，本版没有混入。
- https://developer.android.com/topic/performance/inspecting-overview ：支持Android9及以上的Perfetto用于识别线程调度、后台I/O及渲染瓶颈。明日可抓短时调度轨迹，不能用整机CPU百分比替代因果定位。

智能守卫0秒：私有诊断曾记录魔法管理器相机后台在nativeAddPreviewSurface路径因为空对象触发JNI中止，也曾在约99%CPU压力下观察到录像文件持续增长。高负载可以加重竞争或启动超时，但不能作为全部0秒的唯一解释。BYD的BMM原生相机链不等同标准Camera2，因此标准CAMERA_IN_USE等错误只能提供排查方向，不能当作本车已观测错误。本版没有修改守卫或魔法管理器，也没有声明其0秒已修复。


## test57：音频缓冲释放与上游修复（2026-10-06）

音频解码输出先复制到复用的PCM数组，立即归还MediaCodec输出缓冲，再执行可能阻塞的AudioTrack写入。复用BufferInfo，减少持续播放期间的小对象分配。保留test55音频线程优先级和test56视频积压恢复策略，不改用户500ms缓冲，也不改导航声道、滚轮、分屏和网络。

上游对照：发布版v0.2.12（22d2aacedcadc4ec1aef0d74b161b05321f708a7），主分支a29241b19433b4035cc7563a424f01015f6817ab。按功能定点移植，不整包覆盖车型适配。音频初始化移植MediaCodecStartup及其失败释放测试；创建成功后configure/start失败会释放实例，清理异常不覆盖原始故障。此项减少失败重试后的资源泄漏风险，不等于已证明它是本车卡顿的根因。

本版另行移植歌曲元数据去重和待传输专辑图保留（#161/#162/#228）：仅比较实际显示的标题、歌手、专辑、时长、来源应用及图片引用，播放进度和播放状态仍实时更新；更换会话强制重发，失败发布不记入缓存。歌词标题变化立即发送，不新增延迟。缺字段保留歌手的解析本来已具备，无需重复修改。新专辑图未完成时保留旧图，确认失败或明确清空后清除。保留自用歌曲栏、歌手开关、SongUpdateDispatcher和氛围灯逻辑。

最新上游还包含分屏、显示参数、车辆投屏、外观、USB和连接方式的更新。已具备的功能不重复替换；网络扫描暂停、频道选择、热点和同一局域网连接均未引入。系统降噪和回声消除继续停用，避免恢复本车已确认的开头丢声。上游最新DiLink3投屏恢复时序先留作参考，不替换已实测的21平台窗口和L1叠层路径。

下一次实车对比保留全部必要应用：先比较相同歌曲和导航下音频队列、欠载、视频恢复次数；确认实际解码器名称及硬件/软件标记。守卫0秒抓取准确故障时刻、目标录像文件大小与修改时间、BMM取帧/编码/封装/写入及相机后台崩溃证据。持续高负载与短暂99%负载须分开分析；不以一个短压力测试排除用户观察，也不把整机CPU百分比当作唯一原因。当前没有修改守卫或魔法管理器安装包。

上游依据：[0.2.12发布说明](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.12)、[最新更新记录](https://github.com/shihabal3amri/DiPlay/blob/a29241b19433b4035cc7563a424f01015f6817ab/CHANGELOG.md)。

本地验收：test57/code85编译成功；common/shared共966项测试通过，零失败、零跳过；lint零错误。签名与test55相同，10个原生库逐字节一致。安装包已保存到下载文件夹，尚未安装；音视频改善和守卫0秒定位仍须实车验证。


## test58：分屏切换与仪表保留（2026-10-06）

保留test57性能与歌曲修复。中控H.264 updateViewArea采用上游主分支的300ms动画参数和其它已声明区域列表，保留原HEVC限制、请求确认、重试和连接写入边界。未引入任何网络配置或传输方式更新。

同一后台renderer重新被中控接管时，已就绪且有效的仪表镜像Surface不再reset/reapply，避免调用setMirrorSurface关闭原解码器。新会话、未就绪或无效Surface仍按原方式重建；过期回调保持代际隔离。中控Activity销毁时，如果还有有效背景会话，不关闭Legacy仪表；镜像回调交给仅持有renderer的闭包，不保留已销毁Activity。真实断联/关闭会话仍清理窗口，旧Activity不能清理较新owner的全局镜像回调。此保留针对23款21平台Legacy仪表，不宣称所有Presentation路线均已保活。

正常切换最多捕获一份640x360以内的静态最后画面，用FIT_CENTER过渡，新几何与后续Texture更新确认后淡出；没有可用画面才用启动logo。3秒未确认保留过渡层，不直接露出未就绪画面；6秒按当前代际取消并恢复fit兜底，不永久冻结旧导航。快照只在切换时生成，释放引用后交给系统回收，避免RenderThread仍绘制时强行recycle。中控Texture被销毁时清除已有帧标记，不把新空Texture当作可捕获旧画面。

实车仍需检查全↔分切换时是否发生Activity重建、Surface销毁、111流inactive或iPhone另行重建仪表流。代码保留不保证设备始终保留主屏Surface，也不等于所有资源负载下完全无卡顿。

本地验收：test58/code86最终源码编译成功，common/shared共981项测试通过，零失败、零跳过；lint零错误。签名与test55相同，10个原生库逐字节一致。安装包已保存到下载文件夹，尚未安装。全屏与分屏过渡、仪表保留以及真实断联后的恢复需实车验证。

## test59：缺失HUD服务的后台重试（2026-10-06）

现场发现21平台没有SomeIpServerService，却每300ms反复尝试绑定，生成系统服务未找到日志。绑定前检查明确服务；缺服务改为60秒重新检查，存在但绑定失败使用5/15/60秒退避。新会话立即检查，成功连接清除退避。有效连接后的300ms导航输出不变，歌曲、导航声道14、滚轮和网络不变。此项消除无效后台调用，不能直接证明它是音视频卡顿的主要根因。

第58版实车用户反馈稍有改善，仍有接近2秒接收/输出间隔。智能守卫0秒与嘟嘟菜单栏未恢复仍未修复，不能将本版计作这些第三方问题的修复。

本地验收：test59/code87编译成功，共984项测试通过，零失败、零跳过，lint零错误。签名匹配，10个原生库与test55逐字节一致。安装包保存下载文件夹，尚未安装；当前实车为test58/code86。
