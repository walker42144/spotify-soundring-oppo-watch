param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = '',
    [string]$ApkPath = ''
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $AdbPath)) { throw '找不到 adb.exe，请使用 -AdbPath 指定路径。' }
$apk = if ($ApkPath) { $ApkPath } else { Join-Path (Split-Path $PSScriptRoot -Parent) 'SoundRing-X3-1.0.7.apk' }
if (!(Test-Path -LiteralPath $apk)) { throw '缺少安装包。' }
$devices = @(& $AdbPath devices)
$ready = @($devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (!$Serial) {
    if ($ready.Count -ne 1) { throw '请只连接一台手表；多设备时用 -Serial 指定手表。' }
    $Serial = $ready[0]
}
if ($ready -notcontains $Serial) { throw '目标设备未连接或未授权。' }
$model = (& $AdbPath -s $Serial shell getprop ro.product.model | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $model -ne 'OWW261') { throw "目标型号为 $model，当前安装脚本只接受已验证的 OWW261。" }
Write-Output "目标：$model / $Serial。正在保留数据覆盖安装。"
& $AdbPath -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw '安装失败；没有自动卸载或清除数据。' }
& $AdbPath -s $Serial shell am start -n local.watchremote/.MainActivity
if ($LASTEXITCODE -ne 0) { throw '安装成功，但启动失败，请从手表应用列表打开。' }
Write-Output '安装并启动完成。'
