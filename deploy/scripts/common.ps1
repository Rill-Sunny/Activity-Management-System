$ErrorActionPreference = 'Stop'
$DeployRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$RepoRoot = [IO.Path]::GetFullPath((Join-Path $DeployRoot '..'))

function Invoke-Program {
    param([string]$File, [string[]]$Arguments, [string]$InputText,
          [string]$OutputFile, [string]$InputFile, [switch]$AllowFailure)
    $psi = [Diagnostics.ProcessStartInfo]::new($File)
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true
    foreach($argument in $Arguments) { [void]$psi.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $stream = $null
    try {
        if($OutputFile) {
            $stream = [IO.File]::Create([IO.Path]::GetFullPath($OutputFile))
            $outputTask = $process.StandardOutput.BaseStream.CopyToAsync($stream)
        } else { $outputTask = $process.StandardOutput.ReadToEndAsync() }
        if($InputFile) {
            $inputStream = [IO.File]::OpenRead([IO.Path]::GetFullPath($InputFile))
            try { $inputStream.CopyTo($process.StandardInput.BaseStream) } finally { $inputStream.Dispose() }
        } elseif($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $outputTask.GetAwaiter().GetResult()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0 -and -not $AllowFailure) {
            throw "$File exited $($process.ExitCode): $errorText"
        }
        return [pscustomobject]@{ExitCode=$process.ExitCode; Output=$output; Error=$errorText}
    } finally { if($stream){$stream.Dispose()}; $process.Dispose() }
}
function Get-ComposeArguments {
    param([string]$Project, [string]$EnvFile)
    if($Project -notmatch '^[a-z0-9][a-z0-9_-]+$') { throw 'Invalid Compose project name' }
    return @('compose','--project-name',$Project,'--env-file',[IO.Path]::GetFullPath($EnvFile),'-f',(Join-Path $DeployRoot 'compose.yaml'))
}
function Read-DeploymentEnvironment {
    param([string]$Path)
    $values=@{}
    foreach($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($Path))) {
        if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {$values[$Matches[1]]=$Matches[2]}
    }
    foreach($name in @('MYSQL_DATABASE','MYSQL_USER','MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_USERNAME',
            'RABBITMQ_PASSWORD','JWT_SECRET_BASE64','METRICS_PASSWORD','GRAFANA_PASSWORD')) {
        if(-not $values[$name] -or $values[$name] -match 'REPLACE_') {throw "Missing or placeholder: $name"}
    }
    if($values.MYSQL_DATABASE -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') {throw 'Invalid MYSQL_DATABASE'}
    if([Convert]::FromBase64String($values.JWT_SECRET_BASE64).Length -lt 32) {throw 'JWT key must contain at least 32 random bytes'}
    return $values
}
function Invoke-DatabaseSql {
    param([string[]]$Compose, [string]$Sql, [string]$Database, [switch]$Root)
    if($Database -and $Database -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') {throw 'Invalid database name'}
    $script = if($Root) {'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names "$@"'}
              else {'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" --batch --raw --skip-column-names "$MYSQL_DATABASE"'}
    $arguments=$Compose+@('exec','-T','mysql','sh','-c',$script,'sql')
    if($Root -and $Database){$arguments+= $Database}
    return (Invoke-Program docker $arguments -InputText $Sql).Output.Trim()
}
