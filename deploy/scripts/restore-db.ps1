param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$EnvFile,
    [Parameter(Mandatory)][string]$BackupFile,
    [Parameter(Mandatory)][ValidatePattern('^las_restore_[a-z0-9_]{1,48}$')][string]$TargetDatabase
)
. (Join-Path $PSScriptRoot 'common.ps1')
$settings=Read-DeploymentEnvironment $EnvFile
if($TargetDatabase -eq $settings.MYSQL_DATABASE){throw 'Cannot restore over the running application database'}
$compose=Get-ComposeArguments $Project $EnvFile
$source=[IO.Path]::GetFullPath($BackupFile)
$metadata=Get-Content -LiteralPath ($source+'.json') -Raw | ConvertFrom-Json
if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $metadata.sha256){throw 'Backup SHA-256 mismatch'}
# CREATE DATABASE deliberately fails if the target already exists. Never drop or overwrite it.
[void](Invoke-DatabaseSql $compose "CREATE DATABASE $TargetDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" -Root)
$script='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 "$1"'
[void](Invoke-Program docker ($compose+@('exec','-T','mysql','sh','-c',$script,'restore',$TargetDatabase)) -InputFile $source)
$tables=Invoke-DatabaseSql $compose 'SHOW TABLES;' -Database $TargetDatabase -Root
@{targetDatabase=$TargetDatabase;restoredAt=[DateTimeOffset]::Now.ToString('o');backupSha256=$metadata.sha256;
    tables=@($tables -split "\r?\n");status='RESTORED_RECONCILIATION_REQUIRED'} |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($source+'.restore.json') -Encoding utf8
Write-Output "Restored to isolated database: $TargetDatabase; run reconciliation before declaring success."
