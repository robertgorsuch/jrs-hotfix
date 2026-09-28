@echo off
rem Runs Maven with the JDK 21 this project requires. Usage: scripts\mvn.cmd verify
setlocal
if "%JRSHOTFIX_JDK%"=="" set "JRSHOTFIX_JDK=C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot"
if not exist "%JRSHOTFIX_JDK%\bin\java.exe" ( echo JDK 21 not found at "%JRSHOTFIX_JDK%". Set JRSHOTFIX_JDK. 1>&2 & exit /b 1 )
set "JAVA_HOME=%JRSHOTFIX_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0..\mvnw.cmd" -B %*
