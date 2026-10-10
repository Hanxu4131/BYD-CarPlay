# 第29版：比例切换过渡、全屏系统栏、直角实验

BYD Carplay 第29版（2026-10-03）
版本0.2.9-tang21-test29，versionCode57，基于第28版。

第28版实车结果：用户已确认H.264分屏⇄全屏不断连且重新排版满屏。初始header已确认coded1920x1080、viewport1284x990、matched=true；单次成功不代表任意尺寸/HEVC支持。

第29版改动
- 分屏/全屏已声明区域切换时显示独立中控过渡动画，遮住旧比例；收到目标geometry后等待同一Texture下一次呈现，再220ms淡出。无额外最短展示时长；3秒无确认撤罩保留fit。超过2px未知尺寸不永久遮罩；2px抖动归到已声明尺寸。呈现确认未新增codec帧世代，仍需实车判断有无闪回旧比例。
- 本应用位于display0的真实全屏窗口隐藏系统状态栏/导航栏，在resume/focus/配置和尺寸变化时重应用，不修改原用户偏好。嘟嘟顶部48px独立overlay属于com.dudu.autoui/type2032，本应用窗口的沉浸式不能隐藏外部悬浮窗；未强退桌面、禁用overlay或修改系统服务。
- “中控直角画面（实验）”默认开启，可关闭后重连。仅main /info声明cornerMasks=true，要求mask独立提供；本应用不叠加独立圆角mask，不拉伸或裁掉边缘按钮。来源依据是本地对照程序的squareCorner→布尔cornerMasks主屏字典路径；接收端SetCornerMask为no-op。是否影响iPhone输出须截图验证，不能将capability字段当作已经成功的画面形状。仪表111声明保持。
- 继承第27版等待期间本应用导航提示隐藏、第28版独立“歌曲与歌词”板块和H.264实验开关。

验证
显示遮罩/Host/artwork/fullscreen定点32测试已通过；新增cornerMasks main-only/关闭恢复与geometry测试，整包编译及lint结果另附。覆盖安装和现场测试另记。网络目录、orchestration、AirPlayPersistence逐字节保持第28版；Wi-Fi、热点、配对、连接设置不改。只扩展显示协议字段。

仍未闭合
歌词约3秒慢：本轮播放PCM硬件队列0.24～0.42秒，压缩队列另计；中控recvToPresent各窗口均值76～108ms。音频请求延迟1000ms，反馈仍基于wallclock外推，不是AudioTrack实播头。未找到足以安全改时钟/缓冲的证据，未加负时间偏移。
导航滚轮：普通Activity窗口流实验已无效，全局force需特殊权限且不能重定向直接MUSIC写入。shell临时探针被系统Killed，未获得请求成功输出，临时jar已删除；未修改音量值，仍不宣称可用。

构建缓存调整：首次整包任务阻塞在旧生成目录的重复命名Iap2WirelessCarPlayEndpoint 2.class读取。已保留旧目录并终止本次任务，后续构建通过build-local.init.gradle定向Library/Caches独立目录，打包从同一新目录取APK。验证时以versionCode57/versionName test29及签名、auth assets比对为准，避免误取旧mobile/build。

本机构建命令（生成目录避免使用Documents同步目录）：

```sh
source work/build-env.sh
export DIPLAY_AUTH_ASSETS_DIR="$PWD/work/runtime-assets"
export DIPLAY_LOCAL_BUILD_ROOT="$(cat work/test29-build-path.txt)"
cd work/DiPlay-main
./gradlew --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 -I ../build-local.init.gradle -Pkotlin.incremental=false :mobile:assembleStandaloneDebug :mobile:lintDebug
```

APK从`$DIPLAY_LOCAL_BUILD_ROOT/mobile/outputs/apk/debug/mobile-debug.apk`取。打包脚本同源，先校验版本及签名再安装。缓存目录仅存编译产物，不打进源码包。
