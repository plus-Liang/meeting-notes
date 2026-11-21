# scripts/restore.ps1 —— 从备份恢复（US-11 / T2.8）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore.ps1 -BackupDir backups\<时间戳> -Force
# 纪律：不带 -Force 必须拒绝；恢复前停 app（避免边恢复边消费）；备份校验存在且非空。

param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# ---- 0) 防误删闸门 ----
if (-not $Force) {
    Write-Host '[restore] 拒绝执行：恢复会清空当前数据！确认后加 -Force 重新运行。'
    Write-Host '[restore] 用法: scripts\restore.ps1 -BackupDir backups\<时间戳> -Force'
    exit 10
}

# ---- 0b) preflight：依赖容器必须 running（T2.9 F-4），否则给出可操作提示，不进 pg_restore 误报 ----
foreach ($svc in @('postgres', 'minio')) {
    # -aq 含已停止容器；只查运行状态而不是"容器不存在"，才能区分"没起"和"起了但停了"
    $cid = docker compose ps -aq $svc | Where-Object { $_ } | Select-Object -First 1
    $running = $false
    if ($cid) {
        $running = (docker inspect -f '{{.State.Running}}' $cid.Trim()).Trim() -eq 'true'
    }
    if (-not $running) {
        Write-Host "[restore] 依赖容器未运行: $svc —— 恢复前请先执行: docker compose up -d"
        Write-Host '[restore] （不自动启动依赖，避免掩盖环境问题；D-064）'
        exit 20
    }
}

# ---- 1) 校验备份 ----
$backupPath = $BackupDir
if (-not [System.IO.Path]::IsPathRooted($backupPath)) {
    if (Test-Path (Join-Path $repo $BackupDir)) { $backupPath = Join-Path $repo $BackupDir }
    elseif (Test-Path (Join-Path $repo "backups\$BackupDir")) { $backupPath = Join-Path $repo "backups\$BackupDir" }
}
$dbDump = Join-Path $backupPath 'db.dump'
$minioTar = Join-Path $backupPath 'minio-data.tar.gz'
$manifestFile = Join-Path $backupPath 'manifest.json'
foreach ($f in @($dbDump, $minioTar, $manifestFile)) {
    if (-not (Test-Path $f) -or ((Get-Item $f).Length -eq 0)) {
        Write-Host "[restore] 备份不完整或为空: $f"; exit 11
    }
}
$manifest = Get-Content $manifestFile -Raw | ConvertFrom-Json
Write-Host ("[restore] 备份创建于 {0}；DB 行数: {1}" -f $manifest.created_at,
    (($manifest.row_counts.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' '))

# ---- 2) 停 app（避免边恢复边消费）----
Write-Host '[restore] 停止 app 容器 ...'
docker compose stop app | Out-Null

# ---- 3) DB：pg_restore --clean --if-exists ----
Write-Host '[restore] 恢复数据库 ...'
docker compose cp "$dbDump" postgres:/tmp/mn-restore.dump
docker compose exec -T postgres sh -c "pg_restore -U $($manifest.pg_user) --clean --if-exists -d $($manifest.pg_database) /tmp/mn-restore.dump"
if ($LASTEXITCODE -ne 0) { Write-Host '[restore] pg_restore 失败（见上方错误）'; docker compose start app | Out-Null; exit 12 }
docker compose exec -T postgres rm /tmp/mn-restore.dump | Out-Null

# ---- 4) MinIO volume：整卷清空（含隐藏项）再解包 ----
Write-Host '[restore] 恢复 MinIO volume ...'
docker compose stop minio | Out-Null
$minioCid = (docker compose ps -aq minio).Trim()
# T4.51：rm -rf /data/* 不匹配隐藏目录——新卷会残留新实例写的 .minio.sys（drive UUID 与备份不一致），
# MinIO 判 "inconsistent drive found"，元数据只读、写入全 500。只有换机/卷被删/down -v 重建时才踩到。
# find -mindepth 1 -delete 能删 .minio.sys 与空目录，实现真正的整卷清空。
docker run --rm --volumes-from $minioCid -v "${backupPath}:/backup:ro" python:3.12-slim `
    sh -c "find /data -mindepth 1 -delete; tar xzf /backup/minio-data.tar.gz -C /; [ -f /data/.minio.sys/format.json ] || exit 14"
if ($LASTEXITCODE -eq 14) { Write-Host '[restore] MinIO 卷重建后缺 /data/.minio.sys/format.json——备份包可能不含 MinIO 元数据，请核对备份'; docker compose start minio app | Out-Null; exit 14 }
if ($LASTEXITCODE -ne 0) { Write-Host '[restore] MinIO volume 解包失败'; docker compose start minio app | Out-Null; exit 13 }
Write-Host '[restore] MinIO 卷已整卷重建（含 .minio.sys）'
docker compose start minio | Out-Null

# ---- 5) 起服务并自检 ----
Write-Host '[restore] 启动 app 并自检 ...'
docker compose start app | Out-Null
# 健康探测端口跟随 APP_PORT（T4.51：隔离栈/异端口部署下不再误探 8080——那会读到别的栈的 UP）
$appPort = if ($env:APP_PORT) { $env:APP_PORT } else { '8080' }
$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep 3
    try { $health = (curl.exe -s --noproxy '*' "http://localhost:${appPort}/actuator/health" | ConvertFrom-Json).status } catch { $health = '' }
} until ($health -eq 'UP' -or (Get-Date) -gt $deadline)

$tables = @('task', 'task_result', 'glossary', 'quota', 'dead_letter')
$ok = $true
foreach ($t in $tables) {
    $expected = $manifest.row_counts.$t
    $actual = [int]((docker compose exec -T postgres psql -U $manifest.pg_user -d $manifest.pg_database -t -A -c "select count(*) from $t").Trim())
    $match = if ($actual -eq $expected) { 'OK' } else { 'MISMATCH'; $ok = $false }
    Write-Host ("[restore] {0}: {1}/{2} {3}" -f $t, $actual, $expected, $match)
}
Write-Host ("[restore] health: {0}" -f $health)
if ($ok -and $health -eq 'UP') {
    Write-Host '[restore] ===== 恢复完成，自检通过 ====='
    exit 0
}
Write-Host '[restore] ===== 自检不一致，请人工核对 ====='
exit 14
