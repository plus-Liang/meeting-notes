# save.ps1 - one-command commit + push helper (solo dev, safe defaults)
#
# Why: the daily loop should be one command, but blind `git add -A` is banned
# in this project. So:
#   - default: stage ONLY tracked changes (modified/deleted)
#   - -All   : also stage new files, after printing exactly what will be added
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "feat(asr): add openai-compatible server"
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "..." -All
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\save.ps1 -Message "..." -NoPush

param(
    [Parameter(Mandatory = $true)][string]$Message,
    [switch]$All,
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "== working tree ==" -ForegroundColor Cyan
git status --short

$dirty = (git status --porcelain)
if (-not $dirty) {
    Write-Host "nothing to commit." -ForegroundColor Green
    exit 0
}

# --- stage ---------------------------------------------------------------
if ($All) {
    $untracked = @(git ls-files --others --exclude-standard)
    if ($untracked.Count -gt 0) {
        Write-Host ""
        Write-Host "new files to be added:" -ForegroundColor Yellow
        $untracked | ForEach-Object { Write-Host ("  + " + $_) }
        $answer = Read-Host "confirm adding these $($untracked.Count) new file(s)? (y/N)"
        if ($answer -ne 'y' -and $answer -ne 'Y') {
            Write-Host "aborted." -ForegroundColor Red
            exit 1
        }
    }
    git add -A
} else {
    # tracked files only: modified + deleted
    git add -u
}

if (-not (git diff --cached --name-only)) {
    Write-Host "nothing staged." -ForegroundColor Yellow
    exit 0
}

# --- commit --------------------------------------------------------------
Write-Host ""
Write-Host "== staged ==" -ForegroundColor Cyan
git diff --cached --name-only

git commit -m $Message
if ($LASTEXITCODE -ne 0) { throw "commit failed" }

# --- push ----------------------------------------------------------------
$remotes = @(git remote)
if ($remotes -notcontains 'origin') {
    Write-Host ""
    Write-Host "committed locally. no 'origin' remote yet - add it with:" -ForegroundColor Yellow
    Write-Host "  git remote add origin https://github.com/<user>/meeting-notes.git"
    exit 0
}

if ($NoPush) {
    Write-Host "committed locally (-NoPush)." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "== pushing ==" -ForegroundColor Cyan
$branch = git rev-parse --abbrev-ref HEAD
git push -u origin $branch
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "push failed. common causes:" -ForegroundColor Red
    Write-Host "  - network: try again / use proxy"
    Write-Host "  - auth: Windows credential prompt may be hidden; run 'git push' manually once"
    Write-Host "  - 403 with password: create a Personal Access Token and use it as password"
    exit 1
}

Write-Host ""
Write-Host "done." -ForegroundColor Green
git log --oneline -1
