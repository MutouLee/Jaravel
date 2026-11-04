@echo off
@rem jaravel 包装脚本(Windows)—— 由 `jaravel wrapper:install` 生成,类比 mvnw.cmd。
@rem
@rem 用途:不用全局安装 jaravel,项目里直接 jaravelw make:model Post
@rem 版本钉在下面那一行 —— 它跟着项目走。
@rem
@rem ⚠ 这个文件与 jaravelw(sh 版)是**同一件事的两份实现**,会各自腐坏。
@rem    有一条测试核对两边的版本号、主类名与缓存文件名一致。
setlocal

set JARAVEL_VERSION=0.1.0-SNAPSHOT

@rem 缓存跟着脚本走,而不是跟着当前目录
set SCRIPT_DIR=%~dp0
set CACHE_DIR=%SCRIPT_DIR%.jaravel
set CLASSPATH_FILE=%CACHE_DIR%\cli-classpath
set VERSION_FILE=%CACHE_DIR%\cli-version
set MARKER_POM=%CACHE_DIR%\cli.pom.xml

set NEED_RESOLVE=
if not exist "%CLASSPATH_FILE%" set NEED_RESOLVE=1
if not exist "%VERSION_FILE%" set NEED_RESOLVE=1
if not defined NEED_RESOLVE (
  set CACHED_VERSION=
  for /f "usebackq delims=" %%v in ("%VERSION_FILE%") do set CACHED_VERSION=%%v
  if not "%CACHED_VERSION%"=="%JARAVEL_VERSION%" set NEED_RESOLVE=1
)

if defined NEED_RESOLVE (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

  @rem 标记 pom:只有一条依赖,交给 Maven 去解析(与下载)。
  @rem cmd 里 < > 要用 ^ 转义
  >  "%MARKER_POM%" echo ^<?xml version="1.0" encoding="UTF-8"?^>
  >> "%MARKER_POM%" echo ^<project xmlns="http://maven.apache.org/POM/4.0.0"^>
  >> "%MARKER_POM%" echo   ^<modelVersion^>4.0.0^</modelVersion^>
  >> "%MARKER_POM%" echo   ^<groupId^>io.jaravel.wrapper^</groupId^>
  >> "%MARKER_POM%" echo   ^<artifactId^>jaravel-cli-classpath^</artifactId^>
  >> "%MARKER_POM%" echo   ^<version^>1^</version^>
  >> "%MARKER_POM%" echo   ^<packaging^>pom^</packaging^>
  >> "%MARKER_POM%" echo   ^<dependencies^>
  >> "%MARKER_POM%" echo     ^<dependency^>
  >> "%MARKER_POM%" echo       ^<groupId^>io.jaravel^</groupId^>
  >> "%MARKER_POM%" echo       ^<artifactId^>jaravel-cli^</artifactId^>
  >> "%MARKER_POM%" echo       ^<version^>%JARAVEL_VERSION%^</version^>
  >> "%MARKER_POM%" echo     ^</dependency^>
  >> "%MARKER_POM%" echo   ^</dependencies^>
  >> "%MARKER_POM%" echo ^</project^>

  echo [jaravelw] 解析 jaravel-cli %JARAVEL_VERSION% 的依赖^(结果会缓存^)… 1>&2

  set MVN=mvn
  if exist "%SCRIPT_DIR%mvnw.cmd" set MVN=%SCRIPT_DIR%mvnw.cmd

  call "%MVN%" -q -f "%MARKER_POM%" dependency:build-classpath -Dmdep.outputFile="%CLASSPATH_FILE%" -Dmdep.includeScope=runtime
  if errorlevel 1 (
    echo [jaravelw] 解析依赖失败 —— 没法知道用什么 classpath 启动 jaravel。 1>&2
    exit /b 1
  )
  > "%VERSION_FILE%" echo %JARAVEL_VERSION%
)

set JAVACMD=java
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set JAVACMD=%JAVA_HOME%\bin\java.exe

@rem ★ 这里**不**指定输出编码。
@rem cmd.exe 下 JDK 自己探到的控制台编码就是对的 ——
@rem 强塞 UTF-8 反而会把中文打成乱码。
@rem (sh 版要处理这件事,因为 Git Bash 的终端是 UTF-8
@rem  而 JDK 探到的是 Windows 的代码页。)
@rem 需要时用 JARAVEL_JVM_ARGS=-Dstdout.encoding=UTF-8 覆盖。

for /f "usebackq delims=" %%c in ("%CLASSPATH_FILE%") do set JARAVEL_CP=%%c

"%JAVACMD%" %JARAVEL_JVM_ARGS% -cp "%JARAVEL_CP%" io.jaravel.cli.Jaravel %*
exit /b %ERRORLEVEL%
