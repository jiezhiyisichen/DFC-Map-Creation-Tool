@echo off
setlocal
rem 用 JAVA_HOME 指向的 JDK；没设就退回 PATH 上的 javac 所在目录
if defined JAVA_HOME (
  set "JDK=%JAVA_HOME%\bin"
) else (
  for %%I in (javac.exe) do set "JDK=%%~dpI"
)
set GRADLE=%USERPROFILE%\.gradle
set FRJ=%GRADLE%\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12\forge-1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12-recomp.jar
set NETTY=%GRADLE%\caches\forge_gradle\maven_downloader\io\netty\netty-all\4.1.9.Final\netty-all-4.1.9.Final.jar
set CL3=%GRADLE%\caches\forge_gradle\maven_downloader\org\apache\commons\commons-lang3\3.5\commons-lang3-3.5.jar
set CP=build\sourcesSets\main;%FRJ%;%NETTY%;%CL3%
if not exist tools\canvastest\out mkdir tools\canvastest\out
"%JDK%\javac.exe" --release 8 -encoding UTF-8 -nowarn -cp "%CP%" -d tools\canvastest\out tools\canvastest\CanvasDump.java
if errorlevel 1 exit /b 1
"%JDK%\java.exe" -Dfile.encoding=UTF-8 -DdumpDir=tools\canvastest\dump -cp "tools\canvastest\out;%CP%" CanvasDump
