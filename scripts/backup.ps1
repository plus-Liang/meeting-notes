# scripts/backup.ps1 —— 备份 DB（pg_dump -Fc）与 MinIO volume（US-11 / T2.8）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup.ps1 [-Keep 7]
# 产物：backups\<时间戳>\{db.dump, minio-data.tar.gz, manifest.json}
# 纪律：备份包不含 .env（D-023，密钥不进备份）；backups/ 不进 git。

param(
    [int]$Keep = 7
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $repo "backups\$stamp"
New-Item -ItemType Directory -Path $dest -Force | Out-Null

# .env 提供连接参数（不打印 value）
$pgUser = 'mn'
$pgDb = 'meetingnotes'
if (Test-Path (Join-Path $repo '.env')) {
    Get-Content (Join-Path $repo '.env') | ForEach-Object {
        if ($_ -match '^\s*POSTGRES_USER\s*=\s*(\S+)') { $pgUser = $Matches[1] }
        if ($_ -match '^\s*POSTGRES_DB\s*=\s*(\S+)')   { $pgDb = $Matches[1] }
    }
}

# ---- 1) DB：pg_dump -Fc（容器内落盘再 docker cp，绕开 PowerShell 对二进制流的文本重定向）----
Write-Host "[backup] 1/3 数据库 pg_dump -Fc ..."
docker compose exec -T postgres sh -c "pg_dump -U $pgUser -Fc $pgDb > /tmp/mn-db.dump"
if ($LASTEXITCODE -ne 0) { Write-Host '[backup] pg_dump 失败'; exit 1 }
docker compose cp postgres:/tmp/mn-db.dump "$dest\db.dump"
docker compose exec -T postgres rm /tmp/mn-db.dump | Out-Null

# ---- 2) MinIO volume：用本机已有的 python:3.12-slim 打包（--volumes-from 免镜像搬运）----
Write-Host "[backup] 2/3 MinIO volume 打包 ..."
$minioCid = (docker compose ps -q minio).Trim()
if (-not $minioCid) { Write-Host '[backup] minio 容器未运行'; exit 1 }
docker run --rm --volumes-from $minioCid -v "${dest}:/backup" python:3.12-slim `
    tar -czf /backup/minio-data.tar.gz -C / data
if ($LASTEXITCODE -ne 0) { Write-Host '[backup] MinIO volume 打包失败'; exit 1 }

# ---- 3) 采集统计并写 manifest（restore 用它核对行数）----
Write-Host "[backup] 3/3 采集统计 ..."
$tables = @('task', 'task_result', 'glossary', 'quota', 'dead_letter')
$rowStats = @{}
foreach ($t in $tables) {
    $n = (docker compose exec -T postgres psql -U $pgUser -d $pgDb -t -A -c "select count(*) from $t")
    $rowStats[$t] = [int]($n.Trim())
}
$objectCount = docker run --rm --volumes-from $minioCid python:3.12-slim `
    sh -c "find /data -type f 2>/dev/null | wc -l"
$dbDumpHash = (Get-FileHash "$dest\db.dump" -Algorithm SHA256).Hash
$tarHash = (Get-FileHash "$dest\minio-data.tar.gz" -Algorithm SHA256).Hash
$dbSize = (Get-Item "$dest\db.dump").Length
$tarSize = (Get-Item "$dest\minio-data.tar.gz").Length

$manifest = [ordered]@{
    created_at    = (Get-Date).ToUniversalTime().ToString('o')
    pg_user       = $pgUser
    pg_database   = $pgDb
    row_counts    = $rowStats
    minio_objects = [int]($objectCount.Trim())
    sha256        = @{ db_dump = $dbDumpHash; minio_tar = $tarHash }
    sizes         = @{ db_dump = $dbSize; minio_tar = $tarSize }
}
$manifest | ConvertTo-Json -Depth 5 | Out-File (Join-Path $dest 'manifest.json') -Encoding utf8

Write-Host '[backup] ===== 备份完成 ====='
Write-Host ("[backup] 目录: backups\{0}" -f $stamp)
Write-Host ("[backup] db.dump          {0} bytes  sha256={1}" -f $dbSize, $dbDumpHash.Substring(0, 16) + '...')
Write-Host ("[backup] minio-data.tar.gz {0} bytes  sha256={1}" -f $tarSize, $tarHash.Substring(0, 16) + '...')
Write-Host ("[backup] DB 行数: " + (($rowStats.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '))
Write-Host ("[backup] MinIO 对象数: " + $objectCount.Trim())

# ---- 4) 保留策略：只留最近 $Keep 份 ----
$dirs = Get-ChildItem (Join-Path $repo 'backups') -Directory | Sort-Object Name -Descending
if ($dirs.Count -gt $Keep) {
    $dirs | Select-Object -Skip $Keep | ForEach-Object {
        Write-Host ("[backup] 保留策略清理: " + $_.Name)
        Remove-Item $_.FullName -Recurse -Force
    }
}
exit 0
