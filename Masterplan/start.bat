@echo off
if "%~1"=="" (
    echo Usage: .\start.bat ISSUE_ID1 ISSUE_ID2 ...
    echo Example: .\start.bat COL12 IMG2
    exit /b 1
)
echo Starting workflow session...
py tools\cb_workflow.py start %*
