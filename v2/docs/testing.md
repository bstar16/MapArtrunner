# V2 environment probe

The environment probe was **locally verified on 28 October 2026** after the operator explicitly accepted the Minecraft EULA for the disposable test instances. A real client/server positive run passed, and a fresh deliberate wrong-server-block run failed for the required reason. This proves local adapter feasibility; headless/CI equivalence and gameplay builder behavior remain unproven.

Use the independent v2 build from the repository root. Never install/load v1 and v2 together. The release JAR currently contains metadata and the core library; there is no production Fabric gameplay entrypoint or builder.

## Compile without launching

Use an installed **JDK 21** in a fresh PowerShell process. Set `JAVA_HOME` to its installation directory in that process if it is not already configured; otherwise these snippets discover the JDK from `javac` on PATH. They validate the version and do not change user/machine environment settings:

```powershell
if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source)
}
$probeJavaVersion = (& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
if ($probeJavaVersion -notmatch '^javac 21(?:\.|$)') { throw 'Set process-local JAVA_HOME to an installed JDK 21.' }
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

The test launch also sets `-Djava.awt.headless=true`. Pinned Loader 0.19.2 gates its AWT error-dialog branch on `!GraphicsEnvironment.isHeadless()` and requests `System.exit(1)` outside that branch; assertion logging happens beforehand. This skips the test process's AWT error dialog while retaining the failure. It does **not** make Minecraft's GLFW/LWJGL rendering headless or remove its graphics requirement. The controlled runs below verified both the specific failure and real screenshots with this property; it is a process-local test-launch setting, not a production mod flag.

API references: [client context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.html), [singleplayer context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/TestSingleplayerContext.html), [server context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerContext.html), [pinned source JAR](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-client-gametest-api-v1/4.3.5%2Bbfe9fcf43e/fabric-client-gametest-api-v1-4.3.5%2Bbfe9fcf43e-sources.jar). Javadocs may display Mojang names; implementation compiles against Yarn **1.21.11+build.4**, using `MinecraftClient`, `ServerPlayerEntity`, `BlockPos`, `Items`, `Hand`, etc.

## Real launch — requires explicit operator EULA acceptance first

The operator must explicitly accept the [Minecraft EULA](https://www.minecraft.net/eula) for the intended test instances before anyone runs a game task or supplies `-PmapartTestEula=true`. Acceptance was obtained for the local/CI disposable test instances; the evidence below covers local runs only. Compilation and JVM/graphics inventory do not grant acceptance for other operators or instances.

After acceptance, run this in a **fresh Windows PowerShell 7 process**, from the repository root. It requires `.NET Process.Kill(Boolean)` for process-tree termination; this host has PowerShell 7.6.5 and that overload. Do not substitute Windows PowerShell 5 or kill only the Gradle wrapper. The environment changes last only for this process. The external stdout/stderr transcripts and supervisor outcome survive Loom clearing the run directory, including failures before the test entrypoint starts:

```powershell
$ErrorActionPreference = 'Stop'
if (!$IsWindows -or $PSVersionTable.PSVersion.Major -lt 7 -or
    ![System.Diagnostics.Process].GetMethod('Kill', [type[]]@([bool]))) {
    throw 'Requires Windows PowerShell 7 and Process.Kill(Boolean); do not launch unsupervised.'
}
if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source)
}
$probeJavaVersion = (& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
if ($probeJavaVersion -notmatch '^javac 21(?:\.|$)') { throw 'Set process-local JAVA_HOME to an installed JDK 21.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Dfabric.client.gametest.modid=mapart-test -Djava.awt.headless=true'
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

## Local execution evidence

The initial positive/negative runs used the reviewed source tree `02946336de56e613d5cebb2f7da55f10983598fd` at commit `6dc8f737863de51e3589ca62b79c5e51f0651105`. A follow-up positive used docs-only commit `b5c591b0219280c59799d68c2f2b6e21572bb66c`, with the portable supervisor snippet unchanged. These first three runs preceded the AWT property addition. All runs used Java 21.0.10, Minecraft 1.21.11, Loader 0.19.2 and Fabric API 0.141.1+1.21.11. No probe Java/build change was needed. All paths below are under `v2/fabric/build/scenario-results/` and retained outside the cleared game directory.

| Run | Launch evidence directory | Probe evidence directory | Outcome |
| --- | --- | --- | --- |
| Positive | `launch-ccafd00a-8378-4111-a5d7-9cb20c0fddab` | `environment-probe-48e67495-b56d-477f-ad1f-09fcf77571b8` | Server stone after 1 tick; client observed confirmation; `success.png`; PASS after world close; Gradle exit 0, `timedOut=False`. |
| Deliberate wrong expectation | `launch-49c3eab1-abfc-4cbf-98ce-4050d9eae9d6` | `environment-probe-6fea99dd-d3a3-4100-9690-bf79e2a58891` | Server timeout after 200 ticks: expected gold, actual stone; `failure.png`; FAIL; saved/closed world; Gradle exit 1, `timedOut=False`. |
| Positive after failure | `launch-33ab9d26-fa4f-4109-8e56-aa562b0bb413` | `environment-probe-6ed4128d-05c8-490e-8320-55c83fb35e46` | Fresh fixture with the same canonical hash; server stone after 1 tick; `success.png`; PASS after world close; Gradle exit 0, `timedOut=False`; no related JVM remained. |

All three screenshots were opened and visually inspected: rendered bedrock platform, placed stone, chest and stone in the selected hotbar slot. Each probe retained `probe.log` and `minecraft-latest.log`; the launch directories retained stdout/stderr, supervisor outcome and copied game logs. The initial positive build took 1m52s including assets; the negative took 59s; the follow-up positive took 50s. The exact negative assertion propagated through Fabric's test runner/loader, with child Java exit `-1073740791` (`0xC0000409`) and Gradle exit 1; it was not an outer timeout. The pinned framework intentionally rethrows failed tests into Loader's crash handling, whose source requests `System.exit(1)`. That explains the assertion/crash-handler log, **not** this native status: its cause remains unproven, with no matching Minecraft crash report, JVM fatal log/native dump or Windows application error event found in the inspected locations/time window. No related game JVM remained after exit. The negative assertion proof does not establish that native shutdown was clean or that this exact native exit code is intended.

The third run cleared the wrong-expectation flag and created a new world/invocation after the failure. Its independent server confirmation, inspected screenshot, PASS/close and exit 0 complete a positive–failure–positive carryover check: that failed case did not prevent the next ordinary probe from succeeding. This does not resolve the native-status cause or establish the later managed reset/soak contracts.

### Controlled AWT error-dialog isolation

One source-backed controlled negative added only `-Djava.awt.headless=true` to the process-local JVM options, preserving the same supervisor, fixture, gold expectation and assertions. Java then exited **1** instead of `0xC0000409`. One matching positive with the identical property proved that the actual Minecraft framebuffer/screenshot still worked. Both used docs-only commit `e9db0a95c3eb0c04218f903c1694e7ba0776955e`; neither changed Java/build/pins or weakened an assertion.

| Controlled run | Launch evidence directory | Probe evidence directory | Outcome |
| --- | --- | --- | --- |
| Wrong expectation, AWT dialog disabled | `launch-6ca40bd1-cdfa-4296-b050-94bb2635cbab` | `environment-probe-6c922d4a-5c2d-41a6-b182-25bf3cfe667b` | Same 200-tick expected-gold/actual-stone FAIL, inspected `failure.png`, saved/closed world; actual Java 1, Gradle 1, `timedOut=False`; 1m. |
| Positive, same property | `launch-cf671b13-6631-4595-9c76-c14ef897b640` | `environment-probe-7a35ed48-10da-4298-88c8-34cf92555158` | Independent server stone after 1 tick, inspected `success.png`, PASS/world close; Java task successful, Gradle 0, `timedOut=False`; 48s. |

Both retained the same fixture hash, complete launch/probe evidence and rendered stone/chest/hotbar screenshots. No owned root or related game JVM remained afterward. Combined with Loader's source condition, the changed exit is consistent with the AWT-enabled error-handling/shutdown path causing the earlier host-specific anomaly. The exact native fault mechanism remains unproven; the controlled test supports the documented AWT-dialog bypass, not a claim that Minecraft now works without a display or that the original native fault was diagnosed from a dump. No further repetition was needed.

This Windows host initialized LWJGL 3.3.3-snapshot and rendered through LLVMPipe after `vm3dgl` could not initialize its SVGA driver. That observed software-rendering fallback was sufficient for these actual screenshots; it does not establish hardware acceleration or headless/clean-machine compatibility. Retained startup messages also include development-classpath/no-main-Java, default anisotropic-option and offline test-profile/Realms lookup warnings; no run failed on those paths.

## Remaining limits

Compilation alone proves mapped signatures. The local runs additionally prove this fixture's inventory/world synchronization, placement round-trip, independent server assertion, actual screenshots, world close and useful process outcomes on this host. This probe does not verify a builder, refill, Baritone, managed reset repeatability, CI/headless equivalence, or any later scenario. There is no release or installation claim.
