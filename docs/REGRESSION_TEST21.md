# 第21版：外观恢复、仪表首帧和歌词字段

安装后日志：14:41:37 恢复活动会话并发送深色；14:41:45 系统与Activity的uiMode从33变为17，随后发送浅色，eventChannelReady=true。确认检测和发送正常；实体CarPlay页面变化仍需用户确认。

BYD Carplay 第21版（2026-10-03）
版本0.2.9-tang21-test21，versionCode49，基于test20，覆盖安装保留原设置。

深浅模式：实车test20系统与Host均已夜间，但重用后台连接的Host没有收到活动会话通知，activeAirPlaySession为空导致外观轮询不运行。修复attachUi补发当前活动会话，保护UI、会话身份及关闭状态；避免重复回调。变化和发送结果使用有界W日志及私有日志。不改连接、握手或网络配置。

仪表开场：保留最少1.5秒、5秒兜底、1700毫秒扫光和同样logo大小。除平台OnFrameRendered外，在当前有效decoder/Surface成功释放两幅非配置/非EOS画面后，经80毫秒短暂稳定等待确认可呈现的解码输出。防止厂商回调缺失时每次都走5秒兜底。真正输出缺失仍等5秒，不用假定时器伪造地图就绪；实车需日志验证codec_callback/decoded_output及firstFrameMs。

歌曲/歌词：实车手机title-only更新会错误清除artist；本版按字段增量合并，显式空字段与断开仍清空。不从歌词文本猜歌名，MediaSession直接保留手机TITLE/ARTIST（同时映射DISPLAY_TITLE/DISPLAY_SUBTITLE）。手机把歌词放title时嘟嘟就读取歌词；独立歌名/歌手以手机实际提供字段为准。安装后需手机新会话或再次artist更新才补回旧版已丢失字段。

按用户主要使用嘟嘟歌词的要求，原同步开关改为车机媒体会话；原车仪表直接写入另设开关，默认关闭、可选恢复，避免每句歌词都启动慢的OEM写入。实测一例receiveToMainMs15、OEMqueueMs0/writeMs4203，source失败而state/text成功。保留可选路径分段诊断。

CarPlay自身歌词比嘟嘟慢仍未认定修复：前者是iPhone编码的视频页面，后者读取媒体数据，来源不同；主视频近期rx/shown基本一致、recoveries0，无固定3秒延时的源码或实测积压证据。此版本不盲目改音视频缓冲。
L1Mini真正淡入淡出仍未实现，原恢复次序保持。
