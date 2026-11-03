$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$python = Get-Command python -ErrorAction SilentlyContinue
if (!$python) { throw '请先安装 Python 3.11 或更新版本，并启用 Add Python to PATH。https://www.python.org/downloads/windows/' }
& $python.Source -c "import sys; assert sys.version_info >= (3,11), 'Python 3.11+ required'"
if ($LASTEXITCODE -ne 0) { throw 'Python 版本不满足要求。' }
& $python.Source -m venv (Join-Path $root '.venv')
if ($LASTEXITCODE -ne 0) { throw '创建虚拟环境失败。' }
& (Join-Path $root '.venv/Scripts/python.exe') -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw '安装依赖失败，请检查电脑网络后重试。' }
Write-Output '配置环境已就绪，请双击 tools/启动配置.cmd。'
