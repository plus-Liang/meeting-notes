# scripts/download-model.ps1 ----- 下载 faster-whisper 模型（T4.9；T4.110 起落点跟随 ASR_MODEL_HOST_DIR）
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1              # small（约461MB）
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Model medium
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Model large-v3
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Force        # 清除本模型缓存后整包重下（下载中断/怀疑不完整时用）
# 说明：
#   - 用 asr 镜像自带的 python/huggingface_hub 下载（docker compose run），宿主机无需装 Python；
#   - 国内网络慢时先设 HF_ENDPOINT：  $env:HF_ENDPOINT = "https://hf-mirror.com"
param(
    [ValidateSet('small', 'medium', 'large-v3')]
    [string]$Model = 'small',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not (Test-Path (Join-Path $repo 'docker-compose.yml'))) { Write-Host '[download-model] 未找到 docker-compose.yml，请在仓库根运行'; exit 2 }

# T4.110/C1：读仓库根 .env 的单键值（-Encoding UTF8 读、容忍 BOM、^\s*KEY= 匹配）；键缺失/为空返回 $null。
function Get-DotEnvValue {
    param([string]$Repo, [string]$Key)
    $path = Join-Path $Repo '.env'
    if (-not (Test-Path $path)) { return $null }
    $line = Get-Content $path -Encoding UTF8 | Where-Object { $_ -match ('^\s*' + $Key + '\s*=') } | Select-Object -First 1
    if (-not $line) { return $null }
    $value = ($line -split '=', 2)[1].Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return $value
}

# T4.110/C1：模型根目录跟随 ASR_MODEL_HOST_DIR（此前写死 repo\models，自定义模型盘的用户下载进 repo、
# asr 却挂自定义目录 → 循环重启「模型未找到」，T4.107 实测）。解析口径：shell 环境变量 > .env > 缺省
# <repo>\models（与 compose 插值优先级一致）；相对路径相对仓库根解析；绝对路径原样（统一 / 风格供挂载）。
$modelsRoot = $env:ASR_MODEL_HOST_DIR
if (-not $modelsRoot) { $modelsRoot = Get-DotEnvValue $repo 'ASR_MODEL_HOST_DIR' }
if (-not $modelsRoot) { $modelsRoot = Join-Path $repo 'models' }
if ($modelsRoot -notmatch '^[A-Za-z]:') { $modelsRoot = Join-Path $repo $modelsRoot }
$modelsRoot = $modelsRoot.TrimEnd('\', '/').Replace('\\', '/')
if (-not (Test-Path $modelsRoot)) { New-Item -ItemType Directory -Force -Path $modelsRoot | Out-Null }
$modelsItem = Get-Item $modelsRoot -Force
if ($modelsItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
    Write-Host "[download-model] 警告：模型根目录是链接（$($modelsItem.LinkType)）——Docker Desktop 的 bind mount 可能不解析它，容器写入会静默消失（坑库 K-108/D3），失败请改用真实目录。"
}
Write-Host "[download-model] 模型根目录 = <$modelsRoot>（来自 ASR_MODEL_HOST_DIR）"

# T4.110/C2 连带：compose 调用统一带项目名（防目录名与已有栈同名导致误接管；与 first-run 同源解析：
# shell 环境变量 > .env > 目录名）。
$proj = $env:COMPOSE_PROJECT_NAME
if (-not $proj) { $proj = Get-DotEnvValue $repo 'COMPOSE_PROJECT_NAME' }
if (-not $proj) { $proj = Split-Path -Leaf $repo }

# 完整性判断（T4.14b 修 F1「下载假完成」）：不能只看 snapshots 目录非空——下载中断会留下 0 字节占位文件，
# 起栈时 asr 才报 incomplete。校验 snapshots/<rev>/ 下 model.bin、config.json、tokenizer.json 存在且 Length > 0，
# 词表 vocabulary.txt 或 vocabulary.json（large-v3 用后者）至少其一非空；<rev> 取第一个子目录，不硬编码 commit。
# 返回缺失清单（空数组 = 完整）。first-run.ps1 内联同一判断，两脚本保持独立可跑。
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

$hubDir = Join-Path $modelsRoot ("hub\models--Systran--faster-whisper-" + $Model)
$snapDir = Join-Path $hubDir 'snapshots'
$missing = @(Get-MissingModelFiles $snapDir)
if ($Force) {
    # -Force：清除本模型缓存后整包重下。链接/junction 只删链接本身（PS 5.1 的 Remove-Item -Recurse 会穿透链接删到目标）
    if (Test-Path $hubDir) {
        Write-Host "[download-model] -Force：清除 $hubDir 后重新下载…"
        $hubItem = Get-Item $hubDir -Force
        if ($hubItem.LinkType) { [System.IO.Directory]::Delete($hubDir) } else { Remove-Item $hubDir -Recurse -Force }
    }
} elseif ($missing.Count -eq 0) {
    Write-Host "[download-model] 模型 $Model 已存在（$snapDir），跳过下载。"
    exit 0
} else {
    if (Test-Path $snapDir) {
        Write-Host "[download-model] 检测到模型 $Model 下载不完整（缺 $($missing -join '、')），重新下载…"
    } else {
        Write-Host "[download-model] 模型 $Model 不存在，开始下载…"
    }
}

Write-Host "[download-model] 开始下载 faster-whisper-$Model 到 $modelsRoot\hub（small 约 461MB，请耐心等待）…"
if ($env:HF_ENDPOINT) { Write-Host "[download-model] 使用镜像源：$env:HF_ENDPOINT" }

$py = "from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-$Model', cache_dir='/dl/hub')"
# /models 在 compose 里是 :ro（运行态安全）——下载挂到独立可写点 /dl（宿主同一目录），不与 ro 挂载冲突；
# T4.110/C1：挂载源跟随 ASR_MODEL_HOST_DIR 解析出的 $modelsRoot（不再写死 repo\models）
# HF_ENDPOINT 只在非空时注入（空串会让 huggingface_hub 的 ENDPOINT 变空 → 拼出相对 URL → UnsupportedProtocol）；
# 不设时走 huggingface.co 官方默认，容器可直连
# compose run 不会自动构建——全新克隆时 asr 镜像尚不存在，先显式构建（已构建则秒过）
Write-Host '[download-model] 确认 asr 镜像（不存在则构建，首次约几分钟）…'
docker compose -p $proj build asr
if ($LASTEXITCODE -ne 0) { Write-Host '[download-model] asr 镜像构建失败。'; exit $LASTEXITCODE }

$dockerArgs = @('compose', 'run', '--rm',
    '-e', 'HF_HUB_OFFLINE=0',
    '-e', 'HF_HUB_DISABLE_XET=1',
    '-e', 'HF_HOME=/dl',
    '-v', "${modelsRoot}:/dl")
if ($env:HF_ENDPOINT -and $env:HF_ENDPOINT.Trim()) { $dockerArgs += @('-e', "HF_ENDPOINT=$($env:HF_ENDPOINT.Trim())") }
$dockerArgs += @('asr', 'python', '-c', $py)
docker @dockerArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "[download-model] 下载失败（exit $LASTEXITCODE）。国内网络可先执行：  `$env:HF_ENDPOINT = 'https://hf-mirror.com'  后重试。"
    Write-Host "[download-model] 若反复中断，可先删 $modelsRoot 再重跑（或加 -Force 让脚本代删后整包重下）。"
    exit $LASTEXITCODE
}

# 下载流程结束后再验一次完整性——防止再次「假完成」（T4.14b F1）
$missing = @(Get-MissingModelFiles $snapDir)
if ($missing.Count -gt 0) {
    Write-Host "[download-model] 下载流程已结束但模型仍不完整（缺 $($missing -join '、')）。建议清除后整包重下："
    Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Model $Model -Force"
    exit 1
}

$sizeMB = [math]::Round((Get-ChildItem (Join-Path $modelsRoot 'hub') -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
Write-Host "[download-model] 完成：模型 $Model 已就绪，占用 $sizeMB MB（$modelsRoot\hub）。"
Write-Host '[download-model] 下一步：  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\first-run.ps1'
exit 0