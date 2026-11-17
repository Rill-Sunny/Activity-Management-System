param(
    [string]$MavenCommand = 'mvn',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Image = 'las-backend:1.0.0',
    [string]$SourceCommit = 'unrecorded',
    [string]$JavaRuntimeImage = 'eclipse-temurin:23.0.2_7-jre-noble@sha256:482448f7d10c123f7453a2ef54035a315399547fdfc8339c37b69995845b8738',
    [switch]$Online
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$previousJava=$env:JAVA_HOME
Push-Location $repo
try {
    if($JavaHome){$env:JAVA_HOME=$JavaHome}
    $arguments=@('-B','-DskipTests','package')
    if(-not $Online){$arguments=@('-o')+$arguments}
    & $MavenCommand @arguments
    if($LASTEXITCODE -ne 0){throw 'Maven package failed'}
    if($SourceCommit -ne 'unrecorded' -and $SourceCommit -notmatch '^[a-f0-9]{40}$'){throw 'SourceCommit must be a complete commit ID'}
    & docker build -f deploy/Dockerfile --build-arg "JAVA_RUNTIME_IMAGE=$JavaRuntimeImage" --build-arg "SOURCE_REVISION=$SourceCommit" -t $Image .
    if($LASTEXITCODE -ne 0){throw 'Docker image build failed'}
} finally {$env:JAVA_HOME=$previousJava; Pop-Location}
