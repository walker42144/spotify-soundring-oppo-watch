# Windows 安装与首次授权

## 1. 准备工具

从本仓库 Releases 下载 Windows 发布包，完整解压到任意工作目录，例如 `D:\SoundRing`。不要直接在压缩包内双击脚本。

必须安装：

| 工具 | 官方来源 | 用途 |
| --- | --- | --- |
| Python 3.11 或更新版本（64 位） | https://www.python.org/downloads/windows/ | 本地配对工具；安装时勾选 Add Python to PATH |
| Android SDK Platform-Tools for Windows | https://developer.android.com/tools/releases/platform-tools | USB 调试安装及本地配对通道；下载解压，保留整个 platform-tools 文件夹 |
| 浏览器 | 现有浏览器即可 | 打开 Spotify 官方登录页 |

**普通用户无需安装 Android Studio、Java、Git 或 Gradle。** 如已有 Android Studio，其 SDK 中的 platform-tools 可直接使用。仓库上传了项目自有工具及依赖清单；上述第三方工具请从官方取得，未捆绑不明来源的安装器。Python 依赖由下一步安装。

## 2. 建立 USB 调试连接

1. 在手表“设置 → 关于”找到版本信息，连续点击版本号以开启开发者选项；菜单名称可能随固件不同。
2. 返回设置，在开发者选项开启 **USB 调试**。只有 USB 调试也可以完成全部配置，不需要无线调试。
3. 使用支持数据传输的连接方式连接电脑；仅充电线不能建立调试连接。
4. 若手表弹出调试授权，确认是自己的电脑后允许。
5. 在发布包解压目录打开 PowerShell。以下示例假设 ADB 解压在 `D:\Tools\platform-tools`，请替换成实际路径：

```powershell
& 'D:\Tools\platform-tools\adb.exe' devices
```

成功信号：列表中仅有这一台手表，状态为 `device`。序列号无需分享。`unauthorized` 表示需在手表确认；空列表请检查线材、接口和驱动。配对工具要求仅连接一台已授权的调试设备，先断开其他手机。

## 3. 安装 APK

在解压目录执行，替换 ADB 路径：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Install.ps1 -AdbPath 'D:\Tools\platform-tools\adb.exe'
```

成功信号：显示“安装并启动完成”，手表打开“声环”。脚本只接受 OWW261，使用覆盖安装保留数据，不自动卸载、不清除数据。不需要修改系统全局执行策略。

若使用源码目录，先下载 APK 到仓库根目录，或者用 `-ApkPath '实际APK路径'` 指定。更新时须使用同一签名来源的 APK。遇到签名不一致请先阅读故障排查，不能通过覆盖解决。

## 4. 创建自己的 Spotify Developer 应用

1. 在电脑打开 https://developer.spotify.com/dashboard ，登录使用的 Spotify 账号。
2. 创建或打开自己的开发者应用；若平台暂不允许创建，先解决开发者平台准入问题，本工具不能绕过。
3. 如要求选择 API，选择 **Web API**。
4. 在应用设置中添加下面这个 **Redirect URI**，逐字保持一致并保存：

```text
http://127.0.0.1:8765/callback
```

不要替换成 `localhost`，不要加尾部 `/`，不要填手表 IP。

5. 保存后取得 **Client ID**。Client Secret 不需要输入，也不应分享。
6. 开发模式下，确保实际登录用户有权限使用此应用；如使用不同账号，按 Dashboard 的用户管理界面将其加入允许名单。Premium 与用户数量等限制以 Spotify 当前官方规则为准。

授权原理：[PKCE 官方说明](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)。回调规则：[Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)。

## 5. 安装配置工具依赖

在解压目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Setup.ps1
```

成功信号：“配置环境已就绪”。脚本在本目录创建 `.venv`，从 Python 包源安装 `requirements.txt` 固定版本，不修改系统网络设置，不安装 ADB。若 `python` 打开 Microsoft Store，请安装正式 Python 并重新打开终端。

## 6. 配对与登录

1. 手表打开“声环 → 更多 → 电脑配对”，保持应用可见。
2. 手表显示 8 位配对码，有效期 5 分钟。
3. 电脑双击 `tools/启动配置.cmd`。
4. 按提示填写自己的 Client ID；若未找到 ADB，再填写 `adb.exe` 的完整路径。
5. 输入手表的 8 位配对码。**输入时不显示字符属于正常现象**，输完回车。
6. 工具打开 Spotify 官方登录页；在 4 分钟内登录、检查权限并授权。请确认是自己要控制的账号。
7. 浏览器显示“已收到授权，请返回配置工具”。终端应显示“配置成功”，手表返回播放页。

若超时，重新在手表点“电脑配对”取得新码，然后重跑工具。工具仅使用本机 `127.0.0.1` 回调及 ADB 转发，不用开放局域网端口；不要将配对码、授权回调 URL 或令牌发到 issue。

## 7. 验证播放控制

1. 手机或电脑登录同一账号，打开 Spotify 并播放歌曲。
2. 手表连接能访问 Spotify 的网络，打开声环，等待显示歌名。
3. 点播放/暂停，确认实际播放端变化；再测试切歌。
4. 点顶部设备名称选择播放设备。没有设备时，先打开手机/电脑 Spotify，再刷新设备。

配置成功后电脑无需常驻，USB 可以断开。手表直接请求 Spotify Web API；网络不通时不会借电脑自动中转。

## 8. 日常操作

- 主页下方：歌单、调节、更多；顶部可切换设备。
- 圆周弧线显示进度，触摸定位在“调节”页。
- 进入歌单可全部播放或选曲，单曲确认成功后回主页。返回键逐级返回并保留来源列表位置。
- 音量/进度拖动时先显示预览，松手后合并提交。请求未完成时避免重复点按。
- 表冠默认关闭；更多中开启，调节页选择滚动、音量或进度。真实方向和兼容性需自行检查，所有功能保留触摸入口。
- 更多中可关闭动态背景、界面动画、触觉，或将同步速度改为省电。
- 默认播放时约每秒请求状态；显示延迟仍取决于网络、Spotify 接口及播放端，不保证即时推送。
- 息屏或离开应用停止周期同步，重新打开刷新。

## 9. 更新、退出和卸载

同签名版本执行安装步骤即可更新，通常保留登录。自行构建与发布 APK 的签名可能不同，不要直接卸载以解决更新错误，否则会丢失授权。

退出：手表“更多 → 退出登录”，确认清除授权和封面缓存。需要撤销服务器权限时，还应前往 Spotify 账号的应用管理中移除该应用访问权限。

卸载可用手表系统设置，也可在确认要删除本地授权后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Uninstall.ps1 -AdbPath 'D:\Tools\platform-tools\adb.exe' -Serial '你的手表序列号'
```

脚本会要求输入 `UNINSTALL`；删除的仅为 `local.watchremote` 应用，不恢复或更改系统设置。使用完毕可按个人需要关闭 USB 调试。
