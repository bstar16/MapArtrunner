# V2 managed gameplay verification

The public test script runs disposable Minecraft instances and retains their evidence. Local evidence on **30 October 2026** covers the imported single-lane cases and a bounded two-lane forward build/reset sequence. Full v1 parity and replacement remain pending.

## Quick start

Use **Windows, PowerShell 7, an installed JDK 21 with both `java.exe` and `javac.exe`, and working Minecraft graphics**. From the repository root, after explicitly accepting the [Minecraft EULA](https://www.minecraft.net/eula) for these disposable instances, run:

```powershell
pwsh -NoProfile -File scripts/test-v2.ps1 -Scenario grounded-two-lane -Repeat 2 -EulaAccepted
```

This runs an expected failed prelude followed by two independently checked builds in one owned world. Keep the terminal open until the script returns; inspect the printed evidence directory and rendered screenshots afterward. No Java edits, private helper or local evidence archive is needed to run it.

To run all existing cases and the new two-lane sequence:

```powershell
pwsh -NoProfile -File scripts/test-v2.ps1 -EulaAccepted
```

The script discovers JDK 21 from `JAVA_HOME`, PATH and bounded standard installation locations, rejecting candidates without both version-21 tools. If needed, add `-JavaHome 'C:\path\to\jdk-21'` using your actual installation directory; an invalid explicit path fails rather than falling back. Alternatively, an operator who has accepted the EULA may set process-local `MAPART_TEST_EULA_ACCEPTED=1` and omit `-EulaAccepted`; other values do not grant acceptance. Neither command accepts terms on the operator's behalf.

Use the independent v2 build. Never install/load v1 and v2 together. Production registers `com.mapartrunner.fabric.MapArtClient` and has a Path-based schematic importer; normal command and UI wiring remain later parity work. These automated scenarios do not provide an in-game `/mapart load/start` recipe or replace v1.

## Ordinary inspect session

The separate inspect launch is wired for a managed two-lane world and in-game commands:

```powershell
pwsh -NoProfile -File scripts/test-v2.ps1 -Inspect -Scenario grounded-two-lane -EulaAccepted
```

Keep the supervisor terminal open. After real world binding and command readiness, use `/maparttest reset grounded-two-lane`, `/maparttest run grounded-two-lane`, `/maparttest rerun grounded-two-lane` or read-only `/maparttest last`. Run requires a successful reset; rerun performs reset→run→assert in one operation. Mutation requests stay busy through cleanup. Close the client normally when finished. Readiness and process exit0 do not establish a successful scenario; inspect each owned operation's asserted result/evidence.

`-Inspect` requires the explicit exact selector and forbids explicit `-Repeat` (including 2), `-WrongExpectedBlock` and support controls. It reuses EULA provenance and local JDK 21 discovery, with no persistent environment changes. It selects ordinary Loom `:fabric:runInspect`, fixture classes/resources and gated client initializer, using only `v2/fabric/build/run/inspect` for game state and copied logs/crashes. It does not adopt/copy a saved world or clear the automatic game directory. Inspect omits `mapartTestEula=true`, whose pinned automatic EULA task also deletes the automatic directory; explicit operator acceptance remains required and recorded.

Inherited JVM options in `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS` and `GRADLE_OPTS` are checked before launch work. Inspect rejects any `fabric.client.gametest` property, including false/empty, and an inherited `mapart.test.inspect` value other than case-insensitive true. Automatic launches reject inherited `mapart.test.inspect=true`. JVM property names are case-sensitive. Compatible diagnostics/inactive settings remain intact; inspect appends no automatic provider/filter/headless settings. Do not add `fabric.client.gametest=false`: the pinned module enables on presence.

Inspect uses the existing owned-process/receipt/archive supervisor: startup remains 600000 ms, an admitted WORK gets 600000 ms with bounded cleanup, and CLOSE shares one 10000 ms ceiling. Idle readiness does not renew automatic clocks or grant PASS. Configuration/classpath resolution and compilation are source checks only. Native loaded classpath, property isolation, mixins, real session startup and normal/pending shutdown acceptance remain open; this source slice launches no Minecraft.

For counted ordinary inspect TWO_LANE runs, construction and SHIFT pause share the fixture's unchanged `N=runTicks` explicit wait-request allowance. Each attempted `waitTick` is charged before calling the Host, including rejected clock samples and calls that block or throw. Pause requires twenty accepted independent physics intervals, with client age/time and server age/time/tick all strictly advancing from the last accepted sample; equal or corrected clocks never become a new anchor. Every candidate still checks retained engine/run/traversal progress, native simulation, controls, native issuance and the original binding. After the existing verified-terminal and pre-release assertions, release may spend unused construction requests plus its original twenty: construction usage `C` permits at most `N-C+20` release requests, with total construction/release requests at most `N+20`. Construction cannot borrow the release reserve. Release still requires twenty accepted independent intervals, final observer advancement and the current full-plan/client/server checks. Request counts do not measure elapsed native ticks. Compact numeric attempt records retain exact clock pairs and predicate bits before candidate assertions; request exhaustion and original Host/scope failures remain hard failures. The 600-second WORK deadline, separate cleanup authority, dense 6000-sample/witness caps and fixture 200/1200/120/20 values remain unchanged. Automatic baseline, dirty prelude/release, compact traversal, readiness and screenshot waits keep their existing routes and caps, including compact's exact twenty native release ticks/server records. This source change supplies no new native acceptance evidence.

## Compile without launching

Use an installed **JDK 21** in a fresh PowerShell process. Set `JAVA_HOME` to its installation directory in that process if it is not already configured; otherwise these snippets discover the JDK from `javac` on PATH. They validate the version and do not change user/machine environment settings:

```powershell
if (!$env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source)
}
$probeJavaVersion = (& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
if ($probeJavaVersion -notmatch '^javac 21(?:\.|$)') { throw 'Set process-local JAVA_HOME to an installed JDK 21.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat -p v2 :core:test :fabric:test :fabric:compileGametestJava :fabric:remapJar --no-daemon --console=plain
```

This compiles the actual Yarn-mapped API calls and produces `v2/fabric/build/libs/mapartrunner-v2-2.0.0-dev.jar`. Compilation proves mapped signatures, not runtime behavior. Do not use root `build` (v1), v2 `build`, or game tasks merely to compile. A task marked UP-TO-DATE reused its prior result; it did not freshly execute. [Current build/release evidence](#current-build-and-release-evidence) and historical results below retain that distinction.

## Production runtime and managed lane cases

The actual public client-thread facade is `MapArtClient.start(BuildPlan plan, BuildPlan.GridPos origin, BuildEngine.Settings settings): UUID`, `pause(): void`, `resume(): void`, `stop(): void` and `status(): FabricBuildAdapter.Status`. The production initializer registers one `END_CLIENT_TICK` callback, plus world-change, disconnect, screen-opening and client-stopping cleanup. The fixture calls this facade; it neither constructs a second engine nor supplies engine ticks. Test START/END observers read status/client evidence without advancing core. Independent server reads are test assertions and never production observations.

`GroundedSingleLaneGameTest` creates a fresh managed world for each of these 15 enum-dispatched cases, preserving DISCONNECT → REPLACEMENT adjacency:

| Cases | Actual boundary exercised |
| --- | --- |
| BASELINE; POST_FAULT_BASELINE; POST_OBSERVATION_BASELINE; POST_STATE_BASELINE; POST_REPEATED_STATE_BASELINE | Complete ordinary baseline: production import of checked-in 12×1×5 stone schematic, 60 targets, ID24 initially stone and 59 physical placements. Native movement exceeds measured reach; outer IDs0/48, support hold/resumed motion, prediction→OBSERVED, explicit pause, screen/manual resume, full unique 60-target OBSERVED verification and independent server blocks/inventory are required. Reserved slot0 stays stone 7, usable stock finishes 5. Each pause/terminal hold measures 20 real client/server physics ticks. The historical imported evidence retains 58 distinct ACCEPTED snapshots; see its evidence limit below. |
| STOP; FAILURE; DISCONNECT; REPLACEMENT | Native cancellation, material-starved SUPPORT_TIMEOUT, owned disconnect followed by fresh-world idle, and actual dimension-replacement cancellation; released controls and ordinary physics are checked. |
| WITHHELD_RESPONSE | A real cumulative action response is withheld at the native invocation. Matching stone remains PREDICTED/pending despite all 60 correct server blocks; the engine reaches FAILED/PENDING_EVIDENCE_TIMEOUT at eligible age 80, then releases for 20 physics ticks. |
| WITHHELD_OBSERVATION | Genuine accepted outer target 11 at `(11,0,0)`, raw PREDICTED then OBSERVED stone and honest support; the placement-list producer continuously omits 11 through VERIFY and terminal/release. E50/V52/T130 proves pending80 after VERIFY rather than false SUCCESS. |
| COMPUTED_STOP; COMPUTED_PAUSE_RESUME | Real stop or synchronous same-UUID pause/resume after computation but before effects rejects the old callback's effects. Later fresh callbacks can complete the resumed run. |
| WITHHELD_STATE | Actual production stone action 0, suppressed matching state, normal response PRE/POST restoring original air, then the next genuine production OBSERVED-air mismatch leaves target 0 unfinished before retry. Real stop/release20 follows; independent server still has 60 stone and 7/63 stock. |
| REPEATED_STATE | Separately labeled native-only survival snow 2/selected 1 fixture: actual sequence n1=1 creates layer 1; a second real interaction at n1 PRE creates layers 2/n2=2. Immediate older POST retains layers 2/pending; later covering POST removes pending and restores original air. Server independently has layers 2, other 59 stone and exhausted snow. No production ACCEPTED placement or building credit is assigned. |

Fault cases use explicitly hashed causal prefill variants; they are not full-building baselines. A `success.png` in a fault directory means the fixture's assertion passed, even when its production run correctly FAILED or was CANCELLED. The unchanged full ordinary baseline runs after each fault family. Controls include all nine keys and actual ownership; release checks observe unedited physics rather than freezing position. C8 disconnect/screen-cleanup/binding-fallback limits remain, and `constantSprint=false` does not prove every sprint/sticky option.

OBSERVED means loaded client-world state without an outstanding vanilla prediction; it is not a fresh per-attempt server receipt. Accepted interactions and completed traversal cannot establish success without the full final scan. Immediate native PRE/POST diagnostics, observer recaptures, production capture samples and eligible ticks are distinct coordinates.

## What the probe does

The `mapart-test` fixture mod preserves `EnvironmentProbeGameTest` alongside the lane/import providers and appended `GroundedSmokeGameTest`. The original probe uses `ClientGameTestContext.worldBuilder().create()` with Fabric's consistent settings (flat, seed 1) to create and join a disposable integrated-server world.

`ScenarioEnvironment` owns one invocation. It creates a UUID evidence directory before creating the world. Server-thread setup creates a bounded bedrock platform at Y=64, air target `(0,65,0)`, chest `(2,65,0)` containing 16 stone, and a creative player at `(0.5,65,2.5)` with 8 stone in selected slot 0. Client-thread polling observes the fixture, inventory and teleport. The client calls `ClientPlayerInteractionManager.interactBlock` on the support's top face. A separate test-thread loop reads the server's target block with `computeOnServer`, waiting at most `DEFAULT_TIMEOUT` (200 ticks, 10 seconds of simulation). Client block observation follows the independent server assertion. Chunk rendering is also bounded to 200 ticks before the screenshot.

Context methods are called only on the gametest thread. `runOnServer`/`computeOnServer` and `runOnClient`/`computeOnClient` marshal game work; client wait predicates never call server contexts. The world context closes before PASS is recorded. Failures capture a screenshot before world cleanup where possible, preserve the exception, and propagate to Fabric. There are no reset hooks, commands or production fault controls. Each invocation creates a new world; stop/cleanup ends with the context closing, and no fixture state is retained across invocations.

The pinned screenshot implementation waits for its screenshot future without a deadline. A stalled screenshot can therefore block success, failure capture, world cleanup and rethrow; the 200-tick checks do not bound the whole run. Use the wall-clock process supervision below for every launch. It covers startup, screenshots and shutdown without calling game-context APIs from a timer thread.

## Selection and exact case manifest

Automatic mode accepts case-sensitive `-Scenario all|grounded-two-lane|grounded-traversal` and `-Repeat 1|2`; defaults are `all` and `2`. For two-lane, Repeat counts successful starts requested after its dirty failed prelude: Repeat1 has two starts; Repeat2 has three. Traversal repeats each of its seven fixture profiles. Repeat does not repeat the 17 old cases. `-WrongExpectedBlock` requires automatic `-Scenario grounded-two-lane`.

A full default invocation starts these five providers in order:

1. `EnvironmentProbeGameTest`: one probe.
2. `GroundedSingleLaneGameTest`: BASELINE, STOP, FAILURE, DISCONNECT, REPLACEMENT, WITHHELD_RESPONSE, POST_FAULT_BASELINE, WITHHELD_OBSERVATION, POST_OBSERVATION_BASELINE, COMPUTED_STOP, COMPUTED_PAUSE_RESUME, WITHHELD_STATE, POST_STATE_BASELINE, REPEATED_STATE, POST_REPEATED_STATE_BASELINE.
3. `SchematicImportGameTest`: one import/empty case.
4. `GroundedSmokeGameTest`: the two-lane failed-prelude/reset/repeat invocation.
5. `TraversalGameTest`: seven managed traversal fixture profiles with the selected repeat count.

Historical four-provider results below contain 17 old cases plus one two-lane invocation (18 published case directories); they do not prove traversal. The recovery also produced two lane-import directories and one archive self-check directory; these auxiliaries are not extra passing cases. Selected runs mark unselected providers `NOT_EXECUTED`, never PASS. A fatal assertion stops execution; later cases or starts receive no credit.

The pinned Fabric API **0.141.1+1.21.11**, client gametest module **4.3.5+bfe9fcf43e**, filters entrypoints by mod ID through `fabric.client.gametest.modid`; it does not select Java methods/classes. The public script selects `mapart-test`, and the project's own validated scenario/repeat properties control provider dispatch. Gradle transports those properties into the actual game JVM. No method-name filter or private selector is required. The [test metadata](../fabric/src/gametest/resources/fabric.mod.json) and [test mixin configuration](../fabric/src/gametest/resources/mapart-test.mixins.json) are excluded from production.

The child environment clears the old probe/observation/state negative flags and ordinary support selector, setting only the two-lane wrong-expectation flag when requested. Automatic mode retains compatible operator `JAVA_TOOL_OPTIONS` and appends the mod filter and `-Djava.awt.headless=true`. The latter disables Loader's AWT error dialog; Minecraft still needs GLFW/LWJGL graphics and renders actual screenshots. It is not headless gameplay or a universal native-shutdown repair.

Pinned API references: [client context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.html), [server context](https://maven.fabricmc.net/docs/fabric-api-0.141.1%2B1.21.11/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerContext.html), [source JAR](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-client-gametest-api-v1/4.3.5%2Bbfe9fcf43e/fabric-client-gametest-api-v1-4.3.5%2Bbfe9fcf43e-sources.jar). Implementation uses Yarn **1.21.11+build.4** names.

## Supervision and retained evidence

[The public script](../../scripts/test-v2.ps1) owns a hidden worker and its Gradle/game descendants through a Windows Job Object before allowing the worker to launch. Automatic startup is **600000 ms**; selected two-lane overall is **600000 ms**, while all/traversal overall is the checked `600000 + 50 * repeat * sum(seven runTicks)` allowance. Neither clock renews at readiness. It uses `--no-daemon`, up to **1000 ms** normal descendant drain within the applicable deadline, and a shared **10000 ms** process cleanup allowance. JDK probes have a 5000 ms wait and bounded cleanup. These are infrastructure limits, not gameplay timing goals or a bound on every preflight/archive operation.

The exclusive `v2/fabric/build/scenario-results/supervisor.lock` prevents overlapping launches from clearing each other's game directory. A nonempty marker blocks rerun until ownership/evidence retention is resolved. Preserve the marker and evidence on failure; do not bypass it or kill unrelated Java processes.

| Outcome | Interpretation |
| --- | --- |
| `0` | Require observed native exit0, expected case assertions, normal closes, `TimedOut=false`, `CleanupStatus=settled`, `ArchiveStatus=retained`, and no supervisor/cleanup/archive error. |
| Ordinary nonzero, including `1` | Actual worker failure is propagated; inspect the native exit and exact assertion. A known negative remains a failed invocation. |
| `124` | Outer deadline failure; not proof of a gameplay assertion. A previously captured nonzero worker failure is retained if cleanup reaches the deadline. |
| `125` | Preflight/startup/supervision failure, or an otherwise successful run with unresolved cleanup/archive/native-exit evidence. Inspect the detailed outcome. |

Evidence is printed as `v2/fabric/build/scenario-results/launch-<UUID>/`: `request.json`, `outcome.json`, `exit-code.txt`, raw `launch.stdout.log`/`launch.stderr.log`, copied game logs/crash reports and before/after scenario inventories. Inspect launches and historical archives retain physical `scenarios/` copies. New automatic launches retain each fresh original UUID scenario root exactly once at `v2/fabric/build/scenario-results/<scenario-UUID>/`; `scenario-references.json` replaces the additional supervisor payload copy. Runner phase archives and separate complete changed-game retention remain required. A failed launch may have no scenario or screenshot if graphics/startup never reached the fixture; a closed empty root set is truthful startup evidence, not game success.

The automatic manifest schema is `mapartrunner-scenario-references-v1`. It binds the actual launch ID, a fresh closure ID, exact original root location and the selected after-minus-before root set captured under the same supervisor lock. Each root includes its complete ordinary directory/file set (including empty directories), relative paths, file sizes and SHA-256 hashes, plus opaque link type/target/attributes. Live and dangling links are never followed. A top-level link is opaque evidence and cannot satisfy an ordinary provider-root requirement. `reparse-points.json` continues to retain opaque mutable-log/crash link metadata; inspect archives also record their copied-scenario link metadata there.

Only the existing finalizer's settled cleanup permits closure. The writer flushes a partial manifest, reads it back and verifies the complete payload before publishing the closed filename, then verifies the published manifest again before retained status/outcome/lock release. Partial manifests are not consumable archives. Unresolved cleanup still retains available inventories/logs/crashes, fails reference retention and preserves the nonempty lock and original failure. Reference failures change an otherwise successful exit to125; worker/native failures and timeouts retain precedence. There is no duplicate-copy fallback.

Reference-aware review must first require the actual outcome's `CleanupStatus=settled` and `ArchiveStatus=retained`, then pin the closed manifest hash, launch/closure identity and exact original root set in the cohort receipt. Within a dot-sourced `scripts/test-v2.ps1`, the existing-owner seam `Read-V2ScenarioReferences -Evidence <exact-launch-directory> -ScenarioRoot <exact-scenario-results-root> -Owner <repository> -LaunchId <expected-launch-UUID> -ClosureId <pinned-closure-UUID>` validates the schema/identity/inventories and regenerates every referenced entry/hash before returning the manifest. The writer establishes the first closure ID; subsequent consumers must supply the pinned ID and verify their receipt's manifest hash before consuming results or images. Select each provider's exact named ordinary root and reject missing, added, changed, ambiguous or redirected ordinary entries. Never search similar UUIDs or substitute a current-game pointer. Historical physical archive consumers keep their existing layout. This is an internal read/validation seam, not a new public command or an automatically migrated postflight procedure.

The manifest and original roots jointly constitute durable evidence. A manifest alone is not a portable evidence bundle. Ending an attempt freeze or clearing the settled supervisor lock does not permit editing, moving, deleting or cleaning those roots; all later Gradle/build/cleanup work must exclude retained roots and launch evidence. This is workflow protection with verification on consumption, not a filesystem read-only/ACL change. The cooperating-launcher lock does not prevent arbitrary external writes. The separate reference-aware receipt/postflight and storage admission must be reviewed before a new cohort is admitted; this layout change alone grants no native-run authority or lower storage floor.

The disposable game directory is `v2/fabric/build/run/clientGameTest`; Loom may clear it on the next run. Keep each launch archive independently. Scenario directories retain fixture/definition hashes, settings/versions, run/world identities, native observer/proof and mismatch records, screenshots and close/failure evidence. Two-lane directories additionally retain guarded reset archives, actual before-start validation, response-release diagnostics and `invocation-result.json`. Open the actual images, but use native client/server assertions for numeric acceptance.

For script diagnostics without Minecraft, run `pwsh -NoProfile -File scripts/test-v2-selftest.ps1`. It uses JDK probes, bounded dummy workers and filesystem fixtures; it never invokes Gradle or Minecraft. The retained [scalar-outcome/self-check report](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-9a-scalar-report.md) records passing checks separately from native evidence. The fixed issue was extra stream-completion values in the supervisor return; actual pre-fix dummy exits0/7 were preserved, so no proven pre-fix public CLI exit masking is claimed.

## Deliberate negative proof

After the selected baseline, run this separately:

```powershell
pwsh -NoProfile -File scripts/test-v2.ps1 -Scenario grounded-two-lane -Repeat 2 -EulaAccepted -WrongExpectedBlock
```

The build still places stone. Only the independent server expectation changes to gold. Require the exact mismatch at `(0,65,0)`, `expected=minecraft:gold_block` versus `actual=minecraft:stone`, invocation FAIL, readable `failure.png`, ordinary nonzero native → Gradle/worker → public exits, normal world close, `TimedOut=false` and settled owned cleanup. An unrelated assertion, native `0xC0000409`, graphics/startup failure or timeout does not satisfy this gate.

The first counted engine run can reach SUCCESS and 20 released physics ticks before the independent assertion fails. That is not an asserted scenario success: the accepted negative has countedStarts=1, assertedSuccessCount=0, no counted result file and no second counted start. The requested Repeat2 remains unchanged. Follow the negative with a genuinely separate full unflagged invocation:

```powershell
pwsh -NoProfile -File scripts/test-v2.ps1 -EulaAccepted
```

The historical probe/observation/state negatives below retain their original flags and outcomes as evidence. The public script clears those legacy flags; they are not additional public options.

## Current two-lane forward and reset evidence

The following are retained local Windows/PowerShell 7/JDK 21 records from **30 October 2026**, not files required by the public workflow or distributed by Git. Native input preflight bound the games to source commit `775587c23252c8ee3e1a597ee6b7d61908c62b1b`, tree `c6cd9d306074c87dbf7c86fd3261d9aad3426559`, with 281 admitted inputs and 20 H1 compiled classes unchanged. Public script SHA-256 is `0754a5e30a37b864d6be93db04f25224808e7851ba849afe13537c0484107831`; selftest SHA-256 is `378e556887125581cb7affd6b6ba4dca7a3ed08d19733bf371eed079d5602769`.

| Gate and retained report | Actual launch / result |
| --- | --- |
| [Selected Repeat2 baseline](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-10-baseline-public-report.md) | `launch-32be2891-676f-41ab-9117-ae10f1823fbd`; public/worker/native 0/0/0, 128.43 s, old providers NOT_EXECUTED; dirty FAILED then two asserted successes in one world, three UUIDs; eight owned exits 0/zero remaining. |
| [Exact wrong-block negative](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-10-negative-public-report.md) | `launch-b90bd942-8a2e-4f36-aa60-178dc00dd0ab`; public/worker/native 1/1/1, 105.63 s; exact independent gold/stone mismatch; invocation **FAIL**, expected-negative gate accepted; second counted start NOT_EXECUTED; eight owned exits observed/zero remaining. |
| [Separate post-negative full recovery](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-10-recovery-public-report.md) | `launch-56f17c9e-ee40-4f45-b7d1-37c0375e06d3`; only EulaAccepted supplied, native defaults all/2, public/worker/native 0/0/0, 285.38 s; all 17 old cases plus complete new sequence, nine owned exits 0/zero remaining. |

All three have `TimedOut=false`, normal owned world close, settled cleanup, retained archives and no supervisor/cleanup/archive error. Original images were inspected (4 baseline, 4 negative, 21 recovery). Recovery retained all 24 required copy receipts and complete zero-mismatch posthash scans; older baseline, negative and failed attempts remain independently preserved. The earlier [H1 convergence review](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-8-native-convergence-review.md) accepted selected1/full-default2 transport and provider/count continuity; it does not substitute for these additional H3 runs.

The [canonical fixture](../test-fixtures/grounded-two-lane/definition.json) is a 12×1×10 stone plan at origin `(0,65,0)`, flat overworld seed 1, five-wide lanes/stride 5, EAST → SOUTH shift → WEST. Settings are halfWidth=2, stride=5, constantSprint=false, reservedHotbarSlots=1, survival, autoJump=false, selected slot 1; usable stone starts at 128 and reserved slot 0 has stone 7. Readiness/run/transition/release budgets are 200/1200/120/20 ticks; repeatMaximum=2. Versions: Minecraft 1.21.11, Java 21 (actual Microsoft 21.0.10), Loader 0.19.2, Fabric API 0.141.1+1.21.11, Yarn 1.21.11+build.4, Loom 1.16.1, Gradle 9.5.0, JUnit 5.10.2 and client gametest 4.3.5+bfe9fcf43e. Baritone is not required or exercised by this native-only fixture.

Each asserted success requires all 120 current OBSERVED targets and 120 independent server targets, including one seeded stone and 119 physical placements, usable remainder 9/reserved 7, all 43 inventory slots, bounded apron/containers, native movement/entry/support, pause/manual resume with 20 held physics ticks, terminal release for 20 physics ticks and full final scan. Reset is guarded to its owned world/region: stop and archive old evidence, release the original held responses, clear old observers/faults, restore fixture/player/inventory/plan/origin/settings, then independently validate actual starting state before granting start authority. Old-run response isolation and normal close are asserted. The dirty failure is deliberate fault injection, not a baseline build failure hidden by retry.

Recovery invocation `cd5abfbe-ef61-416b-940b-bafa402bba42`, native world 1667180588, contains dirty `967e737e-082f-4c73-9642-38043e4a408c` and successes `e3a85c80-bc6e-4375-963d-5074de7d7bc1` / `8fac90d9-cd1f-4fbe-b640-40034a188e92`. Definition SHA-256 is `81fad1bb9c4e356c941455f0cd50e7fd6a82dcc4f56e22d0740a1ec670337127`. Both actual stable-start hashes equal `560a33399fc29362a0acac386d21052118a9e2b6e8efc6fa4328be0832848967` within that world; this hash is not pinned across independent invocations. The complete retained game manifest is `task-7-phase-a-runtime-7121b0fe-c5a2-49bb-b56c-f964dc74806a/full-game-retention.json` under the local report directory, SHA-256 `68dcb120ef0a390d531ea8b05d99739b91b5171bcf21e6aa251b6dbbc7d40b7b`, 572 regular files.

Original proof frames bind to existing observer/run/world fields (22+21 moving-END frames per positive invocation). The standalone old binder was unavailable; native assertions remain the geometry/world/physics oracle. Native caller checks compared exactly eight named environment entries, not the entire parent environment; source/dummy environment checks are separate. Preserved metadata preparation/projection errors caused no game retry or assertion change. This bounded sequence does not establish soak or statistical freedom from flakes.

## Current build and release evidence

The retained [Task10 build/release report](../../.superpowers/sdd/2026-10-28-v2-two-lane-reset/task-10-release-report.md) records one exit0 invocation of the compile/test/remap command above, 38.97s, unchanged 70-input manifest SHA-256 `5b4f9649ed30c0db1ef3a60c39ee6a073d2235281832b8042d2571658ea35e4b`. Fabric 232 tests freshly executed with zero failures/errors/skips; core 354 results were retained XML with `:core:test UP-TO-DATE`, and gametest compilation was UP-TO-DATE. Do not call this 586 freshly executed tests. Remap actually executed.

| Actual archive | Bytes / entries | SHA-256 |
| --- | --- | --- |
| `mapartrunner-v2-2.0.0-dev.jar` | 153249 / 44 | `b780fea6145b25a063ba7354fe57517e066a6ef20a3af58ae7584c29b43bf716` |
| Nested `META-INF/jars/mapartrunner-v2-core-2.0.0-dev.jar` | 89756 / 62 | `7d33d6c685721df774ffb26c8bd123098dd52ada8ec41751c92c7b1ce0a65951` |

Recursive name/content/metadata inspection excluded all 101 enumerated test classes, eight test resources and seven test mixins, explicitly `TestAudioShutdownMixin`, plus runner/provider/fault/fixture data, test mod/entrypoints/commands and test flags. Production metadata retains `mapart`, client entrypoint `com.mapartrunner.fabric.MapArtClient`, two production prediction mixins and only the nested core JAR. Archive exclusion is not installation, publication or replacement approval. The build report's earlier open native gates are historical; the later native reports above supply their own results.

## Current schematic import evidence

The accepted import evidence below is historical (28 October); current two-lane/release evidence is recorded above. Its old 17-case suite predates the appended two-lane provider.

Task 4 and its bounded test-only audio/readiness integration were independently accepted at source commit `766ef0dc3bf2781263805c1e8e37b51a3ba1d54d`, tree `53320b6f974faac8bf3cecd712c6e83de819c6b8`. [Commit receipts](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-bc-commit-report.md) preserve the tested working cohort and Git-normalized line-ending distinction: all 179 immutable + 9 runtime inputs matched before/after commits. The games preceded those commits; no separate committed-tree game or final whole-6d/Checkpoint B acceptance is claimed here. [Fixture provenance and literal expectations](../test-fixtures/schematic/README.md) define all three inputs and the state/seed/outer/omission mapping **0/24/48/11**; historical programmatic mapping was 0/2/4/55.

All launch names below are under `v2/fabric/build/scenario-results/`. The [final runtime report](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-final-runtime-report.md) and [retained directory index](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-final-runtime-directories.json) link complete transcripts, actual held game-process outcomes, ownership checks, causal records and inspected images.

| Historical import gate | Launch directory | Measured result |
| --- | --- | --- |
| Imported producer omission negative | `launch-efbb35d2-65bd-4f52-b091-99ad0951433f` | Actual game 1 / outer 1, timedOut=False; accepted physical `(11,0,0)`/ID11 omitted, E50/V52/T130/pending80/VERIFY/release20, exact false-SUCCESS assertion; failure image inspected and world closed normally. |
| Forced loaded-chunk import negative | `launch-efa4c27c-39b9-45f2-bfbc-8c1535461ee4` | Actual game 1 / outer 1, timedOut=False; accepted state target0, ack rollback/server60, stop/release20, one real loaded-chunk resend and armed BEFORE-import fatal retained after later matching stone. Exact DELIBERATE TARGET_CHUNK_RELOAD primary; known suppressed generic sticky-fault cleanup assertion retained. Image inspected and world closed normally. |
| Separate full unflagged recovery | `launch-857eb615-c525-4fe2-9953-fd4c4209b5c5` | Actual game 0 / outer 0, timedOut=False, 3m42s; original probe + all 15 imported lane cases + import/empty provider = 17 PASS/0 FAIL. All 17 images inspected; normal world closes; all captured owned process identities gone in all three runs. |

The recovery's [all-case index](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-final-runtime-recovery-f06cdc60-891a-40d5-9946-b0e6a22cc256/all-case-outcomes.json) retains every actual directory/run UUID, fixture hash, readiness and screenshot path. For example, `single-lane-BASELINE-f249789a-0fc1-4309-b082-9a386a211396` has run `7735241c-0fb5-4761-a2cc-521a68ce0d3f`. All five baselines independently end with 60 correct server cells, seed24, usable64→5/reserved7, movement beyond measured reach4.5, outer0/48, support holds, full verification, pause/fault/release checks. This proves 59 physical placements plus seed; **only 58 distinct ACCEPTED snapshots are logged**, missing target50's event. Its timestamp/cause is unrecorded; no 59th logged event is claimed. All reached lane readiness waits naturally observed four FULL chunks/content/owned bindings before start, within the unchanged WAIT200 budget.

The appended `schematic-import-061fbe9b-2eec-4b1b-803d-777420486d40` has empty run `7751926c-3915-4aae-bdf0-0359663bfd51`. Native assertions check both real formats, all literal states/materials/regions/raw and semantic hashes, axis mutation, malformed-unused-palette rejection before start with unchanged facade status, and the permanent factory gate. The empty 3×2×4/24-air-cell plan returns real facade SUCCESS/NOTHING_TO_BUILD with the same run, owner NONE, nine keys false, no failure/effects/pending/unfinished/lane/eligible/verify advancement. One readiness wait checks four FULL chunks, owned bindings and 392 exact cells; feet65/bedrock64, bounded server state, full inventory and selection remain unchanged across **20 advancing client/server physics intervals**. These are independent numeric assertions, not screenshot inferences.

Historical importer unit evidence from the original settled cohort is retained in [Task 4 unit counts](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-evidence/xml-counts.json), [focused log](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-evidence/focused.log), [combined log](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-evidence/settled.log) and sibling `focused-xml/`, `settled-core-xml/`, `settled-fabric-xml/`: focused19 and core327 + Fabric206 = **533**, zero failures/errors/skips. The combined command retained below actually exited0, 56s/13 tasks executed, before later test-only audio/readiness edits. It is **not a fresh final-HEAD unit execution**. The factory-ordering RED is retained in `task-4-evidence/red-outcome.json` and `red-inputs/`: ordinary Java1 from the permanent seed24-versus-old2 assertion, not a game RED. The same assertion passed natively in the final appended provider. These ignored local evidence archives are retained in this workspace, not distributed by Git.

After the final three games, exactly one `:fabric:remapJar --rerun-tasks --no-daemon --console=plain` exited0 in28s/7 executed tasks; no units were rerun. [Packaging evidence](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-final-packaging-report.md) retains the raw build exit/log and [recursive archive inspection](../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-final-packaging-87af1edb-fd82-4a52-9e13-e30f84ee1284/release-inspection.json). The then-inspected release: **144846 bytes/44 entries**, SHA256 `af00616c771cb1ebd6e24b9918bc5e500282d88b99864236f994b183cf8fdcde`; nested core: **58 entries**, SHA256 `35bb8bc14c1fceceaabc0a7622e05fa0a4039240b94e22ea2892bd3dc6813047`. All 78 main and 39 gametest compiled classes/native inputs remained equal. Outer/nested names, metadata and uncompressed content exclude test providers/fault controls/three flags/all fixtures, explicitly TestAudioShutdownMixin; production importer, client entrypoint/two production mixins and pure nested core remain. Archive exclusion is not installation approval.

The original Task11 `5efb` forced negative, first Task4 omission `94d` and early Task4a `93c` remain failed native `0xC0000409` attempts. Later exact-owned Windows evidence locates the Task4/4a failures in OpenAL1.23.1 at offset `a2b05`; this does not establish root cause or host repair. The new normal negatives and separate recovery refresh those specific gates without relabeling historical games. That diagnostic monitor's own exit value was not retained; game handles and raw JavaExec outcomes were. Broad native/graphics/audio stability remains unproven.

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

## Historical programmatic lane execution evidence

The final state-fault source is `d96bbcaef1cb0e62698f143dbed32f0c2645483b`, published into the same accepted source tree at `a2f23447d21f855e15913ff07189cce21b8b6be5`. These are retained local executions, not new Task12 game launches. All paths below are under `v2/fabric/build/scenario-results/`; the accepted final-source pair used identical source, settings, JDK21, supervisor and simulation deadlines.

| Evidence | Launch directory | Actual outcome |
| --- | --- | --- |
| C9 held response and fresh baseline | `launch-task-9-positive-2e961b63-438f-4fbb-9050-11a931529869` | Probe + 7 lane cases passed; actual pending80 fault and full ordinary recovery. Exit 0, timedOut=False. |
| C10 producer-omission deliberate negative | `launch-task-10.1-final-negative-c9543e3e-484f-46aa-98d2-071c4ded3691` | Exact false-SUCCESS assertion after ID55 omission/VERIFY/pending80/release. CORRECTION: Gradle/supervisor1 and timedOut=False, but actual gamechild-1073740791/0xC0000409. Not a normal-native-negative PASS; gate reopened. |
| C10 separate full recovery | `launch-task-10.1-final-positive-4ec7bd6b-822d-4d38-ad28-53a2141552b5` | Probe + 9 lane cases passed, including full fresh POST_OBSERVATION_BASELINE. Exit 0, timedOut=False. |
| C10 computed lifecycle barriers | `launch-task-10.2-positive-55470714-d433-41ac-b588-3adbceeaf3d3` | Probe + 11 lane cases passed, including computed stop and same-UUID pause/resume. Exit 0, timedOut=False. |
| C10b final forced-import negative | `launch-task-11-final-negative-5efb2f68-22fa-4c30-838a-54305e9e522e` | Exact DELIBERATE TARGET_CHUNK_RELOAD, later matching stone did not erase fatal; normal owned close. CORRECTION: Gradle/supervisor1 and timedOut=False, but actual gamechild-1073740791/0xC0000409. Not a normal-native-negative PASS; gate reopened. |
| C10b genuinely separate full recovery | `launch-task-11-final-positive-fef0c768-6d24-4462-87d6-45de0b1c18d8` | Probe + 15 lane cases, each exactly 1 PASS/0 FAIL; all worlds closed, zero owned JVMs. Exit 0, timedOut=False; 3m18s. |

The final positive main state case is `single-lane-WITHHELD_STATE-e77a2c8a-e161-41f0-91a7-4bc1bcdc347e`: genuine production action 0/target 0/sample834, normal sequence1 PRE3/POST4, then actual START/END3 eligible2→3 consumes OBSERVED air and retains unfinished0 before retry. Main suppressed SINGLE=2, DELTA=0. The native-only `single-lane-REPEATED_STATE-0ee749c7-4b2c-4014-8240-a549f0159295` records FIRST_NATIVE1, actual PRE4, SECOND_NATIVE5, older POST6 still layers 2/pending and covering POST10 air/nonpending; SINGLE=4, DELTA=0. **DELTA has source/compile coverage only, no live route credit.** Both following ordinary baselines used the unchanged full baseline and finished with independent server 60/stock7+5. The forced-negative case `single-lane-WITHHELD_STATE-d5e7917c-4068-4561-aedc-b37dcab7c4e9` records the actual loaded target-chunk BEFORE-import ordinal5 at age41/eligible3/armedAge17, within the unchanged 200-physics scope; SINGLE=2, DELTA=0.

All 16 final positive screenshots and the final negative failure screenshot were inspected by the owning task; Task12 additionally opened the final negative, repeated-native and POST_REPEATED_STATE_BASELINE images. These show the matching surface/7+63, empty snow slot/original-air hole and full ordinary surface/7+5 respectively. Screenshots supplement the native event/callback/independent-server assertions. Gametest compilation during those launches was UP-TO-DATE after actual final changed-source compilation. Separate earlier remap invocations were also UP-TO-DATE; those are unchanged-artifact inspection evidence, not fresh release builds.

Earlier failures remain evidence: C10.1's first negative failed the unchanged probe due to an inactive-hook off-client access; the hook was corrected before final-source proof. Its `launch-task-10.1-final-negative-6229bc0e-5411-4b92-969d-4b86a4198029` instead crashed in pinned Fabric's SERVERBOUND 10-second wait during STOP world creation, before setup/start/arm. That unrelated crash is not the omission negative. The source-supported lost-notification hypothesis has no recorded historical counters/interleaving and is not a verified cause or repair.

C10b's pre-correction `launch-task-11-negative-60cc54c9-06ed-4fa2-b386-0fb224589f12` reached the specific import negative, while `launch-task-11-positive-af06dc82-7353-487f-91cc-cefa36d8def4` passed the probe + 13 lane cases then failed REPEATED_STATE setup before start/arm: server-only selection had not selected client snow. The approved public client selection setter was added only in BEFORE-start setup, preserving readiness validation and the no-poststart-correction rule. Both required launches were rerun on final source; the partial run is not repeated-native credit. Retain those failures rather than substituting the final recovery for them.

A separate earlier pre-probe startup phase/semaphore stall was explicitly aborted (`exitCode=-1`, timedOut=False), not naturally completed. Pinned Fabric permits a late-registration race matching the waits, but actual registration ordering was not captured. That stall, the SERVERBOUND timeout and the unexplained native shutdown are distinct signatures. Later successful runs do not establish host stability or repair. LLVMPipe/SVGA fallback, audio, anisotropic-option, development-authentication and lag diagnostics remain disclosed host limits.

## Historical settled-source verification

On **28 October 2026**, Task12 ran exactly one full local verification at revision `a2f23447d21f855e15913ff07189cce21b8b6be5`, tree `682d73e131a9b9b481daaede696130c46848507d`, with process-local `JAVA_HOME=C:/Users/Bryce/.jdks/ms-21.0.10`, from repository root:

```powershell
.\gradlew.bat -p v2 :core:test :fabric:test :fabric:compileGametestJava :fabric:remapJar --no-daemon --console=plain --rerun-tasks
```

Actual native exit 0; BUILD SUCCESSFUL in 43s, 12 actionable tasks/12 executed. Core test, Fabric test, gametest compilation and release remap all actually executed. Fresh XML from all 8 core suites and 4 Fabric suites was archived and parsed: **327 core + 187 Fabric = 514 tests; failures=0/errors=0/skipped=0 in both modules**. These are measured results, not thresholds or reuse of historical counts. Reports originate at `v2/core/build/test-results/test/TEST-*.xml` and `v2/fabric/build/test-results/test/TEST-*.xml`; HTML is under each module's `build/reports/tests/test/`. All 45 tracked source/resource/build inputs hashed before the run matched afterward. No v1/shared-root change required a v1 rerun.

Retained final evidence is `.superpowers/sdd/2026-10-28-v2-tick-boundary/task-12-verification-d0869621-05ae-40da-8776-f067bc32b6cb/`: `verification-input.json`, complete `gradle.log`, `native-exit.txt` captured immediately before any log read, `source-hashes-before.json`/`source-hash-check.json`, archived `core-xml/`/`fabric-xml/`, `suite-counts.json`, `release.jar` and `release-inspection.json`. Future wrappers must likewise store native `$LASTEXITCODE` before tailing/reading logs; a successful log-display command is not compiler evidence. A cached/UP-TO-DATE test task is not a fresh test execution. The log retains the Gradle 10 deprecation warning; it does not fail the build.

The actually remapped release `v2/fabric/build/libs/mapartrunner-v2-2.0.0-dev.jar` is 133099 bytes, SHA256 `bb63ad40857e7d31e638137cb470f65791e825334c1517cbeca1fbbd1c4013f4` (same bytes as the previously inspected archive). Fresh outer 41/nested-core 58 entry/content inspection found no scenario/fixture/gametest/fault classes, test configuration/entrypoints or any of the three negative flags. Exact production metadata: id `mapart`, version `2.0.0-dev`, client environment, sole client entrypoint `com.mapartrunner.fabric.MapArtClient`, sole client config `mapart.client.mixins.json`, nested `META-INF/jars/mapartrunner-v2-core-2.0.0-dev.jar`. The required JAVA_21/minVersion=0.8.7 config contains exactly `ClientWorldPendingUpdateAccessor` and `PendingUpdateManagerMixin` in `com.mapartrunner.fabric.mixin`; nested generated core mod id is `com_mapartrunner_core`. Unit recording sinks establish policy/order; actual game assertions independently establish resulting blocks. Archive inspection does not grant release or installation approval.

Hosted checks at the same accepted verification revision `a2f23447d21f855e15913ff07189cce21b8b6be5` also passed (jobs 122618504568/122618482604), with nontruncated logs showing actual core test, Fabric test and gametest compilation plus core=327/failures=0/errors=0/skipped=0. Their existing cold-Loom remap-modifier and Gradle deprecation warnings, plus Ubuntu26 migration notice, remain recorded. The Ubuntu/Temurin21 workflow runs `:core:check :fabric:compileJava :fabric:compileGametestJava :fabric:test` and parses only core XML totals; it neither runs Minecraft nor remaps a release. No hosted Fabric count or CI gameplay/headless equivalence is inferred. Task12 adds no game launch because the accepted final-source negative/recovery pair already covers unchanged behavior. The independent whole-6c review recorded approval at17a87ac, but a subsequent raw-child audit found the final C10b negative was actual0xC0000409 despite supervisor1; its required normal-negative/parent6c gate is reopened. Unit/package/positive/CI evidence remains, but does not waive that missing normal-native proof. Broader replacement gates remain pending.

## Remaining limits

The imported one-lane boundary was accepted by the [whole-6d review](../../.superpowers/sdd/2026-10-28-v2-schematic-import/final-review.md). The newer local native evidence establishes bounded two-lane forward construction and managed failed-prelude/reset/two-success repeatability. It does not complete the preservation matrix.

Normal commands/info/material reporting, broad specialized-state physical placement, partial lanes, smart resume/selective reverse cleanup, persistence/settings/keys/HUD/overlays/helpers/timer, recovery/stall behavior, genuine Baritone refill/return, persistent inspect/user-file workflow and human acceptance, broader soak, dedicated-server variation, CI gameplay/headless/clean-machine equivalence, flight and executable 1.12.2 remain unproved or explicitly deferred as recorded in [behavior-parity.md](behavior-parity.md). Pause/manual resume inside this owned fixture is not smart-resume/reverse acceptance. This documentation-only change reran no build, game, selftest or v1 suite. Keep v1 usable; no automatic installation/migration, full parity, release-readiness or replacement claim follows.
