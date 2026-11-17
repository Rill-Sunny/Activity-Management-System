param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$SourceCommit,
    [string]$Selection,[switch]$RealOutages,
    [string]$MavenCommand='D:\Computer_Science\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd',
    [string]$JavaHome='C:\Users\32170\.jdks\openjdk-23.0.1')
. (Join-Path $PSScriptRoot 'common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($SourceCommit -match '^[a-f0-9]{40}$') 'Frozen source commit required'
$phase=Join-Path $lab.directory ('native-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$runner=Join-Path $phase 'runner';[void][IO.Directory]::CreateDirectory($runner)
$schema='day26_suite_'+[Guid]::NewGuid().ToString('N')
$base='jdbc:mysql://127.0.0.1:'+$lab.settings.TEST_MYSQL_PORT+'/'+$lab.settings.MYSQL_DATABASE+'?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$url=$base.Replace('/'+$lab.settings.MYSQL_DATABASE+'?','/'+$schema+'?')
$variables=@{JAVA_HOME=$JavaHome;SPRING_DATASOURCE_URL=$url;SPRING_DATASOURCE_USERNAME='root';SPRING_DATASOURCE_PASSWORD=$lab.settings.MYSQL_ROOT_PASSWORD;DB_PASSWORD=$lab.settings.MYSQL_ROOT_PASSWORD;RABBITMQ_HOST='127.0.0.1';RABBITMQ_PORT=$lab.settings.TEST_RABBIT_PORT;RABBITMQ_USER=$lab.settings.RABBITMQ_USERNAME;RABBITMQ_USERNAME=$lab.settings.RABBITMQ_USERNAME;RABBITMQ_PASSWORD=$lab.settings.RABBITMQ_PASSWORD;REDIS_HOST='127.0.0.1';REDIS_PORT=$lab.settings.TEST_REDIS_PORT;JWT_SECRET_BASE64=$lab.settings.JWT_SECRET_BASE64;DAY26_BASE_URL=$base;DAY26_SUITE_SCHEMA=$schema;DAY26_PHASE_DIRECTORY=$phase;DAY26_EVIDENCE_DIRECTORY=(Join-Path $phase 'at-evidence');DAY26_SOURCE_COMMIT=$SourceCommit;MANAGEMENT_OTLP_TRACING_ENDPOINT=('http://127.0.0.1:'+$lab.settings.TEST_OTLP_PORT+'/v1/traces');DAY21_COMPOSE_FILE=(Join-Path $DeployRoot 'compose.yaml');DAY21_COMPOSE_ENV_FILE=$lab.context.envFile;DAY21_COMPOSE_PROJECT=$lab.context.project}
foreach($name in @('RUN_DAY20_LINKAGE_TESTS','RUN_DAY21_TRACE_TESTS','RUN_MQ_RELIABILITY_TESTS','REDIS_TEST_ENABLED','RUN_DAY22_TESTS','RUN_DAY23_TESTS','RUN_DAY26_TESTS','RUN_DAY28_TESTS')){$variables[$name]='true'}
$variables.RUN_DAY21_FAULT_TESTS=([bool]$RealOutages).ToString().ToLowerInvariant();$variables.RUN_DAY21_RECONCILIATION_TESTS='false'
$previous=@{};$created=$false
function SourceHashes{@(@(Get-ChildItem src,tests,deploy -Recurse -File)+@(Get-Item pom.xml)|Where-Object{$_.Name -notlike '.env*'}|ForEach-Object{@{path=$_.FullName.Substring($RepoRoot.Length+1);hash=(Get-FileHash $_.FullName).Hash}})}
try{
    foreach($name in $variables.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')}
    # Generate from this checkout and its resolved test dependencies, never a previous ignored run.
    & $MavenCommand -o -B '-Dtest=ReleaseApiContractTest' '-Drelease.classpath=true' test *> (Join-Path $phase 'classpath-bootstrap.log')
    Assert-Lab ($LASTEXITCODE -eq 0) 'Release contract/classpath bootstrap failed'
    $classpath=[IO.File]::ReadAllText((Join-Path $RepoRoot 'target/release-test-classpath.txt'))
    & (Join-Path $JavaHome 'bin/javac.exe') --release 23 -encoding UTF-8 -proc:none -classpath $classpath -d $runner tests/fixtures/day26/Day26TestDatabase.java *> (Join-Path $phase 'helper-compile.log')
    Assert-Lab ($LASTEXITCODE -eq 0) 'Database helper compilation failed'
    $runtime=$runner+';'+$classpath;$created=$true
    & (Join-Path $JavaHome 'bin/java.exe') -classpath $runtime Day26TestDatabase create *> (Join-Path $phase 'database-prepare.log')
    Assert-Lab ($LASTEXITCODE -eq 0) 'Owned test database setup failed'
    $before=SourceHashes;Save-Lab (Join-Path $phase 'source-before.json') $before
    $arguments=@('-o','-B','clean','test','-Djunit.jupiter.extensions.autodetection.enabled=true',('-Dcache.redis.key-prefix=las:test:day28:'+$schema+':'))
    if($Selection){$arguments+=('-Dtest='+$Selection)}
    $start=[DateTimeOffset]::Now;Write-Output "NATIVE_LOG=$phase/maven.log"
    & $MavenCommand @arguments *> (Join-Path $phase 'maven.log')
    $exit=$LASTEXITCODE;$finish=[DateTimeOffset]::Now
    $after=SourceHashes;Save-Lab (Join-Path $phase 'source-after.json') $after
    $unchanged=@(Compare-Object @($before|ForEach-Object{$_.path+':'+$_.hash}) @($after|ForEach-Object{$_.path+':'+$_.hash})).Count -eq 0
    [void][IO.Directory]::CreateDirectory((Join-Path $phase 'surefire'))
    Get-ChildItem target/surefire-reports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue|Copy-Item -Destination (Join-Path $phase 'surefire')
    foreach($directory in @('target/day21','target/day22','target/day23','target/verification')){if(Test-Path $directory){Copy-Item $directory -Destination $phase -Recurse}}
    $counts=@{tests=0;failures=0;errors=0;skipped=0}
    foreach($file in Get-ChildItem (Join-Path $phase 'surefire') -Filter 'TEST-*.xml'){
        [xml]$xml=Get-Content $file.FullName -Raw;foreach($key in $counts.Keys.Clone()){$counts[$key]+=[int]$xml.testsuite.GetAttribute($key)}
    }
    $summary=@{sourceCommit=$SourceCommit;gitHead=(& git rev-parse HEAD);sourceUnchangedDuringRun=$unchanged;startedAt=$start.ToString('o');finishedAt=$finish.ToString('o');exitCode=$exit;counts=$counts;command=('mvn '+($arguments -join ' '));realOutages=[bool]$RealOutages;schema=$schema}
    Save-Lab (Join-Path $phase 'summary.json') $summary;$summary|ConvertTo-Json -Depth 6
    Assert-Lab $unchanged 'Source changed during native test execution'
    Assert-Lab ($exit -eq 0) "Maven failed; inspect $phase/maven.log"
}finally{
    if($created){& (Join-Path $JavaHome 'bin/java.exe') -classpath $runtime Day26TestDatabase drop *> (Join-Path $phase 'database-cleanup.log')}
    Restore-LabEnvironment $previous
}
