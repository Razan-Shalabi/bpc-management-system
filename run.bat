@echo off
rem Builds and starts the BPC desktop app.
rem Needs: JDK 17+ (set JAVA_HOME if "java" on your PATH is older), the JavaFX SDK,
rem and the MySQL Connector/J jar inside lib\
rem Set PATH_TO_FX to your JavaFX SDK "lib" folder, e.g.
rem   set PATH_TO_FX=C:\javafx-sdk-17.0.19\lib

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

if exist out rmdir /s /q out
"%JAVAC%" -encoding UTF-8 --module-path "%PATH_TO_FX%" --add-modules javafx.controls -d out src\bpc\*.java || exit /b 1
"%JAVA%" --module-path "%PATH_TO_FX%" --add-modules javafx.controls -cp "out;src;lib\*" bpc.Main
