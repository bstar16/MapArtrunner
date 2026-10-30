# Bounded dummy processes only; this script never invokes Gradle or Minecraft.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$testRoot = Join-Path $repo ('v2/fabric/build/scenario-results/selftest-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
Start-Transcript -Path (Join-Path $testRoot 'selftest.log') | Out-Null
try {
    . (Join-Path $PSScriptRoot 'test-v2.ps1')
    function Assert($Condition, [string]$Message) { if (!$Condition) { throw $Message } }
    function Reject([scriptblock]$Action, [string]$Message) {
        $rejected = $false
        try { & $Action | Out-Null } catch { $rejected = $true }
        Assert $rejected $Message
    }
    function Run([string]$Name, [scriptblock]$Body) {
        & $Body
        Write-Output "PASS $Name"
    }
    function Invoke-V2EnvironmentFixture([string[]]$Names, [scriptblock]$Body) {
        $saved=@{}
        foreach ($name in $Names) { $saved[$name]=Get-Item "Env:$name" -ErrorAction SilentlyContinue }
        try { & $Body } finally {
            foreach ($name in $Names) {
                if ($saved[$name]) { [Environment]::SetEnvironmentVariable($saved[$name].Name,[string]$saved[$name].Value) }
                else { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
            }
        }
    }
    $regressions=[Collections.Generic.List[object]]::new()
    function Regression([int]$Finding, [scriptblock]$Body) {
        try { & $Body; $regressions.Add(@{ Finding=$Finding; Passed=$true }); Write-Output "PASS review finding$Finding" }
        catch {
            $regressions.Add(@{ Finding=$Finding; Passed=$false; Error=$_.Exception.ToString() })
            Write-Output "FAIL review finding${Finding}: $($_.Exception.Message)"
            if ($_.Exception.Message -like 'HOLD:*') {
                $regressions | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'review-regressions.json')
                throw
            }
        }
        $regressions | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'review-regressions.json')
    }
    Run 'arguments rejected before worker start' {
        Reject { New-V2Request -Scenario bogus } 'invalid scenario accepted'
        Reject { New-V2Request -Repeat 3 } 'invalid repeat accepted'
        Reject { New-V2Request -Scenario all -WrongExpectedBlock } 'all negative accepted'
        Reject { New-V2Request -EulaEnvironment '' } 'missing EULA accepted'
        Reject { New-V2Request -EulaEnvironment true } 'nonexact EULA accepted'
        $default = New-V2Request -EulaEnvironment 1
        Assert ($default.Arguments -notmatch 'mapart.test') 'default flags mask actual source defaults'
        $selected = New-V2Request -Scenario grounded-two-lane -Repeat 1 -EulaAccepted
        Assert ($selected.Arguments -contains '-Pmapart.test.scenario=grounded-two-lane') 'scenario not forwarded'
        Assert ($selected.Arguments -contains '-Pmapart.test.repeat=1') 'repeat not forwarded'
    }
    Run 'JDK requires both tools/version21; stale candidate fallback; explicit invalid; none' {
        $fake = Join-Path $testRoot 'fake-jdk'
        New-Item -ItemType Directory -Path (Join-Path $fake bin) | Out-Null
        Set-Content (Join-Path $fake bin/java.exe) ''
        $probe = { param($Executable) if ($Executable -like '*javac.exe') { 'javac 17.0.1' } else { 'openjdk version "21.0.1"' } }
        Reject { Find-V2Jdk -JavaHome $fake -Probe $probe } 'JRE without javac accepted'
        Set-Content (Join-Path $fake bin/javac.exe) ''
        Reject { Find-V2Jdk -JavaHome $fake -Probe $probe } 'javac17 accepted'
        $probe = { param($Executable) if ($Executable -like '*javac.exe') { 'javac 21.0.1' } else { 'openjdk version "17.0.1"' } }
        Reject { Find-V2Jdk -JavaHome $fake -Probe $probe } 'java17 accepted'
        $probe = { param($Executable) if ($Executable -like '*javac.exe') { 'javac 21.0.1' } else { 'openjdk version "21.0.1"' } }
        $found = Find-V2Jdk -Candidates @((Join-Path $testRoot stale), $fake) -Probe $probe
        Assert ($found.Home -eq $fake) 'stale automatic candidate prevented fallback'
        Reject { Find-V2Jdk -JavaHome (Join-Path $testRoot stale) -Candidates @($fake) -Probe $probe } 'explicit invalid home fell back'
        Reject { Find-V2Jdk -Candidates @((Join-Path $testRoot stale)) -Probe $probe } 'no qualifying JDK accepted'
        $actual = Find-V2Jdk
        Assert ($actual.JavaVersion -match 'version "21[.\"]' -and $actual.JavacVersion -match '^javac 21[.\s]') 'installed tools not actually21'
        $script:actualJdk = $actual
    }
    Regression 1 {
        $original=Get-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
        try {
            $env:JAVA_TOOL_OPTIONS='-Doperator.diagnostic=preserved'
            $probe=Find-V2Jdk -JavaHome $actualJdk.Home
            $probe | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'diagnostic-jdk.json')
            Assert ($probe.JavaVersion -match '(?m)^Picked up JAVA_TOOL_OPTIONS:' -and $probe.JavaVersion -match '(?m)^openjdk version "21\.') 'actual diagnostic JDK banner not retained'
        } finally {
            if ($original) { [Environment]::SetEnvironmentVariable($original.Name,[string]$original.Value) }
            else { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
        }
    }
    Regression 2 {
        $accepted=[Collections.Generic.List[string]]::new()
        foreach ($selection in @(@{Scenario='GROUNDED-TWO-LANE'},@{Scenario='ALL'},@{Repeat=1.4},@{Repeat='01'},@{Repeat='1.0'},@{Repeat='+1'})) {
            foreach ($boundary in 'function','public') {
                $rejected=$false
                try {
                    if ($boundary -eq 'function') { New-V2Request @selection -EulaAccepted | Out-Null }
                    else { . (Join-Path $PSScriptRoot 'test-v2.ps1') @selection -EulaAccepted }
                } catch { $rejected=$true }
                if (!$rejected) { $accepted.Add("$boundary $($selection | ConvertTo-Json -Compress)") }
            }
        }
        $accepted | ConvertTo-Json -AsArray | Set-Content (Join-Path $testRoot 'selection-accepted-invalid.json')
        Assert ($accepted.Count -eq 0) "invalid selection accepted before coercion: $($accepted -join '; ')"
    }
    Regression 3 {
        $accepted=[Collections.Generic.List[string]]::new()
        foreach ($invalid in @('', '   ')) {
            foreach ($boundary in 'function','public') {
                $rejected=$false
                try {
                    if ($boundary -eq 'function') { Find-V2Jdk -JavaHome $invalid -Candidates @($actualJdk.Home) | Out-Null }
                    else { . (Join-Path $PSScriptRoot 'test-v2.ps1') -JavaHome $invalid }
                } catch { $rejected=$true }
                if (!$rejected) { $accepted.Add("$boundary explicit '$invalid'") }
            }
        }
        $automatic=Find-V2Jdk -Candidates @($actualJdk.Home)
        Assert ($automatic.Home -eq $actualJdk.Home) 'omitted JavaHome did not use automatic candidates'
        Assert ($accepted.Count -eq 0) "explicit invalid home accepted: $($accepted -join '; ')"
    }
    Regression 4 {
        $proof=[Collections.Generic.List[object]]::new()
        $absent='MAPART_SELFTEST_ABSENT_' + [guid]::NewGuid().ToString('N')
        $empty='MAPART_SELFTEST_EMPTY_' + [guid]::NewGuid().ToString('N')
        try {
            [Environment]::SetEnvironmentVariable($empty,'')
            foreach ($failBody in @($false,$true)) {
                $before=[Environment]::GetEnvironmentVariables()
                $bodyFailed=$false
                try { Invoke-V2EnvironmentFixture @($absent,$empty) { [Environment]::SetEnvironmentVariable($absent,'fixture'); [Environment]::SetEnvironmentVariable($empty,'fixture'); if ($failBody) { throw 'deliberate fixture body failure' } } }
                catch { $bodyFailed=$true }
                $after=[Environment]::GetEnvironmentVariables()
                $equal=$before.Count -eq $after.Count
                foreach ($key in $before.Keys) { if (!$after.Contains($key) -or $before[$key] -cne $after[$key]) { $equal=$false } }
                $proof.Add(@{ FailingBody=$failBody; BodyFailed=$bodyFailed; BeforeCount=$before.Count; AfterCount=$after.Count; EqualAfterFinally=$equal; AbsentPresent=(Test-Path "Env:$absent"); EmptyPresent=(Test-Path "Env:$empty"); EmptyValue=[Environment]::GetEnvironmentVariable($empty) })
                Remove-Item "Env:$absent" -ErrorAction SilentlyContinue
            }
        } finally { Remove-Item "Env:$absent","Env:$empty" -ErrorAction SilentlyContinue }
        $proof | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'post-finally-environment-proof.json')
        Assert (@($proof | Where-Object { !$_.EqualAfterFinally -or $_.BodyFailed -ne $_.FailingBody -or $_.AbsentPresent -or !$_.EmptyPresent -or $_.EmptyValue -cne '' }).Count -eq 0) 'fixture restoration changed membership/value after finally'
    }
    Regression 5 {
        $ready=Join-Path $testRoot 'short-child-ready'; $release=Join-Path $testRoot 'short-child-release'
        $child="Set-Content -LiteralPath '" + $ready.Replace("'","''") + "' ready; while (!(Test-Path -LiteralPath '" + $release.Replace("'","''") + "')) { Start-Sleep -Milliseconds 10 }; Start-Sleep -Milliseconds 300; exit 0"
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($child))
        $body=[scriptblock]::Create('$p=Start-Process -FilePath (Join-Path $PSHOME pwsh.exe) -WindowStyle Hidden -ArgumentList "-NoProfile","-EncodedCommand","' + $encoded + '" -PassThru; Write-Output "shortChild=$($p.Id)"; while (!(Test-Path -LiteralPath ' + "'" + $ready.Replace("'","''") + "')) { Start-Sleep -Milliseconds 10 }; Set-Content -LiteralPath '" + $release.Replace("'","''") + "' release; exit 0")
        $result=Invoke-V2Supervisor -Worker $body -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
        if ($result.CleanupStatus -ne 'settled') { throw 'HOLD: short-tail ownership unresolved; no more launches.' }
        $output=Get-Content (Join-Path $result.Evidence 'launch.stdout.log') -Raw
        Assert ($output -match 'shortChild=(\d+)') 'short child identity missing'
        $childId=[int]$Matches[1]
        Assert ($result.ExitCode -eq 0 -and @($result.Processes | Where-Object { $_.Pid -eq $childId -and $_.ExitCode -eq 0 }).Count -eq 1) 'naturally closing short child was forced/failed'
    }
    Regression 6 {
        $atomicRoot=Join-Path $testRoot 'atomic-inventory'; $game=Join-Path $atomicRoot game
        New-Item -ItemType Directory -Path $game -Force | Out-Null
        $staleSnapshot=@(Get-V2ScenarioInventory $atomicRoot)
        $bPath=Join-Path $atomicRoot 'two-lane-B'; $aPath=Join-Path $atomicRoot 'two-lane-A'
        $b=Invoke-V2Supervisor -Worker ([scriptblock]::Create("New-Item -ItemType Directory -Path '" + $bPath.Replace("'","''") + "' | Out-Null; Set-Content -LiteralPath '" + (Join-Path $bPath artifact.txt).Replace("'","''") + "' B")) -EvidenceRoot $atomicRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
        if ($b.CleanupStatus -ne 'settled') { throw 'HOLD: interleaved B ownership unresolved; no more launches.' }
        Assert ($b.ExitCode -eq 0) 'interleaved B failed'
        $inventoryImplementation=${function:Get-V2ScenarioInventory}; $inventoryProof=[Collections.Generic.List[object]]::new()
        try {
            Set-Item Function:Get-V2ScenarioInventory -Value {
                param($ScenarioRoot)
                $held=$false; $probe=$null
                try { $probe=[IO.File]::Open((Join-Path $ScenarioRoot 'supervisor.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None) }
                catch [IO.IOException] { $held=$true }
                finally { if ($probe) { $probe.Dispose() } }
                $inventoryProof.Add(@{ ScenarioRoot=$ScenarioRoot; ExclusiveHandleHeld=$held })
                & $inventoryImplementation $ScenarioRoot
            }
            $archive={ param($evidence,$before) Save-V2Artifacts -Evidence $evidence -Game $game -ScenarioRoot $atomicRoot -Before $before -Owner $repo }
            $a=Invoke-V2Supervisor -Worker ([scriptblock]::Create("New-Item -ItemType Directory -Path '" + $aPath.Replace("'","''") + "' | Out-Null; Set-Content -LiteralPath '" + (Join-Path $aPath artifact.txt).Replace("'","''") + "' A")) -EvidenceRoot $atomicRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000 -Archive $archive
        } finally { Set-Item Function:Get-V2ScenarioInventory -Value $inventoryImplementation }
        @{ StaleExternalSnapshot=$staleSnapshot; InventoryEvents=@($inventoryProof); InvocationA=$a.Evidence; InvocationB=$b.Evidence } | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $testRoot 'atomic-inventory-proof.json')
        if ($a.CleanupStatus -ne 'settled') { throw 'HOLD: interleaved A ownership unresolved; no more launches.' }
        Assert ($a.ExitCode -eq 0 -and @($inventoryProof | Where-Object { !$_.ExclusiveHandleHeld }).Count -eq 0 -and $inventoryProof.Count -ge 2) 'inventory not taken under the exclusive launch handle'
        Assert (Test-Path (Join-Path $a.Evidence 'scenarios/two-lane-A/artifact.txt')) 'A artifact missing'
        Assert (!(Test-Path (Join-Path $a.Evidence 'scenarios/two-lane-B'))) 'completed interleaved B credited to A'
    }
    Regression 7 {
        $childPidFile=Join-Path $testRoot 'failed-worker-child-pid.txt'
        $childEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Start-Sleep -Seconds 30'))
        $worker=[scriptblock]::Create('$child=Start-Process -FilePath (Join-Path $PSHOME pwsh.exe) -WindowStyle Hidden -ArgumentList "-NoProfile","-EncodedCommand","' + $childEncoded + '" -PassThru; Set-Content -LiteralPath ' + "'" + $childPidFile.Replace("'","''") + "' `$child.Id; exit 7")
        $phase=@{ CapturedWorker7=$false; ChildOwned=$false; RequestedDeadlineMilliseconds=10000 }
        $updateImplementation=${function:Update-V2Processes}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $updateImplementation $Job $Records $Handles -Fast:$Fast
                if ($Fast -and !$phase.CapturedWorker7 -and $result.WorkerExitCode -eq 7) {
                    $childId=[int](Get-Content -LiteralPath $childPidFile -Raw)
                    $phase.CapturedWorker7=$true
                    $phase.ChildOwned=$Job.Pids() -contains $childId
                    $phase.ChildPid=$childId
                    $phase.ClockAtCapturedFailure=$clock.ElapsedMilliseconds
                    $phase.CleanupClockAtCapturedFailure=$cleanupClock.ElapsedMilliseconds
                    # Test-only: shorten remaining allowance after observing the real failure; no startup timing guess.
                    $phase.InjectedDeadlineMilliseconds=[int]$clock.ElapsedMilliseconds + 200
                    if ($phase.InjectedDeadlineMilliseconds -ge $TimeoutMilliseconds) { throw 'Insufficient original allowance for phase-controlled regression.' }
                    Set-Variable -Name TimeoutMilliseconds -Value $phase.InjectedDeadlineMilliseconds -Scope 1
                }
            }
            $outcome=Invoke-V2Supervisor -Worker $worker -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000 |
                Where-Object { $_.PSObject.Properties['Evidence'] }
        } finally { Set-Item Function:Update-V2Processes -Value $updateImplementation }
        $phase.Outcome=$outcome
        $phase | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $testRoot 'captured-failure-drain-proof.json')
        if ($outcome.CleanupStatus -ne 'settled') { throw 'HOLD: captured-failure descendant ownership unresolved; no more launches.' }
        Assert ($phase.CapturedWorker7 -and $phase.ChildOwned -and $outcome.TimedOut -and $outcome.WorkerExitCode -eq 7) 'captured-failure cleanup deadline phase was not exercised'
        Assert ($outcome.ExitCode -eq 7) 'cleanup deadline replaced captured worker7'
        Assert (@($outcome.Processes | Where-Object { $null -eq $_.ExitCode }).Count -eq 0) 'held process exit missing after captured-failure cleanup'
    }
    Run 'child environment clears old negatives and ordinary selector; parent preserved' {
        $names = @('MAPART_PROBE_WRONG_EXPECTED_BLOCK','MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS',
            'MAPART_STATE_FAULT_FORCE_TARGET_RELOAD','MAPART_TWO_LANE_SUPPORT_CASE','MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK',
            'JAVA_TOOL_OPTIONS','JAVA_HOME','PATH')
        Invoke-V2EnvironmentFixture $names {
            foreach ($name in $names[0..4]) { [Environment]::SetEnvironmentVariable($name, 'historical') }
            $env:JAVA_TOOL_OPTIONS = '-Doperator.diagnostic=preserved'
            $before = [Environment]::GetEnvironmentVariables().Clone()
            foreach ($wrong in @($false, $true)) {
                $childEnv = New-V2Environment -Jdk $actualJdk -WrongExpectedBlock:$wrong
                $output = Join-Path $testRoot ('child-' + $wrong + '.json')
                $body = [scriptblock]::Create('@{ historical=$env:MAPART_PROBE_WRONG_EXPECTED_BLOCK; observation=$env:MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS; state=$env:MAPART_STATE_FAULT_FORCE_TARGET_RELOAD; support=$env:MAPART_TWO_LANE_SUPPORT_CASE; wrong=$env:MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK; diagnostic=$env:JAVA_TOOL_OPTIONS; home=$env:JAVA_HOME } | ConvertTo-Json | Set-Content -LiteralPath ' + "'" + $output.Replace("'","''") + "'")
                $result = Invoke-V2Supervisor -Worker $body -Environment $childEnv -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
                Assert ($result.ExitCode -eq 0) 'environment child failed'
                $data = Get-Content $output -Raw | ConvertFrom-Json
                Assert (!$data.historical -and !$data.observation -and !$data.state -and !$data.support) 'historical flag leaked'
                Assert ($data.wrong -eq $(if ($wrong) {'1'} else {$null})) 'negative flag provenance incorrect'
                Assert ($data.diagnostic -match 'operator.diagnostic=preserved') 'operator diagnostics lost'
                Assert ($data.home -eq $actualJdk.Home) 'JDK not passed to child'
            }
            $after = [Environment]::GetEnvironmentVariables()
            Assert ($before.Count -eq $after.Count) 'parent environment size changed'
            foreach ($key in $before.Keys) { Assert ($before[$key] -ceq $after[$key]) "parent environment changed: $key" }
            $environmentProof=@{ Before=@{}; After=@{}; BeforeCount=$before.Count; AfterCount=$after.Count; EveryParentVariableCompared=$true }
            foreach ($name in $names) { $environmentProof.Before[$name]=$before[$name]; $environmentProof.After[$name]=$after[$name] }
            $environmentProof | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'parent-environment-proof.json')
        }
    }
    Run 'owned worker exits0/7; startup125; archive failure never0 or masks7' {
        foreach ($code in @(0,7)) {
            $result = Invoke-V2Supervisor -Worker ([scriptblock]::Create("Write-Output 'dummy stdout'; [Console]::Error.WriteLine('dummy stderr'); exit $code")) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
            Assert (@($result).Count -eq 1) "exit$code supervisor returned multiple pipeline values"
            Assert ($result.ExitCode -eq $code -and $result.WorkerExitCode -eq $code -and $result.CleanupStatus -eq 'settled') "exit$code not retained"
            Assert ((Get-Content (Join-Path $result.Evidence 'launch.stdout.log') -Raw) -match 'dummy stdout') 'stdout missing'
            Assert ((Get-Content (Join-Path $result.Evidence 'launch.stderr.log') -Raw) -match 'dummy stderr') 'stderr missing'
            Assert (@($result.Processes | Where-Object { $_.ExitCode -eq $code }).Count -ge 1) 'real held worker exit missing'
        }
        $result = Invoke-V2Supervisor -Worker { exit 0 } -WorkerExecutable (Join-Path $testRoot missing.exe) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
        Assert ($result.ExitCode -eq 125 -and $null -eq $result.WorkerExitCode) 'startup not125'
        foreach ($code in @(0,7)) {
            $archiveRoot=Join-Path $testRoot "archive-failure-$code"
            $result = Invoke-V2Supervisor -Worker ([scriptblock]::Create("exit $code")) -EvidenceRoot $archiveRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000 -Archive { throw 'deliberate archive failure' }
            Assert ($result.ArchiveStatus -eq 'failed' -and $result.WorkerExitCode -eq $code) 'archive error lost primary outcome'
            Assert ($result.ExitCode -eq $(if ($code) {7} else {125})) 'archive error zero/masked7'
            Reject { Invoke-V2Supervisor -Worker { exit 0 } -EvidenceRoot $archiveRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000 } 'unretained evidence allowed rerun'
        }
    }
    Run 'timeout124 kills owned grandchild and leaves unrelated sentinel; root-exit descendants settled' {
        $sentinel = [Diagnostics.Process]::new()
        $sentinel.StartInfo = New-V2ProcessInfo -Executable (Join-Path $PSHOME pwsh.exe) -WorkingDirectory $repo -Command 'Start-Sleep -Seconds 40'
        Assert $sentinel.Start() 'sentinel start failed'
        try {
            foreach ($rootExits in @($false,$true)) {
                $grandchild = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Start-Sleep -Seconds 30'))
                $body = [scriptblock]::Create('$p = Start-Process -FilePath (Join-Path $PSHOME pwsh.exe) -WindowStyle Hidden -ArgumentList "-NoProfile", "-EncodedCommand", "' + $grandchild + '" -PassThru; Write-Output "grandchild=$($p.Id)"; ' + $(if ($rootExits) {'Start-Sleep -Seconds 2; exit 0'} else {'Start-Sleep -Seconds 30'}))
                $result = Invoke-V2Supervisor -Worker $body -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 5000
                Assert ($result.ExitCode -eq $(if ($rootExits) {125} else {124})) 'timeout/root-exit outcome wrong'
                Assert ($result.TimedOut -eq (!$rootExits) -and $result.CleanupStatus -eq 'settled') 'timeout/settlement missing'
                Assert (@($result.Processes).Count -ge 2) 'owned grandchild not retained'
                Assert (@($result.Processes | Where-Object { $null -eq $_.ExitCode }).Count -eq 0) 'owned held exit missing'
                Assert (!$sentinel.HasExited) 'unrelated sentinel terminated'
            }
        } finally {
            if (!$sentinel.HasExited) { $sentinel.Kill(); Assert $sentinel.WaitForExit(10000) 'sentinel cleanup failed' }
            $sentinel.Dispose()
        }
    }
    Run 'archive retains only new scenario artifacts and copies links as opaque metadata' {
        $archiveFixture=Join-Path $testRoot 'archive-fixture'
        $game=Join-Path $archiveFixture game; $scenarios=Join-Path $archiveFixture scenarios; $retained=Join-Path $archiveFixture retained
        foreach ($dir in @("$game/logs","$game/crash-reports","$scenarios/two-lane-old",$retained)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Set-Content "$scenarios/two-lane-old/stale.txt" 'stale'
        $before=@(Get-V2ScenarioInventory $scenarios)
        New-Item -ItemType Directory -Path "$scenarios/two-lane-new/ordinary" -Force | Out-Null
        Set-Content "$game/logs/latest.log" 'game log'; Set-Content "$game/crash-reports/crash.txt" 'crash'
        Set-Content "$scenarios/two-lane-new/ordinary/sentinel.bin" 'owned sentinel'
        New-Item -ItemType Junction -Path "$scenarios/two-lane-new/alias" -Target "$scenarios/two-lane-new/ordinary" | Out-Null
        $linkPath=(Join-Path $scenarios 'two-lane-new/dangling').Replace('/','\')
        $absentPath=(Join-Path $scenarios 'two-lane-new/absent').Replace('/','\')
        $makeLink=[scriptblock]::Create('& $env:ComSpec /d /c mklink /J ' + "'" + $linkPath.Replace("'","''") + "' '" + $absentPath.Replace("'","''") + "'; `$code=`$LASTEXITCODE; exit `$code")
        $linkResult=Invoke-V2Supervisor -Worker $makeLink -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
        Assert ($linkResult.ExitCode -eq 0) 'owned dangling junction helper failed'
        Save-V2Artifacts -Evidence $retained -Game $game -ScenarioRoot $scenarios -Before $before -Owner $archiveFixture
        Assert (Test-Path "$retained/logs/latest.log") 'game logs not retained'
        Assert (Test-Path "$retained/crash-reports/crash.txt") 'crash reports not retained'
        Assert (Test-Path "$retained/scenarios/two-lane-new/ordinary/sentinel.bin") 'new scenario bytes missing'
        Assert (!(Test-Path "$retained/scenarios/two-lane-old")) 'stale scenario credited'
        Assert (!(Test-Path "$retained/scenarios/two-lane-new/alias")) 'reparse alias followed/recreated'
        $links=@(Get-Content "$retained/reparse-points.json" -Raw | ConvertFrom-Json)
        Assert ($links.Count -eq 2 -and @($links | Where-Object { !$_.LinkTarget }).Count -eq 0) 'opaque live/dangling link provenance missing'
        Assert ((Get-Content "$scenarios/two-lane-new/ordinary/sentinel.bin" -Raw).Trim() -eq 'owned sentinel') 'sentinel bytes changed'
        Reject { Copy-V2Evidence "$scenarios/two-lane-new/ordinary" "$archiveFixture/../escape" $archiveFixture $archiveFixture } 'destination escape accepted'
    }
    $regressions | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'review-regressions.json')
    if (@($regressions | Where-Object { !$_.Passed }).Count) { throw "Review regression(s) failed; evidence=$testRoot" }
    Write-Output "ALL SELF-CHECKS PASSED; dummy processes only; evidence=$testRoot"
} finally { Stop-Transcript | Out-Null }
