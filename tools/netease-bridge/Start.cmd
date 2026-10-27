@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Please install Node.js 22 or newer, then run this file again.
  pause
  exit /b 1
)
node -e "if (Number(process.versions.node.split('.')[0]) < 22) process.exit(1)"
if errorlevel 1 (
  echo Node.js 22 or newer is required. Please update Node.js.
  pause
  exit /b 1
)
if not exist node_modules\@music163\ncm-cli\dist\index.js (
  call npm ci --ignore-scripts --no-audit --no-fund
  if errorlevel 1 (pause & exit /b 1)
)
node server.mjs --setup
pause
