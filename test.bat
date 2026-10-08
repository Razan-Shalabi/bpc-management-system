@echo off
rem Runs the automated tests against the local "bpc" database.
rem   SmokeTest - opens every screen (staff + customer) and reports any SQL error or exception.
rem   FlowTest  - places/cancels orders, receives a PO, issues invoices, and checks the DB.
rem   EdgeTest  - 120+ edge cases: invalid input, limits, permissions, stock and payment rules.
rem The database is reloaded from database\bpc.sql before each suite and again at the end,
rem so you are left with clean demo data.
rem Needs PATH_TO_FX (JavaFX lib folder) and the mysql client: set MYSQL_EXE if mysql is not on PATH, e.g.
rem   set MYSQL_EXE=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe

if "%PATH_TO_FX%"=="" (
  echo Please set PATH_TO_FX to your JavaFX SDK lib folder first.
  exit /b 1
)

set "JAVA=java"
set "JAVAC=javac"
if not "%JAVA_HOME%"=="" (
  set "JAVA=%JAVA_HOME%\bin\java"
  set "JAVAC=%JAVA_HOME%\bin\javac"
)
set "MYSQL=mysql"
if not "%MYSQL_EXE%"=="" set "MYSQL=%MYSQL_EXE%"
set "RUN="%JAVA%" --module-path "%PATH_TO_FX%" --add-modules javafx.controls -cp "test-out;src;lib\*""

if exist test-out rmdir /s /q test-out
"%JAVAC%" -encoding UTF-8 --module-path "%PATH_TO_FX%" --add-modules javafx.controls -d test-out src\bpc\*.java test\bpc\*.java || exit /b 1

set FAILED=0
for %%T in (SmokeTest FlowTest EdgeTest) do (
  call :resetdb || exit /b 1
  echo ===== %%T
  %RUN% bpc.%%T || set FAILED=1
)
call :resetdb
if "%FAILED%"=="1" (echo SOME TESTS FAILED & exit /b 1)
echo ALL TESTS PASSED
exit /b 0

:resetdb
rem Reload schema + demo data as the bpc user (the user/grant lines need root and are skipped).
findstr /v /i /b /c:"drop user" /c:"create user" /c:"grant " /c:"flush " database\bpc.sql | "%MYSQL%" -ubpc -p1234 2>nul
exit /b %errorlevel%
