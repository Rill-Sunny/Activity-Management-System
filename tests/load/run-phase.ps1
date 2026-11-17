param([Parameter(Mandatory)][string]$RunDirectory,[Parameter(Mandatory)][string]$Phase,[switch]$Smoke)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../system/common.ps1')
$lab=Open-Lab $RunDirectory
Wait-Lab 'Tempo ready before formal load' {try{(Invoke-WebRequest ('http://127.0.0.1:'+$lab.settings.TEMPO_PORT+'/ready') -TimeoutSec 5).StatusCode -eq 200}catch{$false}} 180
$runner=Join-Path $PSScriptRoot 'run-round.ps1'
$failures=[Collections.Generic.List[string]]::new()
function Invoke-Round {
    param([string]$RunDirectory,[string]$Phase,[string]$Scenario,[int]$Vus,[switch]$Smoke,[switch]$Cold,[switch]$LastSlot)
    try{& $runner @PSBoundParameters}catch{$failures.Add($_.Exception.Message);Write-Warning $_.Exception.Message}
}
foreach($vu in @(10,50,100)){
    Invoke-Round -RunDirectory $RunDirectory -Phase $Phase -Scenario S08 -Vus $vu -Smoke:$Smoke
}
Invoke-Round -RunDirectory $RunDirectory -Phase $Phase -Scenario S08 -Vus 50 -Cold
foreach($scenario in @('S01','S02','S03','S04','S05','S06','S07','S09')){
    Invoke-Round -RunDirectory $RunDirectory -Phase $Phase -Scenario $scenario -Vus 50
}
foreach($vu in @(10,100)){
    foreach($scenario in @('S01','S03','S04','S05','S06')){Invoke-Round -RunDirectory $RunDirectory -Phase $Phase -Scenario $scenario -Vus $vu}
}
Invoke-Round -RunDirectory $RunDirectory -Phase $Phase -Scenario S01 -Vus 150 -LastSlot
$failures|ConvertTo-Json -AsArray|Set-Content (Join-Path $RunDirectory ($Phase+'/phase-failures.json'))
if($failures.Count){throw "Load phase contains $($failures.Count) failed rounds; original assertions retained."}
