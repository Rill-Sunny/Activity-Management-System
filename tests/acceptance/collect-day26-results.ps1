param([Parameter(Mandatory)][string]$PhaseDirectory,[Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$phase=[IO.Path]::GetFullPath($PhaseDirectory)
$run=[IO.Path]::GetFullPath($RunDirectory)
$summary=Get-Content -LiteralPath (Join-Path $phase 'summary.json') -Raw | ConvertFrom-Json
$cases=@()
foreach($file in Get-ChildItem -LiteralPath (Join-Path $phase 'surefire') -Filter 'TEST-*.xml'){
    [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
    foreach($case in $xml.testsuite.testcase){
        $status=if($case.failure){'FAIL'}elseif($case.error){'ERROR'}elseif($case.skipped){'SKIPPED'}else{'PASS'}
        $cases+=@{class=$case.classname;name=$case.name;result=$status;xml=$file.FullName.Substring($repo.Length+1).Replace('\','/')}
    }
}
$evidence=@(Get-ChildItem -LiteralPath (Join-Path $phase 'at-evidence') -Filter '*.json' | ForEach-Object {
    $item=Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
    $item | Add-Member -NotePropertyName evidenceFile -NotePropertyValue $_.FullName.Substring($repo.Length+1).Replace('\','/') -PassThru
})
function Test-Cases($test){@($cases | Where-Object {$_.class -eq $test.class -and ($_.name -eq $test.method -or $_.name.StartsWith($test.method+'('))})}
function Status($matched){
    if($matched.Count -eq 0){return 'ERROR'}
    foreach($state in @('ERROR','FAIL','SKIPPED')){if(@($matched | Where-Object result -eq $state).Count -gt 0){return $state}}
    return 'PASS'
}
$matrix=@()
foreach($entry in Get-Content -LiteralPath (Join-Path $repo 'tests/fixtures/day26/acceptance-matrix.json') -Raw | ConvertFrom-Json){
    $methods=@()
    foreach($test in $entry.tests){
        $matched=@(Test-Cases $test);$state=Status $matched
        $records=@($evidence | Where-Object {$_.class -eq $test.class -and $_.method -eq $test.method})
        $valid=@($records | Where-Object {$_.captureStatus -eq 'CAPTURED' -and $_.invariantDifferences -eq 0 -and $_.sourceCommit -eq $summary.sourceCommit})
        if($state -eq 'PASS' -and ($records.Count -ne $matched.Count -or $valid.Count -ne $records.Count)){$state='ERROR'}
        $methods+=@{class=$test.class;method=$test.method;result=$state;invocations=$matched.Count;sqlSnapshots=$valid.Count;evidence=@($records.evidenceFile);junit=@($matched.xml | Sort-Object -Unique)}
    }
    $matrix+=@{id=$entry.id;rule=$entry.rule;fixture=$entry.fixture;result=(Status $methods);tests=$methods;sourceCommit=$summary.sourceCommit}
}
$matrix | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $phase 'at-matrix-results.json') -Encoding utf8
$risk=@()
foreach($entry in Get-Content -LiteralPath (Join-Path $repo 'tests/fixtures/day26/risk-matrix.json') -Raw | ConvertFrom-Json){
    $methods=@($entry.tests | ForEach-Object {$m=@(Test-Cases $_);@{class=$_.class;method=$_.method;result=(Status $m);invocations=$m.Count;junit=@($m.xml | Sort-Object -Unique)}})
    $risk+=@{id=$entry.id;rule=$entry.rule;result=(Status $methods);tests=$methods}
}
$risk | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $phase 'risk-matrix-results.json') -Encoding utf8
$dualPath=Join-Path $run 'dual-instance/business-results.json'
$dual=if(Test-Path -LiteralPath $dualPath){@(Get-Content -LiteralPath $dualPath -Raw | ConvertFrom-Json)}else{@()}
$result=@{sourceCommit=$summary.sourceCommit;phase=$phase.Substring($repo.Length+1).Replace('\','/');counts=$summary.counts;atPassed=@($matrix | Where-Object result -eq 'PASS').Count;atTotal=$matrix.Count;riskPassed=@($risk | Where-Object result -eq 'PASS').Count;riskTotal=$risk.Count;dualInstanceScenarios=$dual.Count;dualInstancePassed=@($dual | Where-Object status -eq 'PASSED').Count;sourceUnchanged=$summary.sourceUnchangedDuringRun;exitCode=$summary.exitCode;skipped=@($cases | Where-Object result -eq 'SKIPPED')}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $phase 'acceptance-summary.json') -Encoding utf8
$result | Select-Object sourceCommit,counts,atPassed,atTotal,riskPassed,riskTotal,dualInstanceScenarios,dualInstancePassed,exitCode | ConvertTo-Json -Depth 5
if($summary.exitCode -ne 0 -or -not $summary.sourceUnchangedDuringRun -or $result.atPassed -ne 20){throw 'AT acceptance is incomplete; inspect generated matrix'}
if($summary.stage -eq 'FullSuite' -and ($result.riskPassed -ne $result.riskTotal -or $dual.Count -eq 0 -or $result.dualInstancePassed -ne $dual.Count)){throw 'High-risk or dual-instance evidence is incomplete'}
