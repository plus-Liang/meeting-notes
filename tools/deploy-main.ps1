# deploy-main.ps1 —— 一条命令把「当前 main」部署到主栈并冒烟（T4.72）
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\deploy-main.ps1                     # 主栈（默认）
#   powershell ... -Repo <仓库> -ComposeProject mn-t472b                                          # 隔离栈验证
#   powershell ... -DryRun                                                                        # 只打印计划，不执行变更
# 纪律：
#   - 只动 app 容器（up -d --no-deps app），数据卷一律不碰；绝不 down / volume rm / prune；
#   - 部署前五道守卫（脏区 / HEAD!=origin/main / .env 关键键 / Docker 引擎 / 存量服务健康）；
#   - 失败不自动回滚，打印上一版镜像与回滚示例（保留失败现场）；
#   - 不打印任何令牌/密码；不修改 .env；不 git commit/push。
param(
    [string]$Repo = 'D:\new\projects\meeting-notes',
    [string]$ComposeProject = 'meeting-notes',
    [switch]$SkipBuild,
    [switch]$SkipSmoke,
    [switch]$DryRun,
    [switch]$AllowDataChange,
    [int]$TimeoutSec = 600
)

# docker 原生命令的 stderr 用 exit code 判定（PS 5.1 下 EAP=Stop 会把 stderr 重定向误当终止错误）
$ErrorActionPreference = 'Continue'

function Step([string]$msg) { Write-Host ("[deploy] " + $msg) }
function Fail([int]$code, [string]$msg) {
    Write-Host ("[deploy] ✗ " + $msg) -ForegroundColor Red
    if ($script:prevImageId) {
        Write-Host ("[deploy] 上一版 image = " + $script:prevImageId) -ForegroundColor Yellow
        Write-Host ("[deploy] 回滚示例（不自动执行，保留失败现场）：docker tag " + $script:prevImageId +
            " <本项目的 app 镜像引用> ; docker compose -p " + $ComposeProject + " --project-directory `"" + $Repo +
            "`" up -d --no-deps app") -ForegroundColor Yellow
    }
    exit $code
}

# ---- C1：参数规范化（相对路径按 $PWD 解析） ----
if (-not [System.IO.Path]::IsPathRooted($Repo)) { $Repo = Join-Path $PWD.Path $Repo }
$Repo = [System.IO.Path]::GetFullPath($Repo).TrimEnd('\')
$appContainer = $ComposeProject + '-app-1'
$pgContainer = $ComposeProject + '-postgres-1'
$startedAt = Get-Date
$script:prevImageId = ''
$sha = ''
Step ("仓库 = " + $Repo + "；compose 项目 = " + $ComposeProject + "；DryRun=" + $DryRun + "；SkipBuild=" + $SkipBuild + "；SkipSmoke=" + $SkipSmoke)

# ---- C2.1 脏工作区守卫 ----
$dirty = & git -C $Repo status --porcelain
if ($dirty) {
    Fail 2 ("工作区不干净（部署内容必须可追溯）：" + [Environment]::NewLine +
        (($dirty | Select-Object -First 10) -join [Environment]::NewLine))
}
Step "守卫① 工作区干净 ✓"

# ---- C2.2 HEAD == origin/main（落后/领先都停；fetch 失败降级用本地缓存判定） ----
& git -C $Repo fetch origin 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Step "! git fetch 失败（网络抖动/离线），改用本地 origin/main 缓存判定"
}
$head = (& git -C $Repo rev-parse HEAD)
$originMain = (& git -C $Repo rev-parse origin/main)
if (-not $originMain) { Fail 2 "解析 origin/main 失败（仓库没有 origin/main 跟踪引用）" }
if ($head -ne $originMain) {
    $cnt = (& git -C $Repo rev-list --left-right --count ("origin/main...HEAD"))
    Fail 2 ("HEAD != origin/main（behind/ahead = " + $cnt + "）——只部署已推送的 main，不部署本地未推提交")
}
$sha = (& git -C $Repo rev-parse --short HEAD)
Step ("守卫② HEAD == origin/main ✓（" + $sha + "）")

# ---- C2.3 .env 关键键 ----
$envPath = Join-Path $Repo '.env'
if (-not (Test-Path $envPath)) { Fail 2 ("缺少 .env：" + $envPath) }
$envLines = [System.IO.File]::ReadAllLines($envPath)
foreach ($k in @('ASR_MODEL_HOST_DIR', 'REGISTRY_PREFIX')) {
    $hit = $envLines | Where-Object { $_ -match ('^\s*' + $k + '\s*=') }
    if (-not $hit) { Fail 2 (".env 缺少必需键：" + $k) }
}
Step "守卫③ .env 关键键齐全（ASR_MODEL_HOST_DIR / REGISTRY_PREFIX）✓"

# ---- C2.4 Docker 引擎（docker info + docker ps -a 双验证，K-112） ----
& docker info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 2 "Docker 引擎不可用（docker info 失败）" }
& docker ps -a 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 2 "Docker 引擎不可用（docker ps -a 失败）" }
Step "守卫④ Docker 引擎可用 ✓"

# ---- C2.5 存量服务健康快照 + 数据基线 + 上一版镜像（C6） ----
# 注意：PS 5.1 下 `2>$null` 会吞掉 stdout（重定向变 Out-File），所有原生命令一律 2>&1 或不过滤；
# 存量检查用 docker ps 名字过滤（compose ps --format json 在 PS 5.1 子进程偶发返回空，行为不定）
$svcLines = & docker ps --filter ('name=' + $ComposeProject + '-') --format '{{.Names}}|{{.Status}}'
$appExists = $false
$healthyOthers = @()
foreach ($l in $svcLines) {
    $name = ($l -split '\|')[0]
    $status = ($l -split '\|')[1]
    if ($name -eq $appContainer) { $appExists = $true; continue }
    if ($name -notmatch ('^' + [regex]::Escape($ComposeProject) + '-(postgres|redis|minio|asr)-1$')) { continue }
    if ($status -notmatch '\(healthy\)') {
        Fail 2 ("存量服务未健康：" + $name + " = " + $status + "（先修复存量栈再部署）")
    }
    $healthyOthers += $name
}
if ($healthyOthers.Count -gt 0) {
    Step ("守卫⑤ 存量服务健康 ✓（" + ($healthyOthers -join ', ') + "）")
} else {
    Step "守卫⑤ 该项目还没有存量服务容器（首次部署）：跳过存量健康检查"
}
if ($appExists) {
    $prevApp = (& docker inspect $appContainer) | ConvertFrom-Json
    $script:prevImageId = $prevApp[0].Image
    Step ("当前 app 容器 image = " + $script:prevImageId)
} else {
    Step "app 容器不存在（全新部署）"
}

# 数据基线（只读；postgres 容器不存在时记为 null，S4 自动跳过比对）
$pgUser = 'mn'; $pgDb = 'meetingnotes'
foreach ($line in $envLines) {
    if ($line -match '^\s*POSTGRES_USER\s*=\s*(\S+)\s*$') { $pgUser = $Matches[1] }
    if ($line -match '^\s*POSTGRES_DB\s*=\s*(\S+)\s*$') { $pgDb = $Matches[1] }
}
function Get-DataCount([string]$sql) {
    $pgAlive = & docker ps --format '{{.Names}}' | Select-String -SimpleMatch $pgContainer -Quiet
    if (-not $pgAlive) { return $null }
    $v = & docker exec $pgContainer psql -U $pgUser -d $pgDb -tAc $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ("$v").Trim()
}
$taskBefore = Get-DataCount 'select count(*) from task'
$userBefore = Get-DataCount 'select count(*) from app_user'
Step ("数据基线（只读）：task=" + $taskBefore + " app_user=" + $userBefore)

# ---- DryRun：打印计划与冒烟期望值表，不执行任何变更命令 ----
if ($DryRun) {
    Step "=== DryRun：以下命令在真实运行时将执行（本次不执行） ==="
    Step ("1. " + '$env:GIT_SHA=' + $sha + " ; docker compose -p " + $ComposeProject + " --project-directory `"" + $Repo + "`" build app")
    Step ("2. docker compose -p " + $ComposeProject + " --project-directory `"" + $Repo + "`" up -d --no-deps app")
    Step ("3. 条件轮询健康（每 2s 一次，总预算 " + $TimeoutSec + "s；超时打印最后 50 行日志并 exit 4）")
    Step "=== 冒烟期望值表（exit 5 判据；本 DryRun 未真栈触发） ==="
    Step ("  S1 GET /api/v1/version → 200 且 build 以 " + $sha + " 开头（对不上 = 主栈跑的不是这个 commit）")
    Step "  S2 Cache-Control：/ 与 /tasks.html = no-store；/components.css /confirm.js /drawer.js = no-cache"
    Step "  S3 GET /api/v1/auth/status → 200 且含 registrationEnabled / adminClaimed 两个布尔"
    Step ("  S4 task/app_user 行数与部署前一致（" + $taskBefore + "/" + $userBefore + "；不同 → exit 6，除非 -AllowDataChange）")
    Step "  S5 全过后打印 [deploy] OK image=<id> build=<sha> uptime=<s>"
    Step "DryRun 结束：0 个变更命令被执行"
    exit 0
}

# ---- C3 构建（K-141：REGISTRY_PREFIX 从 .env 传入；GIT_SHA 由 compose args ${GIT_SHA:-dev} 消费） ----
if (-not $SkipBuild) {
    $env:GIT_SHA = $sha
    $env:REGISTRY_PREFIX = 'docker.m.daocloud.io/'
    foreach ($line in $envLines) {
        if ($line -match '^\s*REGISTRY_PREFIX\s*=\s*(\S+)\s*$') { $env:REGISTRY_PREFIX = $Matches[1] }
    }
    $buildStart = Get-Date
    Step ("构建镜像（GIT_SHA=" + $sha + "，REGISTRY_PREFIX=" + $env:REGISTRY_PREFIX + "）…")
    & docker compose -p $ComposeProject --project-directory $Repo build app
    if ($LASTEXITCODE -ne 0) { Fail 3 "docker compose build app 失败" }
    $buildSec = [int]((Get-Date) - $buildStart).TotalSeconds
    $builtImage = (& docker inspect --format '{{.Id}}' ($ComposeProject + '_app') 2>$null)
    Step ("构建完成：耗时 " + $buildSec + "s；新镜像 ID = " + $(if ($builtImage) { $builtImage } else { '（以容器 image 为准）' }))
} else {
    Step "-SkipBuild：跳过构建，使用现有镜像"
}

# ---- C4 部署（只动 app；C6：失败可回滚信息） ----
Step ("部署：docker compose -p " + $ComposeProject + " --project-directory `"" + $Repo + "`" up -d --no-deps app")
& docker compose -p $ComposeProject --project-directory $Repo up -d --no-deps app
if ($LASTEXITCODE -ne 0) { Fail 4 "up -d --no-deps app 失败" }

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    $appJson = (& docker inspect $appContainer) | ConvertFrom-Json
    if ($appJson[0].State.Health.Status -eq 'healthy') { $healthy = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $healthy) {
    Step ("健康等待超时（" + $TimeoutSec + "s）：最后 50 行日志 ↓")
    & docker logs --tail 50 $appContainer
    Fail 4 ("app 未在预算内 healthy（容器 " + $appContainer + "）")
}
$appJson = (& docker inspect $appContainer) | ConvertFrom-Json
$containerId = $appJson[0].Id
$imageId = $appJson[0].Image
$startedAtStr = $appJson[0].State.StartedAt
# 宿主端口从容器实际映射读取（模板里带引号的 index 在 PS 5.1 原生传参会被打碎，改走 JSON）
$hostPort = $appJson[0].NetworkSettings.Ports.'8080/tcp'[0].HostPort
$verBody = (& curl.exe -s ("http://localhost:" + $hostPort + "/api/v1/version"))
$verBuild = ''
try { $verBuild = (($verBody -join "`n") | ConvertFrom-Json).build } catch { $verBuild = '' }
Step ("app 已 healthy：容器 " + $containerId)
Step ("image = " + $imageId + "；StartedAt = " + $startedAtStr + "；/api/v1/version.build = " + $verBuild)

# ---- C5 冒烟（默认执行；任一失败 → exit 5/S4 → exit 6，并打印失败的那一条） ----
if (-not $SkipSmoke) {
    $base = "http://localhost:" + $hostPort

    # S1 版本一致性
    $raw = & curl.exe -s -w "`n__CODE__%{http_code}" ("$base/api/v1/version")
    $code = ("$($raw | Select-Object -Last 1)") -replace '__CODE__', ''
    $body = (($raw | Select-Object -First (($raw | Measure-Object).Count - 1)) -join "`n")
    if ($code -ne '200') { Fail 5 ("S1 失败：/api/v1/version HTTP=" + $code) }
    $ver = $body | ConvertFrom-Json
    if (-not ("$($ver.build)".StartsWith($sha))) {
        Fail 5 ("S1 失败：主栈跑的不是这个 commit（build=" + $ver.build + "，期望前缀 " + $sha + "）")
    }
    Step ("冒烟 S1 ✓ build=" + $ver.build)

    # S2 静态头矩阵
    function Test-CacheHeader([string]$path, [string]$needle) {
        $hdr = & curl.exe -s -D - -o NUL ("$base" + $path)
        $cc = ("$($hdr | Where-Object { $_ -match '^Cache-Control:' })")
        return ($cc -match [regex]::Escape($needle))
    }
    if (-not (Test-CacheHeader '/' 'no-store')) { Fail 5 "S2 失败：/ 的 Cache-Control 不是 no-store" }
    if (-not (Test-CacheHeader '/tasks.html' 'no-store')) { Fail 5 "S2 失败：/tasks.html 的 Cache-Control 不是 no-store" }
    foreach ($p in @('/components.css', '/confirm.js', '/drawer.js')) {
        if (-not (Test-CacheHeader $p 'no-cache')) { Fail 5 ("S2 失败：" + $p + " 的 Cache-Control 不是 no-cache") }
    }
    Step "冒烟 S2 ✓（HTML no-store / CSS·JS no-cache）"

    # S3 注册状态两布尔
    $raw3 = & curl.exe -s -w "`n__CODE__%{http_code}" ("$base/api/v1/auth/status")
    $code3 = ("$($raw3 | Select-Object -Last 1)") -replace '__CODE__', ''
    $body3 = (($raw3 | Select-Object -First (($raw3 | Measure-Object).Count - 1)) -join "`n") | ConvertFrom-Json
    if ($code3 -ne '200' -or $null -eq $body3.registrationEnabled -or $null -eq $body3.adminClaimed) {
        Fail 5 ("S3 失败：/api/v1/auth/status HTTP=" + $code3 + "（缺 registrationEnabled/adminClaimed）")
    }
    Step ("冒烟 S3 ✓ registrationEnabled=" + $body3.registrationEnabled + " adminClaimed=" + $body3.adminClaimed)

    # S4 数据未变（只读比对；-AllowDataChange 放行）
    $taskAfter = Get-DataCount 'select count(*) from task'
    $userAfter = Get-DataCount 'select count(*) from app_user'
    if (($taskAfter -ne $taskBefore) -or ($userAfter -ne $userBefore)) {
        Step ("数据行数变化：task " + $taskBefore + " → " + $taskAfter + "；app_user " + $userBefore + " → " + $userAfter)
        if (-not $AllowDataChange) { Fail 6 "部署前后数据行数不一致（未放行 -AllowDataChange）" }
        Step "-AllowDataChange 已放行：继续"
    } else {
        Step ("冒烟 S4 ✓ 数据未变（task=" + $taskAfter + " app_user=" + $userAfter + "）")
    }
}

# ---- S5 单行汇总 ----
$uptimeSec = 0
try {
    $startedDt = [datetime]::Parse($startedAtStr, [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::RoundtripKind)
    # PS DateTime 减法不转换 Kind——两边都转 UTC 再相减（否则差出一个时区）
    $uptimeSec = [int](((Get-Date).ToUniversalTime() - $startedDt.ToUniversalTime()).TotalSeconds)
} catch { $uptimeSec = -1 }
Step ("OK image=" + $imageId + " build=" + $sha + " uptime=" + $uptimeSec + "s")
exit 0
