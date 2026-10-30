param(
    [string]$MavenCommand = 'mvn',
    [string]$MavenRepository = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'Set DB_PASSWORD before running; skipped MySQL tests do not count as a successful concurrency check.'
}

$taskProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskSuites = @(
    'com.limitactivitysystem.activity.integration.FreeReservationMySqlIntegrationTest',
    'com.limitactivitysystem.activity.integration.FreeReservationSchemaMySqlIntegrationTest',
    'com.limitactivitysystem.reservation.controller.ReservationRequestContractTest'
)
$taskMavenArgs = @("-Dtest=$($taskSuites -join ',')")
if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
    $taskMavenArgs += "-Dmaven.repo.local=$MavenRepository"
}
$taskMavenArgs += 'test'
$taskStartedAt = [DateTime]::UtcNow

Push-Location -LiteralPath $taskProjectRoot
try {
    & $MavenCommand @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Day 9 verification failed (Maven exit $LASTEXITCODE)." }

    $taskResults = @()
    $taskArtifacts = @()
    foreach ($taskSuite in $taskSuites) {
        $taskReportPath = "target/surefire-reports/TEST-$taskSuite.xml"
        if ((Get-Item -LiteralPath $taskReportPath).LastWriteTimeUtc -lt $taskStartedAt) {
            throw "Stale test report: $taskReportPath"
        }
        [xml]$taskReport = Get-Content -LiteralPath $taskReportPath -Raw -Encoding utf8
        $taskResult = [ordered]@{
            suite = $taskSuite
            tests = [int]$taskReport.testsuite.tests
            failures = [int]$taskReport.testsuite.failures
            errors = [int]$taskReport.testsuite.errors
            skipped = [int]$taskReport.testsuite.skipped
        }
        if ($taskResult.tests -eq 0 -or $taskResult.failures -ne 0 -or $taskResult.errors -ne 0 -or $taskResult.skipped -ne 0) {
            throw "Tests failed, were skipped, or did not run: $taskSuite"
        }
        $taskResults += [pscustomobject]$taskResult
        $taskTextReport = "target/surefire-reports/$taskSuite.txt"
        $taskArtifacts += [ordered]@{
            file = [System.IO.Path]::GetFileName($taskTextReport)
            sha256 = (Get-FileHash -LiteralPath $taskTextReport -Algorithm SHA256).Hash
        }
    }

    $taskEvidenceNames = @(
        'day9-at01-multiple-sessions-user.json',
        'day9-at01-multiple-sessions-publisher.json',
        'day9-at03-history.json',
        'day9-at03-public-pool-former-owner.json',
        'day9-at03-public-pool-other-account.json',
        'day9-at02-last-slot-c20.json',
        'day9-http-active-key-race.json',
        'day9-http-cancel-race.json',
        'day9-cancel-boundary-200500000.json',
        'day9-cancel-boundary-205959999.json',
        'day9-cancel-boundary-210000000.json',
        'day9-cancel-boundary-210000001.json',
        'day9-rollback-after-takeOne.json',
        'day9-rollback-after-insertIfReservable.json',
        'day9-rollback-after-cancelIfActiveBeforeStart.json',
        'day9-rollback-after-restoreOne.json',
        'day9-invariants-user.json',
        'day9-invariants-session.json',
        'day9-invariants-activity.json',
        'day9-invariants-paid_activity.json',
        'day9-invariants-quota.json',
        'day9-invariants-capacity.json',
        'day9-invariants-remaining.json'
    )
    foreach ($taskName in $taskEvidenceNames) {
        $taskEvidencePath = Join-Path 'target/verification' $taskName
        if ((Get-Item -LiteralPath $taskEvidencePath).LastWriteTimeUtc -lt $taskStartedAt) {
            throw "Stale verification evidence: $taskEvidencePath"
        }
        $taskArtifacts += [ordered]@{
            file = $taskName
            sha256 = (Get-FileHash -LiteralPath $taskEvidencePath -Algorithm SHA256).Hash
        }
    }
    $taskSummary = [ordered]@{
        startedAtUtc = $taskStartedAt.ToString('o')
        recordedAtUtc = [DateTime]::UtcNow.ToString('o')
        result = 'PASS'
        tests = [int]($taskResults | Measure-Object -Property tests -Sum).Sum
        failures = 0
        errors = 0
        skipped = 0
        acceptance = [ordered]@{ 'AT-01' = 'PASS'; 'AT-02' = 'PASS'; 'AT-03' = 'PASS' }
        invariantSql = [ordered]@{
            file = 'tests/sql/reservation_invariants.sql'
            sha256 = (Get-FileHash -LiteralPath 'tests/sql/reservation_invariants.sql' -Algorithm SHA256).Hash
        }
        suites = $taskResults
        artifacts = $taskArtifacts
    }
    $taskSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath 'target/verification/day9-core-run-summary.json' -Encoding utf8
    Write-Output "Day 9 verification passed: $($taskSummary.tests) tests, no failures, errors, or skips."
    Write-Output 'Evidence: target/verification/day9-core-run-summary.json'
} finally {
    Pop-Location
}
