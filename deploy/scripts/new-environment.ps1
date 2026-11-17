param([string]$OutputFile=(Join-Path $PSScriptRoot '../.env'))
$ErrorActionPreference='Stop'
$destination=[IO.Path]::GetFullPath($OutputFile)
if(Test-Path -LiteralPath $destination){throw 'Environment file already exists; edit it instead of replacing credentials'}
$content=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../.env.example') -Raw
foreach($key in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','RABBITMQ_PASSWORD','METRICS_PASSWORD','GRAFANA_PASSWORD')){
    $secret=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $content=[regex]::Replace($content,"(?m)^$key=.*$",$key+'='+$secret)
}
$jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$content=[regex]::Replace($content,'(?m)^JWT_SECRET_BASE64=.*$','JWT_SECRET_BASE64='+$jwt)
[IO.File]::WriteAllText($destination,$content,[Text.UTF8Encoding]::new($false))
Write-Output "Created local configuration: $destination (secret values are not printed)"
