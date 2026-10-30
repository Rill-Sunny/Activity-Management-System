param(
    [ValidateSet(20, 50)]
    [int[]]$Concurrency = @(20, 50),
    [string]$MavenCommand = 'mvn',
    [string]$MavenRepository = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'Set DB_PASSWORD before running; skipped MySQL tests do not count as a successful concurrency check.'
}

$taskProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskSuites = 'TicketOrderSmallConcurrencyMySqlIntegrationTest,TicketOrderClosingMySqlIntegrationTest,PaymentRetryMySqlIntegrationTest,PaymentRetryExecutorTest'
$taskMavenArgs = @("-Dtest=$taskSuites", "-Dday8.concurrency.levels=$($Concurrency -join ',')")
if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
    $taskMavenArgs += "-Dmaven.repo.local=$MavenRepository"
}
$taskMavenArgs += 'test'

Push-Location -LiteralPath $taskProjectRoot
try {
    & $MavenCommand @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Day 8 verification failed (Maven exit $LASTEXITCODE)." }
    Write-Output 'Day 8 verification passed. Evidence: target/verification/day8-concurrency-*.json'
} finally {
    Pop-Location
}
