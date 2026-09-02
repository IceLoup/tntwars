param(
    [switch]$Push,
    [string]$Tag = "latest",
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
Set-Location $rootDir

$gitSha = "unknown"
$gitDirty = 0
if (Get-Command git -ErrorAction SilentlyContinue) {
    try {
        $gitSha = (git rev-parse --short HEAD 2>$null)
        if ([string]::IsNullOrWhiteSpace($gitSha)) {
            $gitSha = "unknown"
        }
        $gitDirty = (git status --porcelain 2>$null | Measure-Object).Count
    } catch {
        $gitSha = "unknown"
    }
}

if ($gitSha -eq "unknown") {
    $versionTag = "local-$(Get-Date -Format yyyyMMddHHmmss)"
} elseif ($gitDirty -gt 0) {
    $versionTag = "$gitSha-dirty-$(Get-Date -Format yyyyMMddHHmmss)"
} else {
    $versionTag = $gitSha
}

$fullTag = "$Tag-$versionTag"
$cacheArgs = @()
if ($NoCache) {
    $cacheArgs += "--no-cache"
}

Write-Host "==> Building plugin jars"
mvn -q -DskipTests package

Write-Host "==> Staging plugin jars into template contexts"
New-Item -ItemType Directory -Force templates/proxy/plugins | Out-Null
New-Item -ItemType Directory -Force templates/gameserver/plugins | Out-Null
New-Item -ItemType Directory -Force templates/lobby/plugins | Out-Null

Copy-Item tournament-velocity/target/tournament-velocity-1.0.0-SNAPSHOT.jar templates/proxy/plugins/ -Force
Copy-Item tournament-paper/target/tournament-paper-1.0.0-SNAPSHOT.jar templates/gameserver/plugins/ -Force
Copy-Item tournament-paper/target/tournament-paper-1.0.0-SNAPSHOT.jar templates/lobby/plugins/ -Force

Write-Host "==> Building template images (tag: $fullTag)"

Write-Host "  -> Building tournament-velocity..."
docker build @cacheArgs -t "tournament-velocity:$fullTag" -t "tournament-velocity:latest" templates/proxy

Write-Host "  -> Building tournament-gameserver..."
docker build @cacheArgs -t "tournament-gameserver:$fullTag" -t "tournament-gameserver:latest" templates/gameserver

Write-Host "  -> Building tournament-lobby..."
docker build @cacheArgs -t "tournament-lobby:$fullTag" -t "tournament-lobby:latest" templates/lobby

if ($Push) {
    Write-Host "==> Pushing images to registry"
    docker push "tournament-velocity:$fullTag"
    docker push "tournament-velocity:latest"
    docker push "tournament-gameserver:$fullTag"
    docker push "tournament-gameserver:latest"
    docker push "tournament-lobby:$fullTag"
    docker push "tournament-lobby:latest"
}

Write-Host "==> Done. Images built:"
Write-Host "  tournament-velocity:$fullTag (also tagged as latest)"
Write-Host "  tournament-gameserver:$fullTag (also tagged as latest)"
Write-Host "  tournament-lobby:$fullTag (also tagged as latest)"
Write-Host ""
Write-Host "To start the stack: docker compose -f docker/docker-compose.yml up -d"
