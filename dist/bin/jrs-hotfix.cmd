@echo off
rem jrs-hotfix launcher (Windows). Uses the bundled runtime; no JDK or JAVA_HOME needed.
rem After Ctrl-C, cmd.exe may ask "Terminate batch job (Y/N)?" once jrs-hotfix has already
rem cancelled and exited 5; the run is finished either way. A scheduler that needs exit code 5
rem should run runtime\bin\java.exe -jar lib\jrs-hotfix.jar directly.
setlocal
"%~dp0..\runtime\bin\java.exe" -jar "%~dp0..\lib\jrs-hotfix.jar" %*
exit /b %ERRORLEVEL%
