@echo off
setlocal
set "GRADLE_VERSION=9.5.0"
set "GRADLE_HOME=%~dp0.gradle\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%~dp0.gradle\gradle-%GRADLE_VERSION%-bin.zip"
if exist "%GRADLE_HOME%\bin\gradle.bat" goto run
if not exist "%~dp0.gradle" mkdir "%~dp0.gradle"
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_ZIP%'"
if errorlevel 1 (
  echo Failed to download Gradle. Check your internet connection.
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%~dp0.gradle' -Force"
if errorlevel 1 (
  echo Failed to extract Gradle.
  exit /b 1
)
del /q "%GRADLE_ZIP%" >nul 2>&1
:run
call "%GRADLE_HOME%\bin\gradle.bat" %*
endlocal
