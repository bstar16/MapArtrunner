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
    Run 'Task8g automatic archive closes exact original roots once and rejects payload drift' {
        $root=Join-Path $testRoot 'reference-fixture'; $game=Join-Path $root game
        New-Item -ItemType Directory -Path (Join-Path $game logs),(Join-Path $root 'two-lane-stale') -Force | Out-Null
        Set-Content (Join-Path $game 'logs/latest.log') 'reference diagnostics'
        $before=@(Get-V2ScenarioInventory $root)
        $payload=Join-Path $root ('two-lane-'+[guid]::NewGuid())
        New-Item -ItemType Directory -Path (Join-Path $payload empty) -Force | Out-Null
        Set-Content (Join-Path $payload bytes.txt) 'canonical bytes'
        $launchId=[guid]::NewGuid().ToString('D'); $evidence=Join-Path $root ('launch-'+$launchId)
        New-Item -ItemType Directory -Path $evidence | Out-Null
        $context=[pscustomobject]@{LaunchId=$launchId;Evidence=$evidence;CleanupStatus='settled';Request=[pscustomobject]@{Mode='automatic'}}
        Save-V2Artifacts -Evidence $evidence -Game $game -ScenarioRoot $root -Before $before -Owner $repo -Context $context
        Assert (!(Test-Path (Join-Path $evidence scenarios))) 'automatic archive duplicated scenario payload'
        $manifest=Read-V2ScenarioReferences -Evidence $evidence -ScenarioRoot $root -Owner $repo -LaunchId $launchId
        Assert ($manifest.Roots.Count -eq 1 -and $manifest.Roots[0].Name -ceq (Split-Path $payload -Leaf)) 'stale root credited or fresh root lost'
        Assert (@($manifest.Roots[0].Entries | Where-Object { $_.Path -ceq 'empty' -and $_.Kind -ceq 'directory' }).Count -eq 1) 'empty directory identity omitted'
        $read={ Read-V2ScenarioReferences -Evidence $evidence -ScenarioRoot $root -Owner $repo -LaunchId $launchId -ClosureId $manifest.ClosureId }
        foreach ($case in 'changed','missing','added','type','directory','root') {
            $file=Join-Path $payload bytes.txt; $original=[IO.File]::ReadAllBytes($file)
            try {
                switch ($case) {
                    changed { Set-Content $file 'different bytes' }
                    missing { Remove-Item -LiteralPath $file }
                    added { Set-Content (Join-Path $payload added.txt) 'extra' }
                    type { Remove-Item -LiteralPath $file; New-Item -ItemType Directory -Path $file | Out-Null }
                    directory { Remove-Item -LiteralPath (Join-Path $payload empty) }
                    root { Remove-Item -LiteralPath $file; Remove-Item -LiteralPath (Join-Path $payload empty); Remove-Item -LiteralPath $payload }
                }
                Reject $read "reference reader accepted $case payload"
            } finally {
                if ($case -ceq 'type') { Remove-Item -LiteralPath $file }
                if ($case -ceq 'added') { Remove-Item -LiteralPath (Join-Path $payload added.txt) }
                if ($case -cin 'directory','root') { New-Item -ItemType Directory -Path (Join-Path $payload empty) -Force | Out-Null }
                [IO.File]::WriteAllBytes($file,$original)
            }
        }
        $path=Join-Path $evidence 'scenario-references.json'; $original=Get-Content $path -Raw
        foreach ($case in 'schema','identity','closure','escape','duplicate','partial','root') {
            try {
                $bad=$original | ConvertFrom-Json
                switch ($case) {
                    schema { $bad.Schema='unknown' }
                    identity { $bad.LaunchId=[guid]::NewGuid().ToString('D') }
                    closure { $bad.ClosureId=[guid]::NewGuid().ToString('D') }
                    escape { $bad.Roots[0].Entries[0].Path='../outside' }
                    duplicate { $bad.Roots=@($bad.Roots)+@($bad.Roots) }
                    partial { $bad.State='partial' }
                    root { $bad.Roots[0].Name='../outside' }
                }
                $bad | ConvertTo-Json -Depth 30 | Set-Content $path
                Reject $read "reference reader accepted $case manifest"
            } finally { Set-Content $path $original }
        }
        & $read | Out-Null
        $failedEvidence=Join-Path $root ('launch-'+[guid]::NewGuid()); New-Item -ItemType Directory -Path $failedEvidence | Out-Null
        $failedContext=[pscustomobject]@{LaunchId=(Split-Path $failedEvidence -Leaf).Substring(7);Evidence=$failedEvidence;CleanupStatus='settled';Request=[pscustomobject]@{}}
        $originalEntries=${function:Get-V2ReferenceEntries}; $mutation=@{Done=$false}; $originalBytes=[IO.File]::ReadAllBytes((Join-Path $payload bytes.txt))
        try {
            function Get-V2ReferenceEntries {
                param($Path,$Owner,$Relative='.')
                & $originalEntries $Path $Owner $Relative
                if ($Path -eq $payload -and $Relative -ceq '.' -and !$mutation.Done) {
                    $mutation.Done=$true; Set-Content (Join-Path $payload bytes.txt) 'changed during closure'
                }
            }
            Reject { Save-V2Artifacts -Evidence $failedEvidence -Game $game -ScenarioRoot $root -Before $before -Owner $repo -Context $failedContext } 'writer accepted payload drift before closure'
            Assert ($mutation.Done -and (Test-Path (Join-Path $failedEvidence scenario-references.partial.json)) -and !(Test-Path (Join-Path $failedEvidence scenario-references.json))) 'failed readback published a closed manifest'
            Reject { Read-V2ScenarioReferences -Evidence $failedEvidence -ScenarioRoot $root -Owner $repo -LaunchId $failedContext.LaunchId } 'partial manifest consumed as retained archive'
        } finally {
            Set-Item Function:Get-V2ReferenceEntries $originalEntries
            [IO.File]::WriteAllBytes((Join-Path $payload bytes.txt),$originalBytes)
        }
        $empty=Join-Path $root ('launch-'+[guid]::NewGuid()); New-Item -ItemType Directory -Path $empty | Out-Null
        $emptyContext=[pscustomobject]@{LaunchId=(Split-Path $empty -Leaf).Substring(7);Evidence=$empty;CleanupStatus='settled';Request=[pscustomobject]@{}}
        Save-V2Artifacts -Evidence $empty -Game $game -ScenarioRoot $root -Before @(Get-V2ScenarioInventory $root) -Owner $repo -Context $emptyContext
        Assert ((Read-V2ScenarioReferences -Evidence $empty -ScenarioRoot $root -Owner $repo -LaunchId $emptyContext.LaunchId).Roots.Count -eq 0) 'empty startup invented scenario evidence'
    }
    Run 'Task8g automatic cancellation and failures retain diagnostics first cause and ownership' {
        foreach ($case in 'cancelled','cancelled-invalid','worker0-invalid','worker7-invalid','timeout-invalid','unresolved') {
            $root=Join-Path $testRoot ('reference-'+$case); $game=Join-Path $root game
            $payload=Join-Path $root ('two-lane-'+[guid]::NewGuid())
            New-Item -ItemType Directory -Path (Join-Path $game logs),(Join-Path $game crash-reports) -Force | Out-Null
            Set-Content (Join-Path $game 'logs/latest.log') 'automatic diagnostics'
            Set-Content (Join-Path $game 'crash-reports/failure.txt') 'automatic failure'
            $worker="New-Item -ItemType Directory -Path '"+$payload.Replace("'","''")+"' | Out-Null; Set-Content -LiteralPath '"+(Join-Path $payload bytes.txt).Replace("'","''")+"' payload; Start-Sleep -Milliseconds "+$(if($case -cin 'worker7-invalid','unresolved'){'300; exit 7'}elseif($case -ceq 'worker0-invalid'){'300; exit 0'}else{'30000'})
            $observation=[hashtable]::Synchronized(@{}); $pipeline=[Management.Automation.PowerShell]::Create()
            $invocation=$null; $stopping=$null
            try {
                [void]$pipeline.AddScript({
                    param($Source,$Owner,$Root,$Game,$Payload,$Worker,$Case,$Observation)
                    $ErrorActionPreference='Stop'; . $Source
                    $original=${function:Update-V2Processes}
                    function Update-V2Processes {
                        param($Job,$Records,$Handles,[switch]$Fast)
                        & $original @PSBoundParameters
                        if (Test-Path -LiteralPath (Join-Path $Payload bytes.txt)) {
                            if (!$Observation.Ready) { $Observation.Snapshot=$result | ConvertTo-Json -Depth 12 -Compress; $Observation.Ready=$true }
                            if ($Fast -and $Case -ceq 'unresolved') { throw 'Task8g deliberately unresolved after owned worker exit' }
                        }
                    }
                    $archive={
                        param($Evidence,$Before,$Context)
                        Save-V2Artifacts -Evidence $Evidence -Game $Game -ScenarioRoot $Root -Before $Before -Owner $Owner -Context $Context
                        if ($Case -like '*-invalid') {
                            Set-Content -LiteralPath (Join-Path $Payload bytes.txt) 'changed before retention'
                            [void](Read-V2ScenarioReferences -Evidence $Evidence -ScenarioRoot $Root -Owner $Owner -LaunchId $Context.LaunchId)
                        }
                    }
                    Invoke-V2Supervisor -Worker ([scriptblock]::Create($Worker)) -EvidenceRoot $Root -WorkingDirectory $Owner -TimeoutMilliseconds $(if($Case -ceq 'timeout-invalid'){1800}else{10000}) -Archive $archive
                }.ToString()).AddArgument((Join-Path $repo 'scripts/test-v2.ps1')).AddArgument($repo).AddArgument($root).AddArgument($game).AddArgument($payload).AddArgument($worker).AddArgument($case).AddArgument($observation)
                $invocation=$pipeline.BeginInvoke(); $wait=[Diagnostics.Stopwatch]::StartNew()
                while (!$observation.Ready -and !$invocation.IsCompleted -and $wait.ElapsedMilliseconds -lt 10000) { Start-Sleep -Milliseconds 20 }
                Assert $observation.Ready "automatic $case did not reach owned payload observation"
                $observed=$observation.Snapshot | ConvertFrom-Json
                if ($case -like 'cancelled*') {
                    $stopping=$pipeline.BeginStop($null,$null)
                    Assert ($stopping.AsyncWaitHandle.WaitOne(15000)) 'HOLD: automatic cancelled pipeline did not settle'
                    $pipeline.EndStop($stopping)
                }
                try { $returned=@($pipeline.EndInvoke($invocation)) }
                catch [Management.Automation.MethodInvocationException] {
                    if ($case -notlike 'cancelled*' -or $_.Exception.InnerException -isnot [Management.Automation.PipelineStoppedException]) { throw }
                }
                Assert (@($pipeline.Streams.Error | Where-Object { $_.Exception -isnot [Management.Automation.PipelineStoppedException] }).Count -eq 0) "automatic $case pipeline errors: $($pipeline.Streams.Error -join '; ')"
                if ($case -like 'cancelled*') { Assert ($pipeline.InvocationStateInfo.State -eq 'Stopped') 'automatic pipeline was not actually stopped' }
                else { Assert ($returned.Count -eq 1) 'automatic archive polluted scalar result pipeline' }
                $outcome=Get-Content (Join-Path $observed.Evidence outcome.json) -Raw | ConvertFrom-Json
                $lockBytes=(Get-Item (Join-Path $root supervisor.lock)).Length
                Assert ((Get-Content (Join-Path $outcome.Evidence 'logs/latest.log') -Raw).Trim() -ceq 'automatic diagnostics') 'automatic failure lost logs'
                Assert ((Test-Path (Join-Path $outcome.Evidence 'crash-reports/failure.txt')) -and (Test-Path (Join-Path $outcome.Evidence exit-code.txt))) 'automatic failure lost crash/exit evidence'
                Assert ($outcome.LaunchId -ceq $observed.LaunchId -and @($outcome.Processes).Count -gt 0) 'automatic cancellation lost launch/held process identity'
                if ($case -ceq 'unresolved') {
                    Assert ($outcome.CleanupStatus -ceq 'unresolved' -and $outcome.CleanupError -like '*Task8g deliberately unresolved*' -and !(Test-Path (Join-Path $outcome.Evidence scenario-references.json))) 'unresolved cleanup closed immutable references'
                } else {
                    Assert ($outcome.CleanupStatus -ceq 'settled' -and $null -ne $outcome.WorkerExitCode -and @($outcome.Processes | Where-Object { $null -eq $_.ExitCode }).Count -eq 0) 'automatic failure lost settled held exits'
                }
                if ($case -ceq 'cancelled') {
                    Assert ($outcome.ArchiveStatus -ceq 'retained' -and $lockBytes -eq 0 -and $outcome.ExitCode -ne 0 -and $outcome.TerminationRequested -and !$outcome.TimedOut) 'automatic cancellation lost truthful retained outcome'
                    $manifest=Read-V2ScenarioReferences -Evidence $outcome.Evidence -ScenarioRoot $root -Owner $repo -LaunchId $outcome.LaunchId
                    Assert ($manifest.Roots.Count -eq 1 -and !(Test-Path (Join-Path $outcome.Evidence scenarios))) 'automatic cancellation duplicated/lost payload'
                } else {
                    Assert ($outcome.ArchiveStatus -ceq 'failed' -and $outcome.ArchiveError -and $lockBytes -gt 0) 'automatic retention failure cleared ownership'
                    Reject { Invoke-V2Supervisor -Worker { exit 0 } -EvidenceRoot $root -WorkingDirectory $repo } 'reference failure allowed rerun'
                }
                if ($case -cin 'worker7-invalid','unresolved') { Assert ($outcome.ExitCode -eq 7 -and $outcome.WorkerExitCode -eq 7 -and $outcome.PrimaryFailure.ExitCode -eq 7) 'reference/cleanup failure replaced primary worker7' }
                if ($case -ceq 'worker0-invalid') { Assert ($outcome.ExitCode -eq 125 -and $outcome.WorkerExitCode -eq 0 -and !$outcome.PrimaryFailure) 'reference failure did not convert otherwise-successful0 to125' }
                if ($case -ceq 'timeout-invalid') { Assert ($outcome.ExitCode -eq 124 -and $outcome.TimedOut) 'reference failure replaced timeout124' }
                if ($case -ceq 'cancelled-invalid') { Assert ($outcome.ExitCode -ne 0 -and $outcome.TerminationRequested -and !$outcome.TimedOut) 'reference failure hid automatic cancellation' }
            } finally {
                if ($invocation -and !$invocation.IsCompleted) { $pipeline.Stop() }
                if ($stopping) { $stopping.AsyncWaitHandle.Dispose() }
                if ($invocation) { $invocation.AsyncWaitHandle.Dispose() }
                $pipeline.Dispose()
            }
        }
    }
    Run 'Task7 inspect selection is explicit and leaves automatic requests intact' {
        $request=New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted
        Assert ($request.Mode -ceq 'inspect' -and $request.Scenario -ceq 'grounded-two-lane') 'inspect mode/selection missing'
        Assert (($request.Arguments -join ' ') -ceq '-p v2 :fabric:runInspect --no-daemon --console=plain') 'inspect manufactured automatic/EULA arguments'
        Assert ($request.EulaProvenance -ceq 'operator-switch') 'inspect EULA provenance missing'
        Assert ((New-V2Request -Inspect -Scenario grounded-two-lane -EulaEnvironment 1).EulaProvenance -ceq 'process MAPART_TEST_EULA_ACCEPTED=1') 'inspect process EULA provenance missing'
        foreach ($selection in @(@{},@{Scenario='all'},@{Scenario='grounded-traversal'},@{Scenario='GROUNDED-TWO-LANE'},
            @{Scenario='grounded-two-lane';Repeat='2'},@{Scenario='grounded-two-lane';Repeat='1'},@{Scenario='grounded-two-lane';WrongExpectedBlock=$true})) {
            Reject { New-V2Request -Inspect @selection -EulaAccepted } 'invalid inspect request accepted'
            Reject { . (Join-Path $PSScriptRoot 'test-v2.ps1') -Inspect @selection -EulaAccepted } 'invalid public inspect selection accepted'
        }
        Reject { New-V2Request -Inspect -Scenario grounded-two-lane -EulaEnvironment '' } 'inspect missing EULA accepted'
        Reject { . (Join-Path $PSScriptRoot 'test-v2.ps1') -Inspect -Scenario grounded-two-lane -InternalSupportCase controlled -EulaAccepted } 'public inspect support accepted'
        $automatic=New-V2Request -EulaAccepted
        Assert ($automatic.Scenario -ceq 'all' -and $automatic.Repeat -eq 2 -and $automatic.Arguments -contains ':fabric:runClientGameTest') 'automatic defaults changed'
    }
    Run 'Task7 inherited JVM mode conflicts are refused without changing the parent' {
        $names=@('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')
        Invoke-V2EnvironmentFixture $names {
            foreach ($name in $names) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
            $inspectRequest=New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted
            $automaticRequest=New-V2Request -EulaAccepted
            foreach ($name in $names) {
                foreach ($option in @('-Dfabric.client.gametest','-Dfabric.client.gametest=','-Dfabric.client.gametest=false','"-Dfabric.client.gametest=false"')) {
                    [Environment]::SetEnvironmentVariable($name,$option)
                    Reject { New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted } "inspect inherited $name enable accepted"
                    Assert ([Environment]::GetEnvironmentVariable($name) -ceq $option) 'mode check changed parent option'
                }
                foreach ($case in @(
                    @{Value='false';JvmEnabled=$false;WrapperEnabled=$false},
                    @{Value='';JvmEnabled=$false;WrapperEnabled=$false},
                    @{Value='TRUE';JvmEnabled=$true;WrapperEnabled=$true},
                    @{Value='TrUe';JvmEnabled=$true;WrapperEnabled=$true},
                    @{Value='"true"';JvmEnabled=$true;WrapperEnabled=$true},
                    @{Value="'true'";JvmEnabled=$true;WrapperEnabled=$false})) {
                    $option="-Dmapart.test.inspect=$($case.Value)"
                    [Environment]::SetEnvironmentVariable($name,$option)
                    $enabled=$(if($name -in 'JAVA_OPTS','GRADLE_OPTS'){$case.WrapperEnabled}else{$case.JvmEnabled})
                    if ($enabled) {
                        Reject { New-V2Request -EulaAccepted } 'automatic inherited inspect=true accepted'
                        New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted | Out-Null
                    } else {
                        Reject { New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted } 'inspect disabling inherited option accepted'
                        New-V2Request -EulaAccepted | Out-Null
                    }
                }
                foreach ($option in @('-Dmapart.test.inspect','-Dmapart.test.inspect=" true"',"-Dmapart.test.inspect='tr`"ue'",'-Dmapart.test.inspect="tr''ue"')) {
                    [Environment]::SetEnvironmentVariable($name,$option)
                    Reject { New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted } 'inspect inactive inherited option accepted'
                    New-V2Request -EulaAccepted | Out-Null
                }
                foreach ($case in @(@{Request=$inspectRequest;Options='-Dfabric.client.gametest=false'},@{Request=$automaticRequest;Options='-Dmapart.test.inspect=TRUE'})) {
                    [Environment]::SetEnvironmentVariable($name,$case.Options)
                    $refused=Join-Path $testRoot ('mode-refused-'+$name)
                    Reject { Invoke-V2Supervisor -RequireGame -Request $case.Request -EvidenceRoot $refused -WorkingDirectory $repo } 'real supervisor inherited conflict accepted'
                    Assert (!(Test-Path -LiteralPath $refused)) 'inherited conflict reached filesystem work'
                }
                foreach ($option in @('-Doperator.diagnostic=kept','-DMapart.test.inspect=true -DFabric.client.gametest=false')) {
                    [Environment]::SetEnvironmentVariable($name,$option)
                    New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted | Out-Null
                    New-V2Request -EulaAccepted | Out-Null
                }
                Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            }
            $env:JAVA_TOOL_OPTIONS='-Doperator.diagnostic=kept'
            $environment=Resolve-V2GameEnvironment ([pscustomobject]@{Home='C:/unused-jdk'}) (New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted)
            Assert ($environment.JAVA_TOOL_OPTIONS -ceq $env:JAVA_TOOL_OPTIONS -and $environment.JAVA_TOOL_OPTIONS -notmatch 'gametest|headless|mapart.test.scenario|mapart.test.repeat') 'inspect appended automatic JVM settings'
            Reject { Resolve-V2GameEnvironment ([pscustomobject]@{Home='C:/unused-jdk'}) (New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted) controlled } 'inspect controlled support resolved'
        }
    }
    Run 'Task7 wrapper adjacent mode quotes fail closed while direct JVM semantics remain unchanged' {
        # Independent pinned JDK/cmd probe: task-7-review-quotes.json. Wrappers retain literal quotes
        # in ins"pect, tr"ue and game"test; JVM-direct variables concatenate those same fragments.
        $cases=@(
            @{Option='-Dmapart.test.inspect="true"';AutomaticRejected=$true;InspectRejected=$false;AmbiguousWrapper=$false},
            @{Option='-Dmapart.test."ins""pect"=true';AutomaticRejected=$true;InspectRejected=$false;AmbiguousWrapper=$true},
            @{Option='-Dmapart.test.in"sp"ect=true';AutomaticRejected=$true;InspectRejected=$false;AmbiguousWrapper=$false},
            @{Option='-Dmapart.test.inspect="tr""ue"';AutomaticRejected=$true;InspectRejected=$false;AmbiguousWrapper=$true},
            @{Option='"-Dmapart.test.inspect=''true''"';AutomaticRejected=$false;InspectRejected=$true;AmbiguousWrapper=$false},
            @{Option='-Dfabric.client."game""test"=false';AutomaticRejected=$false;InspectRejected=$true;AmbiguousWrapper=$true},
            @{Option='-Dmapart.test.inspect=""';AutomaticRejected=$false;InspectRejected=$true;AmbiguousWrapper=$false},
            @{Option='-DMapart.test.inspect="tr""ue"';AutomaticRejected=$false;InspectRejected=$false;AmbiguousWrapper=$false},
            @{Option='-Doperator.diagnostic="tr""ue"';AutomaticRejected=$false;InspectRejected=$false;AmbiguousWrapper=$false})
        $names=@('JAVA_OPTS','GRADLE_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')
        Invoke-V2EnvironmentFixture $names {
            foreach ($name in $names) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
            foreach ($name in $names) { foreach ($case in $cases) {
                [Environment]::SetEnvironmentVariable($name,$case.Option)
                foreach ($inspect in $false,$true) {
                    $expected=$(if($name -in 'JAVA_OPTS','GRADLE_OPTS' -and $case.AmbiguousWrapper){$true}
                        elseif($inspect){$case.InspectRejected}else{$case.AutomaticRejected})
                    $rejected=$false
                    try { New-V2Request -Inspect:$inspect -Scenario grounded-two-lane -EulaAccepted | Out-Null } catch { $rejected=$true }
                    Assert ($rejected -eq $expected) "quote policy differs for $name / inspect=$inspect / $($case.Option)"
                }
                Assert ([Environment]::GetEnvironmentVariable($name) -ceq $case.Option) 'quote validation changed parent options'
                Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            } }
        }
    }
    Run 'Task7 fixture registers the gated ordinary initializer beside five providers and ten mixins' {
        $fixture=Join-Path $repo 'v2/fabric/src/gametest'
        $metadata=Get-Content (Join-Path $fixture 'resources/fabric.mod.json') -Raw | ConvertFrom-Json
        Assert ((@($metadata.entrypoints.client) -join ',') -ceq 'com.mapartrunner.fabric.scenario.ScenarioClientInitializer') 'ordinary initializer missing/duplicated'
        Assert (@($metadata.entrypoints.'fabric-client-gametest').Count -eq 5) 'automatic provider count changed'
        $mixins=Get-Content (Join-Path $fixture 'resources/mapart-test.mixins.json') -Raw | ConvertFrom-Json
        Assert (@($mixins.client).Count -eq 10 -and $mixins.client -contains 'InspectExitMixin' -and $mixins.client -contains 'InspectKeyboardInvoker') 'accepted mixin configuration changed'
    }
    Run 'Task7 real inspect constructor shares its launch root with the existing archiver' {
        $request=New-V2Request -Inspect -Scenario grounded-two-lane -EulaAccepted
        $root=Join-Path $testRoot 'inspect-constructor'
        $capture=@{}; $sentinel='Task7 before worker construction'; $caught=$null
        $originalInfo=${function:New-V2ProcessInfo}; $originalJdk=${function:Find-V2Jdk}; $originalArchive=${function:Save-V2Artifacts}
        try {
            Set-Item Function:Find-V2Jdk -Value { [pscustomobject]@{Home='C:/unused-jdk'} }
            Set-Item Function:Save-V2Artifacts -Value { param($Evidence,$Game,$ScenarioRoot,$Before,$Owner) $capture.ArchiveGame=$Game }
            Set-Item Function:New-V2ProcessInfo -Value {
                param($Executable,$WorkingDirectory,$Command,$Environment)
                $capture.Game=$game; $capture.Command=$Command; $capture.Environment=$Environment
                $capture.Request=Get-Content (Join-Path $evidence 'request.json') -Raw | ConvertFrom-Json
                & $Archive $evidence $before
                throw $sentinel
            }
            try { Invoke-V2Supervisor -RequireGame -Request $request -EvidenceRoot $root -WorkingDirectory $repo | Out-Null }
            catch { $caught=$_.Exception.Message }
        } finally {
            Set-Item Function:New-V2ProcessInfo -Value $originalInfo; Set-Item Function:Find-V2Jdk -Value $originalJdk; Set-Item Function:Save-V2Artifacts -Value $originalArchive
        }
        Assert ($caught -ceq $sentinel) "inspect failed before constructor sentinel: $caught"
        $expected=Join-Path $repo 'v2/fabric/build/run/inspect'
        Assert ($capture.Game -ceq $expected -and $capture.ArchiveGame -ceq $expected) 'inspect launch/archive roots differ'
        Assert ($capture.Command -match ':fabric:runInspect' -and $capture.Command -notmatch 'runClientGameTest|mapartTestEula|mapart.test.scenario|mapart.test.repeat') 'inspect worker selected automatic route'
        Assert ($capture.Request.Request.Mode -ceq 'inspect' -and $capture.Request.StartupDeadlineMilliseconds -eq 600000) 'inspect request/startup identity changed'
        $capture | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $testRoot 'inspect-constructor-proof.json')
        $refused=Join-Path $testRoot 'inspect-support-refused'
        Reject { Invoke-V2Supervisor -RequireGame -Request $request -InternalSupportCase controlled -EvidenceRoot $refused -WorkingDirectory $repo } 'controlled inspect supervisor accepted'
        Assert (!(Test-Path -LiteralPath $refused)) 'refused support created filesystem state'
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
            $archive={ param($evidence,$before,$context) Save-V2Artifacts -Evidence $evidence -Game $game -ScenarioRoot $atomicRoot -Before $before -Owner $repo -Context $context }
            $a=Invoke-V2Supervisor -Worker ([scriptblock]::Create("New-Item -ItemType Directory -Path '" + $aPath.Replace("'","''") + "' | Out-Null; Set-Content -LiteralPath '" + (Join-Path $aPath artifact.txt).Replace("'","''") + "' A")) -EvidenceRoot $atomicRoot -WorkingDirectory $repo -TimeoutMilliseconds 10000 -Archive $archive
        } finally { Set-Item Function:Get-V2ScenarioInventory -Value $inventoryImplementation }
        @{ StaleExternalSnapshot=$staleSnapshot; InventoryEvents=@($inventoryProof); InvocationA=$a.Evidence; InvocationB=$b.Evidence } | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $testRoot 'atomic-inventory-proof.json')
        if ($a.CleanupStatus -ne 'settled') { throw 'HOLD: interleaved A ownership unresolved; no more launches.' }
        Assert ($a.ExitCode -eq 0 -and @($inventoryProof | Where-Object { !$_.ExclusiveHandleHeld }).Count -eq 0 -and $inventoryProof.Count -ge 2) 'inventory not taken under the exclusive launch handle'
        $references=Read-V2ScenarioReferences -Evidence $a.Evidence -ScenarioRoot $atomicRoot -Owner $repo -LaunchId $a.LaunchId
        Assert ($references.Roots.Count -eq 1 -and $references.Roots[0].Name -ceq 'two-lane-A' -and (Get-Content (Join-Path $aPath artifact.txt) -Raw).Trim() -ceq 'A') 'A artifact missing or interleaved B credited'
        Assert (!(Test-Path (Join-Path $a.Evidence scenarios))) 'automatic atomic inventory duplicated payload'
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
        $referenceLaunch=[guid]::NewGuid().ToString('D'); $referenceEvidence=Join-Path $archiveFixture ('launch-'+$referenceLaunch)
        New-Item -ItemType Directory -Path $referenceEvidence | Out-Null
        New-Item -ItemType Junction -Path "$scenarios/two-lane-redirect" -Target "$scenarios/two-lane-new/ordinary" | Out-Null
        $context=[pscustomobject]@{LaunchId=$referenceLaunch;Evidence=$referenceEvidence;CleanupStatus='settled';Request=[pscustomobject]@{}}
        Save-V2Artifacts -Evidence $referenceEvidence -Game $game -ScenarioRoot $scenarios -Before $before -Owner $archiveFixture -Context $context
        $references=Read-V2ScenarioReferences -Evidence $referenceEvidence -ScenarioRoot $scenarios -Owner $archiveFixture -LaunchId $referenceLaunch
        $ordinaryRoot=$references.Roots | Where-Object Name -CEQ 'two-lane-new'
        $redirect=$references.Roots | Where-Object Name -CEQ 'two-lane-redirect'
        Assert ($redirect.Entries.Count -eq 1 -and $redirect.Entries[0].Kind -ceq 'link') 'top redirect acquired ordinary provider authority'
        $opaque=@($ordinaryRoot.Entries | Where-Object Kind -CEQ 'link')
        Assert ($opaque.Count -eq 2 -and @($opaque | Where-Object { !$_.LinkTarget -or !$_.LinkType }).Count -eq 0) 'reference live/dangling metadata missing'
        Assert (!(Test-Path "$referenceEvidence/scenarios") -and !(Test-Path -LiteralPath $absentPath)) 'reference archive followed/created link targets'
        Assert ((Get-Item -LiteralPath "$scenarios/two-lane-new/alias" -Force).LinkTarget -ceq ($opaque | Where-Object Path -CEQ alias).LinkTarget) 'live link metadata changed'
        Assert ((Get-Item -LiteralPath $linkPath -Force).LinkTarget -ceq ($opaque | Where-Object Path -CEQ dangling).LinkTarget) 'dangling link metadata changed'
        $read={ Read-V2ScenarioReferences -Evidence $referenceEvidence -ScenarioRoot $scenarios -Owner $archiveFixture -LaunchId $referenceLaunch -ClosureId $references.ClosureId }
        try {
            Remove-Item -LiteralPath "$scenarios/two-lane-new/alias"
            New-Item -ItemType Directory -Path "$scenarios/two-lane-new/alias" | Out-Null
            Reject $read 'reference reader accepted reparse replacement'
        } finally {
            Remove-Item -LiteralPath "$scenarios/two-lane-new/alias"
            New-Item -ItemType Junction -Path "$scenarios/two-lane-new/alias" -Target "$scenarios/two-lane-new/ordinary" | Out-Null
        }
        try {
            function Get-Item {
                param($LiteralPath,[switch]$Force)
                $item=Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force -ErrorAction Stop
                if ([IO.Path]::GetFullPath($LiteralPath) -eq [IO.Path]::GetFullPath((Join-Path $scenarios 'two-lane-new/alias'))) { $item | Add-Member LinkType 'Unknown' -Force }
                $item
            }
            Reject $read 'unknown link metadata accepted'
        } finally { Remove-Item Function:Get-Item }
        & $read | Out-Null
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
    Run 'Task7b stopped pipeline retains actual READY, held exits and archive before clearing ownership' {
        foreach ($case in 'ready','before-ready','archive-failure') {
            $caseRoot=Join-Path $testRoot ('stopped-'+$case)
            $observation=[hashtable]::Synchronized(@{})
            $pipeline=[Management.Automation.PowerShell]::Create()
            $invocation=$null; $stopping=$null
            try {
                $worker=$(if($case -ceq 'before-ready') { { Start-Sleep -Seconds 30 } }
                    else { New-V2ReceiptWorker '$receipt=@{schema="mapartrunner-inspect-ready-v1";launchId=$receipt.launchId;pid=$receipt.pid;startedUtc=$receipt.startedUtc;scenario="grounded-two-lane";mode="inspect";sessionId=[guid]::NewGuid().ToString("D");event="INSPECT_WORLD_BOUND_COMMANDS_READY"}' 30000 })
                [void]$pipeline.AddScript({
                    param($Source,$Owner,$Root,$WorkerText,$Case,$Observation)
                    $ErrorActionPreference='Stop'
                    . $Source
                    $originalOperations=${function:Update-V2InspectOperations}
                    $originalProcesses=${function:Update-V2Processes}
                    function Update-V2InspectOperations {
                        param($State,$Result,$WorkingDirectory,$Clock,[switch]$RequireGame,[hashtable]$InternalInspectTest=@{})
                        & $originalOperations @PSBoundParameters
                        if (!$Observation.ContainsKey('Snapshot')) {
                            $Observation.Snapshot=$Result | ConvertTo-Json -Depth 12 -Compress
                            $Observation.HeldPid=$State.Held.Record.Pid
                            $Observation.HeldStartedUtc=$State.Held.Record.StartedUtc
                            $Observation.HeldLive=!$State.Held.Process.HasExited
                            $Observation.Ready=$true
                        }
                    }
                    function Update-V2Processes {
                        param($Job,$Records,$Handles,[switch]$Fast)
                        & $originalProcesses @PSBoundParameters
                        if ($Case -ceq 'before-ready' -and !$Fast -and !$Observation.ContainsKey('Snapshot')) {
                            $Observation.Snapshot=$result | ConvertTo-Json -Depth 12 -Compress
                            $Observation.OwnedPids=@($Job.Pids())
                            $Observation.Ready=$true
                        }
                    }
                    $game=Join-Path $Root 'dummy-game'
                    New-Item -ItemType Directory -Path (Join-Path $game 'logs') -Force | Out-Null
                    Set-Content -LiteralPath (Join-Path $game 'logs/latest.log') 'owned dummy archive'
                    $archive={
                        param($Evidence,$Before)
                        Save-V2Artifacts -Evidence $Evidence -Game $game -ScenarioRoot $Root -Before $Before -Owner $Owner
                        if ($Case -ceq 'archive-failure') { throw 'Task7b deliberate archive failure' }
                    }
                    Invoke-V2Supervisor -Worker ([scriptblock]::Create($WorkerText)) -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $Root -WorkingDirectory $Owner -TimeoutMilliseconds 20000 -StartupMilliseconds 10000 -Archive $archive
                }.ToString()).AddArgument((Join-Path $repo 'scripts/test-v2.ps1')).AddArgument($repo).AddArgument($caseRoot).AddArgument($worker.ToString()).AddArgument($case).AddArgument($observation)
                $invocation=$pipeline.BeginInvoke()
                $wait=[Diagnostics.Stopwatch]::StartNew()
                while (!$observation.Ready -and !$invocation.IsCompleted -and $wait.ElapsedMilliseconds -lt 10000) { Start-Sleep -Milliseconds 20 }
                if (!$observation.Ready -and $invocation.IsCompleted) { [void]$pipeline.EndInvoke($invocation) }
                Assert $observation.Ready "stopped $case never reached actual supervisor observation"
                $accepted=$observation.Snapshot | ConvertFrom-Json
                $observation.Snapshot | Set-Content (Join-Path $caseRoot 'observed-before-stop.json')
                if ($case -ceq 'before-ready') {
                    Assert ($null -eq $accepted.StartReceipt -and $null -eq $accepted.StartIdentity -and $null -eq $accepted.StartupAcceptedElapsedMilliseconds -and $observation.OwnedPids -contains $accepted.WorkerPid) 'pre-READY stop lacked actual owned worker or invented acceptance'
                } else {
                    Assert ($accepted.ReceiptStatus -ceq 'Accepted' -and $accepted.StartReceipt.event -ceq 'INSPECT_WORLD_BOUND_COMMANDS_READY' -and $null -ne $accepted.StartupAcceptedElapsedMilliseconds -and $observation.HeldLive -and $observation.HeldPid -eq $accepted.StartIdentity.Pid -and [DateTimeOffset]$observation.HeldStartedUtc -eq [DateTimeOffset]$accepted.StartIdentity.StartedUtc) 'stop was not after actual consumer acceptance of the same live held identity'
                }
                $stopClock=[Diagnostics.Stopwatch]::StartNew()
                $stopping=$pipeline.BeginStop($null,$null)
                Assert ($stopping.AsyncWaitHandle.WaitOne(15000)) 'HOLD: stopped pipeline exceeded original cleanup bound plus host allowance'
                $pipeline.EndStop($stopping)
                $stopped=$false
                try { [void]$pipeline.EndInvoke($invocation) }
                catch [Management.Automation.MethodInvocationException] {
                    if ($_.Exception.InnerException -isnot [Management.Automation.PipelineStoppedException]) { throw }
                    $stopped=$true
                }
                Assert ($stopped -and $pipeline.InvocationStateInfo.State -eq 'Stopped') 'pipeline was not actually stopped'
                Assert (@($pipeline.Streams.Error | Where-Object { $_.Exception -isnot [Management.Automation.PipelineStoppedException] }).Count -eq 0) "unexpected stopped-pipeline error: $($pipeline.Streams.Error -join '; ')"
                $outcomePath=Join-Path $accepted.Evidence 'outcome.json'
                $proof=@{Case=$case;PipelineState=[string]$pipeline.InvocationStateInfo.State;PipelineStoppedException=$stopped;StopElapsedMilliseconds=$stopClock.ElapsedMilliseconds;OutcomePresent=(Test-Path -LiteralPath $outcomePath);LockBytes=(Get-Item -LiteralPath (Join-Path $caseRoot 'supervisor.lock')).Length;Observation=$accepted}
                $proof | ConvertTo-Json -Depth 14 | Set-Content (Join-Path $caseRoot 'stop-proof.json')
                Assert $proof.OutcomePresent "stopped $case lost durable outcome after actual pipeline cancellation"
                $outcome=Get-Content -LiteralPath $outcomePath -Raw | ConvertFrom-Json
                Assert ($outcome.CleanupStatus -ceq 'settled' -and $null -ne $outcome.WorkerExitCode -and @($outcome.Processes).Count -gt 0 -and @($outcome.Processes | Where-Object {$null -eq $_.ExitCode}).Count -eq 0) 'stopped pipeline lost actual held exits/settlement'
                Assert (@($outcome.Processes | Where-Object {$_.Pid -eq $outcome.WorkerPid -and $_.ExitCode -eq $outcome.WorkerExitCode}).Count -eq 1) 'raw worker exit differs from original held process'
                Assert ($outcome.ExitCode -ne 0 -and $outcome.TerminationRequested -and !$outcome.TimedOut) 'interruption became success or renewed deadline timeout'
                Assert ((Test-Path -LiteralPath (Join-Path $accepted.Evidence 'exit-code.txt')) -and (Get-Content -LiteralPath (Join-Path $accepted.Evidence 'logs/latest.log') -Raw).Trim() -ceq 'owned dummy archive') 'stopped pipeline lost exit summary/archive bytes'
                Assert ($outcome.LaunchId -ceq $accepted.LaunchId -and $outcome.StartupAcceptedElapsedMilliseconds -eq $accepted.StartupAcceptedElapsedMilliseconds -and ($outcome.StartIdentity | ConvertTo-Json -Compress) -ceq ($accepted.StartIdentity | ConvertTo-Json -Compress) -and ($outcome.StartReceipt | ConvertTo-Json -Compress) -ceq ($accepted.StartReceipt | ConvertTo-Json -Compress)) 'cancellation changed accepted receipt/identity/original clock'
                if ($case -ceq 'archive-failure') {
                    Assert ($outcome.ArchiveStatus -ceq 'failed' -and $outcome.ArchiveError -like '*Task7b deliberate archive failure*' -and $proof.LockBytes -gt 0) 'cancelled archive failure lost failure/retained ownership'
                    Reject { Invoke-V2Supervisor -Worker { exit 0 } -EvidenceRoot $caseRoot -WorkingDirectory $repo } 'cancelled archive failure allowed rerun'
                } else { Assert ($outcome.ArchiveStatus -ceq 'retained' -and $proof.LockBytes -eq 0) 'cancelled settled archive did not clear ownership' }
            } finally {
                if ($invocation -and !$invocation.IsCompleted) { $pipeline.Stop() }
                if ($stopping) { $stopping.AsyncWaitHandle.Dispose() }
                if ($invocation) { $invocation.AsyncWaitHandle.Dispose() }
                $pipeline.Dispose()
            }
        }
    }
    Run 'inspect READY establishes one session only after the same owned receipt checks' {
        $request=[pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}
        $originalUpdate=${function:Update-V2Processes}; $proof=@{Checked=$false;Latched=$false;Samples=0;Session=$null;Elapsed=$null}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if ($Fast) { return }
                $held=$Handles | Where-Object { $_.Record.Pid -eq $process.Id } | Select-Object -First 1
                $file=Join-Path $evidence 'test-start.json'
                if (!$proof.Checked) {
                    $proof.Checked=$true
                    $data=@{schema='mapartrunner-inspect-ready-v1';launchId=$launchId;pid=$held.Record.Pid;startedUtc=$held.Record.StartedUtc
                        scenario='grounded-two-lane';mode='inspect';sessionId='a420fdb6-92ee-4691-a827-141d70ca5ab0';event='INSPECT_WORLD_BOUND_COMMANDS_READY'}
                    $valid=$data | ConvertTo-Json -Compress
                    $arguments=@{Evidence=$evidence;LaunchId=$launchId;Request=$Request;Handles=@($Handles);OwnedPids=@($Job.Pids());WorkerPid=$process.Id}
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Missing') 'missing inspect receipt accepted'
                    $cases=@(
                        '{', '{}', '[]', (' '*4097),
                        ($valid -replace '\{"','{"schema":"mapartrunner-inspect-ready-v1","'),
                        ($valid -replace '\{"','{"repeat":2,"'),
                        ($valid -replace '"mode":"inspect"','"mode":"automatic"'),
                        ($valid -replace 'INSPECT_WORLD_BOUND_COMMANDS_READY','SELECTED_PROVIDER_RUNTEST_ENTERED'),
                        ($valid -replace 'mapartrunner-inspect-ready-v1','mapartrunner-test-start-v1'),
                        ($valid -replace 'grounded-two-lane','all'),
                        ($valid -replace 'a420fdb6-92ee-4691-a827-141d70ca5ab0','A420FDB6-92EE-4691-A827-141D70CA5AB0'),
                        ($valid -replace 'a420fdb6-92ee-4691-a827-141d70ca5ab0','not-a-session'),
                        ($valid -replace ('"launchId":"'+$launchId+'"'),'"launchId":"00000000-0000-0000-0000-000000000000"'),
                        ($valid -replace ('"pid":'+$held.Record.Pid),'"pid":1.5'),
                        ($valid -replace '"sessionId":"[^"]+"','"sessionId":1')
                    )
                    foreach ($field in @($data.Keys)) {
                        $partial=@{}+$data; $partial.Remove($field); $cases+=($partial | ConvertTo-Json -Compress)
                    }
                    foreach ($text in $cases) {
                        [IO.File]::WriteAllText($file,$text,[Text.UTF8Encoding]::new($false))
                        $checked=Read-V2StartReceipt @arguments
                        Assert ($checked.Status -ceq 'Rejected' -and !$checked.Receipt -and !$checked.Identity) 'malformed inspect receipt established identity'
                    }
                    [IO.File]::WriteAllBytes($file,[byte[]]@(0xff,0xfe,0xff))
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'invalid inspect UTF8 accepted'
                    [IO.File]::WriteAllText($file,$valid,[Text.UTF8Encoding]::new($false))
                    $arguments.OwnedPids=@()
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'foreign inspect PID accepted'
                    $arguments.OwnedPids=@($Job.Pids()); $arguments.Handles=@()
                    $pending=Read-V2StartReceipt @arguments
                    Assert ($pending.Status -ceq 'Pending' -and !$pending.Receipt -and !$pending.Identity -and !$result.StartReceipt) 'pending inspect inventory established a session'
                    $record=$held.Record.PSObject.Copy(); $record.StartedUtc='2000-01-01T00:00:00Z'
                    $arguments.Handles=@([pscustomobject]@{Process=$held.Process;Record=$record})
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'reused inspect PID accepted'
                    $record=$held.Record.PSObject.Copy(); $record.ExitCode=0
                    $arguments.Handles=@([pscustomobject]@{Process=$held.Process;Record=$record})
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'dead inspect record accepted'
                    $record=$held.Record.PSObject.Copy(); $record.StartedUtc='2000-01-01T00:00:00Z'
                    $arguments.Handles=@([pscustomobject]@{Process=$held.Process;Record=$record})
                    $data.startedUtc=$record.StartedUtc
                    [IO.File]::WriteAllText($file,($data | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'inspect creation record credited over actual held StartTime'
                    [IO.File]::WriteAllText($file,$valid,[Text.UTF8Encoding]::new($false))
                    $record=$held.Record.PSObject.Copy(); $record.IsGame=$true; $record.IdentityVerified=$true
                    $arguments.Handles=@([pscustomobject]@{Process=$held.Process;Record=$record})
                    Assert ((Read-V2StartReceipt @arguments -RequireGame -JavaExecutable (Join-Path $actualJdk.Home 'bin/java.exe')).Status -ceq 'Rejected') 'wrong configured JDK accepted for inspect'
                    $arguments.Handles=@($Handles)
                    Assert ((Read-V2StartReceipt @arguments -RequireGame -JavaExecutable (Join-Path $actualJdk.Home 'bin/java.exe')).Status -ceq 'Rejected') 'dummy credited as inspect game'
                    $arguments.Request=New-V2Request -EulaAccepted
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'untrusted receipt selected inspect mode'
                    $arguments.Request=[pscustomobject]@{Mode='INSPECT';Scenario='grounded-two-lane'}
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'unknown request mode accepted'
                    $arguments.Request=$Request
                    $arguments.Evidence='relative'
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'relative inspect evidence accepted'
                    $linkParent=Join-Path $testRoot ('receipt-link-'+[guid]::NewGuid())
                    New-Item -ItemType Directory -Path $linkParent | Out-Null
                    $link=Join-Path $linkParent "launch-$launchId"
                    try {
                        New-Item -ItemType Junction -Path $link -Target $evidence | Out-Null
                        $arguments.Evidence=$link
                        Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Rejected') 'reparse inspect evidence accepted'
                    } finally { if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link -Force }; Remove-Item -LiteralPath $linkParent -Force }
                    $arguments.Evidence=$evidence
                    Assert ((Read-V2StartReceipt @arguments).Status -ceq 'Accepted') 'valid inspect READY rejected'
                }
                elseif ($result.StartReceipt) {
                    if (!$proof.Latched) { $proof.Session=$result.StartReceipt.sessionId; $proof.Elapsed=$result.StartupAcceptedElapsedMilliseconds }
                    Assert ($result.StartReceipt.sessionId -ceq $proof.Session -and $result.StartupAcceptedElapsedMilliseconds -eq $proof.Elapsed) 'later inspect READY replaced session or renewed time'
                    $proof.Latched=$true
                    $proof.Samples++
                    $later=$result.StartReceipt.PSObject.Copy(); $later.sessionId=[guid]::NewGuid().ToString('D')
                    [IO.File]::WriteAllText($file,($later | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                }
            }
            $outcome=Invoke-V2Supervisor -Worker { Start-Sleep -Milliseconds 700; exit 0 } -Request $request -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        Assert-V2Settled $outcome
        Assert ($proof.Checked -and $proof.Latched -and $proof.Samples -ge 2 -and $outcome.ExitCode -eq 0 -and $outcome.StartReceipt.sessionId -ceq 'a420fdb6-92ee-4691-a827-141d70ca5ab0') 'inspect readiness/latch path was not exercised'
        $early=Invoke-V2Supervisor -Worker { exit 0 } -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
        Assert-V2Settled $early
        Assert ($early.ExitCode -eq 125 -and !$early.StartReceipt -and !$early.StartIdentity) 'early inspect worker established readiness'
        $missing=Invoke-V2Supervisor -Worker { Start-Sleep -Seconds 30 } -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 5000 -StartupMilliseconds 40
        Assert-V2Settled $missing
        Assert ($missing.ExitCode -eq 124 -and $missing.TimeoutPhase -ceq 'STARTUP' -and !$missing.StartReceipt -and !$missing.StartIdentity) 'missing inspect READY escaped startup or established session'
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
    Run 'inspect phase reader enforces exact clocks schema identity paths and original age' {
        Assert ([bool](Get-Command Read-V2InspectOperationReceipt -ErrorAction SilentlyContinue)) 'inspect phase reader is missing'
        $originalUpdate=${function:Update-V2Processes}; $proof=@{Done=$false}
        try {
            Set-Item Function:Update-V2Processes -Value {
                param($Job,$Records,$Handles,[switch]$Fast)
                & $originalUpdate $Job $Records $Handles -Fast:$Fast
                if ($Fast -or $proof.Done -or !$result.StartReceipt) { return }
                $proof.Done=$true; $held=$Handles | Where-Object { $_.Record.Pid -eq $process.Id } | Select-Object -First 1
                $session=$result.StartReceipt.sessionId; $id=[guid]::NewGuid().ToString('D')
                $directory=Join-Path $repo "v2/fabric/build/scenario-results/two-lane-$session/inspect-operations/op-1-$id"
                New-Item -ItemType Directory -Path $directory -Force | Out-Null
                $time=[DateTimeOffset]::UtcNow; $time=[DateTimeOffset]::new($time.Ticks-($time.Ticks%10000),[TimeSpan]::Zero)
                $data=[ordered]@{schema='mapartrunner-inspect-operation-v1';launchId=$launchId;pid=$process.Id;startedUtc=$result.StartReceipt.startedUtc
                    mode='inspect';sessionId=$session;ordinal=1;operationId=$id;command='RUN';phase='WORK';phaseStartedUtc=$time.ToString('o')
                    phaseDeadlineUtc=$time.AddMilliseconds(600000).ToString('o');evidenceDir=$directory;verdict=$null;cleanupStatus=$null}
                $file=Join-Path $evidence 'inspect-op-1.begin.json'
                $arguments=@{Evidence=$evidence;WorkingDirectory=$repo;StartReceipt=$result.StartReceipt;StartIdentity=$result.StartIdentity
                    Held=$held;Ordinal=1;Phase='begin';Previous=$null;PreviousSettledUtc=$null;InternalInspectTest=@{NowUtc=$time}}
                function Check-Phase($Candidate) {
                    [IO.File]::WriteAllText($file,($Candidate|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    Read-V2InspectOperationReceipt @arguments
                }
                Assert ((Check-Phase $data).Status -ceq 'Accepted') 'legitimate future BEGIN deadline rejected'
                foreach($change in @(
                    { $candidate.phaseStartedUtc=$time.AddMilliseconds(1).ToString('o') },
                    { $candidate.phaseDeadlineUtc=$time.AddMilliseconds(600001).ToString('o') },
                    { $candidate.sessionId=[guid]::NewGuid().ToString('D') },
                    { $candidate.evidenceDir=$evidence }, { $candidate.ordinal=2 }, { $candidate.ordinal=1.5 },
                    { $candidate.operationId='1-1-1-1-1' }, { $candidate.pid=$PID }, { $candidate.phase='CLEANUP' },
                    { $candidate.verdict='SUCCESS' }, { $candidate.cleanupStatus='SETTLED' }, { $candidate.extra=1 },
                    { $candidate.Remove('verdict') }, { $candidate.ordinal='1' }, { $candidate.pid='1' },
                    { $candidate.schema='wrong' }, { $candidate.mode='automatic' }, { $candidate.command='STARTUP' },
                    { $candidate.phaseDeadlineUtc=$null }, { $candidate.phaseStartedUtc=$null },
                    { $candidate.phaseStartedUtc=([DateTimeOffset]::Parse($data.startedUtc)).AddMilliseconds(-1).ToString('o') }
                )) {
                    $candidate=@{}+$data; & $change
                    Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'malformed/future/mismatched BEGIN accepted'
                }
                foreach($raw in @('{','[]','{}',(' '*4097), (($data|ConvertTo-Json -Compress) -replace '\{"','{"schema":"duplicate","'))) {
                    [IO.File]::WriteAllText($file,$raw,[Text.UTF8Encoding]::new($false))
                    Assert ((Read-V2InspectOperationReceipt @arguments).Status -ceq 'Rejected') 'malformed/duplicate/oversize JSON accepted'
                }
                [IO.File]::WriteAllBytes($file,[byte[]]@(0xff,0xfe))
                Assert ((Read-V2InspectOperationReceipt @arguments).Status -ceq 'Rejected') 'invalid UTF8 phase accepted'
                $oldHeld=$held.Record.StartedUtc; $oldIdentity=$result.StartIdentity.StartedUtc
                try {
                    $held.Record.StartedUtc=([DateTimeOffset]::Parse($oldHeld)).AddMilliseconds(5).UtcDateTime.ToString('o')
                    $result.StartIdentity.StartedUtc=$held.Record.StartedUtc
                    $candidate=@{}+$data; $candidate.phaseDeadlineUtc=$time.AddMilliseconds(600001).ToString('o')
                    Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'identity10ms tolerance extended deadline'
                } finally {$held.Record.StartedUtc=$oldHeld;$result.StartIdentity.StartedUtc=$oldIdentity}
                $begin=(Check-Phase $data).Receipt
                $arguments.Phase='cleanup'; $arguments.Previous=$begin; $file=Join-Path $evidence 'inspect-op-1.cleanup.json'
                $middle=@{}+$data; $middle.phase='CLEANUP'; $middle.verdict='TIMEOUT'; $middle.phaseDeadlineUtc=$time.AddMilliseconds(10000).ToString('o')
                $checked=Check-Phase $middle
                Assert ($checked.Status -ceq 'Accepted') "legal cleanup rejected: $($checked.Reason)"
                $candidate=@{}+$middle; $candidate.phaseDeadlineUtc=$time.AddMilliseconds(10001).ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'cleanup own cap +1ms accepted'
                $arguments.InternalInspectTest.NowUtc=$time.AddMilliseconds(600001)
                $candidate=@{}+$middle; $candidate.phaseStartedUtc=$time.AddMilliseconds(600001).ToString('o'); $candidate.phaseDeadlineUtc=$time.AddMilliseconds(610001).ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'cleanup original work cap +1ms accepted'
                $arguments.InternalInspectTest.NowUtc=$time
                $cleanup=(Check-Phase $middle).Receipt
                $arguments.Phase='end'; $arguments.Previous=$cleanup; $file=Join-Path $evidence 'inspect-op-1.end.json'
                $end=@{}+$middle; $end.phase='SETTLED'; $end.cleanupStatus='SETTLED'; $end.phaseDeadlineUtc=$null
                $checked=Check-Phase $end
                Assert ($checked.Status -ceq 'Accepted') "valid END rejected: $($checked.Reason)"
                $candidate=@{}+$end; $candidate.phaseDeadlineUtc=$time.ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'nonnull END deadline accepted'
                $candidate=@{}+$end; $candidate.phaseStartedUtc=$time.AddMilliseconds(-1).ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'END occurrence moved backwards'
                $arguments.Ordinal=2; $file=Join-Path $evidence 'inspect-op-2.end.json'
                Assert ((Check-Phase $end).Status -ceq 'Rejected') 'late old END settled newer ordinal'
                Remove-Item -LiteralPath $file; $arguments.Ordinal=1
                $arguments.Phase='begin'; $arguments.Previous=$null; $file=Join-Path $evidence 'inspect-op-1.begin.json'
                $candidate=@{}+$data; $candidate.command='CLOSE'; $candidate.phaseDeadlineUtc=$time.AddMilliseconds(10000).ToString('o')
                $close=(Check-Phase $candidate)
                Assert ($close.Status -ceq 'Accepted') 'CLOSE shared ceiling rejected'
                $candidate.phaseDeadlineUtc=$time.AddMilliseconds(10001).ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'CLOSE cap +1ms accepted'
                $arguments.Phase='cleanup'; $arguments.Previous=$close.Receipt; $arguments.InternalInspectTest.NowUtc=$time.AddMilliseconds(5000)
                $file=Join-Path $evidence 'inspect-op-1.cleanup.json'
                $candidate=@{}+$middle; $candidate.command='CLOSE'; $candidate.phaseStartedUtc=$time.AddMilliseconds(5000).ToString('o'); $candidate.phaseDeadlineUtc=$time.AddMilliseconds(10001).ToString('o')
                Assert ((Check-Phase $candidate).Status -ceq 'Rejected') 'CLOSE CLEANUP renewed shared ceiling'
                foreach($name in 'begin','cleanup','end') { Remove-Item -LiteralPath (Join-Path $evidence "inspect-op-1.$name.json") }
            }
            $worker=New-V2ReceiptWorker '$receipt=@{schema="mapartrunner-inspect-ready-v1";launchId=$receipt.launchId;pid=$receipt.pid;startedUtc=$receipt.startedUtc;scenario="grounded-two-lane";mode="inspect";sessionId=[guid]::NewGuid().ToString("D");event="INSPECT_WORLD_BOUND_COMMANDS_READY"}' 3000
            $outcome=Invoke-V2Supervisor -Worker $worker -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 6000 -StartupMilliseconds 3000
        } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate }
        Assert-V2Settled $outcome
        Assert ($proof.Done -and $outcome.ExitCode -eq 0) 'phase parser dummy failed'
    }
    Run 'Task7b exit metadata policy withholds phases until the same signaled handle supplies a valid bound' {
        # Policy model, not a reproduction of the historical Windows timestamp failures.
        foreach($code in 0,7) {
            $start=[DateTimeOffset]::UtcNow.AddSeconds(-30); $time=$start.AddMilliseconds(100)
            $model=[pscustomobject]@{Id=9000+$code;StartTime=$start.UtcDateTime;HasExited=$true;ExitCode=$code
                Signal=$false;Unknown=($code -eq 0);CachedExit=[DateTime]::FromFileTimeUtc(0);ValidExit=$start.AddSeconds(2).UtcDateTime;Refreshes=0;RefreshMode='valid'}
            $model | Add-Member ScriptMethod WaitForExit {param($Milliseconds) $this.Signal}
            $model | Add-Member ScriptMethod Refresh {
                $this.Refreshes++
                if($this.RefreshMode -ceq 'throw'){throw 'policy model refresh unavailable'}
                if($this.Signal){$this.CachedExit=$(if($this.RefreshMode -ceq 'invalid'){[DateTime]::FromFileTimeUtc(0)}else{$this.ValidExit});$this.Unknown=$false}
            }
            $model | Add-Member ScriptProperty ExitTime {if($this.Unknown){throw 'policy model exit time unknown'};$this.CachedExit}
            $record=[pscustomobject]@{Pid=$model.Id;StartedUtc=$start.ToString('o');Executable='policy-model';IsGame=$false;ExitCode=$null;ExitedUtc=$null;ObservedExitUtc=$null}
            $held=[pscustomobject]@{Process=$model;Record=$record}
            Update-V2ProcessExits @($held) -IncludeExiting
            Assert ($record.ExitCode -eq $code -and $null -eq $record.ExitedUtc) 'early actual code became a known nonsignaled exit bound'
            $launch=[guid]::NewGuid().ToString('D'); $session=[guid]::NewGuid().ToString('D'); $operation=[guid]::NewGuid().ToString('D')
            $evidence=Join-Path $testRoot "launch-$launch"
            $directory=Join-Path $repo "v2/fabric/build/scenario-results/two-lane-$session/inspect-operations/op-1-$operation"
            New-Item -ItemType Directory -Path $evidence,$directory -Force | Out-Null
            $ready=[pscustomobject]@{launchId=$launch;pid=$model.Id;startedUtc=$record.StartedUtc;mode='inspect';sessionId=$session}
            $identity=[pscustomobject]@{Pid=$record.Pid;StartedUtc=$record.StartedUtc;Executable=$record.Executable;IsGame=$false}
            $data=@{schema='mapartrunner-inspect-operation-v1';launchId=$launch;pid=$model.Id;startedUtc=$record.StartedUtc;mode='inspect';sessionId=$session;ordinal=1;operationId=$operation;command='RUN';evidenceDir=$directory}
            $frames=@{}
            foreach($phase in 'begin','cleanup','end') {
                $frame=@{}+$data; $frame.phase=@{begin='WORK';cleanup='CLEANUP';end='SETTLED'}[$phase]
                $frame.phaseStartedUtc=$time.AddMilliseconds(@{begin=0;cleanup=10;end=20}[$phase]).ToString('o')
                $frame.phaseDeadlineUtc=$(if($phase -ceq 'end'){$null}else{$time.AddMilliseconds(@{begin=1000;cleanup=1010}[$phase]).ToString('o')})
                $frame.verdict=$(if($phase -ceq 'begin'){$null}else{'SUCCESS'}); $frame.cleanupStatus=$(if($phase -ceq 'end'){'SETTLED'}else{$null})
                $frames[$phase]=$frame; $frame|ConvertTo-Json -Compress|Set-Content (Join-Path $evidence "inspect-op-1.$phase.json")
            }
            $outcome=[pscustomobject]@{Evidence=$evidence;StartReceipt=$ready;StartIdentity=$identity;WorkerPid=$model.Id;TerminationRequested=$false;ExitCode=0;PrimaryFailure=$null;ObservedFailures=[Collections.Generic.List[object]]::new()}
            Set-V2ObservedFailure $outcome @($record)
            Assert ($outcome.ExitCode -eq $code) 'early code7 failure precedence was delayed'
            $state=[pscustomobject]@{Held=$held;NextOrdinal=1;NextPhase='begin';Previous=$null;SettledUtc=$null;Active=$false;CapElapsed=$null;Accepted=@{};Outcomes=[Collections.Generic.List[object]]::new();MetadataPending=$false}
            $clock=[Diagnostics.Stopwatch]::StartNew(); $allowance=@{WorkMilliseconds=1000;CleanupMilliseconds=1000}
            Update-V2InspectOperations $state $outcome $repo $clock -InternalInspectTest $allowance
            Assert ($state.MetadataPending -and !$state.Accepted.Count -and !$state.Outcomes.Count -and $state.NextOrdinal -eq 1 -and $null -eq $state.CapElapsed) 'unknown metadata advanced provisional phase/ordinal/watchdog state'
            $model.Signal=$true
            # Repair is required even though the code was already latched; a cached zero is not a bound.
            $record.ExitedUtc=[DateTime]::FromFileTimeUtc(0).ToString('o')
            Update-V2ProcessExits @($held) -IncludeExiting
            Assert ($record.ExitCode -eq $code -and [DateTimeOffset]$record.ExitedUtc -eq [DateTimeOffset]$model.ValidExit -and $model.Refreshes -gt 0) 'signaled held timestamp was not repaired independently of latched code'
            $verified=$model.ValidExit
            foreach($failure in 'throw','invalid') {
                foreach($replacement in 'same','changed') {
                    $model.RefreshMode=$failure
                    Update-V2ProcessExits @($held) -IncludeExiting
                    Assert ($record.ExitedUtc -and [DateTimeOffset]$record.ExitedUtc -eq [DateTimeOffset]$verified) "verified exit bound was erased by $failure observation"
                    $model.RefreshMode='valid'; $model.ValidExit=$(if($replacement -ceq 'changed'){$verified.AddSeconds(1)}else{$verified})
                    if($replacement -ceq 'changed') {
                        Reject {Update-V2ProcessExits @($held) -IncludeExiting} "changed verified bound survived $failure observation"
                    } else {Update-V2ProcessExits @($held) -IncludeExiting}
                    Assert ($record.ExitCode -eq $code -and [DateTimeOffset]$record.ExitedUtc -eq [DateTimeOffset]$verified) 'replacement changed immutable bound or original code'
                    $model.ValidExit=$verified
                    Update-V2ProcessExits @($held) -IncludeExiting
                }
            }
            Update-V2InspectOperations $state $outcome $repo $clock -InternalInspectTest $allowance
            Assert (!$state.MetadataPending -and $state.Outcomes.Count -eq 1 -and $state.NextOrdinal -eq 2 -and $outcome.ExitCode -eq $code) 'verified final frames did not reconcile with original failure precedence'
            $arguments=@{Evidence=$evidence;WorkingDirectory=$repo;StartReceipt=$ready;StartIdentity=$identity;Held=$held;Ordinal=1;Phase='end';Previous=[pscustomobject]$frames.cleanup;InternalInspectTest=$allowance}
            foreach($late in @($start.AddSeconds(2).AddMilliseconds(1),$time.AddMilliseconds(1011))) {
                $frame=@{}+$frames.end; $frame.phaseStartedUtc=$late.ToString('o'); $frame|ConvertTo-Json -Compress|Set-Content (Join-Path $evidence 'inspect-op-1.end.json')
                Assert ((Read-V2InspectOperationReceipt @arguments).Status -ceq 'Rejected') 'real post-exit or END deadline +1ms was accepted'
            }
        }
    }
    Run 'Task7b final held settlement reconciles pending metadata within the original cleanup allowance' {
        $proofs=[Collections.Generic.List[object]]::new()
        foreach($case in 'code0','code7','unknown') {
            $originalUpdate=${function:Update-V2Processes}; $originalExits=${function:Update-V2ProcessExits}
            $proof=@{Case=$case;Published=$false;CleanupSamples=0;PendingSamples=0}
            $caseRoot=Join-Path $testRoot ('exit-metadata-'+$case)
            try {
                Set-Item Function:Update-V2ProcessExits -Value {
                    param($Handles,[switch]$IncludeExiting)
                    & $originalExits @PSBoundParameters
                    if (!$proof.Published) {return}
                    foreach($entry in $Handles) {
                        if($entry.Record.Pid -ne $process.Id){continue}
                        if($cleanupClock){$proof.CleanupSamples++;$proof.CleanupElapsed=$cleanupClock.ElapsedMilliseconds}
                        if($proof.Case -ceq 'unknown' -or !$cleanupClock -or $proof.CleanupSamples -lt 2) {
                            $entry.Record.ExitedUtc=$null; $proof.PendingSamples++
                            Assert (!$inspectState.Accepted.Count -and $inspectState.NextOrdinal -eq 1 -and $null -eq $inspectState.CapElapsed) 'metadata-withholding fixture saw provisional acceptance'
                        }
                    }
                }
                Set-Item Function:Update-V2Processes -Value {
                    param($Job,$Records,$Handles,[switch]$Fast)
                    & $originalUpdate @PSBoundParameters
                    if($Fast -or !$result.StartReceipt -or $proof.Published){return}
                    $proof.Published=$true; $stamp=[DateTimeOffset]::UtcNow; $operation=[guid]::NewGuid().ToString('D'); $session=$result.StartReceipt.sessionId
                    $directory=Join-Path $repo "v2/fabric/build/scenario-results/two-lane-$session/inspect-operations/op-1-$operation"
                    New-Item -ItemType Directory -Path $directory -Force|Out-Null
                    foreach($phase in 'begin','cleanup','end') {
                        $frame=@{schema='mapartrunner-inspect-operation-v1';launchId=$launchId;pid=$process.Id;startedUtc=$result.StartReceipt.startedUtc;mode='inspect';sessionId=$session;ordinal=1;operationId=$operation;command='RUN';evidenceDir=$directory
                            phase=@{begin='WORK';cleanup='CLEANUP';end='SETTLED'}[$phase];phaseStartedUtc=$stamp.ToString('o');phaseDeadlineUtc=$(if($phase -ceq 'end'){$null}else{$stamp.AddMilliseconds(1000).ToString('o')});verdict=$(if($phase -ceq 'begin'){$null}else{'SUCCESS'});cleanupStatus=$(if($phase -ceq 'end'){'SETTLED'}else{$null})}
                        $frame|ConvertTo-Json -Compress|Set-Content (Join-Path $evidence "inspect-op-1.$phase.json")
                    }
                    Set-Content (Join-Path $evidence 'metadata-done') 'done'
                    Assert ($process.WaitForExit(3000)) 'metadata fixture worker did not exit'
                }
                $code=$(if($case -ceq 'code7'){7}else{0})
                $body=New-V2ReceiptWorker '$receipt=@{schema="mapartrunner-inspect-ready-v1";launchId=$receipt.launchId;pid=$receipt.pid;startedUtc=$receipt.startedUtc;scenario="grounded-two-lane";mode="inspect";sessionId=[guid]::NewGuid().ToString("D");event="INSPECT_WORLD_BOUND_COMMANDS_READY"}' 0
                $text=$body.ToString() -replace 'Start-Sleep -Milliseconds 0\s+exit 0',('$bound=[Diagnostics.Stopwatch]::StartNew();while(!(Test-Path (Join-Path $env:MAPART_TEST_LAUNCH_DIR "metadata-done"))){if($bound.ElapsedMilliseconds -gt 8000){exit 99};Start-Sleep -Milliseconds 10};exit '+$code)
                $outcome=Invoke-V2Supervisor -Worker ([scriptblock]::Create($text)) -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $caseRoot -WorkingDirectory $repo -StartupMilliseconds 3000 -InternalInspectTest @{WorkMilliseconds=1000;CleanupMilliseconds=1000}
                $proof.Outcome=$outcome
            } finally {Set-Item Function:Update-V2Processes -Value $originalUpdate;Set-Item Function:Update-V2ProcessExits -Value $originalExits}
            $proofs.Add($proof); $proofs|ConvertTo-Json -Depth 14|Set-Content (Join-Path $testRoot 'exit-metadata-proofs.json')
            Assert ($proof.PendingSamples -gt 0 -and $outcome.WorkerExitCode -eq $code) 'metadata withholding missed actual raw worker exit'
            if($case -ceq 'unknown') {
                Assert ($outcome.ExitCode -eq 125 -and $outcome.CleanupStatus -ceq 'unresolved' -and $outcome.InspectIncomplete -and $outcome.CleanupError -like '*exit metadata*' -and !$outcome.InspectOperations.Count -and (Get-Item (Join-Path $caseRoot 'supervisor.lock')).Length -gt 0) 'persistent unknown metadata did not fail closed/retain ownership'
            } else {
                Assert-V2Settled $outcome
                Assert ($outcome.ExitCode -eq $code -and $outcome.InspectOperations.Count -eq 1 -and !$outcome.InspectIncomplete -and !$outcome.TerminationRequested -and (Get-Item (Join-Path $caseRoot 'supervisor.lock')).Length -eq 0) 'final held drain lost valid frames or original code precedence'
            }
        }
    }
    Run 'inspect ordered polling idle timeout cleanup exit and escalation share the owned backend' {
        Reject { Invoke-V2Supervisor -RequireGame -InternalInspectTest @{} -Worker {exit 0} } 'real inspect clock override accepted'
        # Policy-only reproduction of Windows exit-code visibility before its wait handle is signaled.
        $exiting=[pscustomobject]@{HasExited=$true;ExitCode=7;ExitTime=[DateTime]::UtcNow}
        $exiting | Add-Member ScriptMethod WaitForExit { param($Milliseconds) return $false }
        $exitRecord=[pscustomobject]@{ExitCode=$null;ExitedUtc=$null;ObservedExitUtc=$null}
        $exitHandles=@([pscustomobject]@{Process=$exiting;Record=$exitRecord})
        Update-V2ProcessExits $exitHandles
        Assert ($null -eq $exitRecord.ExitCode) 'automatic wait-based exit policy changed'
        Update-V2ProcessExits $exitHandles -IncludeExiting
        Assert ($exitRecord.ExitCode -eq 7 -and $null -eq $exitRecord.ExitedUtc) 'inspect early code was lost or nonsignaled timestamp became a bound'
        $phaseProofs=[Collections.Generic.List[object]]::new()
        foreach($case in 'idle','all-at-once-aged','aged-begin','timeout-settled','missing-cleanup','stuck-cleanup','close-stuck','unresolved','rewrite','exit-final','exit-late-phase','worker7','missing-ordinal','ready-dies-before-anchor') {
            $originalUpdate=${function:Update-V2Processes}; $originalReader=${function:Read-V2StartReceipt}
            $proof=@{Case=$case;Stage=0;Session=[guid]::NewGuid().ToString('D');Id=[guid]::NewGuid().ToString('D')}
            try {
                Set-Item Function:Update-V2Processes -Value {
                    param($Job,$Records,$Handles,[switch]$Fast)
                    & $originalUpdate $Job $Records $Handles -Fast:$Fast
                    if ($Fast) { return }
                    $time=[DateTimeOffset]::UtcNow
                    $held=$Handles | Where-Object { $_.Record.Pid -eq $process.Id } | Select-Object -First 1
                    $done=Join-Path $evidence 'fixture-done'
                    if (!$result.StartReceipt) {
                        $ready=@{schema='mapartrunner-inspect-ready-v1';launchId=$launchId;pid=$process.Id;startedUtc=$held.Record.StartedUtc
                            scenario='grounded-two-lane';mode='inspect';sessionId=$proof.Session;event='INSPECT_WORLD_BOUND_COMMANDS_READY'}
                        [IO.File]::WriteAllText((Join-Path $evidence 'test-start.json'),($ready|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                        return
                    }
                    if (!$proof.ContainsKey('ReadyUtc')) { $proof.ReadyUtc=$time }
                    $age=($time-$proof.ReadyUtc).TotalMilliseconds
                    function Publish-Phase([string]$Suffix,$Occurrence,$Deadline,[string]$Verdict,[string]$Cleanup,[int]$Ordinal=1) {
                        $directory=Join-Path $repo "v2/fabric/build/scenario-results/two-lane-$($proof.Session)/inspect-operations/op-$Ordinal-$($proof.Id)"
                        New-Item -ItemType Directory -Path $directory -Force | Out-Null
                        $phaseName=@{begin='WORK';cleanup='CLEANUP';end='SETTLED'}[$Suffix]
                        $data=@{schema='mapartrunner-inspect-operation-v1';launchId=$launchId;pid=$process.Id;startedUtc=$result.StartReceipt.startedUtc
                            mode='inspect';sessionId=$proof.Session;ordinal=$Ordinal;operationId=$proof.Id;command=$(if($proof.Case -ceq 'close-stuck'){'CLOSE'}else{'RUN'});phase=$phaseName
                            phaseStartedUtc=$Occurrence.ToString('o');phaseDeadlineUtc=$(if($null -ne $Deadline){$Deadline.ToString('o')}else{$null})
                            evidenceDir=$directory;verdict=$(if($Verdict){$Verdict}else{$null});cleanupStatus=$(if($Cleanup){$Cleanup}else{$null})}
                        $path=Join-Path $evidence "inspect-op-$Ordinal.$Suffix.json"
                        Assert (!(Test-Path -LiteralPath $path)) 'fixture attempted phase replacement'
                        [IO.File]::WriteAllText($path,($data|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    }
                    if ($proof.Case -ceq 'idle') { if($age -ge 1600){Set-Content $done 'done'}; return }
                    if ($proof.Case -cin 'all-at-once-aged','aged-begin') {
                        if ($age -ge 1600 -and !$proof.Stage) {
                            $start=$proof.ReadyUtc
                            Publish-Phase begin $start $start.AddMilliseconds(400) '' ''
                            if ($proof.Case -ceq 'all-at-once-aged') {
                                Publish-Phase cleanup $start.AddMilliseconds(30) $start.AddMilliseconds(1000) 'SUCCESS' ''
                                Publish-Phase end $start.AddMilliseconds(50) $null 'FAILURE' 'SETTLED'
                                Set-Content $done 'done'
                            }
                            $proof.Stage=1
                        } elseif($proof.Stage) {
                            $proof.AfterPublicationSamples++
                        }; return
                    }
                    if ($proof.Case -cin 'exit-final','exit-late-phase') {
                        if ($age -ge 300) {Set-Content $done 'done'}
                        if ($held.Process.HasExited -and !$proof.Stage) {
                            $start=$proof.ReadyUtc
                            Publish-Phase begin $start $start.AddMilliseconds(400) '' ''
                            Publish-Phase cleanup $start.AddMilliseconds(20) $start.AddMilliseconds(1000) 'SUCCESS' ''
                            Publish-Phase end $(if($proof.Case -ceq 'exit-late-phase'){$time}else{$start.AddMilliseconds(30)}) $null 'SUCCESS' 'SETTLED'
                            $proof.Stage=1
                        }; return
                    }
                    if (!$proof.Stage) {
                        $proof.Start=$time; $proof.Stage=1
                        if ($proof.Case -ceq 'missing-ordinal') { Publish-Phase begin $time $time.AddMilliseconds(400) '' '' 2 }
                        else { Publish-Phase begin $time $time.AddMilliseconds($(if($proof.Case -ceq 'close-stuck'){1000}else{400})) '' '' }
                        if ($proof.Case -ceq 'worker7') { Set-Content $done 'done' }
                    }
                    if ($proof.Case -ceq 'rewrite' -and $inspectState.Active) {
                        Add-Content -LiteralPath (Join-Path $evidence 'inspect-op-1.begin.json') ' '; return
                    }
                    if ($proof.Case -ceq 'missing-ordinal') {if($age -ge 1600){Set-Content $done 'done'};return}
                    if ($proof.Case -cin 'missing-cleanup','close-stuck','worker7') { return }
                    $workAge=($time-$proof.Start).TotalMilliseconds
                    if ($proof.Stage -eq 1 -and $workAge -ge 400) {
                        Publish-Phase cleanup $time $proof.Start.AddMilliseconds(1400) 'TIMEOUT' ''
                        $proof.Stage=2
                    } elseif ($proof.Stage -eq 2 -and $proof.Case -cne 'stuck-cleanup') {
                        Publish-Phase end $time $null 'TIMEOUT' $(if($proof.Case -ceq 'unresolved'){'UNRESOLVED'}else{'SETTLED'})
                        $proof.Stage=3; if($proof.Case -cne 'unresolved'){Set-Content $done 'done'}
                    }
                }
                $exit=$(if($case -ceq 'worker7'){7}else{0})
                $worker=[scriptblock]::Create('$fixtureClock=[Diagnostics.Stopwatch]::StartNew(); while(!(Test-Path (Join-Path $env:MAPART_TEST_LAUNCH_DIR "fixture-done"))) { if($fixtureClock.ElapsedMilliseconds -gt 8000){exit 99}; Start-Sleep -Milliseconds 10 }; exit '+$exit)
                if ($case -ceq 'ready-dies-before-anchor') {
                    Set-Item Function:Read-V2StartReceipt -Value {
                        param($Evidence,$LaunchId,$Request,$Handles,$OwnedPids,$WorkerPid,$JavaExecutable,[switch]$RequireGame)
                        $checked=& $originalReader @PSBoundParameters
                        if ($checked.Status -ceq 'Accepted') {
                            Set-Content -LiteralPath (Join-Path $Evidence 'fixture-done') 'done'
                            $held=$Handles | Where-Object {$_.Record.Pid -eq $WorkerPid} | Select-Object -First 1
                            Assert ($held.Process.WaitForExit(3000)) 'live READY race fixture did not exit'
                        }
                        $checked
                    }
                }
                $outcome=Invoke-V2Supervisor -Worker $worker -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 100 -StartupMilliseconds 3000 -InternalInspectTest @{WorkMilliseconds=400;CleanupMilliseconds=1000}
            } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate; Set-Item Function:Read-V2StartReceipt -Value $originalReader }
            Assert-V2Settled $outcome
            $expected=$(if($case -cin 'aged-begin','missing-cleanup','stuck-cleanup','close-stuck'){124}elseif($case -cin 'unresolved','rewrite','exit-late-phase','ready-dies-before-anchor'){125}elseif($case -ceq 'worker7'){7}else{0})
            Assert ($outcome.ExitCode -eq $expected) "inspect $case public exit mismatch: $($outcome.ExitCode) $($outcome.SupervisorError) $($outcome.InspectEscalation)"
            Assert (@($outcome.Processes|Where-Object {$null -eq $_.ExitCode}).Count -eq 0) "inspect $case raw exit missing"
            Assert ((Get-Item -LiteralPath (Join-Path $testRoot 'supervisor.lock')).Length -eq 0) "inspect $case settled lock retained"
            if($case -cin 'aged-begin','missing-cleanup','stuck-cleanup','close-stuck','unresolved','rewrite') {Assert ($outcome.TerminationRequested -and $outcome.InspectIncomplete -and $outcome.WorkerExitCode -eq 125) "inspect $case did not use owned Stop/raw125"}
            if($case -cin 'all-at-once-aged','timeout-settled','exit-final') {Assert ($outcome.InspectOperations.Count -eq 1 -and !$outcome.TimedOut -and !$outcome.InspectIncomplete) "inspect $case settlement lost"}
            if($case -ceq 'missing-ordinal') {Assert ($outcome.InspectOperations.Count -eq 0 -and !$outcome.TimedOut) 'consumer skipped failed/missing ordinal'}
            if($case -ceq 'aged-begin') {Assert (!$proof.AfterPublicationSamples -and $outcome.TimedOut) 'delayed BEGIN received a new allowance'}
            if($case -ceq 'ready-dies-before-anchor') {Assert (!$outcome.StartReceipt -and !$outcome.StartIdentity -and $null -eq $outcome.StartupAcceptedElapsedMilliseconds) 'first READY was latched after original process death'}
            $phaseProofs.Add(@{Case=$case;Outcome=$outcome;Fixture=$proof})
        }
        $phaseProofs|ConvertTo-Json -Depth 14|Set-Content (Join-Path $testRoot 'inspect-phase-proofs.json')
    }
    Run 'inspect reconciles records published after Missing at exit and cap decisions' {
        $decisionProofs=[Collections.Generic.List[object]]::new(); $decisionFailures=[Collections.Generic.List[string]]::new()
        foreach($case in 'active-exit','idle-exit','timely-cap','late-cap') {
            $originalUpdate=${function:Update-V2Processes}; $originalPhaseReader=${function:Read-V2InspectOperationReceipt}
            $proof=@{Case=$case;Seeded=$false;Triggered=$false;Session=[guid]::NewGuid().ToString('D');Id=[guid]::NewGuid().ToString('D')}
            try {
                Set-Item Function:Update-V2Processes -Value {
                    param($Job,$Records,$Handles,[switch]$Fast)
                    & $originalUpdate $Job $Records $Handles -Fast:$Fast
                    if ($Fast -or $result.StartReceipt) { return }
                    $held=$Handles|Where-Object {$_.Record.Pid -eq $process.Id}|Select-Object -First 1
                    $ready=@{schema='mapartrunner-inspect-ready-v1';launchId=$launchId;pid=$held.Record.Pid;startedUtc=$held.Record.StartedUtc
                        scenario='grounded-two-lane';mode='inspect';sessionId=$proof.Session;event='INSPECT_WORLD_BOUND_COMMANDS_READY'}
                    [IO.File]::WriteAllText((Join-Path $evidence 'test-start.json'),($ready|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                }
                Set-Item Function:Read-V2InspectOperationReceipt -Value {
                    param($Evidence,$WorkingDirectory,$StartReceipt,$StartIdentity,$Held,$Ordinal,$Phase,$Previous,$PreviousSettledUtc,
                        [switch]$RequireGame,$InternalInspectTest,$Clock)
                    function Publish-DecisionPhase([string]$Suffix,$Occurrence,$Deadline) {
                        $directory=Join-Path $repo "v2/fabric/build/scenario-results/two-lane-$($proof.Session)/inspect-operations/op-1-$($proof.Id)"
                        New-Item -ItemType Directory -Path $directory -Force|Out-Null
                        $data=@{schema='mapartrunner-inspect-operation-v1';launchId=$StartReceipt.launchId;pid=$StartReceipt.pid;startedUtc=$StartReceipt.startedUtc
                            mode='inspect';sessionId=$proof.Session;ordinal=1;operationId=$proof.Id;command='RUN'
                            phase=@{begin='WORK';cleanup='CLEANUP';end='SETTLED'}[$Suffix];phaseStartedUtc=$Occurrence.ToString('o')
                            phaseDeadlineUtc=$(if($null -ne $Deadline){$Deadline.ToString('o')}else{$null});evidenceDir=$directory
                            verdict=$(if($Suffix -ceq 'begin'){$null}else{'SUCCESS'});cleanupStatus=$(if($Suffix -ceq 'end'){'SETTLED'}else{$null})}
                        $path=Join-Path $Evidence "inspect-op-1.$Suffix.json"
                        Assert (!(Test-Path -LiteralPath $path)) 'decision fixture attempted receipt replacement'
                        [IO.File]::WriteAllText($path,($data|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                    }
                    if (!$proof.Seeded -and $Ordinal -eq 1 -and $Phase -ceq 'begin' -and $proof.Case -cne 'idle-exit') {
                        $proof.Start=[DateTimeOffset]::UtcNow; $proof.Seeded=$true
                        Publish-DecisionPhase begin $proof.Start $proof.Start.AddMilliseconds(1000)
                        if ($proof.Case -cin 'timely-cap','late-cap') { Publish-DecisionPhase cleanup $proof.Start $proof.Start.AddMilliseconds(2000) }
                    }
                    $checked=& $originalPhaseReader @PSBoundParameters
                    $expectedMissing=$(if($proof.Case -ceq 'idle-exit'){'begin'}elseif($proof.Case -ceq 'active-exit'){'cleanup'}else{'end'})
                    if (!$proof.Triggered -and $Ordinal -eq 1 -and $Phase -ceq $expectedMissing -and $checked.Status -ceq 'Missing') {
                        # Force publication after the real reader returned Missing; do not let the inventory hook publish it.
                        $proof.Triggered=$true; $proof.MissingPhase=$Phase; $proof.MissingElapsed=$Clock.Elapsed.TotalMilliseconds
                        $proof.ActiveAtMissing=$State.Active; $proof.OriginalCap=$State.CapElapsed
                        if ($proof.Case -ceq 'idle-exit') {
                            $proof.Start=[DateTimeOffset]::UtcNow
                            Publish-DecisionPhase begin $proof.Start $proof.Start.AddMilliseconds(1000)
                        }
                        if ($proof.Case -cin 'active-exit','idle-exit') {
                            $time=[DateTimeOffset]::UtcNow
                            Publish-DecisionPhase cleanup $time $time.AddMilliseconds(2000)
                            Publish-DecisionPhase end ([DateTimeOffset]::UtcNow) $null
                            Set-Content -LiteralPath (Join-Path $Evidence 'decision-done') 'done'
                            Assert ($Held.Process.WaitForExit(3000)) 'decision fixture original worker did not exit'
                            $proof.ActualExitUtc=$Held.Process.ExitTime.ToUniversalTime().ToString('o')
                        } else {
                            Assert ($Clock.Elapsed.TotalMilliseconds -lt $State.CapElapsed) 'cap fixture reached Missing after its cap'
                            if ($proof.Case -ceq 'timely-cap') { Publish-DecisionPhase end ([DateTimeOffset]::UtcNow) $null }
                            $remaining=[Math]::Max(0,[Math]::Ceiling($State.CapElapsed-$Clock.Elapsed.TotalMilliseconds)+20)
                            Start-Sleep -Milliseconds $remaining
                            if ($proof.Case -ceq 'late-cap') { Publish-DecisionPhase end ([DateTimeOffset]::UtcNow) $null }
                            $proof.DecisionElapsed=$Clock.Elapsed.TotalMilliseconds
                        }
                    }
                    if ($proof.Case -ceq 'timely-cap' -and $proof.Triggered -and $Ordinal -eq 1 -and $Phase -ceq 'end' -and $checked.Status -ceq 'Accepted') {
                        Set-Content -LiteralPath (Join-Path $Evidence 'decision-done') 'done'
                    }
                    $checked
                }
                $worker={ $bounded=[Diagnostics.Stopwatch]::StartNew(); while(!(Test-Path (Join-Path $env:MAPART_TEST_LAUNCH_DIR 'decision-done'))) { if($bounded.ElapsedMilliseconds -gt 8000){exit 99}; Start-Sleep -Milliseconds 10 }; exit 0 }
                $outcome=Invoke-V2Supervisor -Worker $worker -Request ([pscustomobject]@{Mode='inspect';Scenario='grounded-two-lane'}) -EvidenceRoot $testRoot -WorkingDirectory $repo -TimeoutMilliseconds 100 -StartupMilliseconds 3000 -InternalInspectTest @{WorkMilliseconds=1000;CleanupMilliseconds=2000}
            } finally { Set-Item Function:Update-V2Processes -Value $originalUpdate; Set-Item Function:Read-V2InspectOperationReceipt -Value $originalPhaseReader }
            Assert-V2Settled $outcome
            $settled=$proof.Triggered -and $outcome.InspectOperations.Count -eq 1 -and !$outcome.InspectIncomplete -and !$outcome.TimedOut -and $outcome.ExitCode -eq 0 -and $outcome.WorkerExitCode -eq 0
            $passed=$(if($case -ceq 'late-cap'){$proof.Triggered -and $outcome.ExitCode -eq 125 -and $outcome.InspectIncomplete -and $outcome.InspectOperations.Count -eq 0 -and $outcome.InspectEscalation -like '*Invalid END*'}else{$settled})
            $passed=$passed -and (Get-Item -LiteralPath (Join-Path $testRoot 'supervisor.lock')).Length -eq 0 -and @($outcome.Processes|Where-Object {$null -eq $_.ExitCode}).Count -eq 0
            $decisionProofs.Add(@{Case=$case;Passed=$passed;Fixture=$proof;Outcome=$outcome})
            if (!$passed) { $decisionFailures.Add("${case}: public=$($outcome.ExitCode), raw=$($outcome.WorkerExitCode), ENDs=$($outcome.InspectOperations.Count), incomplete=$($outcome.InspectIncomplete), timeout=$($outcome.TimedOut), error=$($outcome.SupervisorError), escalation=$($outcome.InspectEscalation)") }
        }
        $decisionProofs|ConvertTo-Json -Depth 14|Set-Content (Join-Path $testRoot 'inspect-decision-proofs.json')
        Assert (!$decisionFailures.Count) ($decisionFailures -join '; ')
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
