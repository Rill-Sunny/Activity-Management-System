param([Parameter(Mandatory)][string]$RunDirectory,[switch]$PrepareOnly,[string]$PreparedDirectory)
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
Assert-Lab ($lab.context.day -in @(29,30)) 'Demo bootstrap is restricted to owned Day 29/30 environments'
Assert-Lab ((Lab-Sql $lab 'SELECT DATABASE();') -eq "day$($lab.context.day)_validation") 'Unexpected demo database'
$folder=if($PreparedDirectory){[IO.Path]::GetFullPath($PreparedDirectory)}else{Join-Path $lab.directory ('demo-'+(Get-Date -Format 'HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6))}
Assert-Lab ($folder.StartsWith($lab.directory+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'Demo evidence must remain inside this lab'
[void][IO.Directory]::CreateDirectory($folder)
$private=Join-Path $folder 'variables.private.json'
$variables=@{};$results=[Collections.Generic.List[object]]::new()
function Resolve-DemoValue($value){
    if($value -is [string]){
        if($value -match '^\{\{([A-Za-z0-9_]+)\}\}$'){Assert-Lab ($variables.ContainsKey($Matches[1])) "Missing variable $($Matches[1])";return $variables[$Matches[1]]}
        return [regex]::Replace($value,'\{\{([A-Za-z0-9_]+)\}\}',{param($m) Assert-Lab ($variables.ContainsKey($m.Groups[1].Value)) "Missing variable $($m.Groups[1].Value)";[string]$variables[$m.Groups[1].Value]})
    }
    if($value -is [Collections.IDictionary]){$copy=@{};foreach($key in $value.Keys){$copy[$key]=Resolve-DemoValue $value[$key]};return $copy}
    if($value -is [array]){return ,@($value|ForEach-Object{Resolve-DemoValue $_})}
    return $value
}
function Read-Pointer($value,[string]$pointer){
    foreach($part in ($pointer -split '/'|Select-Object -Skip 1)){
        if($null -eq $value){return $null}
        if($value -is [array]){$value=$value[[int]$part]}
        elseif($value -is [Collections.IDictionary]){$value=$value[$part]}
        else{$value=$value.$part}
    };return $value
}
function Http-Demo([string]$path,[string]$actor,$body,[string]$method='POST',[switch]$B){
    $headers=@{traceparent='00-'+[Guid]::NewGuid().ToString('N')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,16)+'-01'}
    if($actor){$headers.Authorization='Bearer '+$variables[($actor+'_token')]}
    $requestParameters=@{Uri=$(if($B){$lab.appB}else{$lab.appA})+$path;Method=$method;Headers=$headers;ContentType='application/json';TimeoutSec=30;SkipHttpErrorCheck=$true}
    if($null -ne $body){$requestParameters.Body=$body|ConvertTo-Json -Depth 40 -Compress}
    $response=Invoke-WebRequest @requestParameters
    $text=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
    @{status=[int]$response.StatusCode;body=$(if($text){$text|ConvertFrom-Json -AsHashtable}else{$null});traceId=($headers.traceparent -split '-')[1]}
}
function Set-DemoTimes($times){
    $now=[DateTimeOffset]::Now.ToOffset([TimeSpan]::FromHours(8))
    foreach($name in $times.Keys){$at=$now.AddSeconds($times[$name]);$variables[$name]=$at.ToString('yyyy-MM-ddTHH:mm:ss.fff');$variables[$name+'Offset']=$at.ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')}
}
function Save-PrivateDemo {
    Save-Lab $private $variables
    $values=@($variables.Keys|Sort-Object|ForEach-Object{@{key=$_;value=[string]$variables[$_];enabled=$true;type=$(if($_ -match 'password|token'){'secret'}else{'default'})}})
    Save-Lab (Join-Path $folder 'apifox-environment.private.json') @{name='LAS isolated demo';values=$values;'_postman_variable_scope'='environment'}
}
$started=[DateTimeOffset]::Now
try{
    if($PreparedDirectory){$variables=Get-Content $private -Raw|ConvertFrom-Json -AsHashtable}
    else {
        $variables.prefix='demo_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
        $variables.baseUrl=$lab.appA;$variables.baseUrlB=$lab.appB
        Set-DemoTimes @{openTime=-86400;closeTime=86400;startTime=1728000;endTime=1731600;newStart=2160000;newEnd=2163600}
        foreach($actor in @('publisherA','publisherB','buyerA','buyerB','buyerC','admin')){
            $password='Demo-'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
            $username=$variables.prefix+'_'+$actor
            $registered=Http-Demo '/auth/register' '' @{username=$username;password=$password}
            Assert-Lab ($registered.status -eq 201) 'Demo registration failed'
            $id=[long]$registered.body.id;Assert-Lab ($id -gt 0) 'Invalid registered ID'
            if($actor -like 'publisher*' -or $actor -eq 'admin'){
                $role=if($actor -eq 'admin'){'ADMIN'}else{'PUBLISHER'}
                [void](Lab-Sql $lab "UPDATE app_user SET role='$role' WHERE id=$id AND username='$username' AND role='USER';")
            }
            $login=Http-Demo '/auth/login' '' @{username=$username;password=$password}
            Assert-Lab ($login.status -eq 200) 'Demo login failed'
            $variables[$actor+'_id']=$id;$variables[$actor+'_username']=$username
            $variables[$actor+'_password']=$password;$variables[$actor+'_token']=$login.body.accessToken
        }
        Save-PrivateDemo
    }
    if($PrepareOnly){Write-Output "DEMO_PREPARED=$folder";return}
    $plan=Get-Content (Join-Path $RepoRoot 'tests/fixtures/demo/plan.json') -Raw|ConvertFrom-Json -AsHashtable
    foreach($step in $plan.steps){
        if($step.setTimes){Set-DemoTimes $step.setTimes;continue}
        $at=[DateTimeOffset]::Now;$deadline=$at.AddSeconds([Math]::Max(1,[int]$step.waitSeconds));$attempts=0
        do{
            $attempts++
            if($step.sqlCheck){
                $query=Resolve-DemoValue $step.sqlCheck
                Assert-Lab ($query -match '^SELECT ' -and $query -notmatch '\b(UPDATE|DELETE|INSERT|DROP|ALTER)\b') 'Demo verification SQL must be read-only'
                $actual=Lab-Sql $lab $query;$ok=$actual -eq $step.expected
                $last=@{sql=$query;actual=$actual;expected=$step.expected}
            }else{
                $path=Resolve-DemoValue $step.path
                $body=if($step.ContainsKey('body')){Resolve-DemoValue $step.body}else{$null}
                $response=Http-Demo $path $step.actor $body $step.method -B:($step.instance -eq 'B')
                $ok=$response.status -eq $step.expectedStatus
                if($step.check){foreach($pointer in $step.check.Keys){if((Read-Pointer $response.body $pointer) -ne $step.check[$pointer]){$ok=$false}}}
                $last=@{method=$step.method;path=$path;expectedStatus=$step.expectedStatus;response=$response}
            }
            if($ok){break}
            if(-not $step.waitSeconds -or [DateTimeOffset]::Now -ge $deadline){throw "Demo '$($step.name)' failed: $($last|ConvertTo-Json -Depth 20 -Compress)"}
            Start-Sleep -Milliseconds 1000
        }while($true)
        if($step.capture){foreach($key in $step.capture.Keys){$value=Read-Pointer $response.body $step.capture[$key];Assert-Lab ($null -ne $value) "Missing response value $key in $($last|ConvertTo-Json -Depth 20 -Compress)";$variables[$key]=$value}}
        $results.Add(@{name=$step.name;startedAt=$at.ToString('o');seconds=([DateTimeOffset]::Now-$at).TotalSeconds;attempts=$attempts;passed=$true;detail=$last})
        Save-Lab (Join-Path $folder 'steps.json') @($results)
        Save-PrivateDemo
        Write-Output "PASS $($step.name)"
    }
    Wait-Lab 'demo immediate Outbox and cache tasks converge' {
        (Lab-Sql $lab "SELECT (SELECT COUNT(*) FROM outbox_event e WHERE e.event_type NOT IN ('ORDER_EXPIRED','RESCHEDULE_WINDOW_CLOSED') AND NOT EXISTS(SELECT 1 FROM consumer_process p WHERE p.event_id=e.event_id))+(SELECT COUNT(*) FROM cache_invalidation_task WHERE completed_at IS NULL);") -eq '0'
    } 120
    Lab-Reconcile $lab (Join-Path $folder 'final')
    Save-Lab (Join-Path $folder 'result.json') @{passed=$true;steps=$results.Count;sourceCommit=$lab.context.sourceCommit;startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');seconds=([DateTimeOffset]::Now-$started).TotalSeconds;databaseDifferences=0;roleBootstrapOnly=$true}
    Write-Output "DEMO_DIRECTORY=$folder"
}catch{
    Save-Lab (Join-Path $folder 'failure.json') @{at=[DateTimeOffset]::Now.ToString('o');error=$_.Exception.Message;completedSteps=$results.Count}
    throw
}
