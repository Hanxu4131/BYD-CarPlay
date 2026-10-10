# 第28版：H.264自适应实验与歌曲开关可见性

BYD Carplay 第28版（2026-10-03）
版本0.2.9-tang21-test28，versionCode56，基于第27版。

1. 仪表等待画面
继承第27版：等待/淡出期间隐藏本应用导航提示和位置参考框，加载图层放到最上方；结束立即恢复提示。原车或L1Mini等外部窗口图层不属于本应用控制范围，须目视回归。

2. 分屏自适应（H.264实验）
设置的显示区域新增实验开关，默认关闭。先关闭“高效视频”（HEVC），开启实验，在嘟嘟分屏状态重新连接一次，声明当前分屏和已知最大窗口区域；此后尝试分屏与全屏互换。仅实际H.264流启用，不修改HEVC路径。
使用本应用main UUID和updateViewArea选择握手时已声明的index，保留最大canvas/HID；收到有效且与当前目标匹配的配置header viewport后才裁剪并变换触摸。切换viewport不重启controller；coded尺寸真的变化才重配decoder。3秒未确认会记录并保留fit，不把命令发出当作手机接受。
如果从全屏建立连接且当前分屏尺寸未预声明，遇到新尺寸只能保留fit。现阶段实验并非支持任意窗口尺寸。需要实车确认返回geometry、可见排版和边缘触摸，及厂商decoder是否已经裁剪。

3. 歌曲与歌词
将“同步歌曲信息到车机”移到独立“歌曲与歌词”板块，不再受BYD硬件检测门控；偏好键不变，立即生效。“同时写入原车仪表歌曲信息”保留硬件门控。
播放关联样本：test27中控recvToPresent约百毫秒级，音频AudioTrack estimatedQueuedFrames约11648～20224/48000，即0.24～0.42秒；压缩音频queue仍需计入总缓冲。手机请求audioLatencyMs=1000，反馈用monotonic elapsed减此参数外推，尚无真实播放头反馈；不能由这些样本断言3秒问题已修复或确定手机故障。保持歌词字段原样，不添加负时间偏移。

4. 导航滚轮
仍未解决。Activity窗口的默认流不是车机全局滚轮目标；全局forceVolumeControlStream需系统权限，CAN若直接setStreamVolume(MUSIC)也不会被重定向。临时shell app_process探针被系统Killed，未输出NAV_STREAM_FORCE_REQUESTED，不能据此宣称路由测试成功；临时jar已删除。没有修改用户音量值。

网络目录、orchestration及AirPlayPersistence与第27版逐字节比对保持；网络和Wi-Fi配置不改。实验仅增加显示协议分支，默认关闭。源码不含第三方APK、私有反编译片段、用户图像/歌词或签名密钥。
验证：shared定点34测试、common定点23测试通过；整包编译/lint通过。覆盖安装和现场实验结果另记，不将编译通过当作实车完成。
