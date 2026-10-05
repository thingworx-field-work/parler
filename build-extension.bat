@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0parler-agent"

set "COUNT=0"
if exist "twx-lib\all\" (
  for %%F in ("twx-lib\all\*.jar") do set /a COUNT+=1
)

set "EXTRA=-PuseLocalTwxLib=false"
if !COUNT! GTR 10 (
  set "EXTRA=-PuseLocalTwxLib=true"
  echo build-extension.bat: twx-lib/all has !COUNT! jars - using -PuseLocalTwxLib=true
)

call gradlew.bat assemble --no-daemon !EXTRA!
exit /b %ERRORLEVEL%
