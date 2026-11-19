# 在一个干净容器里跑 run.sh。
#
# 干净 = 空 ~/.m2、空工作目录、没有本机任何缓存;挂进去的只有这个
# 目录(为了拿到 run.sh 和取回 out/)。仓库由容器自己 git clone ——
# 验收的是文档写的那条路,不是本机工作区。
#
#   pwsh verification/v10-quickstart-cold/run-docker.ps1
#   pwsh verification/v10-quickstart-cold/run-docker.ps1 -Image maven:3.9-eclipse-temurin-21
# -SeedM2 <path>:把一份已有的 Maven 本地仓库拷进容器(拷贝不计时),
# 于是计时里**不含第三方依赖下载**。两种跑法都有用:
#   不带 -SeedM2 —— 真·全冷,数字里含下载,强依赖当时的带宽;
#   带   -SeedM2 —— 隔离出构建/生成/测试本身的时间,与带宽无关。
# 拷进去之后 io/jaravel 会被删掉 —— 框架必须从这次 clone 真的构建出来,
# 否则就是在用本机上一次的产物冒充。
param(
    [string]$Image  = "maven:3.9-eclipse-temurin-21",
    [string]$Repo   = "https://github.com/MutouLee/Jaravel.git",
    [string]$SeedM2 = "",
    [string]$Name   = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$outDir = if ($SeedM2) { "out-seeded" } else { "out" }
$dockerArgs = @("run", "--rm", "-v", "${here}:/mnt/script",
          "-e", "REPO=$Repo", "-e", "ROOT=/work", "-e", "OUTDIR=$outDir",
          "-w", "/work")
if ($SeedM2) { $dockerArgs += @("-v", "${SeedM2}:/mnt/m2:ro", "-e", "SEED_M2=1") }
if ($Name)   { $dockerArgs += @("--name", $Name) }
$dockerArgs += @($Image, "bash", "-lc", @'
set -e
export DEBIAN_FRONTEND=noninteractive
command -v git  >/dev/null || (apt-get update -qq && apt-get install -y -qq git)
command -v curl >/dev/null || (apt-get update -qq && apt-get install -y -qq curl)
command -v pkill >/dev/null || (apt-get update -qq && apt-get install -y -qq procps)
if [ "${SEED_M2:-}" = "1" ]; then
  echo "seeding ~/.m2 from /mnt/m2 (not timed) ..."
  mkdir -p /root/.m2/repository
  cp -a /mnt/m2/. /root/.m2/repository/
  rm -rf /root/.m2/repository/io/jaravel
  du -sh /root/.m2/repository
fi
cp /mnt/script/run.sh /tmp/run.sh && chmod +x /tmp/run.sh
set +e
bash /tmp/run.sh
rc=$?
mkdir -p "/mnt/script/${OUTDIR}" && cp -r /work/out/. "/mnt/script/${OUTDIR}/" 2>/dev/null
exit $rc
'@)

& docker $dockerArgs

exit $LASTEXITCODE
