@echo off
REM 一键启动服务端（Windows）。首次运行会自动生成 token。
cd /d "%~dp0..\server"
if not exist "..\data" mkdir "..\data"
if exist "..\deploy\sources.yaml" (
  python -m hub.main --port 8787 --data "..\data" --config "..\deploy\sources.yaml"
) else (
  python -m hub.main --port 8787 --data "..\data"
)
pause
