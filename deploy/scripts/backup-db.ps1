param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$OutputFile
)
. (Join-Path $PSScriptRoot 'common.ps1')
$settings=Read-DeploymentEnvironment $EnvFile
$compose=Get-ComposeArguments $Project $EnvFile
$destination=[IO.Path]::GetFullPath($OutputFile)
if(Test-Path -LiteralPath $destination){throw 'Backup file already exists; choose a new filename'}
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
$partial=$destination+'.partial'
if(Test-Path -LiteralPath $partial){throw 'An unfinished backup already exists'}
$version=Invoke-DatabaseSql $compose 'SELECT VERSION();'
$started=[DateTimeOffset]::Now.ToString('o')
$dump='MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u "$MYSQL_USER" --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$MYSQL_DATABASE"'
[void](Invoke-Program docker ($compose+@('exec','-T','mysql','sh','-c',$dump)) -OutputFile $partial)
if((Get-Item -LiteralPath $partial).Length -eq 0){throw 'Backup is empty'}
Move-Item -LiteralPath $partial -Destination $destination
@{project=$Project;database=$settings.MYSQL_DATABASE;mysqlVersion=$version;startedAt=$started;
    finishedAt=[DateTimeOffset]::Now.ToString('o');sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash;
    consistency='single-transaction; concurrent DDL must be stopped'} |
    ConvertTo-Json | Set-Content -LiteralPath ($destination+'.json') -Encoding utf8
Write-Output "Backup saved: $destination"
