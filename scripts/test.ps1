# scripts/test.ps1 —— 统一测试入口（T1.9 / 环境坑 #13 的固化）
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1            # 全量（含 2 个冒烟）
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test.ps1 -UnitOnly  # 跳过 *SmokeTest
# 设计：
#   1) 逐行读仓库根 .env 注入进程环境（只打印 key 名，绝不打印 value，防泄密）；
#   2) ASR_BASE_URL 默认 http://localhost:${ASR_PORT:8001}，但外部已设置时以外部为准（反例可据此造红）；
#   3) maven 定位：MAVEN_HOME / MAVEN_BIN 优先，否则本机默认路径，都没有 → exit 2；
#   4) exit code 透传 mvn 结果，末尾打印 surefire 用例数。

param(
    [switch]$UnitOnly
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

# ---- 1) 定位 Maven 与 JDK ----
$mvn = $null
if ($env:MAVEN_BIN -and (Test-Path $env:MAVEN_BIN)) { $mvn = $env:MAVEN_BIN }
elseif ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'))) { $mvn = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' }
elseif (Test-Path 'E:\tools\apache-maven-3.9.9\bin\mvn.cmd') { $mvn = 'E:\tools\apache-maven-3.9.9\bin\mvn.cmd' }
else { Write-Host '[test.ps1] 找不到 mvn.cmd：请设 MAVEN_BIN 或 MAVEN_HOME'; exit 2 }

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\tools\jdk-21.0.2' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# ---- 2) 注入 .env（只打印 key 名，不打印 value）----
$envFile = Join-Path $repo '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            Set-Item -Path "env:$($Matches[1])" -Value $Matches[2]
            Write-Host ("[test.ps1] env <- {0}" -f $Matches[1])
        }
    }
} else {
    Write-Host '[test.ps1] 未找到 .env（全默认值跑，LLM 冒烟可能跳过 key 校验失败）'
}

# ---- 3) ASR 冒烟地址：外部已设置时以外部为准（反例：故意设错端口必须红）----
if (-not $UnitOnly) {
    if (-not $env:ASR_BASE_URL) {
        $asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { '8001' }
        $env:ASR_BASE_URL = "http://localhost:$asrPort"
        Write-Host "[test.ps1] ASR_BASE_URL <- $env:ASR_BASE_URL（默认值，可用外部变量覆盖）"
    } else {
        Write-Host "[test.ps1] ASR_BASE_URL 保持外部值：$env:ASR_BASE_URL"
    }
    if (-not $env:ASR_SMOKE_AUDIO) {
        Write-Host '[test.ps1] 未设置 ASR_SMOKE_AUDIO：冒烟用例需要一个本地真实音频（绝对路径）'
        Write-Host '[test.ps1] 用法示例：  $env:ASR_SMOKE_AUDIO = "D:\datasets\meeting-notes-samples\probe.wav"; scripts\test.ps1'
        Write-Host '[test.ps1] 或跳过冒烟：  scripts\test.ps1 -UnitOnly'
        exit 3
    }
    if (-not (Test-Path $env:ASR_SMOKE_AUDIO)) {
        Write-Host "[test.ps1] 冒烟音频不存在：$env:ASR_SMOKE_AUDIO（设 ASR_SMOKE_AUDIO 指向本地真实音频）"
        exit 3
    }
}

# ---- 4) 跑测试（先清掉上次的 surefire 报告，避免汇总读到旧用例数；控制台切 UTF-8 治中文乱码）----
Push-Location (Join-Path $repo 'app')
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Remove-Item (Join-Path $repo 'app\target\surefire-reports') -Recurse -Force -ErrorAction SilentlyContinue
    if ($UnitOnly) {
        Write-Host '[test.ps1] -UnitOnly：跳过 AsrSmokeTest / LlmSmokeTest'
        & $mvn -B test "-Dtest=!AsrSmokeTest,!LlmSmokeTest"
    } else {
        & $mvn -B test
    }
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

# ---- 5) 汇总 surefire 用例数 ----
$total = 0
Get-ChildItem (Join-Path $repo 'app\target\surefire-reports\*.txt') -ErrorAction SilentlyContinue | ForEach-Object {
    $line = (Get-Content $_ | Select-String 'Tests run').Line
    if ($line -match 'Tests run: (\d+)') { $total += [int]$Matches[1] }
}
Write-Host ("[test.ps1] 用例数 {0}，mvn exit {1}" -f $total, $code)
exit $code
