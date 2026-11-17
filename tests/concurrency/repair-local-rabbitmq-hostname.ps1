#Requires -Version 5.1
#Requires -RunAsAdministrator

# Repair the local rabbit@nohost node without renaming it or changing its data directory.
# Run only after confirming that this machine's RabbitMQ uses rabbit@nohost.
$ErrorActionPreference = 'Stop'
$hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
$service = Get-Service -Name RabbitMQ
$original = [System.IO.File]::ReadAllBytes($hostsPath)
if ($original.Length -ge 2 -and
    (($original[0] -eq 255 -and $original[1] -eq 254) -or
     ($original[0] -eq 254 -and $original[1] -eq 255))) {
    throw 'The hosts file is UTF-16; review its encoding before editing it.'
}
$entries = @([System.Text.Encoding]::UTF8.GetString($original) -split '\r?\n' |
    ForEach-Object { ($_ -split '#', 2)[0] } |
    Where-Object { ($_ -split '\s+') -contains 'nohost' })
if ($entries | Where-Object { $_ -notmatch '^\s*127\.0\.0\.1\s+' }) {
    throw 'An existing nohost entry points elsewhere. Review it before changing local name resolution.'
}
if ($entries.Count -eq 0) {
    $backupPath = $hostsPath + '.rabbitmq-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.bak'
    [System.IO.File]::WriteAllBytes($backupPath, $original)
    if ((Get-FileHash -LiteralPath $hostsPath).Hash -ne (Get-FileHash -LiteralPath $backupPath).Hash) {
        throw 'The hosts backup does not match the original; no change was made.'
    }
    $prefix = [System.Text.Encoding]::ASCII.GetBytes(
        "# Local RabbitMQ node rabbit@nohost`r`n127.0.0.1 nohost`r`n")
    $updated = New-Object byte[] ($prefix.Length + $original.Length)
    [System.Buffer]::BlockCopy($prefix, 0, $updated, 0, $prefix.Length)
    [System.Buffer]::BlockCopy($original, 0, $updated, $prefix.Length, $original.Length)
    [System.IO.File]::WriteAllBytes($hostsPath, $updated)
    Write-Output "Original hosts file preserved at: $backupPath"
}

Clear-DnsClientCache
$addresses = [System.Net.Dns]::GetHostAddresses('nohost').IPAddressToString
if ($addresses -notcontains '127.0.0.1') {
    throw 'nohost still does not resolve to 127.0.0.1; RabbitMQ was not restarted.'
}
Restart-Service -InputObject $service
$service.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))

$deadline = [DateTime]::UtcNow.AddSeconds(45)
do {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.ConnectAsync('127.0.0.1', 5672)
        if ($connect.Wait(500) -and $client.Connected) {
            Write-Output 'RabbitMQ TCP port 127.0.0.1:5672 is reachable. AMQP authentication and IDEA tests still need verification.'
            return
        }
    } catch [System.AggregateException] {
        # A refused connection is expected while RabbitMQ starts.
    } finally {
        $client.Dispose()
    }
    Start-Sleep -Seconds 1
} while ([DateTime]::UtcNow -lt $deadline)
throw 'RabbitMQ did not open port 5672 within 45 seconds. Inspect the latest broker startup log; no data was removed.'
