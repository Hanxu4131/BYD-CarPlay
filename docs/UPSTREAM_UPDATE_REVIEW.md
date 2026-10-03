# 上游0.2.10更新对比

检查时间：2026-10-03。上游main提交 `81a0767ac6eaaeec6c934d3270822bd0874a7493`；最新发行版 [0.2.10](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.10)。本仓库仍为已实车使用的0.2.9/test31，不在上传时整包覆盖。

| 更新 | 对本版的作用 | 建议 |
| --- | --- | --- |
| 歌曲标题、歌手、专辑、来源、播放进度及封面 | 与本版MediaSession有重叠；上游信息更完整，还有封面队列限制及旧会话丢弃 | 优先移植状态及封面模块，保留本版原车直接同步、歌手开关和持续写入工作进程 |
| 可用AirPlay端口选择、USBMUX补丁 | 对端口被占用和部分USB启动问题有帮助 | 独立审查后按需合入；当前已确认网络配置不动 |
| 通话回声消除／降噪 | 利用Android支持的音频效果 | 值得后续停车测试，不能据发行说明保证本车有效 |
| CAN／CANFD电池读数 | 拓展部分BYD车机读数兼容 | 本车协议和字段另行验证 |
| 首页仪表镜像显示开关 | 控制首页地图卡片，不是主屏分屏尺寸适配 | 可按界面需求移植 |
| 启动、蓝牙、USB及麦克风诊断 | 更容易定位问题 | 可保留内部诊断，不必重新露出私用设置里的诊断板块 |

上游Host仍对固定画布进行适配，部分放大路径重启会话，没有本版H.264 `viewAreas` / `updateViewArea` 的几何确认机制；不能替代本车已通过的不断连重新排版。车机主题取值／主动外观消息也未解决本版遇到的全部场景。没有新增真实Ultra主题或导航音量滚轮支持。

上游仍有新标题不带歌手就清空歌手的旧逻辑，移植时保留本版增量处理。歌词到原车显示的延迟不是有了封面就会自动解决。

源码定位：`CarPlayPlaybackStatus.kt`、`CarPlayMediaKeys.kt`、`NowPlayingArtworkQueue.kt`、`Iap2FileTransferReceiver.kt`；分屏及外观见`CarPlayHostActivity.kt`和`AirPlaySession.kt`。[上游发行核验记录](https://github.com/shihabal3amri/DiPlay/blob/81a0767ac6eaaeec6c934d3270822bd0874a7493/docs/RELEASE-NOTES-0.2.10.md)说明本次没有新增实车验证，合入后仍需要本车验收。
