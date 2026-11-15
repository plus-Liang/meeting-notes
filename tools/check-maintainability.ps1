# 可维护性机器自检（对应 `docs/项目/录音纪要平台/可维护性要求.md` 的 M-xx）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-maintainability.ps1
#       [-Repo <代码仓路径>]（不给则自动判断：脚本在 <repo>\tools\ 下时用其父目录，否则用本机默认路径）
# 退出码：0 = 无阻塞项；1 = 有阻塞项（必须在 commit 前处理或写进 allowlist 并在 review 说明）
# 设计：允许清单 tools\maintainability-allowlist.txt 每行 "相对路径|正则"，用于登记已知技术债（必须可解释、不得随意增加）。

param(
    [string]$Repo = '',
    [int]$MaxJavaFileLines = 300
)

# 自动定位代码仓：优先"本脚本父目录"（脚本被同步进 <repo>\tools\ 时），否则用本机默认路径。
if ([string]::IsNullOrWhiteSpace($Repo)) {
    $candidate = Split-Path -Parent $PSScriptRoot
    if (Test-Path (Join-Path $candidate 'docker-compose.yml')) { $Repo = $candidate }
    else { $Repo = 'D:\new\projects\meeting-notes' }
}

$ErrorActionPreference = 'Stop'
$src = Join-Path $Repo 'app\src\main\java'
$test = Join-Path $Repo 'app\src\test\java'
$yml = Join-Path $Repo 'app\src\main\resources\application.yml'
$envExample = Join-Path $Repo '.env.example'
$allowFile = Join-Path $PSScriptRoot 'maintainability-allowlist.txt'

if (-not (Test-Path $src)) { throw "找不到 Java 源码目录: $src" }

$blocking = New-Object System.Collections.ArrayList
$warnings = New-Object System.Collections.ArrayList

function RelPath([string]$p) { return $p.Replace($Repo + '\', '').Replace('\', '/') }

$allow = @()
if (Test-Path $allowFile) {
    $allow = Get-Content $allowFile -Encoding UTF8 | Where-Object { $_ -and -not $_.TrimStart().StartsWith('#') }
}
function Is-Allowed([string]$rel, [string]$line) {
    foreach ($a in $allow) {
        $parts = $a.Split('|', 2)
        if ($parts.Count -ne 2) { continue }
        if ($rel -eq $parts[0].Trim() -and $line -match $parts[1].Trim()) { return $true }
    }
    return $false
}

$javaFiles = Get-ChildItem $src -Recurse -File -Filter *.java
$javaLines = @{}
foreach ($f in $javaFiles) { $javaLines[(RelPath $f.FullName)] = [System.IO.File]::ReadAllLines($f.FullName) }

Write-Host "=== 可维护性自检：$Repo ===" -ForegroundColor Cyan
Write-Host ("Java 文件 {0} 个；文件行数上限 {1}" -f $javaFiles.Count, $MaxJavaFileLines)

# M4 硬编码：路径 / 回环地址 / 明文凭证 / 端口
$hardPatterns = @('C:\\\\', 'D:\\\\', 'http://localhost', '127\.0\.0\.1', 'minioadmin', 'sk-[A-Za-z0-9]{8}')
foreach ($rel in $javaLines.Keys) {
    $ln = 0
    foreach ($line in $javaLines[$rel]) {
        $ln++
        foreach ($p in $hardPatterns) {
            if ($line -match $p -and -not (Is-Allowed $rel $line)) {
                [void]$blocking.Add(("M4 硬编码 {0}:{1} → {2}" -f $rel, $ln, $line.Trim()))
            }
        }
    }
}

# M4b 魔法阈值：端口/时长/大小/采样率直接出现在代码里
$magicPatterns = @('\b8080\b', '\b9000\b', '\b5432\b', '\b6379\b', '\b209715200\b', '\b3600\b')
foreach ($rel in $javaLines.Keys) {
    $ln = 0
    foreach ($line in $javaLines[$rel]) {
        $ln++
        foreach ($p in $magicPatterns) {
            if ($line -match $p -and -not (Is-Allowed $rel $line)) {
                [void]$blocking.Add(("M4 魔法阈值 {0}:{1} → {2}" -f $rel, $ln, $line.Trim()))
            }
        }
    }
}

# M5 分层依赖方向：内层不得 import 外层 Controller/Advice
foreach ($rel in $javaLines.Keys) {
    if ($rel -match '/(task|upload|worker)/') {
        $ln = 0
        foreach ($line in $javaLines[$rel]) {
            $ln++
            if ($line -match '^import com\.meetingnotes\.web\.' -and -not (Is-Allowed $rel $line)) {
                [void]$blocking.Add(("M5 反向依赖 {0}:{1} → {2}" -f $rel, $ln, $line.Trim()))
            }
        }
    }
}

# M3 文件规模
foreach ($rel in $javaLines.Keys) {
    $count = $javaLines[$rel].Count
    if ($count -gt $MaxJavaFileLines) {
        [void]$blocking.Add(("M3 文件过长 {0} = {1} 行（上限 {2}）" -f $rel, $count, $MaxJavaFileLines))
    }
}

# M6 吞异常 / 空 catch
foreach ($rel in $javaLines.Keys) {
    $text = ($javaLines[$rel] -join "`n")
    $m = [regex]::Matches($text, 'catch\s*\([^)]*\)\s*\{\s*\}')
    if ($m.Count -gt 0 -and -not (Is-Allowed $rel 'catch')) {
        [void]$blocking.Add(("M6 空 catch {0} × {1}（吞异常必须 log 或抛）" -f $rel, $m.Count))
    }
}

# M7 禁止 System.out / printStackTrace
foreach ($rel in $javaLines.Keys) {
    $ln = 0
    foreach ($line in $javaLines[$rel]) {
        $ln++
        if ($line -match 'System\.(out|err)\.print|printStackTrace' -and -not (Is-Allowed $rel $line)) {
            [void]$blocking.Add(("M7 调试输出 {0}:{1} → {2}" -f $rel, $ln, $line.Trim()))
        }
    }
}

# M9 TODO/FIXME 不许留在主代码
# T3.5 复核（2026-11-14）：只扫**注释文本**（// 行注释、/* 或 * 开头的块注释行、行尾 // ...），
# 不扫标识符与字符串字面量——否则 result.todo() 这类合法标识符、@JsonProperty("todo") 这类键名会误报。
# 字符串内的 // 通过"引号计数"跳过（URL 如 http://... 不算注释）。
function Get-JavaCommentText([string]$line) {
    $t = $line.Trim()
    if ($t.StartsWith('//') -or $t.StartsWith('/*') -or $t.StartsWith('*')) { return $t }
    $inStr = $false
    for ($i = 0; $i -lt $line.Length - 1; $i++) {
        $c = $line[$i]
        if ($c -eq '"') { $inStr = -not $inStr }
        elseif (-not $inStr -and $c -eq '/' -and $line[$i + 1] -eq '/') { return $line.Substring($i) }
    }
    return ''
}
foreach ($rel in $javaLines.Keys) {
    $ln = 0
    foreach ($line in $javaLines[$rel]) {
        $ln++
        $comment = Get-JavaCommentText $line
        if ($comment -match '\b(TODO|FIXME|XXX)\b' -and -not (Is-Allowed $rel $line)) {
            [void]$blocking.Add(("M9 遗留 TODO {0}:{1} → {2}" -f $rel, $ln, $line.Trim()))
        }
    }
}

# M8 测试：不许禁用/跳过（注释里提到不算）；只有 *SmokeTest 允许按环境跳过
if (Test-Path $test) {
    $testFiles = Get-ChildItem $test -Recurse -File -Filter *.java
    foreach ($f in $testFiles) {
        $rel = RelPath $f.FullName
        $code = ([System.IO.File]::ReadAllLines($f.FullName) |
            Where-Object { $t = $_.Trim(); -not ($t.StartsWith('//') -or $t.StartsWith('*') -or $t.StartsWith('/*')) }) -join "`n"
        if ($code -match '@Disabled|@Ignore') {
            [void]$blocking.Add(("M8 测试被禁用/跳过：{0}" -f $rel))
        }
        if ($code -match 'assumeTrue|Assumptions\.') {
            $base = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
            if ($base -notmatch 'SmokeTest$') {
                [void]$blocking.Add(("M8 非冒烟测试用 assumeTrue 跳过：{0}（只有 *SmokeTest 允许按环境跳过）" -f $rel))
            }
        }
        if ($code -notmatch 'assert|assertThat|assertThatThrownBy|Assertions\.') {
            [void]$warnings.Add(("M8 测试没有断言：{0}" -f $rel))
        }
    }
} else {
    [void]$warnings.Add('M8 没有测试目录')
}

# M2 配置外置：yml 变量必须都在 .env.example（复用配置清单 §2 判据）
if ((Test-Path $yml) -and (Test-Path $envExample)) {
    # 注意：本清单与 docs/项目/录音纪要平台/配置清单.md §2 的 $allowed 必须一致（T4.12/K-53：两处副本会漂移）。
    # TZ：由 compose 传给 app+postgres，且 app 的 TimezoneConsistencyCheck 直接 @Value 消费（T4.12/F-23）→ 豁免。
    $allowed = @('ASR_PORT','INBOX_HOST_DIR','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REGISTRY_PREFIX',
        'MINIO_PORT','MINIO_CONSOLE_PORT','OLLAMA_PORT','MINIO_IMAGE','OLLAMA_IMAGE','ASR_MODEL_HOST_DIR','PIP_INDEX_URL',
        'ASR_MODEL_NAME','ASR_LANGUAGE','ASR_COMPUTE_TYPE','ASR_BEAM_SIZE','ASR_SMOKE_AUDIO','FFPROBE_BIN','TZ')
    $vars = (Select-String -Path $yml -Pattern '\$\{([A-Z0-9_]+)' -AllMatches).Matches |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $envs = (Select-String -Path $envExample -Pattern '^([A-Z0-9_]+)=' -AllMatches).Matches |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $missing = $vars | Where-Object { $envs -notcontains $_ }
    if ($missing) { [void]$blocking.Add(("M2 yml 用到但 .env.example 缺失：{0}" -f ($missing -join ', '))) }
    $extra = $envs | Where-Object { $vars -notcontains $_ -and $allowed -notcontains $_ }
    if ($extra) { [void]$warnings.Add(("M2 .env.example 多余（非 compose-only）：{0}" -f ($extra -join ', '))) }
} else {
    [void]$warnings.Add('M2 缺少 application.yml 或 .env.example')
}

# M5b 对外契约：Jackson 必须 snake_case（D-041）
if (Test-Path $yml) {
    $ymlText = [System.IO.File]::ReadAllText($yml)
    if ($ymlText -notmatch 'property-naming-strategy:\s*SNAKE_CASE') {
        [void]$blocking.Add('M5b 缺少 spring.jackson.property-naming-strategy: SNAKE_CASE（D-041）')
    }
}

Write-Host ""
Write-Host ("阻塞项 {0} 个 / 提示 {1} 个" -f $blocking.Count, $warnings.Count) -ForegroundColor Yellow
foreach ($b in $blocking) { Write-Host ("✗ " + $b) -ForegroundColor Red }
foreach ($w in $warnings) { Write-Host ("! " + $w) -ForegroundColor DarkYellow }
Write-Host ""
if ($blocking.Count -gt 0) {
    Write-Host "结论：不通过（先把阻塞项修掉，或写进 tools\maintainability-allowlist.txt 并在 review 里说明理由）" -ForegroundColor Red
    exit 1
}
Write-Host "结论：通过 ✅" -ForegroundColor Green
exit 0
