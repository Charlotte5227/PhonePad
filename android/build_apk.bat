@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo ============================================
echo  PhonePad アプリ(APK)をビルドします
echo ============================================

rem --- ビルドに使える Java (17〜24) を探す。25以降は新しすぎて使えない ---
set "JDK="
set "JMAJ="
call :try "%JAVA_HOME%"
if not defined JDK call :try "%ProgramFiles%\Android\Android Studio\jbr"
if not defined JDK call :try "%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JDK call :try "%LOCALAPPDATA%\PhonePad\jdk21"
if defined JDK goto :havejdk

echo.
echo ビルド用の Java 21 をダウンロードします（約200MB・初回のみ）...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; $d=Join-Path $env:LOCALAPPDATA 'PhonePad'; New-Item -ItemType Directory -Force $d | Out-Null; $z=Join-Path $d 'jdk21.zip'; Invoke-WebRequest -UseBasicParsing 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $z; $t=Join-Path $d 'jdk21_tmp'; if (Test-Path $t) { Remove-Item -Recurse -Force $t }; Expand-Archive $z $t; $in=Get-ChildItem $t | Select-Object -First 1; $dst=Join-Path $d 'jdk21'; if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }; Move-Item $in.FullName $dst; Remove-Item -Recurse -Force $t; Remove-Item $z"
if errorlevel 1 goto :jdkfail
call :try "%LOCALAPPDATA%\PhonePad\jdk21"
if not defined JDK goto :jdkfail

:havejdk
set "JAVA_HOME=%JDK%"
echo Java: %JAVA_HOME%  (バージョン %JMAJ%)

rem --- Android SDK の場所 ---
if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if not defined ANDROID_HOME (
  echo.
  echo [エラー] Android SDK が見つかりません。Android Studio を一度起動して、初回セットアップを完了させてください。
  pause
  exit /b 1
)
echo SDK : %ANDROID_HOME%

echo.
echo ビルド中です（初回は必要なファイルのダウンロードで数分かかります）...
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 (
  echo.
  echo [エラー] ビルドに失敗しました。この画面の内容を Claude に伝えてください。
  pause
  exit /b 1
)

copy /y "app\build\outputs\apk\release\app-release.apk" "..\PC\PhonePad.apk" >nul
echo.
echo ============================================
echo  完了しました: PC\PhonePad.apk
echo  PhonePad.exe を起動すると、ブラウザの画面から
echo  スマホへダウンロードできます。
echo ============================================
pause
exit /b 0

:jdkfail
echo.
echo [エラー] Java 21 を用意できませんでした。インターネット接続を確認してもう一度実行してください。
pause
exit /b 1

rem ---- 指定フォルダの Java が 17〜24 なら JDK に設定する ----
:try
set "C=%~1"
set "V="
set "M="
if "%C%"=="" exit /b 0
if not exist "%C%\bin\java.exe" exit /b 0
"%C%\bin\java.exe" -version 2> "%TEMP%\phonepad_jv.txt"
for /f "usebackq tokens=3" %%v in (`findstr /i /c:" version " "%TEMP%\phonepad_jv.txt"`) do set "V=%%~v"
if not defined V exit /b 0
for /f "delims=.-+" %%m in ("%V%") do set "M=%%m"
if not defined M exit /b 0
if %M% LSS 17 exit /b 0
if %M% GTR 24 (
  echo ※ %C% の Java %M% は新しすぎるため使いません
  exit /b 0
)
set "JDK=%C%"
set "JMAJ=%M%"
exit /b 0
