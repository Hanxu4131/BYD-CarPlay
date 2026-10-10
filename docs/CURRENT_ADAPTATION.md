# 2023款唐 DM-i / 21平台适配

二次开发与实车测试：寒叙（[@Hanxu4131](https://github.com/Hanxu4131)）。

本仓库保存个人使用的BYD CarPlay，基于DiPlay及后续有选择的移植，当前完整源码为v1.0.13／versionCode105。二次开发与实车验证持续针对2023款唐DM-i冠军版、21平台／控制器；提交上游继续使用DiPlay名称及默认包名。

实车范围为2023款比亚迪唐 DM-i、21平台／控制器。这些结论不代表所有2023款车辆或所有固件都支持。

| 修改 | 主要文件 | 当前状态 |
| --- | --- | --- |
| 旧平台独立仪表窗口 | `LegacyClusterMap.kt`、`LegacyClusterTarget.kt`、`ClusterMapActivity.kt` | 在目标仪表副屏显示CarPlay地图，保留原车车速、挡位；已实车显示 |
| 仪表整体区域、关键信息区域调整 | `LegacyClusterLayout.kt`、`LegacyClusterKeyArea.kt`、`LegacyClusterPreviewState.kt` | 上下左右、缩放、实时预览、保存及取消；已实车使用 |
| 独立转弯提示与细调 | `LegacyClusterGuidance.kt`、`LegacyClusterTurnArea.kt` | 本地导航提示卡独立调整；不代表可任意拆开iPhone视频中已合成的车道图层 |
| 仪表启动重试 | `LegacyClusterRetryPolicy.kt`、`LegacyClusterMap.kt` | 失败后静默等5秒再试；真实呈现成功停止；逻辑测试通过，完整冷启动失败恢复仍需专项实车复现 |
| L1Mini显示顺序与唤醒恢复 | `LegacyL1MiniOrder.kt`、`LegacyL1WakeRecovery.kt`及相关策略类 | 地图启动后恢复L1窗口；左右摄像头与中间地图同时可见已确认；冷启动恢复有条件限制；未修改L1Mini APK |
| 嘟嘟分屏／全屏及直接进入CarPlay | `CarPlayHostActivity.kt`、`DiPlayActivity.kt`、`AndroidManifest.xml` | 保留会话、连接成功进入CarPlay、已连接再次打开时直接回到CarPlay；已实车测试 |
| H.264不断连重新排版 | `MainAreaViewport.kt`、`AdaptiveResizeTransition.kt`、`AirPlaySession.kt` | 分屏与全屏切换采用声明的视区、尺寸确认及有限重发；用户已确认来回切换铺满；HEVC不能按此路径保证不断连重新排版 |
| 切屏过渡、直角边框及全屏系统栏 | `CarPlayHostActivity.kt`及相关显示类 | 已用于实车；应用之外的桌面覆盖层由桌面决定 |
| 车机外观跟随 | `HeadUnitAppearance.kt`、`AppearanceCommands.kt`、`AirPlaySession.kt` | 读取系统外观并在现有会话发送变化；保留手机及日出日落等选项；不同固件需复核 |
| 歌曲信息与歌手开关 | `CarPlayMediaKeys.kt`、`BydClusterSong.kt`、`BydSongMetadata.kt`及OEM桥接类 | MediaSession持续提供信息给桌面；原车歌曲栏同步独立开关、可选歌手；持续写入替代逐条启动进程，仍可能存在源端与显示端延迟 |
| 控制页重启及方向盘按键映射 | `DiPlayActivity.kt`、`SteeringWheelMappings.kt` | 提供重启、上一曲、下一曲、接听、挂断、Siri、Home映射；是否收到按键由车机输入路由决定 |
| 仪表及中控加载画面 | `ClusterStartupView.kt`、`StartupLogoPreferences.kt`及资源 | 支持不同窗口比例、帧就绪结束和时限；Ultra字样为加载视觉，不是CarPlay Ultra仪表主题实现 |

文件均位于 `common/src/main/java/com/shilapi/xcertplay` 或 `shared/src/main/java/com/shilapi/xcertplay`。各模块单元测试随源码保留；实车日志和逐次安装记录仅保留在本机。

## v1.0.1至v1.0.13后续补充

- 专辑封面随实际媒体开始播放恢复，歌词标题不作为唯一换歌依据；无彩色时增加大面积白色／冷白判断。
- 音乐欠载后有限重新缓冲、残余PCM计数修正，视频积压恢复和硬件解码器选择。
- 通话音频模式及麦克风效果初始化修订，NS／AEC独立开关保留；未证明可选择主驾物理麦克风，也未保证对端回声全部消除。
- 备用仪表视频流结束不连带关闭主会话；event或主视频故障保留必要清理。
- 连接期间Android无线运行时保护，断开释放；不调整连接配置。
- 普通CarPlay封面独立暖色玻璃图标，Ultra现有画面保持。

本地v1.0.13完整1212项测试通过；新增普通加载封面尚未完成实车视觉验收。历史卡顿、守卫0秒和偶发断连不记作全部解决。

## 边界

- 媒体／导航通道0（自动）及1–20原项目已有，不列为此次新增。
- 导航播报时滚轮隔离已获目标车型实车确认；后续补充了无障碍绑定检查和恢复，间歇性失效仍需日志复核，不据此保证所有固件。
- CarPlay Ultra真实主题、车辆数据主题渲染尚未实现。
- 不能对另一进程的L1Mini窗口保证淡入淡出或任意控制图层。当前主要通过启动顺序配合。
- H.264实车通过不等于HEVC、所有屏幕尺寸或所有固件都通过。
- 原车网络、配对和已保存连接配置保持原使用方式，不为提交或上传调整。

## 记录使用

本文件只记录通用功能与验证边界，不保存本车序列号、手机、热点、密码、连接记录或原始日志。逐次安装与实车记录只保存在本机。源码和测试中有协议占位值、模拟数据和默认设置，它们不代表个人设备资料。
