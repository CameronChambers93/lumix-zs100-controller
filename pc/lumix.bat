@echo off
rem Starts the Lumix web app on this PC. Needs Python 3.9+ (python.org, tick "Add to PATH").
rem Photos are saved to %LUMIX_LIBRARY% (default: Pictures\Lumix). Open http://localhost:8420
cd /d "%~dp0"
if "%LUMIX_LIBRARY%"=="" set "LUMIX_LIBRARY=%USERPROFILE%\Pictures\Lumix"
if not exist "%LUMIX_LIBRARY%" mkdir "%LUMIX_LIBRARY%"
set "LUMIX_DATA=%~dp0data"
python app\server.py
pause
