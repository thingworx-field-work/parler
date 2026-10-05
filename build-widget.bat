@echo off
setlocal
set ROOT=%~dp0
cd /d "%ROOT%parler-ui"
call npm run build:tw
if errorlevel 1 exit /b 1
cd /d "%ROOT%parler-ui-widget"
call npm run sync
if errorlevel 1 exit /b 1
node "%ROOT%twx-wc-sdk-utility\bin\cli.js"
exit /b %ERRORLEVEL%
