# V2 single-lane verification

The programmatic single-lane runtime and its managed client/server scenarios were **locally verified on 28 October 2026**, after the operator accepted the Minecraft EULA for the disposable test instances. The original environment probe remains part of the suite. Real negative assertions and separate full recoveries establish the specific evidence boundaries below; CI gameplay equivalence, host stability and full v1 parity remain unproven.

Use the independent v2 build from the repository root. Never install/load v1 and v2 together. Production registers `com.mapartrunner.fabric.MapArtClient`; command, importer and UI wiring remain later parity work. This milestone does not provide an in-game `/mapart` launch recipe or replace v1.

## Compile without launching

Use an installed **JDK 21** in a fresh PowerShell process. Set `JAVA_HOME` to its installation directory in that process if it is not already configured; otherwise these snippets discover the JDK from `javac` on PATH. They validate the version and do not change user/machine environment settings:

```powershell
if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source)
}
$probeJavaVersion = (& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
if ($probeJavaVersion -notmatch '^javac 21(?:\.|$)') { throw 'Set process-local JAVA_HOME to an installed JDK 21.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat -p v2 :fabric:compileGametestJava :fabric:remapJar --no-daemon --console=plain
```

This compiles the actual Yarn-mapped API calls and produces `v2/fabric/build/libs/mapartrunner-v2-2.0.0-dev.jar`. Compilation proves mapped signatures, not runtime behavior. Do not use root `build` (v1), v2 `build`, or game tasks merely to compile. For a fresh full settled-source verification, use the same JDK setup and the command in [Settled-source verification](#settled-source-verification).

## Production runtime and managed lane cases

The actual public client-thread facade is `MapArtClient.start(BuildPlan plan, BuildPlan.GridPos origin, BuildEngine.Settings settings): UUID`, `pause(): void`, `resume(): void`, `stop(): void` and `status(): FabricBuildAdapter.Status`. The production initializer registers one `END_CLIENT_TICK` callback, plus world-change, disconnect, screen-opening and client-stopping cleanup. The fixture calls this facade; it neither constructs a second engine nor supplies engine ticks. Test START/END observers read status/client evidence without advancing core. Independent server reads are test assertions and never production observations.

`GroundedSingleLaneGameTest` creates a fresh managed world for each of these 15 enum-dispatched cases, preserving DISCONNECT → REPLACEMENT adjacency:

| Cases | Actual boundary exercised |
| --- | --- |
| BASELINE; POST_FAULT_BASELINE; POST_OBSERVATION_BASELINE; POST_STATE_BASELINE; POST_REPEATED_STATE_BASELINE | Complete ordinary baseline: immutable programmatic 12×1×5 stone plan, 60 targets, ID2 initially stone and 59 real placements. Native movement exceeds measured reach; both outer columns, support hold/resumed motion, prediction→OBSERVED, explicit pause, screen/manual resume, full unique 60-target OBSERVED verification and independent server blocks/inventory are required. Reserved slot0 stays stone 7, usable stock finishes 5. Each pause/terminal hold measures 20 real client/server physics ticks. |
| STOP; FAILURE; DISCONNECT; REPLACEMENT | Native cancellation, material-starved SUPPORT_TIMEOUT, owned disconnect followed by fresh-world idle, and actual dimension-replacement cancellation; released controls and ordinary physics are checked. |
| WITHHELD_RESPONSE | A real cumulative action response is withheld at the native invocation. Matching stone remains PREDICTED/pending despite all 60 correct server blocks; the engine reaches FAILED/PENDING_EVIDENCE_TIMEOUT at eligible age 80, then releases for 20 physics ticks. |
| WITHHELD_OBSERVATION | Genuine accepted outer target 55, raw PREDICTED then OBSERVED stone and honest support; the placement-list producer continuously omits 55 through VERIFY and terminal/release. E50/V52/T130 proves pending80 after VERIFY rather than false SUCCESS. |
| COMPUTED_STOP; COMPUTED_PAUSE_RESUME | Real stop or synchronous same-UUID pause/resume after computation but before effects rejects the old callback's effects. Later fresh callbacks can complete the resumed run. |
| WITHHELD_STATE | Actual production stone action 0, suppressed matching state, normal response PRE/POST restoring original air, then the next genuine production OBSERVED-air mismatch leaves target 0 unfinished before retry. Real stop/release20 follows; independent server still has 60 stone and 7/63 stock. |
| REPEATED_STATE | Separately labeled native-only survival snow 2/selected 1 fixture: actual sequence n1=1 creates layer 1; a second real interaction at n1 PRE creates layers 2/n2=2. Immediate older POST retains layers 2/pending; later covering POST removes pending and restores original air. Server independently has layers 2, other 59 stone and exhausted snow. No production ACCEPTED placement or building credit is assigned. |

Fault cases use explicitly hashed causal prefill variants; they are not full-building baselines. A `success.png` in a fault directory means the fixture's assertion passed, even when its production run correctly FAILED or was CANCELLED. The unchanged full ordinary baseline runs after each fault family. Controls include all nine keys and actual ownership; release checks observe unedited physics rather than freezing position. C8 disconnect/screen-cleanup/binding-fallback limits remain, and `constantSprint=false` does not prove every sprint/sticky option.

OBSERVED means loaded client-world state without an outstanding vanilla prediction; it is not a fresh per-attempt server receipt. Accepted interactions and completed traversal cannot establish success without the full final scan. Immediate native PRE/POST diagnostics, observer recaptures, production capture samples and eligible ticks are distinct coordinates.

## What the probe does

The `mapart-test` fixture mod preserves `EnvironmentProbeGameTest` alongside `GroundedSingleLaneGameTest`. The original probe uses `ClientGameTestContext.worldBuilder().create()` with Fabric's consistent settings (flat, seed 1) to create and join a disposable integrated-server world.

`ScenarioEnvironment` owns one invocation. It creates a UUID evidence directory before creating the world. Server-thread setup creates a bounded bedrock platform at Y=64, air target `(0,65,0)`, chest `(2,65,0)` containing 16 stone, and a creative player at `(0.5,65,2.5)` with 8 stone in selected slot 0. Client-thread polling observes the fixture, inventory and teleport. The client calls `ClientPlayerInteractionManager.interactBlock` on the support's top face. A separate test-thread loop reads the server's target block with `computeOnServer`, waiting at most `DEFAULT_TIMEOUT` (200 ticks, 10 seconds of simulation). Client block observation follows the independent server assertion. Chunk rendering is also bounded to 200 ticks before the screenshot.

Context methods are called only on the gametest thread. `runOnServer`/`computeOnServer` and `runOnClient`/`computeOnClient` marshal game work; client wait predicates never call server contexts. The world context closes before PASS is recorded. Failures capture a screenshot before world cleanup where possible, preserve the exception, and propagate to Fabric. There are no reset hooks, commands or production fault controls. Each invocation creates a new world; stop/cleanup ends with the context closing, and no fixture state is retained across invocations.

The pinned screenshot implementation waits for its screenshot future without a deadline. A stalled screenshot can therefore block success, failure capture, world cleanup and rethrow; the 200-tick checks do not bound the whole run. Use the wall-clock process supervision below for every launch. It covers startup, screenshots and shutdown without calling game-context APIs from a timer thread.

## Selection supported by the pinned API

Fabric API **0.141.1+1.21.11** includes client gametest module **4.3.5+bfe9fcf43e**. Its `TestSystemProperties.MOD_ID_FILTER_PROPERTY` is `fabric.client.gametest.modid`; `FabricClientGameTestRunner.getTestToRun()` parses a comma-separated list of **mod IDs** and filters entrypoint providers. It does not select Java methods/classes. Invalid/unloaded IDs and an empty matching set throw. A normal launch runs both registered `fabric-client-gametest` entrypoints: `EnvironmentProbeGameTest` and `GroundedSingleLaneGameTest`. The latter dispatches all 15 lane cases above; this property cannot select one case. Test-only `mapart-test.mixins.json` registers WithheldActionResponseMixin, WithheldObservationMixin, ComputedDecisionBarrierMixin and WithheldBlockStateMixin, required with defaultRequire=1/JAVA_21. These are excluded from the production archive.

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
Remove-Item Env:MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS -ErrorAction SilentlyContinue
Remove-Item Env:MAPART_STATE_FAULT_FORCE_TARGET_RELOAD -ErrorAction SilentlyContinue
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

Require exit code 0, exactly one PASS/no FAIL per probe/lane case, normal owned world closes, actual independent server assertions and readable screenshots. A full unflagged launch contains the probe plus 15 lane cases. Verify zero remaining owned processes; do not select unrelated JVMs by name. Open the screenshot to check the actual rendered world. The game directory is `v2/fabric/build/run/clientGameTest`; disposable saves remain there until Loom clears the directory on a later run. Retained probe artifacts are under `v2/fabric/build/scenario-results/environment-probe-<UUID>/`:

- `probe.log`: start time, versions, canonical fixture and SHA-256, seed/settings, world save path, stages, interaction result, independent server observation or timeout, screenshot path, terminal PASS/FAIL and failure trace.
- `success.png` or `failure.png`: actual Fabric screenshot; screenshot errors propagate or attach to the primary failure.
- `minecraft-latest.log`: snapshot of the game log at completion/failure; launcher transcript also retains the eventual framework exception and exit outcome.

Lane artifacts are under `v2/fabric/build/scenario-results/single-lane-<KIND>-<UUID>/`: `fixture.txt`, `scenario.log`, screenshot, `minecraft-latest.log` and applicable `fault-diagnostics.txt`/`state-diagnostics.txt`. Preserve fixture hash, settings/versions, run/world identities, original action/sample coordinates, mismatches, actual SINGLE/DELTA route counts and release/close evidence. The unique artifact path is printed to stdout before world creation. Failures before probe construction are covered by the launch transcript; no screenshot is promised if Minecraft cannot initialize graphics. Archive relevant crash reports from `build/run/clientGameTest/crash-reports` into the same launch evidence directory before another launch if startup crashes.

## Deliberate negative proof

After the normal run passes, repeat the launch script in another fresh PowerShell process, replacing its `Remove-Item Env:MAPART_PROBE_WRONG_EXPECTED_BLOCK ...` line with:

```powershell
$env:MAPART_PROBE_WRONG_EXPECTED_BLOCK = '1'
```

This flag exists only in gametest source. The client still places stone, but the independent server check now expects gold. Require a **nonzero** Gradle/game exit, `FAIL`, a server timeout after 200 ticks with `expected=minecraft:gold_block, actual=minecraft:stone`, and `failure.png`. Require `timedOut=False`: an outer wall-clock timeout (`124`) or unrelated startup, inventory, thread, screenshot or graphics error does not prove the negative assertion. Retain both UUID directories and launch transcripts. Run without the flag again if a baseline recovery check is needed; never weaken the expected block to get green.

For lane negatives, use the same complete supervisor in another fresh process, retain all three normal flag removals, then set **only one** of the following flags immediately before constructing the worker:

| Test-only flag | Required specific normal nonzero outcome |
| --- | --- |
| `$env:MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS = '1'` | `Deliberate false SUCCESS expectation: withheld ID55 producer evidence correctly caused PENDING_EVIDENCE_TIMEOUT after VERIFY`, after actual omission/VERIFY/pending80 and 20 released physics ticks. |
| `$env:MAPART_STATE_FAULT_FORCE_TARGET_RELOAD = '1'` | `DELIBERATE TARGET_CHUNK_RELOAD`: finish main state proof/stop/release20, queue the actual already-loaded owned chunk once through native `ChunkDataSender.add`, let ordinary server ticks send it, require the armed/loaded BEFORE-import guard's first fatal, then later matching client stone/absent pending with the same binding and fatal retained. This test-forced native resend cannot turn into PASS; later matching state is not attributed exclusively to the import payload. |

Both lane flags accept absent/empty ordinary mode or exactly `1`; other nonempty values fail validation. Require exact causal diagnostics, FAIL, inspected `failure.png`, normal owned cleanup, ordinary Java/Gradle nonzero exit and `timedOut=False`. A negative stops the suite at its intentional failure; cases after it did not run. Follow it with a genuinely separate full unflagged recovery when required. Unrelated assertion/startup crash, native `0xC0000409`, explicit abort or outer timeout never substitutes for the specific negative. The mod filter always runs all registered entrypoints; no class/method selector is introduced.

## Historical probe execution evidence

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

Both retained the same fixture hash, complete launch/probe evidence and rendered stone/chest/hotbar screenshots. No owned root or related game JVM remained afterward. The controlled comparison supports the documented AWT-dialog bypass but does not identify the native fault mechanism, establish that Minecraft works without a display or diagnose the original fault from a dump. Later retained Task4.2 evidence includes another `0xC0000409` negative shutdown even with this property, followed by an unchanged normal exit 1 comparison. The bypass remains useful but is not a universal native-shutdown fix; the native fault site remains unknown.

This Windows host initialized LWJGL 3.3.3-snapshot and rendered through LLVMPipe after `vm3dgl` could not initialize its SVGA driver. That observed software-rendering fallback was sufficient for these actual screenshots; it does not establish hardware acceleration or headless/clean-machine compatibility. Retained startup messages also include development-classpath/no-main-Java, default anisotropic-option and offline test-profile/Realms lookup warnings; no run failed on those paths.

## Accepted lane execution evidence

The final state-fault source is `d96bbcaef1cb0e62698f143dbed32f0c2645483b`, published into the same accepted source tree at `a2f23447d21f855e15913ff07189cce21b8b6be5`. These are retained local executions, not new Task12 game launches. All paths below are under `v2/fabric/build/scenario-results/`; the accepted final-source pair used identical source, settings, JDK21, supervisor and simulation deadlines.

| Evidence | Launch directory | Actual outcome |
| --- | --- | --- |
| C9 held response and fresh baseline | `launch-task-9-positive-2e961b63-438f-4fbb-9050-11a931529869` | Probe + 7 lane cases passed; actual pending80 fault and full ordinary recovery. Exit 0, timedOut=False. |
| C10 producer-omission deliberate negative | `launch-task-10.1-final-negative-c9543e3e-484f-46aa-98d2-071c4ded3691` | Exact false-SUCCESS assertion after ID55 omission/VERIFY/pending80/release. Ordinary Java/Gradle 1, timedOut=False. |
| C10 separate full recovery | `launch-task-10.1-final-positive-4ec7bd6b-822d-4d38-ad28-53a2141552b5` | Probe + 9 lane cases passed, including full fresh POST_OBSERVATION_BASELINE. Exit 0, timedOut=False. |
| C10 computed lifecycle barriers | `launch-task-10.2-positive-55470714-d433-41ac-b588-3adbceeaf3d3` | Probe + 11 lane cases passed, including computed stop and same-UUID pause/resume. Exit 0, timedOut=False. |
| C10b final forced-import negative | `launch-task-11-final-negative-5efb2f68-22fa-4c30-838a-54305e9e522e` | Exact DELIBERATE TARGET_CHUNK_RELOAD, later matching stone did not erase fatal; normal owned close. Ordinary Java/Gradle 1, timedOut=False. |
| C10b genuinely separate full recovery | `launch-task-11-final-positive-fef0c768-6d24-4462-87d6-45de0b1c18d8` | Probe + 15 lane cases, each exactly 1 PASS/0 FAIL; all worlds closed, zero owned JVMs. Exit 0, timedOut=False; 3m18s. |

The final positive main state case is `single-lane-WITHHELD_STATE-e77a2c8a-e161-41f0-91a7-4bc1bcdc347e`: genuine production action 0/target 0/sample834, normal sequence1 PRE3/POST4, then actual START/END3 eligible2→3 consumes OBSERVED air and retains unfinished0 before retry. Main suppressed SINGLE=2, DELTA=0. The native-only `single-lane-REPEATED_STATE-0ee749c7-4b2c-4014-8240-a549f0159295` records FIRST_NATIVE1, actual PRE4, SECOND_NATIVE5, older POST6 still layers 2/pending and covering POST10 air/nonpending; SINGLE=4, DELTA=0. **DELTA has source/compile coverage only, no live route credit.** Both following ordinary baselines used the unchanged full baseline and finished with independent server 60/stock7+5. The forced-negative case `single-lane-WITHHELD_STATE-d5e7917c-4068-4561-aedc-b37dcab7c4e9` records the actual loaded target-chunk BEFORE-import ordinal5 at age41/eligible3/armedAge17, within the unchanged 200-physics scope; SINGLE=2, DELTA=0.

All 16 final positive screenshots and the final negative failure screenshot were inspected by the owning task; Task12 additionally opened the final negative, repeated-native and POST_REPEATED_STATE_BASELINE images. These show the matching surface/7+63, empty snow slot/original-air hole and full ordinary surface/7+5 respectively. Screenshots supplement the native event/callback/independent-server assertions. Gametest compilation during those launches was UP-TO-DATE after actual final changed-source compilation. Separate earlier remap invocations were also UP-TO-DATE; those are unchanged-artifact inspection evidence, not fresh release builds.

Earlier failures remain evidence: C10.1's first negative failed the unchanged probe due to an inactive-hook off-client access; the hook was corrected before final-source proof. Its `launch-task-10.1-final-negative-6229bc0e-5411-4b92-969d-4b86a4198029` instead crashed in pinned Fabric's SERVERBOUND 10-second wait during STOP world creation, before setup/start/arm. That unrelated crash is not the omission negative. The source-supported lost-notification hypothesis has no recorded historical counters/interleaving and is not a verified cause or repair.

C10b's pre-correction `launch-task-11-negative-60cc54c9-06ed-4fa2-b386-0fb224589f12` reached the specific import negative, while `launch-task-11-positive-af06dc82-7353-487f-91cc-cefa36d8def4` passed the probe + 13 lane cases then failed REPEATED_STATE setup before start/arm: server-only selection had not selected client snow. The approved public client selection setter was added only in BEFORE-start setup, preserving readiness validation and the no-poststart-correction rule. Both required launches were rerun on final source; the partial run is not repeated-native credit. Retain those failures rather than substituting the final recovery for them.

A separate earlier pre-probe startup phase/semaphore stall was explicitly aborted (`exitCode=-1`, timedOut=False), not naturally completed. Pinned Fabric permits a late-registration race matching the waits, but actual registration ordering was not captured. That stall, the SERVERBOUND timeout and the unexplained native shutdown are distinct signatures. Later successful runs do not establish host stability or repair. LLVMPipe/SVGA fallback, audio, anisotropic-option, development-authentication and lag diagnostics remain disclosed host limits.

## Settled-source verification

On **28 October 2026**, Task12 ran exactly one full local verification at revision `a2f23447d21f855e15913ff07189cce21b8b6be5`, tree `682d73e131a9b9b481daaede696130c46848507d`, with process-local `JAVA_HOME=C:/Users/Bryce/.jdks/ms-21.0.10`, from repository root:

```powershell
.\gradlew.bat -p v2 :core:test :fabric:test :fabric:compileGametestJava :fabric:remapJar --no-daemon --console=plain --rerun-tasks
```

Actual native exit 0; BUILD SUCCESSFUL in 43s, 12 actionable tasks/12 executed. Core test, Fabric test, gametest compilation and release remap all actually executed. Fresh XML from all 8 core suites and 4 Fabric suites was archived and parsed: **327 core + 187 Fabric = 514 tests; failures=0/errors=0/skipped=0 in both modules**. These are measured results, not thresholds or reuse of historical counts. Reports originate at `v2/core/build/test-results/test/TEST-*.xml` and `v2/fabric/build/test-results/test/TEST-*.xml`; HTML is under each module's `build/reports/tests/test/`. All 45 tracked source/resource/build inputs hashed before the run matched afterward. No v1/shared-root change required a v1 rerun.

Retained final evidence is `.superpowers/sdd/2026-10-28-v2-tick-boundary/task-12-verification-d0869621-05ae-40da-8776-f067bc32b6cb/`: `verification-input.json`, complete `gradle.log`, `native-exit.txt` captured immediately before any log read, `source-hashes-before.json`/`source-hash-check.json`, archived `core-xml/`/`fabric-xml/`, `suite-counts.json`, `release.jar` and `release-inspection.json`. Future wrappers must likewise store native `$LASTEXITCODE` before tailing/reading logs; a successful log-display command is not compiler evidence. A cached/UP-TO-DATE test task is not a fresh test execution. The log retains the Gradle 10 deprecation warning; it does not fail the build.

The actually remapped release `v2/fabric/build/libs/mapartrunner-v2-2.0.0-dev.jar` is 133099 bytes, SHA256 `bb63ad40857e7d31e638137cb470f65791e825334c1517cbeca1fbbd1c4013f4` (same bytes as the previously inspected archive). Fresh outer 41/nested-core 58 entry/content inspection found no scenario/fixture/gametest/fault classes, test configuration/entrypoints or any of the three negative flags. Exact production metadata: id `mapart`, version `2.0.0-dev`, client environment, sole client entrypoint `com.mapartrunner.fabric.MapArtClient`, sole client config `mapart.client.mixins.json`, nested `META-INF/jars/mapartrunner-v2-core-2.0.0-dev.jar`. The required JAVA_21/minVersion=0.8.7 config contains exactly `ClientWorldPendingUpdateAccessor` and `PendingUpdateManagerMixin` in `com.mapartrunner.fabric.mixin`; nested generated core mod id is `com_mapartrunner_core`. Unit recording sinks establish policy/order; actual game assertions independently establish resulting blocks. Archive inspection does not grant release or installation approval.

Hosted checks at the same accepted verification revision `a2f23447d21f855e15913ff07189cce21b8b6be5` also passed (jobs 122618504568/122618482604), with nontruncated logs showing actual core test, Fabric test and gametest compilation plus core=327/failures=0/errors=0/skipped=0. Their existing cold-Loom remap-modifier and Gradle deprecation warnings, plus Ubuntu26 migration notice, remain recorded. The Ubuntu/Temurin21 workflow runs `:core:check :fabric:compileJava :fabric:compileGametestJava :fabric:test` and parses only core XML totals; it neither runs Minecraft nor remaps a release. No hosted Fabric count or CI gameplay/headless equivalence is inferred. Task12 adds no game launch because the accepted final-source negative/recovery pair already covers unchanged behavior. Whole-6c independent review and broader replacement gates remain pending.

## Remaining limits

The production single-lane controls/evidence boundary has actual local managed client/server evidence. The first normal human build check remains Checkpoint B after the later real schematic/import/entrypoint setup is implemented and independently tested; this Java fixture does not supply that workflow. A useful visual check then is lane entry, yaw/head/body stability, outer placement and pause/stop release.

All broad preservation/correction rows remain owned by their later gates in [behavior-parity.md](behavior-parity.md): supported import/materials, commands/aliases/UI, two-lane/partial/reverse/resume, persistence/settings/keys/HUD/overlays/helpers/timer, recovery/stall behavior, genuine Baritone refill/return, reset repeatability/soak and dedicated-server variation. Keep v1 usable. No automatic installation/migration, full parity, release-readiness or replacement claim follows from this milestone. Whole-6c independent review remains separate from these task results.
