param([Parameter(Mandatory)][string]$Label,[string]$Parent='eea3badecc63fd1fc0c638dbb9db628d415f0fcd')
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Lab ($Label -match '^[a-z0-9-]+$') 'Invalid snapshot label'
$folder=Join-Path $RepoRoot 'qa/verification/day27';[void][IO.Directory]::CreateDirectory($folder)
$index=Join-Path $folder ('snapshot-index-'+[Guid]::NewGuid().ToString('N'))
$normal=(Get-FileHash (Join-Path $RepoRoot '.git/index')).Hash
$previous=$env:GIT_INDEX_FILE
try{
    $env:GIT_INDEX_FILE=$index
    & git read-tree $Parent
    Assert-Lab ($LASTEXITCODE -eq 0) 'Cannot read snapshot parent'
    & git -c core.safecrlf=false add -- src tests deploy pom.xml .gitignore .dockerignore docs README.md 2> (Join-Path $folder 'snapshot-warnings.log')
    Assert-Lab ($LASTEXITCODE -eq 0) 'Snapshot staging failed'
    $paths=@(& git diff --cached --name-only $Parent)
    Assert-Lab (-not @($paths|Where-Object{$_ -match '(^|/)\.env($|\.)' -and $_ -notmatch '\.example$'}).Count) 'Private env file detected'
    $tree=(& git write-tree).Trim();$commit=(& git commit-tree $tree -p $Parent -m "Day 27/28 $Label source snapshot").Trim()
    Assert-Lab ($LASTEXITCODE -eq 0 -and $commit -match '^[a-f0-9]{40}$') 'Snapshot commit failed'
    & git update-ref "refs/heads/codex/day27-day28-$Label" $commit
    Save-Lab (Join-Path $folder ("snapshot-$Label.json")) @{commit=$commit;parent=$Parent;tree=$tree;changedPaths=$paths;createdAt=[DateTimeOffset]::Now.ToString('o');mainIndexHash=$normal}
    Write-Output $commit
}finally{
    $env:GIT_INDEX_FILE=$previous
    Assert-Lab ((Get-FileHash (Join-Path $RepoRoot '.git/index')).Hash -eq $normal) 'Original Git index changed'
}
