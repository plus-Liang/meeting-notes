# scripts/first-run.ps1 ----- 陌生人一键起全栈（T4.9）
# 用法（仓库根执行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\first-run.ps1
# 行为：无 .env 则从 .env.example 复制 -> 模型缺失/不完整则下载 -> docker compose up -d --build ->
#       轮询到 5 个服务 healthy（超时 10 分钟）-> 打印 Web 地址。
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# 1) .env
if (-not (Test-Path (Join-Path $repo '.env'))) {
    Copy-Item (Join-Path $repo '.env.example') (Join-Path $repo '.env')
    Write-Host '[first-run] 已从 .env.example 生成 .env。部署后请修改：MINIO 密钥（S3_ACCESS_KEY、S3_SECRET_KEY）/ 数据库密码（POSTGRES_PASSWORD）等敏感项。'
}

# 1.1) API_TOKEN（T4.73）：.env 里为空则生成 32 字节随机 -> 64 位 hex 写入；已有非空值不覆盖。
#      应用侧守卫（WeakApiTokenGuard）会拒绝空/默认/短于 32 位的 API_TOKEN——首启必须在这里补齐，
#      否则陌生人一键启动会被守卫打断（T4.73 C3，C2 的连带必要项）。
#      编码纪律：必须 [IO.File] 读写 + UTF8Encoding($false)——Get-Content|Set-Content -Encoding UTF8
#      会写出 BOM（239,187,191），docker compose 解析 .env 首行即坏（T4.9 同款坑）。
#      RNG 纪律：PS 5.1 无 [System.RandomNumberGenerator]::Fill 静态方法（实测抛 RuntimeException），
#      必须 Create()+GetBytes()；禁止 Get-Random（密码学上不合格）。
$envFile = Join-Path $repo '.env'
$tokenLines = [System.IO.File]::ReadAllLines($envFile)
$tokenIdx = -1
for ($i = 0; $i -lt $tokenLines.Count; $i++) {
    if ($tokenLines[$i] -match '^\s*API_TOKEN\s*=') { $tokenIdx = $i; break }
}
if ($tokenIdx -ge 0) {
    $curToken = ($tokenLines[$tokenIdx] -split '=', 2)[1].Trim()
    if ([string]::IsNullOrWhiteSpace($curToken)) {
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $tokenBytes = New-Object byte[] 32
        $rng.GetBytes($tokenBytes)
        $rng.Dispose()
        $newToken = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })
        $tokenLines[$tokenIdx] = 'API_TOKEN=' + $newToken
        [System.IO.File]::WriteAllLines($envFile, $tokenLines, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host '[first-run] 已生成随机 API_TOKEN（64 位），接入外部系统时复用该值。'
    }
}

# 2) 模型
$modelLine = (Get-Content $envFile | Where-Object { $_ -match '^\s*ASR_MODEL_NAME\s*=' } | Select-Object -First 1)
$modelName = if ($modelLine) { ($modelLine -split '=', 2)[1].Trim() } else { 'small' }
# 完整性判断与 download-model.ps1 内联同款（T4.14b 修 F1「下载假完成」）：关键文件存在且非空才算完整，
# 下载中断留下的 0 字节占位文件必须触发重下，不能当成「已存在」。两脚本各自内联，保持独立可跑。
function Get-MissingModelFiles {
    param([string]$SnapRoot)
    $rev = Get-ChildItem $SnapRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $rev) { return @('model.bin', 'config.json', 'tokenizer.json', 'vocabulary.txt') }
    $missing = @()
    foreach ($f in @('model.bin', 'config.json', 'tokenizer.json')) {
        $item = Get-Item (Join-Path $rev.FullName $f) -Force -ErrorAction SilentlyContinue
        if (-not $item) { $missing += $f; continue }
        if ($item.Length -gt 0) { continue }
        # 下载在 Linux 容器内跑，HF 建的是符号链接；Windows 侧读不到目标，Length 恒为 0。
        # 带 ReparsePoint 属性的条目按「已下载」处理，否则 461MB 真下载会被误判不完整（T4.15 实测）。
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { continue }
        $missing += $f
    }
    $vocabOk = $false
    foreach ($v in @('vocabulary.txt', 'vocabulary.json')) {
        $item = Get-Item (Join-Path $rev.FullName $v) -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Length -gt 0 -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint))) { $vocabOk = $true; break }
    }
    if (-not $vocabOk) { $missing += 'vocabulary.txt' }
    return $missing
}

$snapDir = Join-Path $repo ("models\hub\models--Systran--faster-whisper-" + $modelName + "\snapshots")
$missing = @(Get-MissingModelFiles $snapDir)
if ($missing.Count -gt 0) {
    if (Test-Path $snapDir) {
        Write-Host "[first-run] 检测到 faster-whisper-$modelName 下载不完整（缺 $($missing -join '、')），重新下载…"
        & (Join-Path $PSScriptRoot 'download-model.ps1') -Model $modelName -Force
    } else {
        Write-Host "[first-run] 模型 faster-whisper-$modelName 不存在，先下载…"
        & (Join-Path $PSScriptRoot 'download-model.ps1') -Model $modelName
    }
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