param([Parameter(Mandatory)][string]$Label,[string]$Parent='eb3ae20c7c3e9f34a0a3add152842e63b516c8d6')
. (Join-Path $PSScriptRoot '../system/common.ps1')
Assert-Lab ($Label -match '^[a-z0-9-]+$') 'Invalid release snapshot label'
$branch="refs/heads/codex/release-$Label"
& git show-ref --verify --quiet $branch
Assert-Lab ($LASTEXITCODE -ne 0) 'Snapshot ref exists; choose a new label instead of rewriting history'
$dir=Join-Path $RepoRoot 'qa/verification/day29';[void][IO.Directory]::CreateDirectory($dir)
$prior=[Environment]::GetEnvironmentVariable('GIT_INDEX_FILE','Process')
$normal=(Get-FileHash (Join-Path $RepoRoot '.git/index')).Hash
try{
    $env:GIT_INDEX_FILE=Join-Path $dir ('index-'+[Guid]::NewGuid().ToString('N'))
    & git read-tree $Parent
    Assert-Lab ($LASTEXITCODE -eq 0) 'Snapshot parent missing'
    & git -c core.safecrlf=false add -A -- src tests deploy database docs pom.xml README.md .gitignore .dockerignore 2> (Join-Path $dir 'snapshot-warnings.log')
    Assert-Lab ($LASTEXITCODE -eq 0) 'Release staging failed'
    $paths=@(& git diff --cached --name-only $Parent)
    Assert-Lab (-not @($paths|Where-Object{$_ -match '(^|/)\.env($|\.)' -and $_ -notmatch '\.example$'}).Count) 'Private environment file staged'
    $tree=(& git write-tree).Trim()
    $commit=(& git commit-tree $tree -p $Parent -m "Prepare LAS release: $Label").Trim()
    Assert-Lab ($LASTEXITCODE -eq 0 -and $commit -match '^[a-f0-9]{40}$') 'Commit creation failed'
    & git update-ref $branch $commit
    Save-Lab (Join-Path $dir "snapshot-$Label.json") @{commit=$commit;parent=$Parent;branch=$branch;changedPaths=$paths;mainIndexHash=$normal;at=[DateTimeOffset]::Now.ToString('o')}
    Write-Output $commit
}finally{
    Restore-LabEnvironment @{GIT_INDEX_FILE=$prior}
    Assert-Lab ((Get-FileHash (Join-Path $RepoRoot '.git/index')).Hash -eq $normal) 'Original Git index changed'
}
