# Cold start measurement. v0.1 acceptance target: < 1500 ms
#
# Runs with an exploded classpath, which is the deployment mode v0.1 supports.
# A fat jar requires template precompilation (jaravel-maven-plugin, planned v0.9).
#
# NOTE: ASCII-only output on purpose -- Windows PowerShell 5.1 reads .ps1 using
# the ANSI codepage, so non-ASCII text here breaks the parser.
#
# Usage: powershell -File coldstart.ps1 [-Runs 4]
param([int]$Runs = 4)

Set-Location $PSScriptRoot

Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Start-Sleep -Seconds 2

Write-Output "preparing dependencies..."
& mvn -o -q dependency:copy-dependencies "-DoutputDirectory=target/deps" "-DincludeScope=runtime" 2>&1 |
    Select-String "ERROR" | Select-Object -First 3
& mvn -o -q compile 2>&1 | Select-String "ERROR" | Select-Object -First 3

$cp = "target/classes;target/deps/*"
$dbFile = Join-Path $PSScriptRoot "storage\todo.db"
$times = @()

for ($run = 1; $run -le $Runs; $run++) {
    if (Test-Path $dbFile) { Remove-Item -LiteralPath $dbFile -Force -ErrorAction SilentlyContinue }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $p = Start-Process -FilePath "java" -ArgumentList "-cp", $cp, "app.Application" `
            -PassThru -RedirectStandardOutput "$env:TEMP\cs$run.log" `
            -RedirectStandardError "$env:TEMP\cs${run}e.log" -WindowStyle Hidden

    $ready = $false
    while ($sw.Elapsed.TotalSeconds -lt 25) {
        try {
            if ((Invoke-WebRequest http://localhost:8000/ -TimeoutSec 1 -UseBasicParsing).StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 30
        }
    }
    $sw.Stop()

    if ($ready) {
        $times += $sw.Elapsed.TotalMilliseconds
        Write-Output ("  run {0}: {1:N0} ms  (to first 200, includes first template compile)" -f $run, $sw.Elapsed.TotalMilliseconds)
    } else {
        Write-Output ("  run {0}: NOT READY" -f $run)
        Get-Content "$env:TEMP\cs$run.log" -Tail 10 -ErrorAction SilentlyContinue
        Get-Content "$env:TEMP\cs${run}e.log" -Tail 6 -ErrorAction SilentlyContinue
    }

    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

if ($times.Count) {
    $min = ($times | Measure-Object -Minimum).Minimum
    $avg = ($times | Measure-Object -Average).Average
    $verdict = "OVER"
    if ($min -lt 1500) { $verdict = "PASS" }
    Write-Output ""
    Write-Output ("  fastest {0:N0} ms   average {1:N0} ms   target 1500 ms   => {2}" -f $min, $avg, $verdict)
}
