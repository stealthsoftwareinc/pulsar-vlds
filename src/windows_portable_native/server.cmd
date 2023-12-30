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

SET PATH=%prefix%\usr\local\lib;%PATH%

"%prefix%\usr\local\bin\pulsar-vlds-server.exe" ^
  "-Dfile.encoding=UTF-8" ^
  "-Djava.io.tmpdir=%prefix%\tmp" ^
  "--prefix=%prefix%" ^
  "--home=%prefix%\home" ^
  "--config=%prefix%\%~n0.cfg" ^
  "--config=%prefix%\lexicon.cfg" ^
  %* ^
 &

PAUSE
