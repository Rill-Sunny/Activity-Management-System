param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][ValidateSet('F01','F02','F07')][string]$Mode,
    [string]$EventId,[Parameter(Mandatory)][string]$OutputDirectory,[string]$PublisherToken)
. (Join-Path $PSScriptRoot 'common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($lab.context.day -eq 28) 'Crash probe requires the owned Day 28 environment'
$java='C:/Users/32170/.jdks/openjdk-23.0.1/bin'
$classpath=[IO.File]::ReadAllText((Join-Path $RepoRoot 'qa/verification/day23/cached-test-classpath.txt'))
# Production classes and cached dependencies only. Test configuration must not enter the probe context.
$classpath=(@($classpath.Split(';')|Where-Object{$_ -notmatch 'target[\\/]test-classes$'}) -join ';')
$classes=Join-Path $OutputDirectory 'probe-classes';[void][IO.Directory]::CreateDirectory($classes)
& (Join-Path $java 'javac.exe') --release 23 -encoding UTF-8 -proc:none -cp $classpath -d $classes tests/fixtures/day28/Day28CrashProbe.java *> (Join-Path $OutputDirectory 'probe-compile.log')
Assert-Lab ($LASTEXITCODE -eq 0) 'Crash probe compilation failed'
$url='jdbc:mysql://127.0.0.1:'+$lab.settings.TEST_MYSQL_PORT+'/day28_validation?connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false'
$vars=@{RUN_DAY28_FAULTS='true';SPRING_DATASOURCE_URL=$url;SPRING_DATASOURCE_USERNAME=$lab.settings.MYSQL_USER;SPRING_DATASOURCE_PASSWORD=$lab.settings.MYSQL_PASSWORD;DB_PASSWORD=$lab.settings.MYSQL_PASSWORD;RABBITMQ_HOST='127.0.0.1';RABBITMQ_PORT=$lab.settings.TEST_RABBIT_PORT;RABBITMQ_USER=$lab.settings.RABBITMQ_USERNAME;RABBITMQ_PASSWORD=$lab.settings.RABBITMQ_PASSWORD;REDIS_HOST='127.0.0.1';REDIS_PORT=$lab.settings.TEST_REDIS_PORT;JWT_SECRET_BASE64=$lab.settings.JWT_SECRET_BASE64;DAY28_EVENT_ID=$EventId;DAY28_CRASH_EVIDENCE=(Join-Path $OutputDirectory 'crash-boundary.json');DAY28_PUBLISHER_TOKEN=$PublisherToken}
$previous=@{}
$vars.MANAGEMENT_OTLP_TRACING_ENDPOINT='http://127.0.0.1:'+$lab.settings.TEST_OTLP_PORT+'/v1/traces'
try{
    foreach($key in $vars.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$vars[$key],'Process')}
    $result=Invoke-Program (Join-Path $java 'java.exe') @('-Xms128m','-Xmx512m','-cp',($classes+';'+$classpath),'Day28CrashProbe',$Mode) -AllowFailure -OutputFile (Join-Path $OutputDirectory 'probe.log')
    $result.Error|Set-Content (Join-Path $OutputDirectory 'probe-error.log')
    Assert-Lab ($result.ExitCode -eq 77) "Expected abrupt exit 77, received $($result.ExitCode)"
    Assert-Lab (Test-Path (Join-Path $OutputDirectory 'crash-boundary.json')) 'Crash boundary evidence is missing'
}finally{Restore-LabEnvironment $previous}
