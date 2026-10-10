# 第 17 版：仪表地图启动画面

基于重新从第 15 版整理的 test16-r2/version44，增加仪表地图首帧前的本地占位画面。

- 图案使用 Apple 官网 CarPlay 页面中的 CarPlay Ultra 标识；这只是开场视觉，不代表接入 Ultra 仪表主题。
- 标识在已保存的仪表地图区域内居中显示，深色渐变背景，轻微缩放、亮度变化和短线呼吸效果。
- 仪表镜像的 MediaCodec 首帧输出到当前 Surface 后，180ms 淡出；没有最短播放时间。
- 8 秒未收到首帧回调时也移除占位，避免特定车机解码器不回调导致地图长期被覆盖。
- 每个窗口/Surface 单独判断首帧，旧连接、旧 Surface 的回调不能结束新窗口的动画。
- 关闭窗口会取消动画和回调；实时调整区域时，占位跟着同一地图区域移动。
- 原车显示由车机保留；占位只画在 BYD Carplay 自己的地图矩形里。若用户将矩形移到原车信息位置，仍需自行调整回合适位置。
- 网络、Wi-Fi、热点、连接参数均与 test16-r2 保持一致。

标识来源：
https://www.apple.com/ios/carplay/
https://www.apple.com/v/ios/carplay/n/images/overview/dashboard_carplay_ultra_logo__fky4p284nnau_large_2x.png
标识版权属于 Apple。PNG 保留原始文件，不改字形。

本地编译、测试与实际车辆效果分别核验。车机切换窗口、应用尚未开始绘制之前的黑闪，无法由应用占位保证消除。
