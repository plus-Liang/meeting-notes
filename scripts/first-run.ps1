# scripts/first-run.ps1 ----- 陌生人一键起全栈（T4.9）
# 用法（仓库根执行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\first-run.ps1
# 行为：无 .env 则从 .env.example 复制 -> 无模型则下载 -> docker compose up -d --build ->
#       轮询到 5 个服务 healthy（超时 10 分钟）-> 打印 Web 地址。
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# 1) .env
if (-not (Test-Path (Join-Path $repo '.env'))) {
    Copy-Item (Join-Path $repo '.env.example') (Join-Path $repo '.env')
    Write-Host '[first-run] 已从 .env.example 生成 .env。部署后请修改：API_TOKEN / MINIO 密钥（S3_ACCESS_KEY、S3_SECRET_KEY）/ 数据库密码（POSTGRES_PASSWORD）等敏感项。'
}

# 2) 模型
$envFile = Join-Path $repo '.env'
$modelLine = (Get-Content $envFile | Where-Object { $_ -match '^\s*ASR_MODEL_NAME\s*=' } | Select-Object -First 1)
$modelName = if ($modelLine) { ($modelLine -split '=', 2)[1].Trim() } else { 'small' }
$snapDir = Join-Path $repo ("models\hub\models--Systran--faster-whisper-" + $modelName + "\snapshots")
if (-not (Test-Path $snapDir) -or -not (Get-ChildItem $snapDir -ErrorAction SilentlyContinue)) {
    Write-Host "[first-run] 模型 faster-whisper-$modelName 不存在，先下载…"
    & (Join-Path $PSScriptRoot 'download-model.ps1') -Model $modelName
    if ($LASTEXITCODE -ne 0) { Write-Host '[first-run] 模型下载失败，中止。'; exit $LASTEXITCODE }
} else {
    Write-Host "[first-run] 模型 faster-whisper-$modelName 已存在，跳过下载。"
}

# 3) 起栈
Write-Host '[first-run] docker compose up -d --build（首次构建可能需要几分钟）…'
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Write-Host '[first-run] docker compose up 失败，请检查 Docker 是否在运行。'; exit $LASTEXITCODE }

# 4) 轮询 healthy（超时 10 分钟）
$services = @('app', 'asr', 'minio', 'postgres', 'redis')
$deadline = (Get-Date).AddMinutes(10)
while ($true) {
    Start-Sleep -Seconds 10
    $psOut = docker compose ps --format '{{.Service}} {{.Status}}' 2>$null
    $unhealthy = @()
    foreach ($s in $services) {
        $line = $psOut | Where-Object { $_ -match ('^' + $s + ' ') } | Select-Object -First 1
        if (-not $line -or $line -notmatch '\(healthy\)') { $unhealthy += $line }
    }
    if (-not $unhealthy) {
        Write-Host '[first-run] 5/5 服务全部 healthy。'
        break
    }
    if ((Get-Date) -gt $deadline) {
        Write-Host '[first-run] 超时（10 分钟）——以下服务未 healthy：'
        $unhealthy | ForEach-Object { Write-Host "  $_" }
        Write-Host '[first-run] 排查：  docker compose logs asr  （模型缺失会给出中文提示与下载命令）'
        exit 1
    }
    $left = [math]::Round(($deadline - (Get-Date)).TotalSeconds)
    Write-Host "[first-run] 等待中…（剩余 ${left}s）未就绪：$($unhealthy -join ' | ')"
}

# 5) 打印地址
$portLine = (Get-Content $envFile | Where-Object { $_ -match '^\s*APP_PORT\s*=' } | Select-Object -First 1)
$appPort = if ($portLine) { ($portLine -split '=', 2)[1].Trim() } else { '8080' }
Write-Host ''
Write-Host ("[first-run] 完成。Web 地址：  http://localhost:$appPort")
Write-Host '[first-run] 首次进入请创建管理员账号（第一个注册的账号即管理员）。'
exit 0