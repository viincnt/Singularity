@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b %errorlevel%

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
if not exist native\build mkdir native\build

cl /nologo /std:c++20 /EHsc /MD /LD ^
  /I"%JAVA_HOME%\include" ^
  /I"%JAVA_HOME%\include\win32" ^
  /I"third_party\vulkan-headers\include" ^
  /I"third_party\dlss\include" ^
  /Fo:native\build\singularity_native.obj ^
  native\src\main\cpp\singularity_native.cpp ^
  /link /OUT:native\build\singularity_native.dll ^
  /IMPLIB:native\build\singularity_native.lib ^
  /LIBPATH:third_party\dlss\lib\Windows_x86_64\x64 nvsdk_ngx_d.lib Advapi32.lib User32.lib
if errorlevel 1 exit /b %errorlevel%

copy /Y third_party\dlss\lib\Windows_x86_64\rel\nvngx_dlss.dll native\build\nvngx_dlss.dll >nul
