# 源码构建与测试

## 环境

- JDK 17 或 21（本次主机使用 JDK 21）。
- Android SDK Platform 36、匹配 Build Tools、Platform-Tools；Android Studio SDK Manager 可安装。
- Gradle Wrapper 8.13 已包含，首次运行下载 Gradle 和 Maven 依赖。
- Android Gradle Plugin 8.11.1，Kotlin 2.1.0。
- minSdk 30、targetSdk 30、compileSdk 36。目标是已检查的 Android 11 国行手表侧载，非 Google Play 商店发布配置。

安装路径自行设置，不要提交 `local.properties` 或本机绝对路径。Windows PowerShell 示例：

```powershell
$env:JAVA_HOME = 'D:\Tools\jdk-21'
$env:ANDROID_HOME = 'D:\Tools\AndroidSdk'
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`。

当前 release 关闭调试并启用 R8，使用构建者本机 Android debug key 签名，便于个人侧载。**仓库不包含任何签名私钥**，自行构建的签名不保证和下载版相同。长期分发请配置独立发布密钥并安全保管；不要上传密钥或密码。Gradle 会在本机生成默认调试密钥。

## 测试

播放控制和进度数学测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

15 项，覆盖连点门控、响应分类、限流/退避与平滑进度校正。

加密配对测试，不调用 Spotify、不使用真实账号：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Setup.ps1
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

3 项，覆盖 RSA-OAEP/AES-GCM 往返、篡改拒绝和会话随机性。

离线 Android API 回归（15 项）：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

仅在测试设备/模拟器安装 `app-debug.apk` 和 `app-debug-androidTest.apk` 后运行：

```text
adb -s <测试设备序列号> shell am instrument -w local.watchremote.test/local.watchremote.ValidationInstrumentation
```

测试使用模拟连接和独立测试凭据存储，不访问真实 Spotify。安装 debug 包可能与现有 release 签名冲突；不要为测试而删除用户正式应用或授权。测试包为 `local.watchremote.test`。

## 结构

| 文件 | 职责 |
| --- | --- |
| MainActivity.kt | 页面、生命周期、串行操作、前台同步、列表返回和滑块提交 |
| SpotifyClient.kt | PKCE 凭据刷新、Web API、读取取消、错误分类、有限重试 |
| ControlPolicy.kt | 连点门控、轮询/退避与状态判断 |
| SmoothProgress.kt | 本地单调时钟推进、微小校正平滑、切歌与跳转重置 |
| WatchUi.kt | 圆屏布局、动画背景、进度弧线和触摸控件 |
| AuthStore.kt | Android Keystore/AES-GCM 授权存储 |
| PairServer.kt | 五分钟短期配对、回环监听、一次性授权接收 |
| CoverCache.kt | 有容量限制的封面缓存与取色 |

读取临时错误最多自动补试一次，控制操作不自动重发，以免重复切歌。频率受到 Retry-After 和退避控制；不通过加速轮询绕过限流。系统息屏或应用不可见停止周期请求，没有常驻服务。

## 发布与隐私

上传前仅从源码、工具、文档白名单打包。排除 `.gradle`、`.kotlin`、`build`、`.venv`、私钥、授权、日志及个人截图。APK 放 Release，不纳入 Git 历史。公开报告只包含通用型号与测试结论。
