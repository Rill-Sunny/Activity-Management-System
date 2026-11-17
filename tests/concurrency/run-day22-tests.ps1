param(
    [ValidatePattern('^[A-Za-z0-9_,*]+$')][string]$Tests = 'ReschedulePolicyTest,SessionRescheduleMySqlIntegrationTest',
    [switch]$FullSuite,
    [switch]$Online
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = 'C:\Users\32170\.jdks\openjdk-23.0.1\bin\java.exe'
$javac = 'C:\Users\32170\.jdks\openjdk-23.0.1\bin\javac.exe'
$maven = 'D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'
Push-Location $repo
$previous = @{}
$created = $false
try {
    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    $evidence = Join-Path $repo "qa/verification/day22/$runId"
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null
    $cache = Join-Path $evidence 'runner'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $classpathFile = Join-Path $repo 'qa/verification/day22/cached-test-classpath.txt'
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        $report = Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' | Select-Object -First 1
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        $classpath = ($suite.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
        if (-not $classpath) { throw 'Existing Surefire classpath is required.' }
        [IO.File]::WriteAllText($classpathFile,$classpath)
    }
    $classpath = [IO.File]::ReadAllText($classpathFile)
    & $javac --release 23 -encoding UTF-8 -proc:none -classpath $classpath -d $cache 'tests/fixtures/day22/Day22TestDatabase.java'
    if ($LASTEXITCODE -ne 0) { throw 'Database helper compile failed.' }
    [xml]$config = Get-Content -LiteralPath '.run/Day21-Product.run.xml' -Raw
    $settings = @{}
    foreach ($variable in $config.SelectNodes('//env')) {
        if ($variable.name -match '^(DB_PASSWORD|SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME|RABBITMQ_.+|REDIS_HOST|REDIS_PORT)$') {
            $settings[$variable.name] = $variable.value
        }
    }
    if (-not $settings['DB_PASSWORD'] -or -not $settings.ContainsKey('RABBITMQ_PASSWORD')) { throw 'Existing DB and Rabbit credentials are required.' }
    $baseUrl = if ($settings['SPRING_DATASOURCE_URL']) { $settings['SPRING_DATASOURCE_URL'] } else { 'jdbc:mysql://127.0.0.1:3306/limit_activity_system?serverTimezone=Asia/Shanghai' }
    if ($baseUrl -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1):[0-9]+/[^?]+(\?.*)?$') { throw 'Only approved local MySQL endpoints are supported.' }
    $schema = 'day22_suite_' + [Guid]::NewGuid().ToString('N')
    $settings['DAY22_BASE_URL'] = $baseUrl
    $settings['DAY22_SUITE_SCHEMA'] = $schema
    $settings['DAY22_REDIS_PREFIX'] = 'las:test:day22:' + $runId + ':'
    $settings['DAY22_EVIDENCE_DIRECTORY'] = $evidence
    $question = $baseUrl.IndexOf('?')
    $address = if($question -lt 0) { $baseUrl } else { $baseUrl.Substring(0,$question) }
    $options = if($question -lt 0) { '' } else { $baseUrl.Substring($question) }
    $settings['SPRING_DATASOURCE_URL'] = $address.Substring(0,$address.LastIndexOf('/')+1) + $schema + $options
    foreach ($key in @('RUN_DAY20_LINKAGE_TESTS','RUN_DAY21_TRACE_TESTS','RUN_MQ_RELIABILITY_TESTS','REDIS_TEST_ENABLED','RUN_DAY22_TESTS')) { $settings[$key]='true' }
    foreach ($key in @('RUN_DAY21_FAULT_TESTS','RUN_DAY21_RECONCILIATION_TESTS')) { $settings[$key]='false' }
    $settings['JAVA_HOME']='C:\Users\32170\.jdks\openjdk-23.0.1'
    foreach ($key in $settings.Keys) {
        $previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
        [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')
    }
    $source = @(@(Get-ChildItem -LiteralPath 'src','tests' -Recurse -File) + @(Get-Item -LiteralPath 'pom.xml') | ForEach-Object {
        @{path=$_.FullName.Substring($repo.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    @{head=(& git rev-parse HEAD);phase='PRE_RUN';files=$source} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'source-before.json') -Encoding UTF8
    $runtime = $cache + ';' + $classpath
    # Recover only the exact owned schema recorded by this task's initial URL-parsing failure.
    $failedPreparation=Join-Path $repo 'qa/verification/day22/20261111-075444-f8a7ab84/database-prepare.log'
    $recoveredMarker=Join-Path $repo 'qa/verification/day22/20261111-075444-f8a7ab84/owned-schema-cleaned.log'
    if((Test-Path -LiteralPath $failedPreparation) -and -not (Test-Path -LiteralPath $recoveredMarker)) {
        $failedText=Get-Content -LiteralPath $failedPreparation -Raw
        $ownedMatch=[regex]::Match($failedText,'Unknown time-zone ID: Asia/(day22_suite_[a-f0-9]{32})')
        if($ownedMatch.Success) {
            [Environment]::SetEnvironmentVariable('DAY22_SUITE_SCHEMA',$ownedMatch.Groups[1].Value,'Process')
            & $java -classpath $runtime Day22TestDatabase drop *> $recoveredMarker
            $recoveredExit=$LASTEXITCODE
            [Environment]::SetEnvironmentVariable('DAY22_SUITE_SCHEMA',$schema,'Process')
            if($recoveredExit -ne 0) {throw 'Initial owned schema cleanup failed.'}
        }
    }
    @{schema=$schema;redisPrefix=$settings['DAY22_REDIS_PREFIX']} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'resources.json') -Encoding UTF8
    $created=$true
    & $java -classpath $runtime Day22TestDatabase create *> (Join-Path $evidence 'database-prepare.log')
    if ($LASTEXITCODE -ne 0) { throw "Database preparation failed: $evidence/database-prepare.log" }
    $created=$true
    $arguments=if($FullSuite) {@('-B','clean','test')} else {@('-B','test')}
    $arguments+=('-Dcache.redis.key-prefix='+$settings['DAY22_REDIS_PREFIX'])
    if (-not $Online) { $arguments=@('-o')+$arguments }
    if (-not $FullSuite) { $arguments+=('-Dtest='+$Tests) }
    $start=Get-Date
    Write-Output "Running Maven tests. Evidence: $evidence"
    & $maven @arguments *> (Join-Path $evidence 'maven.log')
    $result=$LASTEXITCODE
    $finish=Get-Date
    $sourceAfter = @(@(Get-ChildItem -LiteralPath 'src','tests' -Recurse -File) + @(Get-Item -LiteralPath 'pom.xml') | ForEach-Object {
        @{path=$_.FullName.Substring($repo.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    $sourceBeforeKeys=@($source | ForEach-Object {$_.path+':'+$_.sha256})
    $sourceAfterKeys=@($sourceAfter | ForEach-Object {$_.path+':'+$_.sha256})
    $unchanged=@(Compare-Object $sourceBeforeKeys $sourceAfterKeys).Count -eq 0
    @{phase='POST_RUN';sourceUnchangedDuringRun=$unchanged;files=$sourceAfter} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'source-after.json') -Encoding UTF8
    New-Item -ItemType Directory -Path (Join-Path $evidence 'surefire') -Force | Out-Null
    Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' | Where-Object {$_.LastWriteTime -ge $start} | Copy-Item -Destination (Join-Path $evidence 'surefire')
    foreach ($directory in @('target/day22','target/verification')) {
        if(Test-Path -LiteralPath $directory) { Copy-Item -LiteralPath $directory -Destination $evidence -Recurse }
    }
    $reports=@(Get-ChildItem -LiteralPath (Join-Path $evidence 'surefire') -Filter 'TEST-*.xml')
    $counts=@{tests=0;failures=0;errors=0;skipped=0}
    foreach($report in $reports) {
        [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
        foreach($key in @('tests','failures','errors','skipped')) {$counts[$key]+=[int]$xml.testsuite.GetAttribute($key)}
    }
    $summary=@{runId=$runId;startedAt=$start.ToString('o');finishedAt=$finish.ToString('o');exitCode=$result;
        command=('mvn '+($arguments -join ' '));counts=$counts;schema=$schema;sourceUnchangedDuringRun=$unchanged;gitHead=(& git rev-parse HEAD)}
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding UTF8
    $summary | ConvertTo-Json -Depth 5
    Get-Content -LiteralPath (Join-Path $evidence 'maven.log') -Tail 18
    exit $result
} finally {
    if ($created) {
        & $java -classpath $runtime Day22TestDatabase drop *> (Join-Path $evidence 'database-cleanup.log')
        if($LASTEXITCODE -ne 0) { Write-Warning "Owned test database cleanup failed; see $evidence/database-cleanup.log" }
    }
    foreach($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
    Pop-Location
}
