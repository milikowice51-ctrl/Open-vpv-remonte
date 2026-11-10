@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 controller.py
) else (
    python controller.py
)
if errorlevel 1 pause
