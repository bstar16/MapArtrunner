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
    Run 'vanished owned PID tolerates stale enumeration without hiding other inventory failures' {
        $originalUpdate=${function:Update-V2Processes}; $capture=@{Process=$null}; $proof=@{}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if (!$capture.Process) {
                    $capture.Process=[Diagnostics.Process]::GetProcessById($process.Id)
                    [void]$capture.Process.Handle
                }
            }
            $outcome=Invoke-V2Supervisor -Worker { Start-Sleep -Milliseconds 300; exit 7 } -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        try {
            Assert ($outcome.WorkerExitCode -eq 7 -and $outcome.ExitCode -eq 7 -and $outcome.CleanupStatus -ceq 'settled') 'real owned race fixture did not exit7 and settle'
            $lookup=$null
            try { $unexpected=[Diagnostics.Process]::GetProcessById($outcome.WorkerPid); $unexpected.Dispose() }
            catch { $lookup=$_ }
            Assert ($lookup.Exception -is [Management.Automation.MethodInvocationException] -and
                $lookup.Exception.InnerException.GetType() -eq [ArgumentException]) 'owned PID no longer has the expected vanished-process exception'
            $proof.LookupType=$lookup.Exception.GetType().FullName; $proof.InnerType=$lookup.Exception.InnerException.GetType().FullName
            $proof.OriginalEvidence=$outcome.Evidence; $proof.ActualExit=$capture.Process.ExitCode
            # Deliberately stale enumeration of this real exited owned PID; it grants no native identity.
            $stale=[pscustomobject]@{Pid=$outcome.WorkerPid;Calls=0}
            $stale | Add-Member ScriptMethod Pids { $this.Calls++; @($this.Pid) }
            $records=[Collections.Generic.List[object]]::new(); $handles=[Collections.Generic.List[object]]::new()
            $record=($outcome.Processes | Where-Object Pid -EQ $outcome.WorkerPid).PSObject.Copy()
            $record.ExitCode=$null; $record.ExitedUtc=$null; $record.ObservedExitUtc=$null
            $handles.Add([pscustomobject]@{Process=$capture.Process;Record=$record})
            Update-V2Processes $stale $records $handles
            Assert (!$records.Count -and $record.ExitCode -eq 7 -and $record.ExitedUtc -and $record.ObservedExitUtc) 'vanished lookup lost held real exit/identity or fabricated a record'
            $proof.StaleSnapshotCalls=$stale.Calls; $proof.HeldRecord=$record
            $existingCim=Get-Item Function:Get-CimInstance -ErrorAction SilentlyContinue
            $failures=[Collections.Generic.List[object]]::new()
            try {
                foreach ($fault in 'argument','access','identity') {
                    Set-Item Function:Get-CimInstance -Value {
                        param($ClassName,$Filter,$OperationTimeoutSec,$ErrorAction)
                        if ($fault -ceq 'argument') { throw [ArgumentException]::new('Deliberate non-lookup argument failure') }
                        if ($fault -ceq 'access') { throw [UnauthorizedAccessException]::new('Deliberate CIM access failure') }
                        [pscustomobject]@{CreationDate=[DateTime]::UtcNow.AddDays(1)}
                    }
                    $live=[pscustomobject]@{Pid=$PID}; $live | Add-Member ScriptMethod Pids { @($this.Pid) }
                    $failure=$null
                    try { Update-V2Processes $live $records ([Collections.Generic.List[object]]::new()) } catch { $failure=$_ }
                    Assert ($failure -and !$records.Count) "non-lookup inventory failure was swallowed: $fault"
                    $failures.Add(@{Case=$fault;Type=$failure.Exception.GetType().FullName;Message=$failure.Exception.Message})
                }
            } finally {
                if ($existingCim) { Set-Item Function:Get-CimInstance -Value $existingCim.ScriptBlock }
                else { Remove-Item Function:Get-CimInstance }
            }
            $proof.NonLookupFailures=@($failures); $proof.Passed=$true
        } finally {
            $proof | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $testRoot 'vanished-pid-race-proof.json')
            if ($capture.Process) { $capture.Process.Dispose() }
        }
    }
    Run 'ordinary environment removes inherited launch identity' {
        $ordinary=New-V2Environment -Jdk ([pscustomobject]@{Home='C:/unused-jdk'})
        foreach ($name in 'MAPART_TEST_LAUNCH_ID','MAPART_TEST_LAUNCH_DIR') {
            Assert ($ordinary.ContainsKey($name) -and $null -eq $ordinary[$name]) "ordinary environment did not clear $name"
        }
    }
    Run 'prelaunch receipt rejection retains an outcome without starting a worker' {
        $originalInfo=${function:New-V2ProcessInfo}; $caught=$null; $outcome=$null
        try {
            Set-Item Function:New-V2ProcessInfo -Value {
                param($Executable,$WorkingDirectory,$Command,$Environment)
                [IO.File]::WriteAllText((Join-Path $evidence 'test-start.json'),'preexisting')
                & $originalInfo $Executable $WorkingDirectory $Command $Environment
            }
            try { $outcome=Invoke-V2Supervisor -Worker { exit 0 } -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 3000 -StartupMilliseconds 1000 }
            catch { $caught=$_ }
        } finally { Set-Item Function:New-V2ProcessInfo -Value $originalInfo }
        Assert (!$caught) 'prelaunch receipt rejection escaped outcome/archive retention'
        Assert ($outcome.ExitCode -eq 125 -and !$outcome.WorkerPid -and $outcome.CleanupStatus -ceq 'settled' -and $outcome.ArchiveStatus -ceq 'retained') 'prelaunch rejection started worker or lost cleanup/archive'
        Assert ($outcome.ReceiptRejectionReason -and (Test-Path -LiteralPath (Join-Path $outcome.Evidence 'outcome.json'))) 'prelaunch rejection reason/outcome missing'
    }
    Run 'worker failure observed between inventory and deadline survives cleanup failure' {
        $originalUpdate=${function:Update-V2Processes}; $window=@{Checked=$false;Injected=$false;ActualExit=$null}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                if ($Fast -and $window.Checked -and !$window.Injected) {
                    $window.Injected=$true
                    throw 'Deliberate cleanup inventory failure after observed worker exit.'
                }
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if (!$Fast -and !$window.Checked -and (Test-Path -LiteralPath $gate)) {
                    $root=$Records | Where-Object Pid -EQ $process.Id | Select-Object -First 1
                    Assert ($null -eq $root.ExitCode) 'worker-poll window was not open'
                    Assert ($process.WaitForExit(2000)) 'owned worker did not exit in poll-window allowance'
                    $window.Checked=$true; $window.ActualExit=$process.ExitCode
                    Start-Sleep -Milliseconds ([Math]::Max(0,[int]($deadlineMilliseconds-$clock.ElapsedMilliseconds+25)))
                }
            }
            # Separate evidence root retains the deliberately unresolved cleanup marker; this worker is already dead.
            $outcome=Invoke-V2Supervisor -Worker { Start-Sleep -Milliseconds 500; exit 7 } -EvidenceRoot (Join-Path $testRoot 'worker-poll-window') -WorkingDirectory $repo -TimeoutMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        Assert ($window.Checked -and $window.Injected -and $window.ActualExit -eq 7 -and $outcome.WorkerExitCode -eq 7) 'real worker-poll/cleanup-failure window not exercised'
        Assert ($outcome.ExitCode -eq 7 -and $outcome.PrimaryFailure.ExitCode -eq 7 -and $outcome.TimedOut -and $outcome.CleanupError) 'already-observed worker7 was replaced at deadline/cleanup failure'
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
    Run 'closed public traversal selection, five registrations and Java provider guards agree' {
        $providers=@('environment-probe','single-lane','schematic-import','grounded-two-lane','grounded-traversal')
        $scenarios=@('all','grounded-two-lane','grounded-traversal')
        foreach ($command in @((Get-Command (Join-Path $PSScriptRoot 'test-v2.ps1')),(Get-Command New-V2Request))) {
            $values=@($command.Parameters['Scenario'].Attributes | Where-Object { $_ -is [Management.Automation.ValidateSetAttribute] }).ValidValues
            Assert (($values -join ',') -ceq ($scenarios -join ',')) 'public/function selector set differs'
            Assert (!$command.Parameters.ContainsKey('InternalSupportCase')) 'controlled support exposed publicly'
        }
        foreach ($scenario in $scenarios) { foreach ($repeat in '1','2') {
            . (Join-Path $PSScriptRoot 'test-v2.ps1') -Scenario $scenario -Repeat $repeat -EulaAccepted
            $request=New-V2Request -Scenario $scenario -Repeat $repeat -EulaAccepted
            Assert ($request.Scenario -ceq $scenario -and $request.Repeat -eq [int]$repeat) 'requested selection/repeat changed'
            Assert (($request.Arguments -contains "-Pmapart.test.scenario=$scenario") -eq ($scenario -cne 'all')) 'scenario default/forwarding changed'
            Assert (($request.Arguments -contains '-Pmapart.test.repeat=1') -eq ($repeat -ceq '1')) 'repeat default/forwarding changed'
        } }
        Reject { New-V2Request -Scenario grounded-traversal -WrongExpectedBlock -EulaAccepted } 'traversal negative accepted'
        foreach ($invalid in 'GROUNDED-TRAVERSAL','grounded-traversal ','single-lane') {
            Reject { New-V2Request -Scenario $invalid -EulaAccepted } 'noncanonical traversal selector accepted'
            Reject { . (Join-Path $PSScriptRoot 'test-v2.ps1') -Scenario $invalid -EulaAccepted } 'public noncanonical traversal selector accepted'
        }
        $root=Join-Path $repo 'v2/fabric/src/gametest'
        $manifest=Get-Content (Join-Path $root 'resources/fabric.mod.json') -Raw | ConvertFrom-Json
        $expected=@('EnvironmentProbeGameTest','GroundedSingleLaneGameTest','SchematicImportGameTest','GroundedSmokeGameTest','TraversalGameTest')
        Assert ((@($manifest.entrypoints.'fabric-client-gametest') -join ',') -ceq (($expected | ForEach-Object { 'com.mapartrunner.fabric.scenario.'+$_ }) -join ',')) 'test registrations missing, duplicated or reordered'
        $java=Get-Content (Join-Path $root 'java/com/mapartrunner/fabric/scenario/GroundedSmokeGameTest.java') -Raw
        $catalog=[regex]::Match($java,'PROVIDERS=List\.of\(([^;]+)\);').Groups[1].Value
        Assert ((@([regex]::Matches($catalog,'"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }) -join ',') -ceq ($providers -join ',')) 'Java provider catalog differs from registration'
        $traversal=Get-Content (Join-Path $root 'java/com/mapartrunner/fabric/scenario/TraversalGameTest.java') -Raw
        Assert ($traversal -match 'runTest\(ClientGameTestContext context\)\s*\{\s*if\s*\(!GroundedSmokeGameTest.selected\("grounded-traversal"\)\) return;') 'traversal selection is not before all fixture/world work'
        Assert ($traversal.Contains('GroundedSmokeGameTest.requestedRepeat()')) 'traversal bypasses shared repeat getter'
        @{Providers=$providers;Scenarios=$scenarios;Repeats=@(1,2);Registrations=$manifest.entrypoints.'fabric-client-gametest';JavaChecks='source guards; compilation separately; native execution pending'} |
            ConvertTo-Json -Depth 4 | Set-Content (Join-Path $testRoot 'traversal-selection-proof.json')
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
        $phase=@{ CapturedWorker7=$false; ChildOwned=$false; RequestedDeadlineMilliseconds=3000 }
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
                    # Observe cleanup crossing the original frozen deadline; never mutate/renew the deadline.
                    $phase.FrozenDeadlineMilliseconds=$deadlineMilliseconds
                    if ($clock.ElapsedMilliseconds -ge $deadlineMilliseconds) { throw 'Insufficient original allowance for phase-controlled regression.' }
                    Start-Sleep -Milliseconds ([int]($deadlineMilliseconds-$clock.ElapsedMilliseconds+25))
                }
            }
            $outcome=Invoke-V2Supervisor -Worker $worker -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 3000 |
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
    Run 'frozen closed traversal calculation includes all and rejects malformed admissions' {
        Assert ((Get-V2TimeoutMilliseconds all 1) -eq 6433400) 'all repeat1 omits traversal deadline'
        Assert ((Get-V2TimeoutMilliseconds all 2) -eq 12266800) 'all repeat2 omits traversal deadline'
        Assert ((Get-V2TimeoutMilliseconds grounded-two-lane 1) -eq 600000) 'baseline deadline changed'
        Assert ((Get-V2TimeoutMilliseconds grounded-two-lane 2) -eq 600000) 'baseline repeat2 deadline changed'
        Assert ((Get-V2TimeoutMilliseconds grounded-traversal 1) -eq 6433400) 'future repeat1 deadline wrong'
        Assert ((Get-V2TimeoutMilliseconds grounded-traversal 2) -eq 12266800) 'future repeat2 deadline wrong'
        foreach ($invalid in @($null,1200,$true,'','0','-1','1.0','01','true','1e3','+1200',' 1200','2147483648','99999999999999999999999999')) {
            Reject { Get-V2TraversalTimeoutMilliseconds @('1200','1200','1200','1200','1200','1200',$invalid) 2 } "invalid tick count accepted: $invalid"
        }
        Reject { Get-V2TraversalTimeoutMilliseconds @('2147483647','1200','1200','1200','1200','1200','1200') 2 } 'overflow total accepted'
        Reject { Get-V2TimeoutMilliseconds unknown 1 } 'unknown internal scenario accepted'
        Reject { Get-V2TraversalTimeoutMilliseconds @('1200') 2 } 'incomplete fixed fixture set accepted'
        Reject { Get-V2TraversalTimeoutMilliseconds @('1200','1200','1200','1200','1200','1200','1200') 3 } 'invalid budget repeat accepted'
    }
    function New-V2ReceiptWorker([string]$Change='', [int]$WorkMilliseconds=0, [switch]$RepeatReceipt) {
        $body=@'
$self=[Diagnostics.Process]::GetCurrentProcess()
$receipt=@{schema='mapartrunner-test-start-v1';launchId=$env:MAPART_TEST_LAUNCH_ID;pid=$PID;startedUtc=$self.StartTime.ToUniversalTime().ToString('o');scenario='all';repeat=2;provider='environment-probe';event='SELECTED_PROVIDER_RUNTEST_ENTERED'}
'@
        $body+="`n$Change`n" + @'
$file=Join-Path $env:MAPART_TEST_LAUNCH_DIR 'test-start.json'
$text=$receipt | ConvertTo-Json -Compress
[IO.File]::WriteAllText($file,$text,[Text.UTF8Encoding]::new($false))
'@
        if ($RepeatReceipt) { $body+="`nforeach (`$iteration in 1..50) { Start-Sleep -Milliseconds 100; [IO.File]::WriteAllText(`$file,`$text,[Text.UTF8Encoding]::new(`$false)) }" }
        $body+="`nStart-Sleep -Milliseconds $WorkMilliseconds`nexit 0"
        [scriptblock]::Create($body)
    }
    function Assert-V2Settled($Outcome) {
        if ($Outcome.CleanupStatus -ne 'settled') { throw 'HOLD: startup regression ownership unresolved; no more launches.' }
        Assert ($Outcome.ArchiveStatus -eq 'retained') 'startup evidence archive failed'
    }
    Run 'controlled support rejects noncanonical requests before JDK or worker construction' {
        $originalJdk=${function:Find-V2Jdk}; $originalInfo=${function:New-V2ProcessInfo}
        $calls=@{Jdk=0;Worker=0}; $proof=[Collections.Generic.List[object]]::new()
        try {
            Set-Item Function:Find-V2Jdk -Value { $calls.Jdk++; throw 'Unexpected JDK probe in rejected support request.' }
            Set-Item Function:New-V2ProcessInfo -Value { $calls.Worker++; throw 'Unexpected worker construction in rejected support request.' }
            $cases=@(
                @{Name='without-game';Game=$false}, @{Name='null-request';Change={ $request=$null }},
                @{Name='all';Change={ $request.Scenario='all' }}, @{Name='traversal';Change={ $request=New-V2Request -Scenario grounded-traversal -Repeat 2 -EulaAccepted }},
                @{Name='scenario-case';Change={ $request.Scenario='GROUNDED-TWO-LANE' }},
                @{Name='scenario-array';Change={ $request.Scenario=@('grounded-two-lane') }},
                @{Name='repeat1';Change={ $request.Repeat=1 }}, @{Name='repeat-string';Change={ $request.Repeat='2' }},
                @{Name='wrong-block';Change={ $request.WrongExpectedBlock=$true }}, @{Name='wrong-string';Change={ $request.WrongExpectedBlock='false' }},
                @{Name='wrong-missing';Change={ $request.PSObject.Properties.Remove('WrongExpectedBlock') }},
                @{Name='eula-missing';Change={ $request.PSObject.Properties.Remove('EulaProvenance') }},
                @{Name='arguments-missing';Change={ $request.Arguments=$request.Arguments[0..5] }},
                @{Name='arguments-extra';Change={ $request.Arguments+='-Pmapart.test.repeat=2' }},
                @{Name='arguments-duplicate';Change={ $request.Arguments+=$request.Arguments[-1] }},
                @{Name='arguments-reordered';Change={ $request.Arguments[0]='v2'; $request.Arguments[1]='-p' }},
                @{Name='arguments-case';Change={ $request.Arguments[-1]='-Pmapart.test.scenario=GROUNDED-TWO-LANE' }},
                @{Name='support-case';Support='CONTROLLED'}, @{Name='support-unknown';Support='other'})
            foreach ($case in $cases) {
                $request=New-V2Request -Scenario grounded-two-lane -Repeat 2 -EulaAccepted
                if ($case.Change) { . $case.Change }
                $arguments=@{RequireGame=($case.Game -ne $false);Request=$request;InternalSupportCase='controlled'
                    EvidenceRoot=(Join-Path $testRoot ('support-invalid-'+$case.Name));WorkingDirectory=$repo;Worker={ exit 0 }}
                if ($case.Support) { $arguments.InternalSupportCase=$case.Support }
                $rejected=$false
                try { Invoke-V2Supervisor @arguments | Out-Null } catch { $rejected=$true }
                $proof.Add(@{Name=$case.Name;Rejected=$rejected;JdkCalls=$calls.Jdk;WorkerCalls=$calls.Worker})
                Assert ($rejected -and !$calls.Jdk -and !$calls.Worker) "support request reached subprocess boundary: $($case.Name)"
            }
            Reject { . (Join-Path $PSScriptRoot 'test-v2.ps1') -InternalSupportCase controlled -EulaAccepted } 'public controlled flag accepted'
        } finally {
            Set-Item Function:Find-V2Jdk -Value $originalJdk
            Set-Item Function:New-V2ProcessInfo -Value $originalInfo
            $proof | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $testRoot 'support-admission-proof.json')
        }
    }
    Run 'shared support resolver transports controlled then clears ordinary in real dummy children' {
        $names=@('MAPART_PROBE_WRONG_EXPECTED_BLOCK','MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS','MAPART_STATE_FAULT_FORCE_TARGET_RELOAD',
            'MAPART_TWO_LANE_SUPPORT_CASE','MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK','MAPART_TEST_LAUNCH_ID','MAPART_TEST_LAUNCH_DIR','JAVA_TOOL_OPTIONS')
        Invoke-V2EnvironmentFixture $names {
            foreach ($name in $names[0..6]) { [Environment]::SetEnvironmentVariable($name,'controlled') }
            $env:JAVA_TOOL_OPTIONS='-Doperator.diagnostic=support-transport'
            $before=[Environment]::GetEnvironmentVariables().Clone(); $proof=[Collections.Generic.List[object]]::new()
            $previousLaunch=$null
            foreach ($support in 'controlled','ordinary') {
                $request=New-V2Request -Scenario grounded-two-lane -Repeat 2 -EulaAccepted
                $childEnv=Resolve-V2GameEnvironment $actualJdk $request $support
                $ordinary=New-V2Environment -Jdk $actualJdk
                Assert ($childEnv.Count -eq $ordinary.Count) 'resolver introduced another environment override'
                foreach ($key in $ordinary.Keys) {
                    if ($key -ne 'MAPART_TWO_LANE_SUPPORT_CASE') { Assert ($childEnv[$key] -ceq $ordinary[$key]) "resolver changed ordinary environment: $key" }
                }
                $change=@'
$receipt.scenario='grounded-two-lane'; $receipt.provider='grounded-two-lane'
Write-Output ('TRANSPORT='+(@{support=$env:MAPART_TWO_LANE_SUPPORT_CASE;launchId=$env:MAPART_TEST_LAUNCH_ID;launchDir=$env:MAPART_TEST_LAUNCH_DIR;probe=$env:MAPART_PROBE_WRONG_EXPECTED_BLOCK;observation=$env:MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS;state=$env:MAPART_STATE_FAULT_FORCE_TARGET_RELOAD;wrong=$env:MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK;options=$env:JAVA_TOOL_OPTIONS;home=$env:JAVA_HOME} | ConvertTo-Json -Compress))
'@
                $outcome=Invoke-V2Supervisor -Worker (New-V2ReceiptWorker $change 1000) -Environment $childEnv -Request $request -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
                Assert-V2Settled $outcome
                Assert ($outcome.ExitCode -eq 0 -and $outcome.WorkerExitCode -eq 0 -and $outcome.ReceiptStatus -ceq 'Accepted') 'support dummy transport failed'
                $line=Get-Content (Join-Path $outcome.Evidence 'launch.stdout.log') | Where-Object { $_.StartsWith('TRANSPORT=') }
                Assert (@($line).Count -eq 1) 'actual dummy transport output missing'
                $data=$line.Substring(10) | ConvertFrom-Json
                Assert ($data.support -ceq $(if($support -ceq 'controlled'){'controlled'}else{$null})) 'actual child selector incorrect'
                Assert (!$data.probe -and !$data.observation -and !$data.state -and !$data.wrong) 'other fault selector leaked'
                Assert ($data.home -ceq $actualJdk.Home -and $data.options -match 'operator.diagnostic=support-transport') 'JDK/operator options lost'
                Assert ($data.launchId -ceq $outcome.LaunchId -and $data.launchDir -ceq $outcome.Evidence -and $data.launchId -cne $previousLaunch) 'fresh child challenge incorrect'
                $saved=Get-Content (Join-Path $outcome.Evidence 'request.json') -Raw | ConvertFrom-Json
                Assert ($saved.Request.ChildEnvironment.MAPART_TWO_LANE_SUPPORT_CASE -ceq $data.support -and $saved.Request.ChildEnvironment.MAPART_TEST_LAUNCH_ID -ceq $data.launchId -and $saved.Request.ChildEnvironment.MAPART_TEST_LAUNCH_DIR -ceq $data.launchDir) 'recorded environment differs from actual child'
                Assert ($saved.Request.SupportCase -ceq 'ordinary' -and $saved.Request.SupportCaseSource -ceq 'ordinary-default') 'dummy falsely claimed private controlled admission'
                Assert ($outcome.StartIdentity.Pid -eq $outcome.WorkerPid -and !$outcome.NativeGameExitCodes.Count -and (Get-Item (Join-Path $testRoot 'supervisor.lock')).Length -eq 0) 'dummy ownership/lock evidence incorrect'
                $previousLaunch=$data.launchId; $proof.Add(@{ResolverCase=$support;Evidence=$outcome.Evidence;ActualChild=$data;Request=$saved.Request})
            }
            $after=[Environment]::GetEnvironmentVariables()
            Assert ($before.Count -eq $after.Count) 'support transport changed parent membership'
            foreach ($key in $before.Keys) { Assert ($after.Contains($key) -and $before[$key] -ceq $after[$key]) "support transport changed parent: $key" }
            @{Calls=@($proof);ParentCompared=$true;ParentCount=$before.Count} | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $testRoot 'support-child-transport-proof.json')
        }
    }
    Run 'RequireGame support replacement preserves override and appends fresh identity before constructor sentinel' {
        $names=@('MAPART_TWO_LANE_SUPPORT_CASE','MAPART_PROBE_WRONG_EXPECTED_BLOCK','MAPART_TEST_LAUNCH_ID','MAPART_TEST_LAUNCH_DIR')
        Invoke-V2EnvironmentFixture $names {
            foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,'controlled') }
            $before=[Environment]::GetEnvironmentVariables().Clone(); $proof=[Collections.Generic.List[object]]::new()
            $request=New-V2Request -Scenario grounded-two-lane -Repeat 2 -EulaAccepted
            $previousLaunch=$null
            foreach ($support in 'controlled','default','ordinary') {
                $root=Join-Path $testRoot ('support-constructor-'+$support)
                $sentinel='Task15 pre-spawn support sentinel '+[guid]::NewGuid(); $capture=@{Calls=0}; $caught=$null
                $originalInfo=${function:New-V2ProcessInfo}
                try {
                    Set-Item Function:New-V2ProcessInfo -Value {
                        param($Executable,$WorkingDirectory,$Command,$Environment)
                        $capture.Calls++; $capture.Environment=@{}+$Environment; $capture.Evidence=$evidence
                        $capture.Request=Get-Content (Join-Path $evidence 'request.json') -Raw | ConvertFrom-Json
                        throw $sentinel
                    }
                    $arguments=@{RequireGame=$true;Request=$request;EvidenceRoot=$root;WorkingDirectory=$repo
                        Environment=@{MAPART_TWO_LANE_SUPPORT_CASE='controlled';MAPART_PROBE_WRONG_EXPECTED_BLOCK='1';MAPART_TEST_LAUNCH_ID='caller';MAPART_TEST_LAUNCH_DIR='caller';CALLER_SUPPORT_ONLY='discard'}}
                    if ($support -cne 'default') { $arguments.InternalSupportCase=$support }
                    try { Invoke-V2Supervisor @arguments | Out-Null } catch { $caught=$_.Exception.Message }
                } finally { Set-Item Function:New-V2ProcessInfo -Value $originalInfo }
                Assert ($caught -ceq $sentinel -and $capture.Calls -eq 1) 'support constructor sentinel was not reached exactly once'
                $saved=$capture.Request; $expected=$(if($support -ceq 'controlled'){'controlled'}else{'ordinary'})
                Assert ($saved.Request.SupportCase -ceq $expected -and $saved.Request.SupportCaseSource -ceq $(if($expected -ceq 'controlled'){'internal-reviewed-controlled-support'}else{'ordinary-default'})) 'support request provenance stale/incorrect'
                Assert ($capture.Environment.MAPART_TWO_LANE_SUPPORT_CASE -ceq $(if($expected -ceq 'controlled'){'controlled'}else{$null})) 'RequireGame replacement erased override or ordinary inherited controlled'
                Assert (!$capture.Environment.MAPART_PROBE_WRONG_EXPECTED_BLOCK -and !$capture.Environment.ContainsKey('CALLER_SUPPORT_ONLY')) 'caller environment bypassed replacement'
                Assert ($saved.Request.Jdk.JavaVersion -match '(?m)^(?:openjdk|java) version "21\.' -and $capture.Environment.JAVA_HOME -ceq $saved.Request.Jdk.Home) 'real JDK discovery not retained'
                Assert ($saved.LaunchId -cne 'caller' -and $saved.LaunchId -cne 'controlled' -and $saved.LaunchId -cne $previousLaunch -and $capture.Environment.MAPART_TEST_LAUNCH_ID -ceq $saved.LaunchId -and $capture.Environment.MAPART_TEST_LAUNCH_DIR -ceq $capture.Evidence) 'fresh supervisor launch pair incorrect'
                foreach ($key in $capture.Environment.Keys) { Assert ($saved.Request.ChildEnvironment.$key -ceq $capture.Environment[$key]) "final request environment differs: $key" }
                Assert (!$saved.WorkerPid -and $saved.CleanupStatus -ceq 'unresolved' -and $saved.ArchiveStatus -ceq 'pending') 'pre-spawn negative falsely claimed settlement'
                Assert ((Get-Content (Join-Path $root 'supervisor.lock') -Raw) -ceq $capture.Evidence) 'isolated unresolved marker not retained'
                foreach ($name in 'worker-assigned','outcome.json','launch.stdout.log','test-start.json') { Assert (!(Test-Path (Join-Path $capture.Evidence $name))) "constructor negative unexpectedly advanced: $name" }
                $previousLaunch=$saved.LaunchId; $proof.Add(@{Case=$support;Caught=$caught;Capture=$capture})
            }
            $after=[Environment]::GetEnvironmentVariables()
            Assert ($before.Count -eq $after.Count) 'RequireGame construction changed parent membership'
            foreach ($key in $before.Keys) { Assert ($after.Contains($key) -and $before[$key] -ceq $after[$key]) "RequireGame construction changed parent: $key" }
            @{Calls=@($proof);ParentCompared=$true;ParentCount=$before.Count;NoWorkerStarted=$true;ExpectedUnresolvedMarkers=3} | ConvertTo-Json -Depth 16 | Set-Content (Join-Path $testRoot 'support-constructor-proof.json')
        }
    }
    Run 'missing receipt, worker0 and JVM-looking logs never prove selected execution' {
        $request=New-V2Request -EulaAccepted
        $missing=Invoke-V2Supervisor -Worker { Start-Sleep -Seconds 30 } -Request $request -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 5000 -StartupMilliseconds 40
        Assert-V2Settled $missing
        Assert ($missing.ExitCode -eq 124 -and $missing.TimeoutPhase -ceq 'STARTUP' -and $null -eq $missing.StartupAcceptedElapsedMilliseconds) 'missing receipt did not expire STARTUP'
        $early=Invoke-V2Supervisor -Worker { exit 0 } -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 5000 -StartupMilliseconds 3000
        Assert-V2Settled $early
        Assert ($early.ExitCode -eq 125 -and $early.WorkerExitCode -eq 0 -and $early.ReceiptRejectionReason) 'worker0 without receipt succeeded'
        $jvm=Invoke-V2Supervisor -Worker { Write-Output 'java net.fabricmc.loader.impl.launch.knot.KnotClient; execution=STARTED'; Start-Sleep -Seconds 30 } -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 5000 -StartupMilliseconds 1500
        Assert-V2Settled $jvm
        Assert ($jvm.ExitCode -eq 124 -and $jvm.TimeoutPhase -ceq 'STARTUP') 'JVM/log substring was credited'
        Reject { Invoke-V2Supervisor -RequireGame -StartupMilliseconds 40 -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo } 'real startup override accepted'
    }
    Run 'wrong challenge/provider/PID/start reject through owned supervisor' {
        foreach ($change in @('$receipt.launchId=[guid]::NewGuid().ToString()','$receipt.provider="unknown"','$receipt.pid=2147483647','$receipt.startedUtc="2000-01-01T00:00:00Z"')) {
            $outcome=Invoke-V2Supervisor -Worker (New-V2ReceiptWorker $change 10000) -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
            Assert-V2Settled $outcome
            Assert ($outcome.ExitCode -eq 125 -and $outcome.ReceiptRejectionReason -and $null -eq $outcome.StartupAcceptedElapsedMilliseconds) "bad receipt accepted: $change"
        }
    }
    Run 'one receipt helper validates exact shape, held identity, job and pending inventory' {
        $originalUpdate=${function:Update-V2Processes}; $proof=@{Checked=$false;Cases=[Collections.Generic.List[object]]::new()}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if (!$Fast -and !$proof.Checked) {
                    $proof.Checked=$true
                    $worker=$Handles | Where-Object { $_.Record.Pid -eq $process.Id } | Select-Object -First 1
                    $data=@{schema='mapartrunner-test-start-v1';launchId=$launchId;pid=$worker.Record.Pid;startedUtc=([DateTimeOffset]::Parse($worker.Record.StartedUtc).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffffffZ'));scenario='all';repeat=2;provider='schematic-import';event='SELECTED_PROVIDER_RUNTEST_ENTERED'}
                    $file=Join-Path $evidence 'test-start.json'; $valid=$data | ConvertTo-Json -Compress
                    $arguments=@{Evidence=$evidence;LaunchId=$launchId;Request=$Request;Handles=@($Handles);OwnedPids=@($Job.Pids());WorkerPid=$process.Id}
                    foreach ($case in @(
                        @{Name='duplicate';Text=($valid -replace '\{"','{"schema":"mapartrunner-test-start-v1","')},
                        @{Name='extra';Text=($valid -replace '\{"','{"extra":true,"')},
                        @{Name='fractional-pid';Text=($valid -replace ('"pid":'+$worker.Record.Pid),'"pid":1.5')},
                        @{Name='string-repeat';Text=($valid -replace '"repeat":2','"repeat":"2"')},
                        @{Name='oversize';Text=(' '*4097)},
                        @{Name='wrong-scenario';Text=($valid -replace '"scenario":"all"','"scenario":"grounded-two-lane"')},
                        @{Name='wrong-event';Text=($valid -replace 'SELECTED_PROVIDER_RUNTEST_ENTERED','STARTED')}
                    )) {
                        [IO.File]::WriteAllText($file,$case.Text,[Text.UTF8Encoding]::new($false))
                        $checked=Read-V2StartReceipt @arguments
                        $proof.Cases.Add(@{Name=$case.Name;Status=$checked.Status;Reason=$checked.Reason})
                        Assert ($checked.Status -ceq 'Rejected') "malformed helper receipt accepted: $($case.Name)"
                    }
                    [IO.File]::WriteAllBytes($file,[byte[]]@(0xff,0xfe,0xff))
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'invalid UTF8 accepted'
                    [IO.File]::WriteAllText($file,$valid,[Text.UTF8Encoding]::new($false))
                    Assert ((Read-V2StartReceipt @arguments -RequireGame -JavaExecutable (Join-Path $actualJdk.Home 'bin/java.exe')).Status -ceq 'Rejected') 'real RequireGame accepted dummy worker'
                    $arguments.OwnedPids=@()
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'foreign job accepted'
                    $arguments.OwnedPids=@($Job.Pids()); $arguments.Handles=@()
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Pending') 'inventory lag was accepted/rejected'
                    $arguments.Handles=@($Handles)
                    foreach ($scenario in 'all','grounded-two-lane','grounded-traversal') { foreach ($repeat in 1,2) {
                        $arguments.Request=New-V2Request -Scenario $scenario -Repeat $repeat -EulaAccepted
                        foreach ($provider in 'environment-probe','single-lane','schematic-import','grounded-two-lane','grounded-traversal') {
                            $data.scenario=$scenario; $data.repeat=$repeat; $data.provider=$provider
                            [IO.File]::WriteAllText($file,($data | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                            $checked=Read-V2StartReceipt @arguments
                            $expected=$(if ($scenario -ceq 'all' -or $scenario -ceq $provider) {'Accepted'} else {'Rejected'})
                            $proof.Cases.Add(@{Name="provider/$scenario/$repeat/$provider";Status=$checked.Status;Reason=$checked.Reason})
                            Assert ($checked.Status -ceq $expected) "first selected receipt admission differs: $scenario/$repeat/$provider"
                        }
                    } }
                    $arguments.Request=$Request
                    [IO.File]::WriteAllText($file,$valid,[Text.UTF8Encoding]::new($false))
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Accepted') 'first selected all provider rejected'
                }
            }
            $outcome=Invoke-V2Supervisor -Worker { Start-Sleep -Milliseconds 500; exit 0 } -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        $proof | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $testRoot 'start-receipt-helper-proof.json')
        Assert-V2Settled $outcome
        Assert ($proof.Checked -and $outcome.ExitCode -eq 0 -and $outcome.StartReceipt.provider -ceq 'schematic-import') 'helper inventory/acceptance path failed'
    }
    Run 'acceptance ends startup without renewing frozen overall; repeated receipt cannot renew' {
        # Fresh PowerShell startup has a documented3s allowance; the work deliberately crosses it.
        $originalUpdate=${function:Update-V2Processes}; $laterProvider=@{Written=$false}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if (!$Fast -and $result.StartReceipt -and !$laterProvider.Written) {
                    $later=$result.StartReceipt.PSObject.Copy(); $later.provider='grounded-traversal'
                    [IO.File]::WriteAllText((Join-Path $evidence 'test-start.json'),($later | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    $laterProvider.Written=$true
                }
            }
            $success=Invoke-V2Supervisor -Worker (New-V2ReceiptWorker '' 3500) -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 7000 -StartupMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        Assert-V2Settled $success
        Assert ($success.ExitCode -eq 0 -and $success.StartupAcceptedElapsedMilliseconds -lt 3000 -and !$success.TimedOut) 'valid work beyond startup failed'
        Assert ($laterProvider.Written -and $success.StartReceipt.provider -ceq 'environment-probe') 'later all provider replaced first startup identity'
        $repeated=@{Writes=0;AcceptedSamples=0;AcceptedElapsed=$null}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if (!$Fast) {
                    $held=$Handles | Where-Object { $_.Record.Pid -eq $process.Id } | Select-Object -First 1
                    Assert ($held -and !$held.Process.HasExited -and $Job.Pids() -contains $held.Record.Pid) 'phase-controlled receipt lost its live held dummy identity'
                    if ($null -ne $result.StartupAcceptedElapsedMilliseconds) {
                        if ($null -eq $repeated.AcceptedElapsed) { $repeated.AcceptedElapsed=$result.StartupAcceptedElapsedMilliseconds }
                        Assert ($repeated.AcceptedElapsed -eq $result.StartupAcceptedElapsedMilliseconds -and $result.StartReceipt.provider -ceq 'environment-probe') 'repeated provider renewed/replaced acceptance'
                        $repeated.AcceptedSamples++
                    }
                    # Dummy phase control uses the actual held worker, independent of cold PowerShell bootstrap.
                    $data=@{schema='mapartrunner-test-start-v1';launchId=$launchId;pid=$held.Record.Pid
                        startedUtc=([DateTimeOffset]::Parse($held.Record.StartedUtc).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffffffZ'))
                        scenario='all';repeat=2;provider=$(if ($repeated.AcceptedSamples -and $repeated.Writes % 2) {'grounded-traversal'} else {'environment-probe'});event='SELECTED_PROVIDER_RUNTEST_ENTERED'}
                    [IO.File]::WriteAllText((Join-Path $evidence 'test-start.json'),($data | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    $repeated.Writes++
                }
            }
            $overall=Invoke-V2Supervisor -Worker { Start-Sleep -Seconds 30 } -Request (New-V2Request -EulaAccepted) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 3500 -StartupMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        Assert-V2Settled $overall
        Assert ($overall.ExitCode -eq 124 -and $overall.TimeoutPhase -ceq 'OVERALL' -and $overall.StartupAcceptedElapsedMilliseconds -lt 3000 -and $overall.DeadlineMilliseconds -eq 3500) 'repeated receipt renewed/escaped overall deadline'
        Assert ($repeated.Writes -ge 2 -and $repeated.AcceptedSamples -ge 1 -and $overall.StartReceipt.provider -ceq 'environment-probe') 'repeated receipt phase/first acceptance was not exercised'
        @{Evidence=$overall.Evidence;PhaseControl=$repeated;Startup=$overall.StartupDeadlineMilliseconds;Overall=$overall.DeadlineMilliseconds;Result=$overall.TimeoutPhase} |
            ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'repeated-receipt-phase-proof.json')
        $request=Get-Content -LiteralPath (Join-Path $success.Evidence 'request.json') -Raw | ConvertFrom-Json
        Assert ($request.Request.ChildEnvironment.MAPART_TEST_LAUNCH_ID -ceq $success.LaunchId -and $request.Request.ChildEnvironment.MAPART_TEST_LAUNCH_DIR -ceq $success.Evidence) 'final child challenge absent from request'
        Assert ($overall.LaunchId -cne $success.LaunchId) 'launch challenge reused'
    }
    Run 'observed native failure precedence has deterministic simultaneous ordering' {
        $failures=@(
            [pscustomobject]@{Pid=400;IsGame=$true;ExitCode=9;ExitedUtc='2026-01-01T00:00:02Z';ObservedExitUtc='2026-01-01T00:00:03Z';StartedUtc='2026-01-01T00:00:00Z'},
            [pscustomobject]@{Pid=401;IsGame=$true;ExitCode=7;ExitedUtc='2026-01-01T00:00:01Z';ObservedExitUtc='2026-01-01T00:00:03Z';StartedUtc='2026-01-01T00:00:00Z'})
        $outcome=[pscustomobject]@{ExitCode=124;WorkerPid=100;PrimaryFailure=$null;ObservedFailures=[Collections.Generic.List[object]]::new();TerminationRequested=$false}
        Set-V2ObservedFailure $outcome $failures
        Assert ($outcome.ExitCode -eq 7 -and $outcome.PrimaryFailure.Pid -eq 401 -and $outcome.ObservedFailures.Count -eq 2) 'native nonzero order/retention wrong'
        $outcome.TerminationRequested=$true
        Set-V2ObservedFailure $outcome @([pscustomobject]@{Pid=100;IsGame=$false;ExitCode=125})
        Assert ($outcome.ExitCode -eq 7 -and $outcome.ObservedFailures.Count -eq 2) 'cleanup termination replaced observed primary'
    }
    $regressions | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $testRoot 'review-regressions.json')
    if (@($regressions | Where-Object { !$_.Passed }).Count) { throw "Review regression(s) failed; evidence=$testRoot" }
    Write-Output "ALL SELF-CHECKS PASSED; dummy processes only; evidence=$testRoot"
} finally { Stop-Transcript | Out-Null }
