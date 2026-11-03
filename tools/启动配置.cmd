@echo off
chcp 65001 >nul
set "PY=%~dp0..\.venv\Scripts\python.exe"
if not exist "%PY%" (
  echo 请先按教程运行 tools\Setup.ps1 创建配置环境。
  pause
  exit /b 1
)
"%PY%" "%~dp0configure.py"
pause
