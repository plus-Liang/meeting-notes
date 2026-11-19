# scripts/download-model.ps1 ----- 下载 faster-whisper 模型到仓库根 ./models（T4.9）
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1              # small（约461MB）
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Model medium
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-model.ps1 -Model large-v3
# 说明：
#   - 用 asr 镜像自带的 python/huggingface_hub 下载（docker compose run），宿主机无需装 Python；
#   - 国内网络慢时先设 HF_ENDPOINT：  $env:HF_ENDPOINT = "https://hf-mirror.com"
param(
    [ValidateSet('small', 'medium', 'large-v3')]
    [string]$Model = 'small'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not (Test-Path (Join-Path $repo 'docker-compose.yml'))) { Write-Host '[download-model] 未找到 docker-compose.yml，请在仓库根运行'; exit 2 }

$snapDir = Join-Path $repo ("models\hub\models--Systran--faster-whisper-" + $Model + "\snapshots")
if (Test-Path $snapDir) {
    $entries = Get-ChildItem $snapDir -ErrorAction SilentlyContinue
    if ($entries) {
        Write-Host "[download-model] 模型 $Model 已存在（$snapDir），跳过下载。"
        exit 0
    }
}

Write-Host "[download-model] 开始下载 faster-whisper-$Model 到 $repo\models\hub（small 约 461MB，请耐心等待）…"
if ($env:HF_ENDPOINT) { Write-Host "[download-model] 使用镜像源：$env:HF_ENDPOINT" }

$py = "from huggingface_hub import snapshot_download; snapshot_download('Systran/faster-whisper-$Model', cache_dir='/dl/hub')"
# /models 在 compose 里是 :ro（运行态安全）——下载挂到独立可写点 /dl（宿主同一目录），不与 ro 挂载冲突
$modelsAbs = (Join-Path $repo 'models') -replace '\\', '/'
docker compose run --rm `
    -e HF_HUB_OFFLINE=0 `
    -e HF_HUB_DISABLE_XET=1 `
    -e HF_HOME=/dl `
    -e HF_ENDPOINT=$env:HF_ENDPOINT `
    -v "${modelsAbs}:/dl" `
    asr python -c $py
if ($LASTEXITCODE -ne 0) {
    Write-Host "[download-model] 下载失败（exit $LASTEXITCODE）。国内网络可先执行：  `$env:HF_ENDPOINT = 'https://hf-mirror.com'  后重试。"
    exit $LASTEXITCODE
}

$sizeMB = [math]::Round((Get-ChildItem (Join-Path $repo 'models\hub') -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
Write-Host "[download-model] 完成：模型 $Model 已就绪，占用 $sizeMB MB（$repo\models\hub）。"
Write-Host '[download-model] 下一步：  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\first-run.ps1'
exit 0