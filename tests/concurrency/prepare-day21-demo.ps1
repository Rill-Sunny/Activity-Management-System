param(
    [Parameter(Mandatory=$true)][string]$JdkDirectory,
    [string]$ConnectionConfiguration = '.run/Day21-Product.run.xml',
    [ValidatePattern('^day21_demo_[a-f0-9]{32}$')][string]$ResumeSchema,
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$java = Join-Path $JdkDirectory 'bin/java.exe'
$javac = Join-Path $JdkDirectory 'bin/javac.exe'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) {
    throw 'Select an existing JDK 23 directory. This script does not install Java or dependencies.'
}
Push-Location $repo
try {
    $report = Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' | Select-Object -First 1
    if ($null -eq $report) { throw 'Compile the project in IDEA first; the existing Surefire classpath is required.' }
    [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
    $classpath = ($suite.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
    if (-not $classpath) { throw 'The Surefire report does not contain java.class.path.' }
    # Exclude test resources: their application.properties disables listeners and workers.
    $entries = @($classpath -split ';' | Where-Object { -not $_.Replace('\','/').TrimEnd('/').EndsWith('/target/test-classes') })
    $classpath = $entries -join ';'
    $compiled = [IO.Path]::GetFullPath('target/day21/demo-preparer')
    New-Item -ItemType Directory -Path $compiled -Force | Out-Null
    $compileArguments = @('--release','23','-encoding','UTF-8','-proc:none','-implicit:none',
        '-d', ('"' + $compiled.Replace('\','/') + '"'),
        '-classpath', ('"' + $classpath.Replace('\','/') + '"'),
        'tests/fixtures/day21/PrepareDay21Demo.java')
    $compileFile = Join-Path $compiled 'javac.args'
    [IO.File]::WriteAllLines($compileFile, $compileArguments, [Text.UTF8Encoding]::new($false))
    & $javac ('@' + $compileFile)
    if ($LASTEXITCODE -ne 0) { throw "Data preparer compilation failed: $LASTEXITCODE" }
    if ($CompileOnly) { Write-Output 'Data preparer compiled; no application or database operation executed.'; return }

    [xml]$configuration = Get-Content -LiteralPath $ConnectionConfiguration -Raw
    $settings = @{}
    foreach ($variable in $configuration.SelectNodes('//env')) {
        if ($variable.name -match '^(DB_PASSWORD|SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME|RABBITMQ_.+|REDIS_HOST|REDIS_PORT)$') {
            $settings[$variable.name] = $variable.value
        }
    }
    if (-not $settings['DB_PASSWORD'] -or -not $settings.ContainsKey('RABBITMQ_PASSWORD')) {
        throw 'The local connection configuration must contain DB_PASSWORD and RABBITMQ_PASSWORD.'
    }
    $baseUrl = if ($settings['SPRING_DATASOURCE_URL']) { $settings['SPRING_DATASOURCE_URL'] } else { 'jdbc:mysql://127.0.0.1:3306/limit_activity_system?serverTimezone=Asia/Shanghai' }
    $databaseUser = if ($settings['SPRING_DATASOURCE_USERNAME']) { $settings['SPRING_DATASOURCE_USERNAME'] } else { 'root' }
    $settings['DAY21_DEMO_BASE_URL'] = $baseUrl
    $settings['DAY21_DEMO_DB_USER'] = $databaseUser
    if ($ResumeSchema) { $settings['DAY21_DEMO_RESUME_SCHEMA'] = $ResumeSchema }
    $secretBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
    $settings['JWT_SECRET_BASE64'] = [Convert]::ToBase64String($secretBytes)
    $previous = @{}
    $logDirectory = Join-Path $repo ('qa/verification/day21/preparer-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $logDirectory | Out-Null
    $log = Join-Path $logDirectory 'application.log'
    $runFile = Join-Path $compiled 'java.args'
    [IO.File]::WriteAllLines($runFile, @('-Dfile.encoding=UTF-8','-classpath',
        ('"' + ($compiled + ';' + $classpath).Replace('\','/') + '"'),'PrepareDay21Demo'), [Text.UTF8Encoding]::new($false))
    try {
        foreach ($key in $settings.Keys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
        }
        Write-Output "Preparing retained demo data. Local log: $log"
        & $java ('@' + $runFile) *> $log
        $code = $LASTEXITCODE
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
    if ($code -ne 0) { throw "Data preparation failed (exit $code). The owned database is retained for diagnosis. See $log" }
    $schemaLine = Get-Content -LiteralPath $log | Where-Object { $_ -match '^Retained demo database: day21_demo_[a-f0-9]{32}$' } | Select-Object -First 1
    if (-not $schemaLine) { throw "Cannot identify prepared database; inspect $log" }
    $schema = $schemaLine.Substring('Retained demo database: '.Length)
    $evidenceDirectory = Join-Path $repo "qa/verification/day21/$schema"
    $prepared = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'preparation.json') -Raw | ConvertFrom-Json
    if ($prepared.status -ne 'PREPARED') { throw 'Preparation did not finish successfully.' }

    # Write only a local, ignored JUnit configuration; do not alter the existing product/MQ configurations.
    $configPath = Join-Path $repo '.run/Day21-Reconciliation.local.run.xml'
    if (Test-Path -LiteralPath $configPath) { Copy-Item -LiteralPath $configPath -Destination (Join-Path $evidenceDirectory 'previous-reconciliation-config.xml') }
    $xml = [xml]'<component name="ProjectRunConfigurationManager"><configuration default="false" name="Day21-Reconciliation" type="JUnit" factoryName="JUnit"><module name="limited-activities-system"/><envs/><method v="2"><option name="Make" enabled="true"/></method></configuration></component>'
    $node = $xml.component.configuration
    $options = @{
        MAIN_CLASS_NAME = 'com.limitactivitysystem.messaging.integration.Day21ReconciliationMySqlIntegrationTest'
        TEST_OBJECT = 'class'
        WORKING_DIRECTORY = '$PROJECT_DIR$'
        ALTERNATIVE_JRE_PATH = $JdkDirectory
        ALTERNATIVE_JRE_PATH_ENABLED = 'true'
    }
    foreach ($key in $options.Keys) {
        $element = $xml.CreateElement('option'); $element.SetAttribute('name', $key); $element.SetAttribute('value', $options[$key])
        $null = $node.AppendChild($element)
    }
    $settings.Remove('JWT_SECRET_BASE64'); $settings.Remove('DAY21_DEMO_BASE_URL'); $settings.Remove('DAY21_DEMO_DB_USER'); $settings.Remove('DAY21_DEMO_RESUME_SCHEMA')
    $settings['SPRING_DATASOURCE_URL'] = $prepared.jdbcUrl
    $settings['SPRING_DATASOURCE_USERNAME'] = $databaseUser
    $settings['RUN_DAY21_RECONCILIATION_TESTS'] = 'true'
    $settings['RUN_DAY21_FAULT_TESTS'] = 'false'
    foreach ($key in ($settings.Keys | Sort-Object)) {
        $element = $xml.CreateElement('env'); $element.SetAttribute('name', $key); $element.SetAttribute('value', $settings[$key])
        $null = $node.SelectSingleNode('envs').AppendChild($element)
    }
    $xml.Save($configPath)
    [ordered]@{ schema=$schema; jdbcUrl=$prepared.jdbcUrl; evidence=$evidenceDirectory; junitConfiguration='Day21-Reconciliation'; sourceCommit=(& git rev-parse HEAD) } |
        ConvertTo-Json | Set-Content -LiteralPath 'qa/verification/day21/latest-demo.json' -Encoding UTF8
    Write-Output "Prepared database: $schema"
    Write-Output "Reconciliation SQL scripts: $($prepared.reconciliationScripts); invariant violations: $($prepared.invariantViolations)"
    Write-Output "IDEA configuration ready: Day21-Reconciliation. Evidence: $evidenceDirectory"
} finally { Pop-Location }
