# BYD CarPlay

寒叙（[@Hanxu4131](https://github.com/Hanxu4131)）基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 做的个人二次开发版，主要适配2023款唐 DM-i、21平台／控制器。

当前版本为 **v1.0.0**，把个人测试第64版作为后续开发的稳定基线：保留此前的唐21平台适配，并局部合入上游PR332的平台加密实现。下一候选版本为v1.0.1。稳定基线是版本命名，不表示第64版已经完成实车改善验证。

主要改动：

- 旧平台仪表地图窗口，整体区域、关键信息与转弯提示位置调整、缩放、实时预览和保存。
- 仪表启动失败的静默5秒重试，以及L1Mini显示顺序和受限唤醒恢复。
- 嘟嘟桌面分屏／全屏，连接成功与再次打开直接进入CarPlay；H.264下保持连接并重新排版。
- 导航播报期间，方向盘滚轮只调整导航音量；播报结束后恢复媒体音量。
- 车机深浅外观跟随、原车歌曲信息同步、歌手显示选项、方向盘按键映射和重启按钮。
- 歌曲信息、音乐氛围灯以及麦克风NS／AEC独立开关。
- 旧21平台电量读取，达到自己设置的低电量阈值后，地图提示寻找充电站。
- 仪表与中控加载画面、分屏切换过渡和直角显示。

[完整修改及验证边界](docs/CURRENT_ADAPTATION.md) · [上游贡献草稿](docs/UPSTREAM_CONTRIBUTION.md) · [作者](AUTHORS.md)

## 构建

需要JDK25、Android SDK37、NDK28.2.13676358，使用仓库内Gradle wrapper。

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

普通源码构建不包含CarPlay认证身份。需要独立运行时，通过本机环境变量`DIPLAY_AUTH_ASSETS_DIR`明确选择外部身份文件；不得把这些文件或Android签名密钥提交进Git。调试版本沿用本车安装ID `com.shihab.diplay.tang21test`、版本代码92，更新现有安装还需要原来的签名。[构建说明](docs/BUILD.md)。

## 说明

本仓库完整保留应用源码、构建脚本、测试和适配记录，不包含认证／签名资产、第三方APK、车机日志和设备资料；普通源码APK无法替代本机已配置身份的独立安装包。

实际CarPlay Ultra仪表主题尚未实现；公开源码保留中性加载图形。L1Mini是独立软件，未内置或修改其APK。既往实车确认范围为2023款唐DM-i、21平台，包括导航滚轮隔离、主题跟随、分屏／外部L1仪表、歌曲与氛围灯，以及旧21平台电量读取和达到自设低电量阈值后的地图充电站建议；这些结果不代表所有车型、HEVC或其他固件均支持。

私用第64版对应源码已完成本地构建，1017项测试通过；本次公开源码另行验证，common 344项、shared 672项，共1016项测试通过。公开源码保留中性加载图，相关图形测试与私用版有所区别。平台加密改动只完成源码验证，尚未确认实车性能提升；网络配置未更动。NS／AEC开关在第63版获得用户确认，旧版不兼容路径不作为本版兼容性保证。

提交给上游时保留 **DiPlay** 名称和默认包名。BYD CarPlay只是本人的应用名，不代表比亚迪或Apple官方产品。

## 来源与许可证

保留DiPlay、xcertplay、DiAuto及已有贡献者署名与许可证：[LICENSE](LICENSE) · [第三方说明](docs/THIRD_PARTY_NOTICES.md) · [原DiPlay说明](docs/DIPLAY_0_2_9_README.md)。代码、Apple标识与BYDMate图像的许可分别处理；Apple及BYDMate图像不因存入本仓库而成为本人创作或被重新授予代码许可证。公开源码的新增加载图形使用中性图案及应用名；私用版Apple描摹图未上传。仓库不自动发布上游下载网站。
