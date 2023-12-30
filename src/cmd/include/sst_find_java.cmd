@REM
@REM For the copyright information for this file, please search up the
@REM directory tree for the first COPYING file.
@REM

@ECHO OFF

:sst_find_java

  SETLOCAL ENABLEEXTENSIONS DISABLEDELAYEDEXPANSION

  VERIFY >NUL
  CALL :sst_find_java_helper_1
  IF ERRORLEVEL 1 (
    EXIT /B 1
  )

  ENDLOCAL & SET JAVA=%JAVA%

EXIT /B 0

:sst_find_java_helper_1

  IF DEFINED JAVA (
    EXIT /B 0
  )

  VERIFY >NUL
  WHERE java >NUL 2>NUL
  IF NOT ERRORLEVEL 1 (
    SET JAVA=java
    EXIT /B 0
  )

  VERIFY >NUL
  CALL "%~dp0\sst_find_java_home.cmd"
  IF ERRORLEVEL 1 (
    EXIT /B 1
  )

  SET JAVA=%JAVA_HOME%\bin\java

EXIT /B 0
