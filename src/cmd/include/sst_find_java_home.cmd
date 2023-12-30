@REM
@REM For the copyright information for this file, please search up the
@REM directory tree for the first COPYING file.
@REM

@ECHO OFF

:sst_find_java_home

  SETLOCAL ENABLEEXTENSIONS DISABLEDELAYEDEXPANSION

  VERIFY >NUL
  CALL :sst_find_java_home_helper_1
  IF ERRORLEVEL 1 (
    EXIT /B 1
  )

  ENDLOCAL & SET JAVA_HOME=%JAVA_HOME%

EXIT /B 0

:sst_find_java_home_helper_1

  IF DEFINED JAVA_HOME (
    EXIT /B 0
  )

  VERIFY >NUL
  CALL :sst_find_java_home_helper_2 "HKLM\SOFTWARE"
  IF NOT ERRORLEVEL 1 (
    EXIT /B 0
  )

  VERIFY >NUL
  CALL :sst_find_java_home_helper_2 "HKLM\SOFTWARE\WOW6432Node"
  IF NOT ERRORLEVEL 1 (
    EXIT /B 0
  )

  EXIT /B 1

EXIT /B 0

:sst_find_java_home_helper_2

  VERIFY >NUL
  CALL :sst_find_java_home_helper_3 "%~1" "JDK"
  IF NOT ERRORLEVEL 1 (
    EXIT /B 0
  )

  VERIFY >NUL
  CALL :sst_find_java_home_helper_3 "%~1" "Java Development Kit"
  IF NOT ERRORLEVEL 1 (
    EXIT /B 0
  )

  EXIT /B 1

EXIT /B 0

:sst_find_java_home_helper_3

  SET k=%~1\JavaSoft\%~2

  SET c=REG QUERY "%k%" /v CurrentVersion
  SET r=
  VERIFY >NUL
  FOR /F "skip=1 tokens=2*" %%G IN ('%c% 2^>NUL') DO (
    SET r=%%H
  )
  IF ERRORLEVEL 1 (
    EXIT /B 1
  )
  IF "%r%" == "" (
    EXIT /B 1
  )

  SET c=REG QUERY "%k%\%r%" /v JavaHome
  SET r=
  VERIFY >NUL
  FOR /F "skip=1 tokens=2*" %%G IN ('%c% 2^>NUL') DO (
    SET r=%%H
  )
  IF ERRORLEVEL 1 (
    EXIT /B 1
  )
  IF "%r%" == "" (
    EXIT /B 1
  )

  SET JAVA_HOME=%r%

EXIT /B 0
