param(
    [ValidateSet('Start','Specialist','DualInstance','FullSuite','Reconcile')][string]$Stage='Start',
    [string]$RunDirectory,
    [string]$SourceCommit,
    [string]$MavenCommand='D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd',
    [string]$JavaHome='C:\Users\32170\.jdks\openjdk-23.0.1'
)
. (Join-Path $PSScriptRoot '../../deploy/scripts/common.ps1')
function Check([bool]$condition,[string]$message){if(-not $condition){throw $message}}
function Save([string]$name,$value){$value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath (Join-Path $evidence $name) -Encoding utf8}
function Compose([string[]]$arguments){(Invoke-Program docker ($compose+$arguments)).Output}
function Ready([string]$url){try{return (Invoke-RestMethod ($url+'/actuator/health') -TimeoutSec 5).status -eq 'UP'}catch{return $false}}
function Wait-Ready {
    $deadline=[DateTime]::UtcNow.AddMinutes(6)
    do{if((Ready $appA) -and (Ready $appB)){return};Start-Sleep -Seconds 2}while([DateTime]::UtcNow -lt $deadline)
    throw 'Day 26 applications did not become ready'
}
function Source-Hashes {
    @(@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src'),(Join-Path $RepoRoot 'tests'),(Join-Path $RepoRoot 'deploy') -Recurse -File)+@(Get-Item (Join-Path $RepoRoot 'pom.xml')) |
        Where-Object {$_.Name -notlike '.env*' -or $_.Name -like '*.example'} | ForEach-Object {
            @{path=$_.FullName.Substring($RepoRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
        })
}
function Reconcile {
    $files=@(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'tests/sql') -Filter '*.sql' | Sort-Object Name)
    $sql="SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;`n"
    foreach($file in $files){$sql+="SELECT 'BEGIN:$($file.Name)';`n"+[IO.File]::ReadAllText($file.FullName)+"`nSELECT 'END:$($file.Name)';`n"}
    $text=Invoke-DatabaseSql $compose ($sql+'COMMIT;')
    $text | Set-Content -LiteralPath (Join-Path $evidence 'reconciliation.txt') -Encoding utf8
    $rows=@()
    foreach($file in $files){
        $match=[regex]::Match($text,'(?s)BEGIN:'+ [regex]::Escape($file.Name)+'\r?\n(.*?)\r?\n?END:'+ [regex]::Escape($file.Name))
        Check $match.Success "Missing audit output $($file.Name)"
        $diagnostic=$file.Name -eq 'messaging_reconciliation.sql';$body=$match.Groups[1].Value.Trim()
        $rows+=@{script=$file.Name;diagnostic=$diagnostic;differences=if($diagnostic){$null}elseif($body){@($body -split '\r?\n').Count}else{0}}
        if(-not $diagnostic){Check (-not $body) "Invariant difference in $($file.Name): $body"}
    }
    Save 'reconciliation.json' $rows
}
Push-Location $RepoRoot
try {
    if($RunDirectory){
        $evidence=[IO.Path]::GetFullPath($RunDirectory)
        $context=Get-Content -LiteralPath (Join-Path $evidence 'context.json') -Raw | ConvertFrom-Json
        Check ($context.project -match '^lasday26-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') 'Not a Day 26 isolated project'
        $project=$context.project;$envFile=$context.envFile
    }else{
        Check ($Stage -eq 'Start') 'Use -RunDirectory for later stages'
        $runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
        $evidence=Join-Path $RepoRoot "qa/verification/day26/$runId"
        [void][IO.Directory]::CreateDirectory($evidence)
        $project='lasday26-'+$runId;$envFile=Join-Path $evidence '.env'
        $content=Get-Content -LiteralPath (Join-Path $DeployRoot '.env.example') -Raw
        $overrides=@{MYSQL_DATABASE='day26_acceptance';MYSQL_USER='day26_app';RABBITMQ_USERNAME='day26_app';TRACING_ENABLED='true';TRACING_SAMPLING_PROBABILITY='1.0';APP_IMAGE='las-backend:day26'}
        $ports=[Collections.Generic.HashSet[int]]::new()
        foreach($name in @('APP_A_PORT','APP_B_PORT','PROMETHEUS_PORT','GRAFANA_PORT','TEMPO_PORT','TEST_MYSQL_PORT','TEST_REDIS_PORT','TEST_RABBIT_PORT','TEST_RABBIT_MANAGEMENT_PORT','TEST_OTLP_PORT')){
            do{$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$probe.Start();$port=$probe.LocalEndpoint.Port;$probe.Stop()}while(-not $ports.Add($port))
            $overrides[$name]=[string]$port
        }
        foreach($name in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_PASSWORD','METRICS_PASSWORD','GRAFANA_PASSWORD')){$overrides[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
        $overrides.JWT_SECRET_BASE64=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
        foreach($name in $overrides.Keys){if($content -match "(?m)^$name="){$content=[regex]::Replace($content,"(?m)^$name=.*$",$name+'='+$overrides[$name])}else{$content+=[Environment]::NewLine+$name+'='+$overrides[$name]}}
        [IO.File]::WriteAllText($envFile,$content,[Text.UTF8Encoding]::new($false))
        Save 'context.json' @{runId=$runId;project=$project;envFile=$envFile;createdAt=[DateTimeOffset]::Now.ToString('o')}
    }
    $settings=Read-DeploymentEnvironment $envFile
    $compose=(Get-ComposeArguments $project $envFile)+@('-f',(Join-Path $DeployRoot 'compose.acceptance.yaml'),'-f',(Join-Path $DeployRoot 'compose.day26-tests.yaml'),'--profile','core','--profile','observability')
    $appA='http://127.0.0.1:'+$settings.APP_A_PORT;$appB='http://127.0.0.1:'+$settings.APP_B_PORT
    Write-Output "Day26 $Stage evidence: $evidence"
    if($Stage -eq 'Start'){
        [void](Compose @('config','--quiet'))
        [void](Compose @('up','-d','--pull','never'))
        Wait-Ready
        $mysql=Invoke-DatabaseSql $compose 'SELECT VERSION();'
        Check ($mysql -match '^8\.4\.11') 'Unexpected MySQL version'
        Save 'versions.json' @{mysql=$mysql;java=(Invoke-Program docker ($compose+@('exec','-T','app-a','java','-version'))).Error.Trim();redis=(Compose @('exec','-T','redis','redis-server','--version')).Trim();rabbitmq=(Compose @('exec','-T','rabbitmq','rabbitmq-diagnostics','-q','server_version')).Trim();docker=(Invoke-Program docker @('version','--format','{{.Server.Version}}')).Output.Trim();image=(Invoke-Program docker @('image','inspect','las-backend:day26','--format','{{.Id}}')).Output.Trim();migrations=(Invoke-DatabaseSql $compose 'SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;')}
        Save 'endpoints.json' @{appA=$appA;appB=$appB;grafana=('http://127.0.0.1:'+$settings.GRAFANA_PORT);tempo=('http://127.0.0.1:'+$settings.TEMPO_PORT)}
    }elseif($Stage -eq 'DualInstance'){
        $dual=Join-Path $evidence 'dual-instance';[void][IO.Directory]::CreateDirectory($dual)
        & (Join-Path $RepoRoot 'tests/deployment/day24-business.ps1') -Compose $compose -AppA $appA -AppB $appB -Settings $settings -Evidence $dual -BrokerUrl ('http://127.0.0.1:'+$settings.TEST_RABBIT_MANAGEMENT_PORT)
        Reconcile
    }elseif($Stage -eq 'Reconcile'){Reconcile}
    else {
        Check ($SourceCommit -match '^[a-f0-9]{40}$') 'Supply the frozen source commit for Java tests'
        $phase=Join-Path $evidence ($Stage.ToLowerInvariant()+'-'+(Get-Date -Format 'HHmmss'))
        $runner=Join-Path $phase 'runner';[void][IO.Directory]::CreateDirectory($runner)
        $schema='day26_suite_'+[Guid]::NewGuid().ToString('N')
        $base='jdbc:mysql://127.0.0.1:'+$settings.TEST_MYSQL_PORT+'/'+$settings.MYSQL_DATABASE+'?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
        $url=$base.Replace('/'+$settings.MYSQL_DATABASE+'?','/'+$schema+'?')
        $variables=@{JAVA_HOME=$JavaHome;SPRING_DATASOURCE_URL=$url;SPRING_DATASOURCE_USERNAME='root';SPRING_DATASOURCE_PASSWORD=$settings.MYSQL_ROOT_PASSWORD;DB_PASSWORD=$settings.MYSQL_ROOT_PASSWORD;RABBITMQ_HOST='127.0.0.1';RABBITMQ_PORT=$settings.TEST_RABBIT_PORT;RABBITMQ_USER=$settings.RABBITMQ_USERNAME;RABBITMQ_USERNAME=$settings.RABBITMQ_USERNAME;RABBITMQ_PASSWORD=$settings.RABBITMQ_PASSWORD;REDIS_HOST='127.0.0.1';REDIS_PORT=$settings.TEST_REDIS_PORT;JWT_SECRET_BASE64=$settings.JWT_SECRET_BASE64;DAY26_BASE_URL=$base;DAY26_SUITE_SCHEMA=$schema;DAY26_PHASE_DIRECTORY=$phase;DAY26_EVIDENCE_DIRECTORY=(Join-Path $phase 'at-evidence');DAY26_SOURCE_COMMIT=$SourceCommit;MANAGEMENT_OTLP_TRACING_ENDPOINT=('http://127.0.0.1:'+$settings.TEST_OTLP_PORT+'/v1/traces')}
        foreach($name in @('RUN_DAY20_LINKAGE_TESTS','RUN_DAY21_TRACE_TESTS','RUN_MQ_RELIABILITY_TESTS','REDIS_TEST_ENABLED','RUN_DAY22_TESTS','RUN_DAY23_TESTS','RUN_DAY26_TESTS')){$variables[$name]='true'}
        foreach($name in @('RUN_DAY21_FAULT_TESTS','RUN_DAY21_RECONCILIATION_TESTS')){$variables[$name]='false'}
        $previous=@{};$created=$false
        try{
            foreach($name in $variables.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')}
            # Use the previous offline suite's dependency classpath; no dependency resolution or installation.
            $classpath=[IO.File]::ReadAllText((Join-Path $RepoRoot 'qa/verification/day23/cached-test-classpath.txt'))
            & (Join-Path $JavaHome 'bin/javac.exe') --release 23 -encoding UTF-8 -proc:none -classpath $classpath -d $runner tests/fixtures/day26/Day26TestDatabase.java *> (Join-Path $phase 'helper-compile.log')
            Check ($LASTEXITCODE -eq 0) 'Fixture helper compilation failed'
            $runtime=$runner+';'+$classpath;$created=$true
            & (Join-Path $JavaHome 'bin/java.exe') -classpath $runtime Day26TestDatabase create *> (Join-Path $phase 'database-prepare.log')
            Check ($LASTEXITCODE -eq 0) 'Owned database setup failed'
            $before=Source-Hashes
            $before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phase 'source-before.json') -Encoding utf8
            $arguments=@('-o','-B','clean','test','-Djunit.jupiter.extensions.autodetection.enabled=true',('-Dcache.redis.key-prefix=las:test:day26:'+$schema+':'))
            if($Stage -eq 'Specialist'){
                $manifest=Get-Content -LiteralPath (Join-Path $RepoRoot 'tests/fixtures/day26/acceptance-matrix.json') -Raw | ConvertFrom-Json
                $selection=@($manifest.tests)+@([pscustomobject]@{class='com.limitactivitysystem.activity.integration.ProductCatalogMySqlIntegrationTest';method='v16QualificationTaskMigrationPreservesExistingTablesAndUsesNoPhysicalForeignKeys'})
                $patterns=@($selection | Group-Object class | ForEach-Object {$_.Name+'#'+(($_.Group.method | Sort-Object -Unique) -join '+')})
                # Guard both current behavior and historical migration fixtures before the full suite.
                $patterns+=@('com.limitactivitysystem.activity.integration.FreeReservationSchemaMySqlIntegrationTest','com.limitactivitysystem.activity.integration.GiftInventorySchemaMySqlIntegrationTest')
                $arguments+=('-Dtest='+($patterns -join ','))
            }
            $started=[DateTimeOffset]::Now
            Write-Output "Running offline $Stage; log: $phase/maven.log"
            & $MavenCommand @arguments *> (Join-Path $phase 'maven.log')
            $exitCode=$LASTEXITCODE;$finished=[DateTimeOffset]::Now
            $after=Source-Hashes
            $unchanged=@(Compare-Object @($before | ForEach-Object {$_.path+':'+$_.sha256}) @($after | ForEach-Object {$_.path+':'+$_.sha256})).Count -eq 0
            $after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phase 'source-after.json') -Encoding utf8
            [void][IO.Directory]::CreateDirectory((Join-Path $phase 'surefire'))
            Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' | Copy-Item -Destination (Join-Path $phase 'surefire')
            foreach($directory in @('target/day22','target/day23','target/verification')){if(Test-Path -LiteralPath $directory){Copy-Item -LiteralPath $directory -Destination $phase -Recurse}}
            $counts=@{tests=0;failures=0;errors=0;skipped=0}
            foreach($file in Get-ChildItem -LiteralPath (Join-Path $phase 'surefire') -Filter 'TEST-*.xml'){
                [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
                foreach($key in $counts.Keys.Clone()){$counts[$key]+=[int]$xml.testsuite.GetAttribute($key)}
            }
            $summary=@{stage=$Stage;sourceCommit=$SourceCommit;gitHead=(& git rev-parse HEAD);sourceUnchangedDuringRun=$unchanged;startedAt=$started.ToString('o');finishedAt=$finished.ToString('o');exitCode=$exitCode;counts=$counts;command=('mvn '+($arguments -join ' '));schema=$schema}
            $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phase 'summary.json') -Encoding utf8
            $summary | ConvertTo-Json -Depth 6
            Check $unchanged 'Source changed during the test run'
            Check ($exitCode -eq 0) "Maven failed; see $phase/maven.log"
        } finally {
            if($created){& (Join-Path $JavaHome 'bin/java.exe') -classpath $runtime Day26TestDatabase drop *> (Join-Path $phase 'database-cleanup.log');if($LASTEXITCODE -ne 0){Write-Warning 'Owned schema cleanup failed; see database-cleanup.log'}}
            foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
        }
    }
} finally {Pop-Location}
