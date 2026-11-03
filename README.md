# 声环 SoundRing for Spotify｜OPPO Watch X3 Spotify 遥控器

<img src="docs/assets/icon.png" width="128" alt="野兽派圆形应用图标">

**专为 Spotify 开发的第三方智能手表遥控 App，针对国行 OPPO Watch X3 适配。**

SoundRing is a Spotify remote controller for the Chinese OPPO Watch X3, built with native Android/Kotlin. Control Spotify playback on your phone or computer from your smartwatch; no on-watch audio playback.

用于**国行 OPPO Watch X3** 的 Spotify 遥控器。手表查看和控制手机、电脑上的 Spotify；声音仍由手机或电脑播放。原生 Kotlin / Android Views，不依赖 Google Play 服务、Wear OS Data Layer 或手机伴侣。

这是独立第三方项目，与 Spotify、OPPO 无隶属或认证关系。图标为 AI 生成的艺术化声波图形；Spotify 名称及相关商标属于其权利人。

## 下载与入门

在本仓库 **Releases** 下载 `SoundRing-X3-1.0.7-Windows.zip` 并完整解压。内含 APK、安装/卸载脚本、Windows 配对工具和教程。无需编译；不包含 Python、Android SDK 等第三方运行时。

首次使用请按顺序阅读 **[完整安装与授权教程](docs/SETUP.md)**。开发者见 [源码构建与测试](docs/DEVELOPMENT.md)，遇到问题见 [故障排查](docs/TROUBLESHOOTING.md)。

## 功能

- 正在播放：封面、歌名、歌手、播放设备；暂停/恢复、上一首/下一首。
- 外围粗绿色进度弧线，本地平滑推进并与接口校准。
- 音量、定位进度、随机播放、循环；按设备开放能力启用。
- 歌单分页、歌单播放、单曲播放；歌曲显示歌手。
- 播放设备查看及转移；当前设备避免重复切换。
- 逐级返回、歌单页码和滚动位置恢复；滑块松手后提交。
- 野兽派圆形图标、专辑取色动态背景、触觉与动画开关。
- 前台同步、断网恢复、限流退避、封面缓存；息屏/离开页面停止周期刷新。

不支持在手表播放音频、下载歌曲、歌词、系统表盘插件或全天后台同步。

## 兼容性与前提

| 项目 | 要求或边界 |
| --- | --- |
| 已检查的设备 | 国行 OPPO Watch X3，型号 OWW261，Android 11 / API 30，466×466，密度 320 |
| 其他设备 | 未验证，安装脚本默认仅接受 OWW261 |
| 账号 | 自己的 Spotify Developer 应用、Premium；开发模式用户需符合 Spotify 当前准入规则 |
| 播放端 | 手机或电脑上打开 Spotify，使用相同账号 |
| 配置电脑 | Windows、Python 3.11+、ADB、浏览器；配置后无需常驻 |
| 网络 | 电脑授权、手表直连 API 都必须可达；电脑能联网不代表手表能联网 |

开发模式 API 的能力随 Spotify 政策变化；部分非本人拥有或协作的歌单内容不可读。参见 [官方迁移说明](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)及[配额模式](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)。

## 仓库包含什么

- `app/`：Android 应用、单元测试、离线 Android 网络回归测试。
- `gradlew`、`gradlew.bat`、`gradle/wrapper/`：Gradle Wrapper。
- `tools/configure.py`：官方 OAuth PKCE 登录及本地加密配对。
- `tools/Setup.ps1`、`启动配置.cmd`、`Install.ps1`、`Uninstall.ps1`：Windows 配置与安装工具。
- `tools/requirements.txt`：固定 Python 配对依赖。
- `tests/test_pairing.py`：加密配对主机测试，无需真实账号。
- `docs/`：教程、架构、安全说明、兼容性记录、第三方资源说明。

## 隐私与安全

不内置任何人的 Client ID、Client Secret、令牌或账号。配置时自行填写 Client ID，**不需要 Client Secret**。令牌通过短期本地 ADB 通道加密传到手表，使用 Android Keystore 保护；退出登录清除授权及封面缓存。仓库没有私钥、手表序列号、个人截图、播放记录或本机路径。详见 [安全与隐私](SECURITY.md)。

构建/真机证据及未验证项见 [验证记录](docs/VALIDATION.md)。源码采用 MIT 许可；第三方及图标说明见 [NOTICE](NOTICE.md)。
