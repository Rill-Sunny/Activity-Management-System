param(
    [Parameter(Mandatory=$true)][ValidateSet('Start','Finish')][string]$Phase,
    [Parameter(Mandatory=$true)][string]$RunId,
    [int]$ExitCode = -999
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'RunId must contain letters, digits, underscore or hyphen.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$destination = Join-Path $repo "qa/verification/day21/$RunId"
$manifestPath = Join-Path $destination 'start.json'
function Get-SourceHashes {
    $hashes = [ordered]@{}
    $paths = & git -C $repo ls-files --cached --others --exclude-standard -- src tests pom.xml
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files.' }
    foreach ($path in ($paths | Sort-Object -Unique)) {
        $fullPath = Join-Path $repo $path
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            $hashes[$path] = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
        }
    }
    return $hashes
}
if ($Phase -eq 'Start') {
    if (Test-Path -LiteralPath $manifestPath) { throw 'Use a new RunId; existing evidence is preserved.' }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    [ordered]@{
        startedUtc = [DateTime]::UtcNow.ToString('o')
        commit = (& git -C $repo rev-parse HEAD)
        workingTree = @(& git -C $repo status --short)
        sourceHashes = Get-SourceHashes
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Output "Run IDEA Maven test now. Evidence: $destination"
    exit 0
}
if ($ExitCode -eq -999) { throw 'Supply the actual IDEA Maven process ExitCode.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$started = [DateTime]::Parse($manifest.startedUtc).ToUniversalTime()
$current = Get-SourceHashes
$original = [ordered]@{}
$manifest.sourceHashes.PSObject.Properties | ForEach-Object { $original[$_.Name] = $_.Value }
$sourceUnchanged = (ConvertTo-Json $original -Compress) -ceq (ConvertTo-Json $current -Compress)
$reports = @(Get-ChildItem -LiteralPath (Join-Path $repo 'target/surefire-reports') -Filter 'TEST-*.xml')
if ($reports.Count -eq 0) { throw 'No Surefire XML reports. A compiler failure is not a test pass.' }
$counts = [ordered]@{ tests=0; failures=0; errors=0; skipped=0 }
$stale = @()
$cases = @()
foreach ($report in $reports) {
    if ($report.LastWriteTimeUtc -lt $started) { $stale += $report.Name; continue }
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    foreach ($suite in $xml.SelectNodes('//testsuite')) {
        foreach ($key in @('tests','failures','errors','skipped')) { $counts[$key] += [int]$suite.GetAttribute($key) }
        foreach ($case in $suite.SelectNodes('testcase')) {
            $cases += [ordered]@{ class=$case.classname; name=$case.name; passed=($null -eq $case.SelectSingleNode('failure|error|skipped')) }
        }
    }
    # Preserve only case results. Raw Surefire XML may contain environment credentials; retain it locally.
}
[ordered]@{
    commit=$manifest.commit; sourceUnchanged=$sourceUnchanged; exitCode=$ExitCode
    counts=$counts; staleReports=$stale; cases=$cases
    suitePassed=($ExitCode -eq 0 -and $sourceUnchanged -and $stale.Count -eq 0 -and $counts.tests -gt 0 -and $counts.failures -eq 0 -and $counts.errors -eq 0)
    m3Status='PENDING: review mandatory skipped tests, reconciliation, broker snapshots and trace evidence'
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $destination 'results.json') -Encoding UTF8
Write-Output "Saved $destination/results.json"
