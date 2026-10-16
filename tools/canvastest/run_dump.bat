@echo off
setlocal
rem ---------------------------------------------------------------------------
rem CanvasDump: render the offscreen ScreenCanvas image and save it as PNG.
rem
rem Why the JDK is hunted for: the mod targets Java 8 bytecode, but javac itself
rem must be JDK 9 or newer (--release was added in JDK 9). This machine also has
rem a JDK 8 on PATH, so simply taking the first javac found would be wrong.
rem
rem Two cmd.exe traps this file works around:
rem  1) for /d with a quoted wildcard path containing a space silently DROPS the
rem     space, turning the path into C:jdk-... -- use  dir /b /ad  instead.
rem  2) .bat must be ASCII + CRLF; cmd reads it with the OEM codepage, so
rem     non-ASCII comments get mangled enough to break parsing.
rem ---------------------------------------------------------------------------
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%\bin"
if not defined JDK for /f "delims=" %%D in ('dir /b /ad /on "%ProgramFiles%\Microsoft\jdk-*" 2^>nul') do set "JDK=%ProgramFiles%\Microsoft\%%D\bin"
if not defined JDK for /f "delims=" %%D in ('dir /b /ad /on "%ProgramFiles%\Java\jdk*" 2^>nul') do set "JDK=%ProgramFiles%\Java\%%D\bin"
if not defined JDK for /f "delims=" %%D in ('dir /b /ad /on "%ProgramFiles%\Eclipse Adoptium\jdk*" 2^>nul') do set "JDK=%ProgramFiles%\Eclipse Adoptium\%%D\bin"
if not defined JDK for /f "delims=" %%I in ('where javac 2^>nul') do if not defined JDK set "JDK=%%~dpI"
if not defined JDK (
  echo [run_dump] JDK not found. Set JAVA_HOME to a JDK 9 or newer installation.
  exit /b 1
)
echo [run_dump] JDK = %JDK%

set GRADLE=%USERPROFILE%\.gradle
set FRJ=%GRADLE%\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12\forge-1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12-recomp.jar
set NETTY=%GRADLE%\caches\forge_gradle\maven_downloader\io\netty\netty-all\4.1.9.Final\netty-all-4.1.9.Final.jar
set CL3=%GRADLE%\caches\forge_gradle\maven_downloader\org\apache\commons\commons-lang3\3.5\commons-lang3-3.5.jar
set CP=build\sourcesSets\main;%FRJ%;%NETTY%;%CL3%
if not exist tools\canvastest\out mkdir tools\canvastest\out
"%JDK%\javac.exe" --release 8 -encoding UTF-8 -nowarn -cp "%CP%" -d tools\canvastest\out tools\canvastest\CanvasDump.java
if errorlevel 1 exit /b 1
"%JDK%\java.exe" -Dfile.encoding=UTF-8 -DdumpDir=tools\canvastest\dump -cp "tools\canvastest\out;%CP%" CanvasDump
