[CmdletBinding()]
param(
    [ValidateSet('all','grounded-two-lane','grounded-traversal',IgnoreCase=$false)][string]$Scenario = 'all',
    [ValidateSet('1','2',IgnoreCase=$false)][string]$Repeat = '2',
    [ValidateScript({ ![string]::IsNullOrWhiteSpace($_) })][string]$JavaHome,
    [switch]$EulaAccepted,
    [switch]$WrongExpectedBlock
)

function New-V2Request {
    param([ValidateSet('all','grounded-two-lane','grounded-traversal',IgnoreCase=$false)][string]$Scenario = 'all',
        [ValidateSet('1','2',IgnoreCase=$false)][string]$Repeat = '2', [switch]$EulaAccepted,
        [string]$EulaEnvironment = $env:MAPART_TEST_EULA_ACCEPTED, [switch]$WrongExpectedBlock)
    if ($WrongExpectedBlock -and $Scenario -ne 'grounded-two-lane') { throw '-WrongExpectedBlock requires -Scenario grounded-two-lane.' }
    if (!$EulaAccepted -and $EulaEnvironment -cne '1') {
        throw 'Accept the Minecraft EULA for these disposable instances, then pass -EulaAccepted or set process MAPART_TEST_EULA_ACCEPTED=1.'
    }
    $arguments = @('-p','v2',':fabric:runClientGameTest','-PmapartTestEula=true','--no-daemon','--console=plain')
    # Omit defaults so the public default invocation exercises the source defaults.
    if ($Scenario -ne 'all') { $arguments += "-Pmapart.test.scenario=$Scenario" }
    if ($Repeat -ne 2) { $arguments += "-Pmapart.test.repeat=$Repeat" }
    [pscustomobject]@{ Scenario=$Scenario; Repeat=[int]$Repeat; WrongExpectedBlock=[bool]$WrongExpectedBlock
        EulaProvenance=$(if ($EulaAccepted) {'operator-switch'} else {'process MAPART_TEST_EULA_ACCEPTED=1'}); Arguments=$arguments }
}

function New-V2ProcessInfo {
    param([string]$Executable, [string]$WorkingDirectory, [string]$Command, [hashtable]$Environment = @{})
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WorkingDirectory = $WorkingDirectory
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in @('-NoProfile','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command)))) { $info.ArgumentList.Add($arg) }
    foreach ($key in $Environment.Keys) {
        if ($null -eq $Environment[$key]) { [void]$info.Environment.Remove($key) } else { $info.Environment[$key] = $Environment[$key] }
    }
    $info
}

function Get-V2ToolVersion {
    param([string]$Executable)
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.ArgumentList.Add('-version')
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        if (!$process.Start()) { throw "Could not probe $Executable" }
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(5000)) { $process.Kill($true); if (!$process.WaitForExit(10000)) { throw "JDK probe cleanup unresolved: $Executable" }; throw "JDK probe timed out: $Executable" }
        if ($process.ExitCode -ne 0) { throw "Version probe failed: $Executable" }
        ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Trim()
    } finally { $process.Dispose() }
}

function Find-V2Jdk {
    param([ValidateScript({ ![string]::IsNullOrWhiteSpace($_) })][string]$JavaHome, [string[]]$Candidates, [scriptblock]$Probe = ${function:Get-V2ToolVersion})
    $explicitHome=$PSBoundParameters.ContainsKey('JavaHome')
    if ($explicitHome) { $Candidates=@($JavaHome) }
    elseif (!$PSBoundParameters.ContainsKey('Candidates')) {
        $Candidates=@($env:JAVA_HOME)
        foreach ($tool in 'javac.exe','java.exe') {
            foreach ($command in @(Get-Command $tool -All -ErrorAction SilentlyContinue)) { $Candidates += Split-Path (Split-Path $command.Source) }
        }
        $roots=@((Join-Path $env:USERPROFILE '.jdks'))
        foreach ($programRoot in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
            if ($programRoot) { foreach ($vendor in 'Java','Eclipse Adoptium','Microsoft') { $roots += Join-Path $programRoot $vendor } }
        }
        foreach ($root in $roots) {
            if (Test-Path -LiteralPath $root -PathType Container) {
                $Candidates += @(Get-ChildItem -LiteralPath $root -Directory | Where-Object { !($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } | Select-Object -ExpandProperty FullName)
            }
        }
    }
    $rejections=[Collections.Generic.List[string]]::new()
    foreach ($candidate in @($Candidates | Where-Object { $_ } | Select-Object -Unique)) {
        try {
            $resolved=(Resolve-Path -LiteralPath $candidate -ErrorAction Stop).ProviderPath
            $java=Join-Path $resolved 'bin/java.exe'; $javac=Join-Path $resolved 'bin/javac.exe'
            if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'both bin/java.exe and bin/javac.exe are required' }
            $javaVersion=& $Probe $java; $javacVersion=& $Probe $javac
            if ($javaVersion -notmatch '(?m)^(?:openjdk|java) version "21(?:\.|"|\-)' -or $javacVersion -notmatch '(?m)^javac 21(?:\.|\s|$|\-)') { throw 'java and javac must both be version21' }
            return [pscustomobject]@{ Home=$resolved; JavaVersion=$javaVersion; JavacVersion=$javacVersion; RejectedCandidates=@($rejections) }
        } catch {
            if ($_.Exception.Message -like '*cleanup unresolved*') { throw }
            $rejections.Add("${candidate}: $($_.Exception.Message)")
            if ($explicitHome) { throw "Invalid explicit -JavaHome '${JavaHome}': $($_.Exception.Message)" }
        }
    }
    throw "No qualifying JDK21 found. Supply -JavaHome with both version21 tools. $($rejections -join '; ')"
}

function New-V2Environment {
    param($Jdk, [switch]$WrongExpectedBlock)
    $options = $env:JAVA_TOOL_OPTIONS
    $options = "$options -Dfabric.client.gametest.modid=mapart-test -Djava.awt.headless=true".Trim()
    @{
        JAVA_HOME=$Jdk.Home; PATH="$(Join-Path $Jdk.Home bin);$env:PATH"; JAVA_TOOL_OPTIONS=$options
        MAPART_PROBE_WRONG_EXPECTED_BLOCK=$null; MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS=$null
        MAPART_STATE_FAULT_FORCE_TARGET_RELOAD=$null; MAPART_TWO_LANE_SUPPORT_CASE=$null
        MAPART_TEST_LAUNCH_ID=$null; MAPART_TEST_LAUNCH_DIR=$null
        MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK=$(if ($WrongExpectedBlock) {'1'} else {$null})
    }
}

function Resolve-V2GameEnvironment {
    param($Jdk, $Request, [ValidateSet('ordinary','controlled',IgnoreCase=$false)][string]$InternalSupportCase='ordinary')
    $environment=New-V2Environment -Jdk $Jdk -WrongExpectedBlock:($Request.WrongExpectedBlock)
    if ($InternalSupportCase -ceq 'controlled') { $environment.MAPART_TWO_LANE_SUPPORT_CASE='controlled' }
    $environment
}

function Get-V2TraversalTimeoutMilliseconds {
    param([object[]]$RunTicks, [int]$Repeat)
    if ($Repeat -notin 1,2 -or $RunTicks.Count -ne 7) { throw 'Expected seven traversal budgets and Repeat1/2.' }
    [long]$sum=0
    [long]$limit=[Math]::Floor(([int]::MaxValue-600000)/(50*$Repeat))
    foreach ($raw in $RunTicks) {
        [long]$ticks=0
        if ($raw -isnot [string] -or $raw -cnotmatch '^[1-9][0-9]*$' -or
            ![long]::TryParse($raw,[ref]$ticks) -or $ticks -gt [int]::MaxValue -or $ticks -gt $limit-$sum) {
            throw 'Traversal runTicks must be positive integers within the checked invocation allowance.'
        }
        $sum+=$ticks
    }
    [int](600000+50*$Repeat*$sum)
}

function Get-V2TimeoutMilliseconds {
    param([string]$Scenario, [int]$Repeat)
    if ($Repeat -notin 1,2) { throw 'Expected Repeat1/2.' }
    if ($Scenario -ceq 'grounded-two-lane') { return 600000 }
    if ($Scenario -cnotin 'all','grounded-traversal') { throw 'Unknown timeout scenario.' }
    $repo=Split-Path $PSScriptRoot
    $ticks=@(foreach ($relative in @(
        'v2/test-fixtures/traversal-resume-21/definition.json',
        'v2/test-fixtures/traversal-reverse-odd/definition.json',
        'v2/test-fixtures/traversal-reverse-even/definition.json',
        'v2/test-fixtures/traversal-reverse-multi/definition.json',
        'v2/test-fixtures/traversal-narrow/definition.json',
        'v2/test-fixtures/traversal-sparse-partial/definition.json',
        'v2/test-fixtures/traversal-scale-128/definition.json')) {
        $path=Assert-V2ManagedPath (Join-Path $repo $relative) $repo
        $document=[Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText($path))
        try {
            $budgets=@($document.RootElement.EnumerateObject() | Where-Object Name -CEQ 'budgets')
            if ($budgets.Count -ne 1) { throw "Invalid budgets: $relative" }
            $value=@($budgets[0].Value.EnumerateObject() | Where-Object Name -CEQ 'runTicks')
            if ($value.Count -ne 1 -or $value[0].Value.ValueKind -ne [Text.Json.JsonValueKind]::Number) { throw "Invalid runTicks: $relative" }
            $value[0].Value.GetRawText()
        } finally { $document.Dispose() }
    })
    Get-V2TraversalTimeoutMilliseconds $ticks $Repeat
}

function Read-V2StartReceipt {
    param([string]$Evidence, [string]$LaunchId, $Request, [object[]]$Handles, [int[]]$OwnedPids,
        [int]$WorkerPid, [string]$JavaExecutable, [switch]$RequireGame)
    $document=$null; $stream=$null
    try {
        $uuid=[guid]::Empty
        if (![guid]::TryParseExact($LaunchId,'D',[ref]$uuid) -or $uuid.ToString('D') -cne $LaunchId -or
            ![IO.Path]::IsPathFullyQualified($Evidence) -or (Split-Path $Evidence -Leaf) -cne "launch-$LaunchId") { throw 'Invalid owned launch identity/directory.' }
        [void](Assert-V2ManagedPath $Evidence (Split-Path $Evidence))
        $path=Join-Path $Evidence 'test-start.json'
        $item=Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        if (!$item) { return [pscustomobject]@{Status='Missing';Reason=$null} }
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Receipt must be an ordinary owned file.' }
        $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
        if ($stream.Length -lt 1 -or $stream.Length -gt 4096) { throw 'Receipt exceeds1..4096 bytes.' }
        $bytes=[byte[]]::new([int]$stream.Length); $stream.ReadExactly($bytes)
        $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
        $document=[Text.Json.JsonDocument]::Parse($text)
        $root=$document.RootElement
        if ($root.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw 'Receipt is not an object.' }
        $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $expected=@('schema','launchId','pid','startedUtc','scenario','repeat','provider','event')
        foreach ($property in $root.EnumerateObject()) {
            if ($expected -cnotcontains $property.Name -or !$names.Add($property.Name)) { throw 'Receipt has unknown or duplicate fields.' }
        }
        if ($names.Count -ne 8) { throw 'Receipt fields are incomplete.' }
        $strings=@{}
        foreach ($name in 'schema','launchId','startedUtc','scenario','provider','event') {
            $value=$root.GetProperty($name)
            if ($value.ValueKind -ne [Text.Json.JsonValueKind]::String) { throw "Receipt $name is not a string." }
            $strings[$name]=$value.GetString()
        }
        [int]$receiptPid=0; $pidValue=$root.GetProperty('pid'); $repeatValue=$root.GetProperty('repeat')
        if ($pidValue.ValueKind -ne [Text.Json.JsonValueKind]::Number -or $pidValue.GetRawText() -cnotmatch '^[1-9][0-9]*$' -or !$pidValue.TryGetInt32([ref]$receiptPid)) { throw 'Receipt PID is not a positive integer.' }
        if ($repeatValue.ValueKind -ne [Text.Json.JsonValueKind]::Number -or $repeatValue.GetRawText() -cnotin '1','2' -or
            $repeatValue.GetInt32() -ne $Request.Repeat) { throw 'Receipt repeat differs from request.' }
        if ($strings.schema -cne 'mapartrunner-test-start-v1' -or $strings.event -cne 'SELECTED_PROVIDER_RUNTEST_ENTERED' -or
            $strings.launchId -cne $LaunchId -or $strings.scenario -cne $Request.Scenario) { throw 'Receipt schema/event/challenge/scenario differs from request.' }
        $providers=@('environment-probe','single-lane','schematic-import','grounded-two-lane','grounded-traversal')
        if ($strings.provider -cnotin $providers -or ($Request.Scenario -cne 'all' -and $strings.provider -cne $Request.Scenario)) { throw 'Receipt provider was not selected.' }
        $started=[DateTimeOffset]::MinValue
        if ($strings.startedUtc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$' -or
            ![DateTimeOffset]::TryParse($strings.startedUtc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal,[ref]$started)) { throw 'Invalid receipt process creation time.' }
        if ($OwnedPids -notcontains $receiptPid) { throw 'Receipt process is foreign to the current owned job or already dead.' }
        $candidates=@($Handles | Where-Object { $_.Record.Pid -eq $receiptPid })
        if (!$candidates.Count) { return [pscustomobject]@{Status='Pending';Reason='Awaiting normal owned process inventory.'} }
        $held=@($candidates | Where-Object { [Math]::Abs(([DateTimeOffset]::Parse($_.Record.StartedUtc)-$started).TotalMilliseconds) -le 10 })
        if ($held.Count -ne 1) { throw 'Receipt PID creation identity differs from held process.' }
        $held=$held[0]; $record=$held.Record
        if ($null -ne $record.ExitCode -or $held.Process.HasExited -or
            [Math]::Abs(($held.Process.StartTime.ToUniversalTime()-$started.UtcDateTime).TotalMilliseconds) -gt 10) { throw 'Receipt held process is dead or its creation identity changed.' }
        if ($RequireGame) {
            if (!$record.IsGame -or !$record.IdentityVerified -or !$JavaExecutable -or
                ![IO.Path]::GetFullPath($record.Executable).Equals([IO.Path]::GetFullPath($JavaExecutable),[StringComparison]::OrdinalIgnoreCase)) { throw 'Receipt is not the configured JDK native game.' }
        } elseif ($record.Pid -ne $WorkerPid -or $record.Ownership -cne 'directly started gated worker') { throw 'Dummy receipt is not the directly held worker.' }
        $receipt=[pscustomobject]@{schema=$strings.schema;launchId=$strings.launchId;pid=$receiptPid;startedUtc=$strings.startedUtc
            scenario=$strings.scenario;repeat=$repeatValue.GetInt32();provider=$strings.provider;event=$strings.event}
        [pscustomobject]@{Status='Accepted';Reason=$null;Receipt=$receipt
            Identity=[pscustomobject]@{Pid=$record.Pid;StartedUtc=$record.StartedUtc;Executable=$record.Executable;IsGame=$record.IsGame;Ownership=$record.Ownership}}
    } catch { [pscustomobject]@{Status='Rejected';Reason=$_.Exception.Message} }
    finally { if ($document) { $document.Dispose() }; if ($stream) { $stream.Dispose() } }
}

function Set-V2ObservedFailure {
    param($Result, [object[]]$Records)
    if ($Result.TerminationRequested) { return }
    $failures=@($Records | Where-Object { ($_.Pid -eq $Result.WorkerPid -or $_.IsGame) -and $null -ne $_.ExitCode -and $_.ExitCode -ne 0 } |
        Sort-Object @{Expression={ if ($_.ExitedUtc) {$_.ExitedUtc} else {$_.ObservedExitUtc} }},StartedUtc,Pid)
    foreach ($failure in $failures) {
        if (!@($Result.ObservedFailures | Where-Object { $_.Pid -eq $failure.Pid -and $_.StartedUtc -ceq $failure.StartedUtc }).Count) {
            $Result.ObservedFailures.Add([pscustomobject]@{Pid=$failure.Pid;StartedUtc=$failure.StartedUtc;ExitCode=$failure.ExitCode
                IsGame=$failure.IsGame;ExitedUtc=$failure.ExitedUtc;ObservedExitUtc=$failure.ObservedExitUtc
                Ordering=$(if($failure.ExitedUtc){'exit time, creation time, PID'}else{'observed refresh time (exit time unavailable), creation time, PID'})})
        }
    }
    if (!$Result.PrimaryFailure -and $failures.Count) { $Result.PrimaryFailure=$Result.ObservedFailures[0] }
    if ($Result.PrimaryFailure) { $Result.ExitCode=$Result.PrimaryFailure.ExitCode }
}

function Initialize-V2JobType {
    if ('V2OwnedJob' -as [type]) { return }
    # A gated worker enters this job before running; Windows keeps all descendants owned after root exit.
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public sealed class V2OwnedJob : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct Basic {
        public long ProcessTime, JobTime; public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet; public uint ActiveLimit;
        public UIntPtr Affinity; public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct Io { public ulong a,b,c,d,e,f; }
    [StructLayout(LayoutKind.Sequential)] struct Extended {
        public Basic Basic; public Io Io; public UIntPtr ProcessMemory, JobMemory, PeakProcess, PeakJob;
    }
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attr, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int kind, ref Extended info, int size);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job, int kind, IntPtr info, int size, out int length);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr job, uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    IntPtr handle;
    public V2OwnedJob() {
        handle=CreateJobObject(IntPtr.Zero,null);
        if(handle==IntPtr.Zero) throw new Win32Exception();
        var limits=new Extended(); limits.Basic.Flags=0x2000;
        if(!SetInformationJobObject(handle,9,ref limits,Marshal.SizeOf<Extended>())) { CloseHandle(handle); handle=IntPtr.Zero; throw new Win32Exception(); }
    }
    public void Assign(IntPtr process) { if(!AssignProcessToJobObject(handle,process)) throw new Win32Exception(); }
    public int[] Pids() {
        int size=65536; IntPtr buffer=Marshal.AllocHGlobal(size);
        try {
            int length;
            if(!QueryInformationJobObject(handle,3,buffer,size,out length)) throw new Win32Exception();
            int count=Marshal.ReadInt32(buffer,4); var ids=new int[count];
            for(int i=0;i<count;i++) ids[i]=checked((int)Marshal.ReadIntPtr(buffer,8+i*IntPtr.Size).ToInt64());
            return ids;
        } finally { Marshal.FreeHGlobal(buffer); }
    }
    public void Stop() { if(!TerminateJobObject(handle,125)) throw new Win32Exception(); }
    public void Dispose() { if(handle!=IntPtr.Zero) { CloseHandle(handle); handle=IntPtr.Zero; } }
}
'@
}

function Assert-V2ManagedPath {
    param([string]$Path, [string]$Owner)
    $full=[IO.Path]::GetFullPath($Path); $base=[IO.Path]::GetFullPath($Owner).TrimEnd('\','/')
    if ($full -ne $base -and !$full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Path escapes managed owner: $Path" }
    $current=$full
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            if ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point forbidden in managed path: $current" }
        }
        $current=Split-Path $current
    }
    $full
}

function Copy-V2Evidence {
    param([string]$Source, [string]$Destination, [string]$SourceOwner, [string]$DestinationOwner)
    [void](Assert-V2ManagedPath $Destination $DestinationOwner)
    if (!(Test-Path -LiteralPath $Source)) { return }
    $item=Get-Item -LiteralPath $Source -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        [void](Assert-V2ManagedPath (Split-Path $Source) $SourceOwner)
        # Scenario filesystem guard fixtures deliberately retain junctions; archive their identity, never targets.
        return [pscustomobject]@{ Source=$Source; Destination=$Destination; LinkTarget=$item.LinkTarget; Attributes=[int]$item.Attributes }
    }
    [void](Assert-V2ManagedPath $Source $SourceOwner)
    if ($item.PSIsContainer) {
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        foreach ($child in Get-ChildItem -LiteralPath $Source -Force) {
            Copy-V2Evidence $child.FullName (Join-Path $Destination $child.Name) $SourceOwner $DestinationOwner
        }
    } else { Copy-Item -LiteralPath $Source -Destination $Destination -ErrorAction Stop }
}

function Get-V2ScenarioInventory {
    param([string]$ScenarioRoot)
    [void](Assert-V2ManagedPath $ScenarioRoot $ScenarioRoot)
    if (Test-Path -LiteralPath $ScenarioRoot) {
        foreach ($item in Get-ChildItem -LiteralPath $ScenarioRoot -Directory -Force) {
            if ($item.Name -match '^(environment-probe|single-lane|lane-import|schematic-import|two-lane|archive-self-check)-') {
                [pscustomobject]@{ Name=$item.Name; CreatedUtc=$item.CreationTimeUtc.ToString('o'); ModifiedUtc=$item.LastWriteTimeUtc.ToString('o'); Attributes=[int]$item.Attributes }
            }
        }
    }
}

function Save-V2Artifacts {
    param([string]$Evidence, [string]$Game, [string]$ScenarioRoot, [object[]]$Before, [string]$Owner)
    [void](Assert-V2ManagedPath $Evidence $Owner)
    $after=@(Get-V2ScenarioInventory $ScenarioRoot)
    @($Before) | ConvertTo-Json -Depth 5 -AsArray | Set-Content (Join-Path $Evidence 'scenarios-before.json')
    $after | ConvertTo-Json -Depth 5 -AsArray | Set-Content (Join-Path $Evidence 'scenarios-after.json')
    $links=[Collections.Generic.List[object]]::new()
    foreach ($folder in 'logs','crash-reports') {
        foreach ($link in @(Copy-V2Evidence (Join-Path $Game $folder) (Join-Path $Evidence $folder) $Game $Evidence)) { $links.Add($link) }
    }
    foreach ($artifact in $after) {
        # UUID directories present before launch are never credited as this invocation's artifacts.
        if (@($Before | Where-Object Name -EQ $artifact.Name).Count) { continue }
        $destination=Join-Path $Evidence ('scenarios/' + $artifact.Name)
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        foreach ($link in @(Copy-V2Evidence (Join-Path $ScenarioRoot $artifact.Name) $destination $ScenarioRoot $Evidence)) { $links.Add($link) }
    }
    $links | ConvertTo-Json -Depth 5 -AsArray | Set-Content (Join-Path $Evidence 'reparse-points.json')
}

function Update-V2Processes {
    param($Job, [Collections.Generic.List[object]]$Records, [Collections.Generic.List[object]]$Handles, [switch]$Fast)
    foreach ($ownedId in $Job.Pids()) {
        if (@($Records | Where-Object { $_.Pid -eq $ownedId -and $null -eq $_.ExitCode }).Count) { continue }
        $process=$null
        try {
            try { $process=[Diagnostics.Process]::GetProcessById($ownedId) }
            catch {
                # Job enumeration can briefly retain a vanished PID; only this lookup failure is harmless.
                if ($_.Exception -is [Management.Automation.MethodInvocationException] -and
                    $_.Exception.InnerException.GetType() -eq [ArgumentException]) { continue }
                throw
            }
            [void]$process.Handle
            $started=$process.StartTime.ToUniversalTime().ToString('o')
            $cim=$null
            if (!$Fast) {
                $cim=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedId" -OperationTimeoutSec 1 -ErrorAction Stop
                if (!$cim -or [Math]::Abs(($cim.CreationDate.ToUniversalTime() - $process.StartTime.ToUniversalTime()).TotalMilliseconds) -gt 10) { throw "Identity unavailable for owned PID $ownedId" }
            }
            $record=[pscustomobject]@{ Pid=$ownedId; ParentPid=$(if ($cim) {[int]$cim.ParentProcessId} else {$null}); StartedUtc=$started
                Executable=$(if ($cim) {$cim.ExecutablePath} else {$process.ProcessName}); CommandLine=$cim.CommandLine; Ownership='gated-worker Windows job membership'
                ExitCode=$null; ExitedUtc=$null; ObservedExitUtc=$null; IdentityVerified=!!$cim
                IsGame=($cim.CommandLine -match 'net\.fabricmc\.(devlaunchinjector\.Main|loader\.impl\.launch\.knot\.KnotClient)') }
            $Records.Add($record); $Handles.Add([pscustomobject]@{ Process=$process; Record=$record })
        } catch {
            if ($process) { $process.Dispose() }
            # A very short lived intermediary can finish between job query and handle capture.
            if ($Job.Pids() -contains $ownedId) { throw }
        }
    }
    $observedUtc=[DateTime]::UtcNow.ToString('o')
    foreach ($held in $Handles) {
        if ($null -eq $held.Record.ExitCode -and $held.Process.WaitForExit(0)) {
            $held.Record.ExitCode=$held.Process.ExitCode; $held.Record.ObservedExitUtc=$observedUtc
            try { $held.Record.ExitedUtc=$held.Process.ExitTime.ToUniversalTime().ToString('o') } catch { }
        }
    }
}

function Invoke-V2Supervisor {
    param([scriptblock]$Worker, [hashtable]$Environment=@{}, [string]$EvidenceRoot,
        [string]$WorkingDirectory, [int]$TimeoutMilliseconds=600000,
        [string]$WorkerExecutable=(Join-Path $PSHOME pwsh.exe), [scriptblock]$Archive={}, $Request=$null,
        [switch]$RequireGame, [ValidateRange(1,2147483647)][int]$StartupMilliseconds=600000,
        [ValidateSet('ordinary','controlled',IgnoreCase=$false)][string]$InternalSupportCase='ordinary')
    $ErrorActionPreference='Stop'
    if ($InternalSupportCase -ceq 'controlled') {
        $canonical=New-V2Request -Scenario grounded-two-lane -Repeat 2 -EulaAccepted
        if (!$RequireGame -or $Request -isnot [pscustomobject] -or $Request.Scenario -isnot [string] -or $Request.Scenario -cne $canonical.Scenario -or
            $Request.Repeat -isnot [int] -or $Request.Repeat -ne 2 -or $Request.WrongExpectedBlock -isnot [bool] -or
            $Request.WrongExpectedBlock -or $Request.EulaProvenance -cnotin 'operator-switch','process MAPART_TEST_EULA_ACCEPTED=1' -or
            @($Request.Arguments).Count -ne $canonical.Arguments.Count) { throw 'Controlled support requires RequireGame and the canonical grounded-two-lane Repeat2 request without WrongExpectedBlock.' }
        for ($i=0; $i -lt $canonical.Arguments.Count; $i++) {
            if ($Request.Arguments[$i] -isnot [string] -or $Request.Arguments[$i] -cne $canonical.Arguments[$i]) { throw 'Controlled support requires the canonical ordered argument vector.' }
        }
    }
    $dummyStartup=$PSBoundParameters.ContainsKey('StartupMilliseconds')
    if ($RequireGame -and $dummyStartup) { throw 'Startup override is dummy-only; real startup is always600000ms.' }
    if ($TimeoutMilliseconds -le 0) { throw 'Whole invocation timeout must be positive.' }
    $requiresReceipt=[bool]($RequireGame -or $dummyStartup)
    if (!$IsWindows -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'Requires Windows and PowerShell7.' }
    Initialize-V2JobType
    [void](Assert-V2ManagedPath $EvidenceRoot $WorkingDirectory)
    New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null
    # The exclusive handle also prevents concurrent invocations from clearing each other's game directory.
    $lockPath=Join-Path $EvidenceRoot 'supervisor.lock'
    $lock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $result=$null; $process=$null; $job=$null
    $stdoutFile=$null; $stderrFile=$null; $stdout=$null; $stderr=$null
    $records=[Collections.Generic.List[object]]::new(); $handles=[Collections.Generic.List[object]]::new()
    try {
        if ($lock.Length) { throw "Ownership/cleanup unresolved. Retain $lockPath and resolve it before rerun." }
        [void](Assert-V2ManagedPath $EvidenceRoot $WorkingDirectory)
        # Snapshot and final launch preflight share this handle through cleanup and archive.
        $before=@(Get-V2ScenarioInventory $EvidenceRoot)
        if ($RequireGame) {
            $TimeoutMilliseconds=Get-V2TimeoutMilliseconds $Request.Scenario $Request.Repeat
            $game=Join-Path $WorkingDirectory 'v2/fabric/build/run/clientGameTest'
            [void](Assert-V2ManagedPath $game $WorkingDirectory)
            $jdkArguments=@{}
            if ($Request.PSObject.Properties['JavaHome']) { $jdkArguments.JavaHome=$Request.JavaHome }
            $jdk=Find-V2Jdk @jdkArguments
            $Environment=Resolve-V2GameEnvironment $jdk $Request $InternalSupportCase
            $wrapper=Join-Path $WorkingDirectory gradlew.bat
            if (!(Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw "Repository wrapper missing: $wrapper" }
            $Request | Add-Member Jdk $jdk -Force
            $Request | Add-Member Wrapper $wrapper -Force
            $Request | Add-Member ScenarioInventoryBefore $before -Force
            $workerText='& ' + "'" + $wrapper.Replace("'","''") + "' " + (($Request.Arguments | ForEach-Object { "'" + $_.Replace("'","''") + "'" }) -join ' ') + "`n`$nativeExit=`$LASTEXITCODE`nexit `$nativeExit"
            $Worker=[scriptblock]::Create($workerText)
            $Archive={ param($evidence,$before) Save-V2Artifacts -Evidence $evidence -Game $game -ScenarioRoot $EvidenceRoot -Before $before -Owner $WorkingDirectory }
        }
        $launchId=[guid]::NewGuid().ToString('D')
        $evidence=Join-Path $EvidenceRoot ('launch-' + $launchId)
        New-Item -ItemType Directory -Path $evidence | Out-Null
        $Environment=@{}+$Environment
        $Environment.MAPART_TEST_LAUNCH_ID=$null; $Environment.MAPART_TEST_LAUNCH_DIR=$null
        if ($requiresReceipt) {
            $Environment.MAPART_TEST_LAUNCH_ID=$launchId; $Environment.MAPART_TEST_LAUNCH_DIR=$evidence
        }
        if (!$Request) { $Request=[pscustomobject]@{Scenario='all';Repeat=2} }
        $Request | Add-Member SupportCase $InternalSupportCase -Force
        $Request | Add-Member SupportCaseSource $(if ($InternalSupportCase -ceq 'controlled') {'internal-reviewed-controlled-support'} else {'ordinary-default'}) -Force
        $Request | Add-Member ChildEnvironment $Environment -Force
        [int]$deadlineMilliseconds=$TimeoutMilliseconds
        $marker=[Text.Encoding]::UTF8.GetBytes($evidence); $lock.Write($marker); $lock.Flush($true)
        $result=[pscustomobject]@{ Evidence=$evidence; LaunchId=$launchId; StartedUtc=[DateTime]::UtcNow.ToString('o'); DeadlineMilliseconds=$deadlineMilliseconds
            StartupDeadlineMilliseconds=$(if($requiresReceipt){$StartupMilliseconds}else{$null}); StartupAcceptedElapsedMilliseconds=$null
            TimeoutPhase=$null; ReceiptStatus=$(if($requiresReceipt){'Missing'}else{'NotRequired'}); ReceiptRejectionReason=$null; StartReceipt=$null; StartIdentity=$null
            WorkerPid=$null; PrimaryFailure=$null; ObservedFailures=[Collections.Generic.List[object]]::new(); TerminationRequested=$false
            ExitCode=125; WorkerExitCode=$null; TimedOut=$false; SupervisorError=$null; CleanupStatus='unresolved'; CleanupError=$null
            ArchiveStatus='pending'; ArchiveError=$null; Request=$Request; Processes=$records; NativeGameExitCodes=@() }
        $result | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidence 'request.json')
        $gate=Join-Path $evidence 'worker-assigned'
        $command="`$ErrorActionPreference='Stop'; `$gateClock=[Diagnostics.Stopwatch]::StartNew(); while (!(Test-Path -LiteralPath '" + $gate.Replace("'","''") + "')) { if (`$gateClock.ElapsedMilliseconds -ge 10000) { exit 125 }; Start-Sleep -Milliseconds 20 };`n" + $Worker.ToString()
        $command | Set-Content (Join-Path $evidence 'worker.ps1')
        $job=[V2OwnedJob]::new()
        $process=[Diagnostics.Process]::new()
        $process.StartInfo=New-V2ProcessInfo $WorkerExecutable $WorkingDirectory $command $Environment
        $clock=[Diagnostics.Stopwatch]::StartNew()
        try {
            if (Get-Item -LiteralPath (Join-Path $evidence 'test-start.json') -Force -ErrorAction SilentlyContinue) {
                $result.ReceiptStatus='Rejected'; $result.ReceiptRejectionReason='Receipt existed before worker launch.'
                throw $result.ReceiptRejectionReason
            }
            if (!$process.Start()) { throw 'Owned worker did not start.' }
            $result.WorkerPid=$process.Id
            [void]$process.Handle
            $rootRecord=[pscustomobject]@{ Pid=$process.Id; ParentPid=$PID; StartedUtc=$process.StartTime.ToUniversalTime().ToString('o')
                Executable=$WorkerExecutable; CommandLine=($process.StartInfo.ArgumentList -join ' '); Ownership='directly started gated worker'
                ExitCode=$null; ExitedUtc=$null; ObservedExitUtc=$null; IdentityVerified=$false; IsGame=$false }
            $records.Add($rootRecord); $handles.Add([pscustomobject]@{ Process=$process; Record=$rootRecord })
            $stdoutFile=[IO.File]::Create((Join-Path $evidence 'launch.stdout.log')); $stderrFile=[IO.File]::Create((Join-Path $evidence 'launch.stderr.log'))
            $stdout=$process.StandardOutput.BaseStream.CopyToAsync($stdoutFile); $stderr=$process.StandardError.BaseStream.CopyToAsync($stderrFile)
            $job.Assign($process.Handle)
            Update-V2Processes $job $records $handles
            Set-V2ObservedFailure $result $records
            if (Get-Item -LiteralPath (Join-Path $evidence 'test-start.json') -Force -ErrorAction SilentlyContinue) {
                # Dummy inventory guards may publish from the actual held identity; real workers are still gated.
                if ($RequireGame) { $result.ReceiptRejectionReason='Receipt existed before worker gate release.'; throw $result.ReceiptRejectionReason }
            }
            if ($clock.ElapsedMilliseconds -lt $deadlineMilliseconds -and (!$requiresReceipt -or $clock.ElapsedMilliseconds -lt $StartupMilliseconds)) { Set-Content -LiteralPath $gate -Value 'assigned' }
            while ($true) {
                Update-V2Processes $job $records $handles
                if ($process.WaitForExit(0)) {
                    $result.WorkerExitCode=$process.ExitCode
                    if ($null -eq $rootRecord.ExitCode) {
                        # The worker can exit after inventory; latch that observation before testing either deadline.
                        $rootRecord.ExitCode=$process.ExitCode; $rootRecord.ObservedExitUtc=[DateTime]::UtcNow.ToString('o')
                        try { $rootRecord.ExitedUtc=$process.ExitTime.ToUniversalTime().ToString('o') } catch { }
                    }
                    if (!$result.PrimaryFailure) { $result.ExitCode=$process.ExitCode }
                }
                Set-V2ObservedFailure $result $records
                if (($requiresReceipt -and $null -eq $result.StartupAcceptedElapsedMilliseconds -and $clock.ElapsedMilliseconds -ge $StartupMilliseconds) -or $clock.ElapsedMilliseconds -ge $deadlineMilliseconds) {
                    $result.TimedOut=$true
                    $result.TimeoutPhase=$(if($requiresReceipt -and $null -eq $result.StartupAcceptedElapsedMilliseconds -and $clock.ElapsedMilliseconds -ge $StartupMilliseconds){'STARTUP'}else{'OVERALL'})
                    if ($result.TimeoutPhase -ceq 'STARTUP') { $result.ReceiptRejectionReason='Startup deadline elapsed without a validated selected-provider receipt.' }
                    if (!$result.PrimaryFailure) { $result.ExitCode=124 }
                    break
                }
                if ($requiresReceipt -and $null -eq $result.StartupAcceptedElapsedMilliseconds) {
                    $receiptArguments=@{Evidence=$evidence;LaunchId=$launchId;Request=$Request;Handles=@($handles);OwnedPids=@($job.Pids());WorkerPid=$process.Id;RequireGame=$RequireGame}
                    if ($RequireGame) { $receiptArguments.JavaExecutable=Join-Path $jdk.Home 'bin/java.exe' }
                    $receipt=Read-V2StartReceipt @receiptArguments
                    $result.ReceiptStatus=$receipt.Status
                    if ($receipt.Status -ceq 'Rejected') {
                        $result.ReceiptRejectionReason=$receipt.Reason
                        if (!$result.PrimaryFailure) { $result.ExitCode=125 }
                        break
                    }
                    if ($receipt.Status -ceq 'Accepted') {
                        if ($clock.ElapsedMilliseconds -ge $StartupMilliseconds -or $clock.ElapsedMilliseconds -ge $deadlineMilliseconds) { continue }
                        # Acceptance latches phase/identity once; neither absolute deadline nor stopwatch is renewed.
                        $result.StartReceipt=$receipt.Receipt; $result.StartIdentity=$receipt.Identity
                        $result.StartupAcceptedElapsedMilliseconds=$clock.ElapsedMilliseconds
                    }
                }
                if ($null -ne $result.WorkerExitCode -or $result.PrimaryFailure) {
                    if ($requiresReceipt -and $null -eq $result.StartupAcceptedElapsedMilliseconds -and !$result.PrimaryFailure) {
                        $result.ReceiptRejectionReason='Worker ended without a validated selected-provider receipt.'; $result.ExitCode=125
                    }
                    break
                }
                Start-Sleep -Milliseconds 50
            }
        } catch { $result.SupervisorError=$_.Exception.ToString(); if (!$result.PrimaryFailure -and !$result.TimedOut) { $result.ExitCode=125 } }
        finally {
            $cleanupClock=[Diagnostics.Stopwatch]::StartNew()
            try {
                # Keep records/handles until every owned member has exited, even when the root already exited.
                Update-V2Processes $job $records $handles -Fast
                Set-V2ObservedFailure $result $records
                # Normal shutdown may outlive the worker briefly; drain shares both existing fixed budgets.
                while ($null -ne $result.WorkerExitCode -and !$result.TimedOut -and !$result.SupervisorError -and
                    @($job.Pids()).Count -and $cleanupClock.ElapsedMilliseconds -lt 1000 -and $clock.ElapsedMilliseconds -lt $deadlineMilliseconds) {
                    Update-V2Processes $job $records $handles -Fast
                    Set-V2ObservedFailure $result $records
                    Start-Sleep -Milliseconds 25
                }
                if (@($job.Pids()).Count) {
                    if (!$result.TimedOut -and !$result.ReceiptRejectionReason -and $clock.ElapsedMilliseconds -ge $deadlineMilliseconds) {
                        $result.TimedOut=$true
                        $result.TimeoutPhase='OVERALL'
                        if (!$result.PrimaryFailure -and $result.ExitCode -eq 0) { $result.ExitCode=124 }
                    }
                    elseif ($result.ExitCode -eq 0) { $result.ExitCode=125; $result.SupervisorError='Owned descendants required forced termination after bounded normal drain.' }
                    $result.TerminationRequested=$true
                    $job.Stop()
                }
                if ($result.WorkerPid -and !$process.HasExited -and @($job.Pids()) -notcontains $result.WorkerPid) { $process.Kill() }
                while (@($job.Pids()).Count -and $cleanupClock.ElapsedMilliseconds -lt 10000) {
                    Update-V2Processes $job $records $handles -Fast
                    Start-Sleep -Milliseconds 25
                }
                if (@($job.Pids()).Count) { throw 'Owned job did not settle within10000ms.' }
                foreach ($held in $handles) {
                    $remaining=[Math]::Max(0,10000-[int]$cleanupClock.ElapsedMilliseconds)
                    if (!$held.Process.WaitForExit($remaining)) { throw "Held owned process did not settle: $($held.Record.Pid)" }
                    $held.Record.ExitCode=$held.Process.ExitCode
                }
                if ($result.WorkerPid -and $process.WaitForExit(0)) { $result.WorkerExitCode=$process.ExitCode }
                Set-V2ObservedFailure $result $records
                foreach ($copy in @($stdout,$stderr)) {
                    if ($copy) {
                        $remaining=[Math]::Max(0,10000-[int]$cleanupClock.ElapsedMilliseconds)
                        if (!$copy.Wait($remaining)) { throw 'Output stream did not settle within cleanup bound.' }
                        [void]$copy.GetAwaiter().GetResult()
                    }
                }
                if ($stdoutFile) { $stdoutFile.Flush($true) }; if ($stderrFile) { $stderrFile.Flush($true) }
                $result.CleanupStatus='settled'
            } catch { $result.CleanupError=$_.Exception.ToString(); if ($result.ExitCode -eq 0) { $result.ExitCode=125 } }
        }
        $result.NativeGameExitCodes=@($records | Where-Object IsGame | Select-Object -ExpandProperty ExitCode)
        if ($RequireGame -and (!$result.NativeGameExitCodes.Count -or @($result.NativeGameExitCodes | Where-Object { $null -eq $_ }).Count)) {
            $result.SupervisorError += ' Native game process exit was not observed.'
            if ($result.ExitCode -eq 0) { $result.ExitCode=125 }
        }
        if ($result.ExitCode -eq 0 -and @($result.NativeGameExitCodes | Where-Object { $_ -ne 0 }).Count) { $result.ExitCode=125; $result.SupervisorError += ' Native game failed despite worker0.' }
        try { & $Archive $evidence $before; $result.ArchiveStatus='retained' }
        catch { $result.ArchiveStatus='failed'; $result.ArchiveError=$_.Exception.ToString(); if ($result.ExitCode -eq 0) { $result.ExitCode=125 } }
        $result | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $evidence 'outcome.json')
        "exitCode=$($result.ExitCode); workerExitCode=$($result.WorkerExitCode); timedOut=$($result.TimedOut); cleanup=$($result.CleanupStatus); archive=$($result.ArchiveStatus)" | Set-Content (Join-Path $evidence 'exit-code.txt')
        # Clear only after durable outcome/evidence and verified cleanup; unresolved ownership blocks rerun.
        if ($result.CleanupStatus -eq 'settled' -and $result.ArchiveStatus -eq 'retained') { $lock.SetLength(0); $lock.Flush($true) }
        return $result
    } finally {
        if ($job) { $job.Dispose() }
        if ($stdoutFile) { $stdoutFile.Dispose() }; if ($stderrFile) { $stderrFile.Dispose() }
        foreach ($held in $handles) { $held.Process.Dispose() }
        if ($process) { $process.Dispose() }
        $lock.Dispose()
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference='Stop'
    try {
        $request=New-V2Request -Scenario $Scenario -Repeat $Repeat -EulaAccepted:$EulaAccepted -WrongExpectedBlock:$WrongExpectedBlock
        if (!$IsWindows -or $PSVersionTable.PSVersion.Major -lt 7) { throw 'Requires Windows and PowerShell7.' }
        $repo=Split-Path $PSScriptRoot
        $scenarioRoot=Join-Path $repo 'v2/fabric/build/scenario-results'
        [void](Assert-V2ManagedPath $scenarioRoot $repo)
        $lockPath=Join-Path $scenarioRoot 'supervisor.lock'
        if (Test-Path -LiteralPath $lockPath) {
            $preflightLock=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
            try { if ($preflightLock.Length) { throw "Previous evidence/ownership unresolved: $lockPath. Resolve before launching any new processes." } }
            finally { $preflightLock.Dispose() }
        }
        if ($PSBoundParameters.ContainsKey('JavaHome')) { $request | Add-Member JavaHome $JavaHome }
        $result=Invoke-V2Supervisor -EvidenceRoot $scenarioRoot -WorkingDirectory $repo -Request $request -RequireGame
        Write-Output "Launch evidence: $($result.Evidence); exit=$($result.ExitCode); nativeGame=$($result.NativeGameExitCodes -join ','); timedOut=$($result.TimedOut); cleanup=$($result.CleanupStatus); archive=$($result.ArchiveStatus)"
        exit $result.ExitCode
    } catch { Write-Error $_ -ErrorAction Continue; exit 125 }
}
