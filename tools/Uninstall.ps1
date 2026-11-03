param([Parameter(Mandatory=$true)][string]$Serial, [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $AdbPath)) { throw '找不到 adb.exe。' }
$model = (& $AdbPath -s $Serial shell getprop ro.product.model | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'OWW261') { throw '仅接受已验证的 OWW261 手表。' }
if ((Read-Host '卸载将删除授权和应用数据；输入 UNINSTALL 确认') -cne 'UNINSTALL') { return }
& $AdbPath -s $Serial uninstall local.watchremote
if ($LASTEXITCODE -ne 0) { throw '卸载失败。' }
