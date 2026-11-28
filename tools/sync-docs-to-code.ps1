# sync-docs-to-code.ps1
# Purpose: 把项目文档同步进代码仓（因为代码 agent 通常只能看到自己的工作目录）。
# 文档唯一权威仍在 D:\new\docs\项目\录音纪要平台\；代码仓里的是「生成副本」，不要手改。
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-docs-to-code.ps1

$ErrorActionPreference = 'Stop'

$srcDocs = 'D:\new\docs\项目\录音纪要平台'
# T4.105：自动定位代码仓——优先"本脚本父目录"（本脚本被同步进 <repo>\tools\ 时，如在 worktree 里跑），
# 否则用本机默认路径（从 D:\new\tools\ 原版运行时）。与 tools\check-maintainability.ps1 的定位方式一致。
$candidate = Split-Path -Parent $PSScriptRoot
if (Test-Path (Join-Path $candidate 'docker-compose.yml')) { $repo = $candidate }
else { $repo = 'D:\new\projects\meeting-notes' }
$dstDocs = Join-Path $repo 'docs\项目\录音纪要平台'

if (-not (Test-Path $srcDocs)) { throw "源文档目录不存在: $srcDocs" }
if (-not (Test-Path (Join-Path $repo 'docker-compose.yml'))) { throw "代码仓路径不对: $repo" }

New-Item -ItemType Directory -Force -Path $dstDocs | Out-Null
# 每个拷贝都**校验**：本机偶发把目标写成"同样长度但全 0x00"的空壳（K-103），
# 而 Copy-Item 不报错、脚本照样打印"同步完成"。所以拷完比 SHA256，不一致重试 3 次，仍不一致就抛错。
function Copy-Verified {
    param([string]$Source, [string]$Destination)
    for ($i = 1; $i -le 3; $i++) {
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
        $h1 = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
        $h2 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        if ($h1 -eq $h2) { return }
        Write-Warning ("拷贝内容不一致（第 {0} 次），重试：{1}" -f $i, $Source)
    }
    throw "同步失败：拷贝后内容与源不一致 $Source -> $Destination"
}

$copied = 0
Get-ChildItem $srcDocs -File -Filter *.md | ForEach-Object {
    Copy-Verified $_.FullName (Join-Path $dstDocs $_.Name)
    $copied++
}

# T4.105：docs/images（SVG 等图片）一并进仓。只同步源目录**第一层**的文件（-File，非递归）——
# 图片按平铺目录管理，不设子目录层级，避免递归拷贝把非图片杂物带进仓。
# 源目录不存在属首次运行前的正常状态（权威仓还没回灌图片），打印跳过、不报错。
$srcImages = 'D:\new\docs\images'
$dstImages = Join-Path $repo 'docs\images'
$images = 0
if (Test-Path $srcImages) {
    New-Item -ItemType Directory -Force -Path $dstImages | Out-Null
    Get-ChildItem $srcImages -File | ForEach-Object {
        Copy-Verified $_.FullName (Join-Path $dstImages $_.Name)
        $images++
    }
} else {
    Write-Host '未发现 docs/images，跳过'
}

# 生成副本标记，防止有人在代码仓里手改文档
$marker = @(
    '# ⚠️ 本目录是「生成副本」，请勿手改',
    '',
    '权威版本在：`D:\new\docs\项目\录音纪要平台\`',
    '',
    '重新同步：`powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-docs-to-code.ps1`',
    '',
    ('最后同步：' + (Get-Date -Format 'yyyy-MM-dd HH:mm'))
)
[System.IO.File]::WriteAllLines(
    (Join-Path $dstDocs '_GENERATED-勿手改.md'),
    $marker,
    (New-Object System.Text.UTF8Encoding($true))
)

Write-Host ("同步完成：{0} 个 md，图片 {1} 个 -> {2}" -f $copied, $images, $dstDocs)

# 审查报告一起进仓：它们是最硬的「证据」（独立上下文复跑的命令与输出）。
# 代码 agent / 面试官在仓里就能看到，不用回工作区翻。
# 只同步「证据类报告」：*验收.md / *演练.md / *实测.md / *核对.md / *验证.md——
# 工作区级别的协作类评审（如多Agent手册评审、可操作性/正确性评审）不属于本项目仓库。
# K-107：改用**黑名单**。原先按后缀白名单（验收|演练|实测|核对）过滤，2026-11-21 的
# `T4.15-下载完整性假失败.md`、`T4.16备份异地与恢复验证.md` 因此被**静默跳过**，而脚本照样打印
# "同步完成"→ 现在只排除 3 份工作区级评审，其余 md 一律进仓；每次同步后仍要用 `git status --short`
# 核对新增 md 的数量。
$reviewExclude = '(可操作性|正确性|方案评审)\.md$'
$srcReview = 'D:\new\docs\协作\review'
$dstReview = Join-Path $repo 'docs\协作\review'
if (Test-Path $srcReview) {
    New-Item -ItemType Directory -Force -Path $dstReview | Out-Null
    $reviews = Get-ChildItem $srcReview -File | Where-Object { $_.Name -notmatch $reviewExclude }
    $skipped = Get-ChildItem $srcReview -File | Where-Object { $_.Name -match $reviewExclude }
    $reviews | ForEach-Object {
        Copy-Verified $_.FullName (Join-Path $dstReview $_.Name)
    }
    Write-Host ("同步完成：{0} 份验收报告 -> {1}" -f $reviews.Count, $dstReview)
    if ($skipped.Count -gt 0) {
        Write-Host ("[提示] 未同步 {0} 份（工作区级评审，不进项目仓）：{1}" -f $skipped.Count, (($skipped | Select-Object -ExpandProperty Name) -join ', '))
    }
}

# 协作基线文档也进仓（写代码的 AI 要用到；它可能只能看见自己的仓库目录）。
# 只同步"方法/模板/出处"这三类，不搬工作区级别的操作手册。
$collabFiles = @('开工准备清单.md', '开发提示词模板.md', '11月13日-音频总结.md', '11月13日-音频转写.txt',
    '11月14日-AI长期开发复杂项目-总结.md', '11月14日-AI长期开发复杂项目-转写.txt',
    'VibeCoding项目开发框架.md', '知识吸收台账.md', '交付给ZCode.md')
$dstCollab = Join-Path $repo 'docs\协作'
New-Item -ItemType Directory -Force -Path $dstCollab | Out-Null
foreach ($name in $collabFiles) {
    $src = Join-Path 'D:\new\docs\协作' $name
    if (Test-Path $src) { Copy-Verified $src (Join-Path $dstCollab $name) }
}
Write-Host ("同步完成：协作基线文档 -> {0}" -f $dstCollab)

# 可维护性自检工具也要进仓：写代码的 AI 在仓库里跑 `powershell -File tools\check-maintainability.ps1`。
# 脚本与允许清单必须成对同步（允许清单是"已知技术债"的登记处，只减不增）。
$dstTools = Join-Path $repo 'tools'
New-Item -ItemType Directory -Force -Path $dstTools | Out-Null
foreach ($name in @('check-maintainability.ps1', 'maintainability-allowlist.txt')) {
    $src = Join-Path 'D:\new\tools' $name
    if (Test-Path $src) { Copy-Verified $src (Join-Path $dstTools $name) }
}
Write-Host ("同步完成：可维护性自检工具 -> {0}" -f $dstTools)

Get-ChildItem $dstDocs -File | Select-Object -ExpandProperty Name
Get-ChildItem (Join-Path $repo 'docs\协作\review') -File -ErrorAction SilentlyContinue | ForEach-Object { 'review/' + $_.Name }
Get-ChildItem $dstCollab -File | ForEach-Object { '协作/' + $_.Name }
Get-ChildItem $dstTools -File | ForEach-Object { 'tools/' + $_.Name }
