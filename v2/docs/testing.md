# V2 environment probe

Task 5 is **prepared, not live-verified**. Do not mark it complete until a real client run passes and the deliberate wrong-server-block run fails for the expected reason. No Minecraft launch or EULA acceptance was performed during preparation. Graphics/OpenGL and headless execution remain unproven.

Use the independent v2 build from the repository root. Never install/load v1 and v2 together. The release JAR currently contains metadata and the core library; there is no production Fabric gameplay entrypoint or builder.

## Compile without launching

In a fresh PowerShell process, set Java only for that process:

```powershell
$env:JAVA_HOME = 'C:\Users\Bryce\.jdks\ms-21.0.10'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat -p v2 :fabric:compileGametestJava :fabric:remapJar --console=plain
```

This compiles the actual Yarn-mapped API calls and produces `v2/fabric/build/libs/mapartrunner-v2-2.0.0-dev.jar`. It does not establish runtime feasibility. Do not use root `build` (v1), v2 `build`, or game tasks just to compile this probe.

## What the probe does

The `mapart-test` fixture mod has one `fabric-client-gametest` entrypoint: `EnvironmentProbeGameTest`. It uses `ClientGameTestContext.worldBuilder().create()` with Fabric's consistent settings (flat, seed 1) to create and join a disposable integrated-server world.

`ScenarioEnvironment` owns one invocation. It creates a UUID evidence directory before creating the world. Server-thread setup creates a bounded bedrock platform at Y=64, air target `(0,65,0)`, chest `(2,65,0)` containing 16 stone, and a creative player at `(0.5,65,2.5)` with 8 stone in selected slot 0. Client-thread polling observes the fixture, inventory and teleport. The client calls `ClientPlayerInteractionManager.interactBlock` on the support's top face. A separate test-thread loop reads the server's target block with `computeOnServer`, waiting at most `DEFAULT_TIMEOUT` (200 ticks, 10 seconds of simulation). Client block observation follows the independent server assertion. Chunk rendering is also bounded to 200 ticks before the screenshot.

Context methods are called only on the gametest thread. `runOnServer`/`computeOnServer` and `runOnClient`/`computeOnClient` marshal game work; client wait predicates never call server contexts. The world context closes before PASS is recorded. Failures capture a screenshot before world cleanup where possible, preserve the exception, and propagate to Fabric. There are no reset hooks, commands or production fault controls. Each invocation creates a new world; stop/cleanup ends with the context closing, and no fixture state is retained across invocations.

The pinned screenshot implementation waits for its screenshot future without a deadline. A stalled screenshot can therefore block success, failure capture, world cleanup and rethrow; the 200-tick checks do not bound the whole run. Use the wall-clock process supervision below for every launch. It covers startup, screenshots and shutdown without calling game-context APIs from a timer thread.

## Selection supported by the pinned API

Fabric API **0.141.1+1.21.11** includes client gametest module **4.3.5+bfe9fcf43e**. Its `TestSystemProperties.MOD_ID_FILTER_PROPERTY` is `fabric.client.gametest.modid`; `FabricClientGameTestRunner.getTestToRun()` parses a comma-separated list of **mod IDs** and filters entrypoint providers. It does not select Java methods/classes. Invalid/unloaded IDs and an empty matching set throw. A normal launch runs all registered entrypoints; currently our test mod registers only this probe.

To explicitly select this mod, set process-local `JAVA_TOOL_OPTIONS=-Dfabric.client.gametest.modid=mapart-test` for the launch process (as below). A Gradle `-D` flag alone is not claimed to forward to the child game JVM. No invented `test`, `filter`, or method-name property is used.

API references: [client context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.html), [singleplayer context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/TestSingleplayerContext.html), [server context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerContext.html), [pinned source JAR](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-client-gametest-api-v1/4.3.5%2Bbfe9fcf43e/fabric-client-gametest-api-v1-4.3.5%2Bbfe9fcf43e-sources.jar). Javadocs may display Mojang names; implementation compiles against Yarn **1.21.11+build.4**, using `MinecraftClient`, `ServerPlayerEntity`, `BlockPos`, `Items`, `Hand`, etc.

## Real launch — requires explicit operator EULA acceptance first

These commands are instructions for a later authorized run. The operator must explicitly accept the [Minecraft EULA](https://www.minecraft.net/eula) before anyone runs a game task or supplies `-PmapartTestEula=true`. Compilation and JVM/graphics inventory do not grant acceptance.

After acceptance, run this in a **fresh Windows PowerShell 7 process**, from the repository root. It requires `.NET Process.Kill(Boolean)` for process-tree termination; this host has PowerShell 7.6.5 and that overload. Do not substitute Windows PowerShell 5 or kill only the Gradle wrapper. The environment changes last only for this process. The external stdout/stderr transcripts and supervisor outcome survive Loom clearing the run directory, including failures before the test entrypoint starts:

```powershell
$ErrorActionPreference = 'Stop'
if (!$IsWindows -or $PSVersionTable.PSVersion.Major -lt 7 -or
    ![System.Diagnostics.Process].GetMethod('Kill', [type[]]@([bool]))) {
    throw 'Requires Windows PowerShell 7 and Process.Kill(Boolean); do not launch unsupervised.'
}
$env:JAVA_HOME = 'C:\Users\Bryce\.jdks\ms-21.0.10'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Dfabric.client.gametest.modid=mapart-test'
Remove-Item Env:MAPART_PROBE_WRONG_EXPECTED_BLOCK -ErrorAction SilentlyContinue
$probeEvidence = Join-Path (Get-Location) ('v2\fabric\build\scenario-results\launch-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $probeEvidence | Out-Null
$probeWorker = @'
.\gradlew.bat -p v2 :fabric:runClientGameTest -PmapartTestEula=true --no-daemon --console=plain
exit $LASTEXITCODE
'@
$probeEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($probeWorker))
$probeTimeoutMilliseconds = 600000 # Ten minutes allows a first cold start; not a gameplay timing target.
$probeProcess = $null
$probeExit = 125
$probeTimedOut = $false
"START $(Get-Date -Format o); deadlineMilliseconds=$probeTimeoutMilliseconds" |
    Set-Content (Join-Path $probeEvidence 'supervisor.log')
try {
    $probeProcess = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -WindowStyle Hidden `
        -ArgumentList '-NoProfile', '-EncodedCommand', $probeEncoded -WorkingDirectory (Get-Location).Path `
        -RedirectStandardOutput (Join-Path $probeEvidence 'launch.stdout.log') `
        -RedirectStandardError (Join-Path $probeEvidence 'launch.stderr.log') -PassThru
    "ownedRootPid=$($probeProcess.Id)" | Add-Content (Join-Path $probeEvidence 'supervisor.log')
    if (!$probeProcess.WaitForExit($probeTimeoutMilliseconds)) {
        $probeTimedOut = $true
        $probeExit = 124
        'TIMEOUT: infrastructure failure; requesting owned process-tree termination.' |
            Add-Content (Join-Path $probeEvidence 'supervisor.log')
    } else {
        $probeExit = $probeProcess.ExitCode
    }
} catch {
    $_ | Out-String | Add-Content (Join-Path $probeEvidence 'supervisor.log')
    $probeExit = 125
} finally {
    # The hidden worker waits for Gradle; --no-daemon keeps this run's game JVM in its owned chain.
    if ($probeProcess -and !$probeProcess.HasExited) {
        $probeProcess.Kill($true)
        if (!$probeProcess.WaitForExit(10000)) {
            throw "Owned process tree did not stop; retain $probeEvidence and do not start another run."
        }
    }
    "exitCode=$probeExit; timedOut=$probeTimedOut" | Set-Content (Join-Path $probeEvidence 'exit-code.txt')
}
foreach ($probeFolder in 'logs', 'crash-reports') {
    $probeSource = Join-Path (Get-Location) "v2\fabric\build\run\clientGameTest\$probeFolder"
    if (Test-Path -LiteralPath $probeSource) {
        Copy-Item -LiteralPath $probeSource -Destination $probeEvidence -Recurse
    }
}
Write-Output "Launch evidence: $probeEvidence; exit=$probeExit"
exit $probeExit
```

Keep the supervisor open until it returns. Its initial allowance is ten minutes plus at most ten seconds for root-process termination after a tree kill. `Kill($true)` targets the owned worker and its Gradle/game descendants, including a single-use Gradle daemon; it does not select unrelated Java processes by name. Do not reuse a detached Gradle daemon or detach the game from this worker. On timeout, retain the stdout/stderr files, `supervisor.log`, `exit-code.txt` (`124`, `timedOut=True`), copied game logs/crash reports and any already-created probe evidence **before another launch**. A forced stop may leave no FAIL marker, screenshot or graceful world close. A termination error requires resolving the owned process cleanup before any rerun; never sweep-kill unrelated Java processes. Exit `125` denotes a supervisor/startup error. The ten-minute limit is an infrastructure deadline, not a successful gameplay result.

Require exit code 0, a logged `PASS`, an authoritative `minecraft:stone` observation and a readable `success.png`. Open the screenshot to check the actual rendered world. The game directory is `v2/fabric/build/run/clientGameTest`; disposable saves remain there until Loom clears the directory on a later run. Retained probe artifacts are under `v2/fabric/build/scenario-results/environment-probe-<UUID>/`:

- `probe.log`: start time, versions, canonical fixture and SHA-256, seed/settings, world save path, stages, interaction result, independent server observation or timeout, screenshot path, terminal PASS/FAIL and failure trace.
- `success.png` or `failure.png`: actual Fabric screenshot; screenshot errors propagate or attach to the primary failure.
- `minecraft-latest.log`: snapshot of the game log at completion/failure; launcher transcript also retains the eventual framework exception and exit outcome.

The unique artifact path is printed to stdout before world creation. Failures before probe construction are covered by the launch transcript; no screenshot is promised if Minecraft cannot initialize graphics. Archive relevant crash reports from `build/run/clientGameTest/crash-reports` into the same launch evidence directory before another launch if startup crashes.

## Deliberate negative proof

After the normal run passes, repeat the launch script in another fresh PowerShell process, replacing its `Remove-Item Env:MAPART_PROBE_WRONG_EXPECTED_BLOCK ...` line with:

```powershell
$env:MAPART_PROBE_WRONG_EXPECTED_BLOCK = '1'
```

This flag exists only in gametest source. The client still places stone, but the independent server check now expects gold. Require a **nonzero** Gradle/game exit, `FAIL`, a server timeout after 200 ticks with `expected=minecraft:gold_block, actual=minecraft:stone`, and `failure.png`. Require `timedOut=False`: an outer wall-clock timeout (`124`) or unrelated startup, inventory, thread, screenshot or graphics error does not prove the negative assertion. Retain both UUID directories and launch transcripts. Run without the flag again if a baseline recovery check is needed; never weaken the expected block to get green.

## Remaining limits

Successful compilation proves mapped signatures, not successful placement, server synchronization, rendering, graphics initialization, screenshot capture or process exit behavior. Actual game execution and the deliberate negative proof are still required. This probe establishes adapter feasibility only; it does not verify a builder, refill, Baritone, reset repeatability, CI/headless equivalence, or any later scenario. There is no release or installation claim.
