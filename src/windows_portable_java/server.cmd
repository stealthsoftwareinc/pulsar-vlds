@REM
@REM For the copyright information for this file, please search up the
@REM directory tree for the first COPYING file.
@REM

@ECHO OFF

SETLOCAL ENABLEEXTENSIONS DISABLEDELAYEDEXPANSION

SET x=%~n0
SET x=%x:ph=PH%
SET x=%x:db=DB%
ECHO Starting the %x% server...

SET prefix=%~dp0
SET prefix=%prefix:~0,-1%

SET include=%prefix%\usr\local\share\pulsar-vlds\cmd\include

VERIFY >NUL
CALL "%include%\sst_find_java.cmd"
IF ERRORLEVEL 1 (
  ECHO Unable to find Java.
  PAUSE
  EXIT /B 0
)

SET PATH=%prefix%\usr\local\lib;%PATH%

"%JAVA%" ^
  "-Dfile.encoding=UTF-8" ^
  "-Djava.io.tmpdir=%prefix%\tmp" ^
  "-XX:+UseG1GC" ^
  %JAVAFLAGS% ^
  "-cp" "%CLASSPATH%;%prefix%\usr\local\share\java\*" ^
  "com.stealthsoftwareinc.pulsarvlds.Server" ^
  "--prefix=%prefix%" ^
  "--home=%prefix%\home" ^
  "--config=%prefix%\%~n0.cfg" ^
  "--config=%prefix%\lexicon.cfg" ^
  %* ^
 &

PAUSE
