# MapArtRunner v2 implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** proposed plan for review; no implementation is authorized by this document alone.

**Goal:** Build a separate, reliable MapArtRunner v2 with stable responsibility boundaries, preserved working behavior, and repeatable game testing.

**Architecture:** An independent `v2/` build contains a pure Java `core` module and a Fabric client adapter. V1 remains intact and usable as the reference. Components share typed observations and intents instead of directly clearing each other's mutable state.

**Tech stack:** Java 21, existing pinned Minecraft/Fabric/Loom/Gradle versions, JUnit Jupiter, test-only ArchUnit, Fabric client game tests.

**Spec:** [MapArtRunner v2 design](../specs/2026-10-28-v2-reliability-design.md).

**Tracking:** [tasks/todo.md](../../../tasks/todo.md). This plan is canonical; the checklist is an ordered index, not a second specification. Later milestones below are intentionally less detailed than the first: expand each into its own small implementation plan after the preceding checkpoint proves its interfaces.

## Global constraints

- Separate v2 implementation; keep v1 production code/build/test expectations unchanged.
- Minecraft 1.21.11; Java 21; Loader 0.19.2; Fabric API 0.141.1+1.21.11; Yarn 1.21.11+build.4; Loom 1.16.1; Gradle 9.5.0; JUnit Jupiter 5.10.2.
- Native active lane/transition movement and placement; Baritone only coarse travel.
- Core has no Minecraft, Fabric, Mixin, Baritone, or game-directory dependency.
- Preserve required target accounting, explicit total-count refill contracts, bounded waits, world/run identity, and exclusive control ownership.
- An accepted placement or a completed traversal cannot establish successful construction by itself.
- Test-only reset/fault controls must not be shipped or operate on unmanaged worlds.
- Preserve valid tests. Never suppress a required failing scenario or weaken the bar to finish a task.
- Keep the 1.12.2 loader/runtime and executable port deferred.

## Review focus

| Condition | Required behavior | Owning task |
|---|---|---|
| Old confirmation arrives after stop/reset/new run | Ignore it; no stale progress or key ownership | 3, 6, 12 |
| World/chunk becomes unavailable during placement | Remain unknown; never report success from stale/predicted state | 6, 12 |
| Partial inventory and reserved hotbar slots | Count usable stock exactly once; collect only the remaining quantity | 10 |
| Resume skips lanes or reverse cleanup skips an odd number | Retain all unfinished targets and reach the correct lane entry | 8 |
| A second scenario starts after failure/disconnect | Fresh state, deterministic fixture, released controls, preserved first-run evidence | 9, 12, 14 |

## Task 1: Pin the reference behavior

**Dependencies:** none. **Scope:** documentation/evidence only.

**Files:** create `v2/docs/behavior-parity.md`, `v2/docs/reference-baseline.json`, and `CONSTRAINTS.md`; update only the instruction links in `AGENTS.md` and `CLAUDE.md` to make their v1/v2 scope explicit. Do not silently rewrite the old roadmap as the new one.

- [ ] Inventory supported grounded behavior, commands/aliases, settings/defaults, HUD/overlays, helper tools, and experimental features against reference commit `51ad8a53a578901e5bb83db082e98f8104abbf58`.
- [ ] Link useful existing tests and the six assessed defects. Treat the defects as expected corrections, not parity requirements. `BuildCoordinator` still supports v1 plan/session services; do not classify it as unused without proof.
- [ ] Record rules from the design in `CONSTRAINTS.md`, including one state owner, forbidden core dependencies, no skipped required tests, and no weakening checks. Distinguish initially written rules from mechanically enforced ones.
- [ ] Verify every existing working feature has an explicit preservation requirement or an owner-approved deferral. Default to preservation. Record the already-run 419-test baseline; rerun v1 only if its environment/shared infrastructure changed.
- [ ] Review and commit this deliverable independently.

**Pass:** a builder can identify what must survive, what deliberately changes, and what is deferred without guessing. V1 source/build/tests are unchanged.

## Task 2: Establish isolated builds and test entry points

**Dependencies:** 1. **Files:** create `v2/settings.gradle`, `v2/build.gradle`, `v2/gradle.properties`, `v2/core/build.gradle`, `v2/fabric/build.gradle`.

**Interfaces:** `:core` is a Java library; `:fabric` depends on it and owns Loom. The repository wrapper runs either project using `-p v2`. Define `:core:test`, `:fabric:test`, and `:fabric:runClientGameTest` through the documented Fabric/Loom setup, with all run/output directories under `v2/`.

- [ ] Configure the pinned tools and separate test-only game-test source set; check the actual task names with `gradlew.bat -p v2 tasks --all`. Do not install/upgrade unrelated dependencies.
- [ ] Verify the core's compile classpath cannot resolve game classes and `:fabric` has no dependency on v1 source directories or its mod JAR.
- [ ] Run `gradlew.bat -p v2 :core:test :fabric:compileJava --no-daemon`. At this scaffolding point, report honestly that meaningful behavior tests arrive in Task 3; an empty test task is not functionality evidence.
- [ ] Verify the test run's setup prerequisites, including graphics and explicit EULA acceptance, without silently setting acceptance. Record exact launch requirements in the testing guide started in Task 5.
- [ ] Review and commit build isolation.

**Pass:** v2 builds independently with the existing wrapper; root v1 build inputs remain unchanged. Client game-test task discovery and prerequisites are documented.

## Task 3: Introduce immutable plan and lifecycle contracts

**Dependencies:** 2. **Files:** create `v2/core/src/main/java/com/mapartrunner/core/model/BuildPlan.java`, `.../run/RunLifecycle.java`, `v2/core/src/test/java/com/mapartrunner/core/model/BuildPlanTest.java`, `.../run/RunLifecycleTest.java`.

**Interfaces:**

- `BuildPlan(String fingerprint, GridSize dimensions, List<Target> targets)` with nested immutable `GridSize(int width,int height,int depth)`, `GridPos(int x,int y,int z)`, `BlockSpec(String id, Map<String,String> properties)`, and `Target(int id, GridPos relativePos, BlockSpec block)`.
- `RunLifecycle.start(Context context)`, `pause()`, `resume(Context context)`, `stop(Outcome outcome)`, and `snapshot()`; nested `Context` contains run UUID, connection/world identity, dimension, plan fingerprint, origin and settings fingerprint. Outcomes distinguish success, partial, failed, and cancelled. Stop is idempotent. Use lifecycle phases and running activities exactly as defined in the design; changed world binding requires a freshly scanned run instead of restoring the old snapshot.

- [ ] Add failing tests for defensive copies, duplicate target IDs, invalid block identifiers, illegal phase transitions, pause snapshot retention, stale run results, world mismatch on resume, and repeated stop/new start.
- [ ] Run `gradlew.bat -p v2 :core:test --tests '*BuildPlanTest' --tests '*RunLifecycleTest'`; confirm failures reflect the absent contracts.
- [ ] Implement the minimum data/lifecycle logic. No I/O, Minecraft singleton, reflection, or control effects.
- [ ] Run the focused command, then `gradlew.bat -p v2 :core:test`; confirm all assertions pass.
- [ ] Review and commit the contracts.

**Pass:** lifecycle ownership and run/world identity are executable rules, with immutable inputs and no game dependency.

## Task 4: Enforce the first architecture boundaries

**Dependencies:** 3. **Files:** modify `v2/core/build.gradle`; create `v2/core/src/test/java/com/mapartrunner/core/architecture/CoreArchitectureTest.java`; update `CONSTRAINTS.md` and `v2/docs/behavior-parity.md`.

**Interfaces:** ordinary JUnit architecture tests run within `:core:test`. Use `com.tngtech.archunit:archunit-junit5:1.5.1` as a reviewed test-only dependency; do not add runtime tooling.

- [ ] Write rules against actual compiled core classes: no forbidden platform dependencies, no core package cycles, immutable model fields. Require matching imported classes so empty scans fail.
- [ ] Demonstrate a forbidden dependency or rule violation in a temporary test fixture, confirm failure, then remove that fixture. Keep the permanent rule.
- [ ] Run `gradlew.bat -p v2 :core:test --tests '*CoreArchitectureTest'` and the core suite. Record which constraints are now enforced.
- [ ] Review and commit the guardrails.

**Checkpoint A:** independent build, reference matrix, meaningful core tests, and enforced boundaries. No claim of a working game builder yet.

## Task 5: Prove the Fabric test adapter can observe and act

**Dependencies:** 4. **Files:** create `v2/fabric/src/main/resources/fabric.mod.json`, `v2/fabric/src/gametest/resources/fabric.mod.json`, `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/EnvironmentProbeGameTest.java`, `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/ScenarioEnvironment.java`, `v2/docs/testing.md`.

**Interfaces:** `ScenarioEnvironment` is test-only. It executes setup on the server thread, client actions on the client thread, and reads authoritative server blocks independently of the mod's eventual status. Use Fabric's actual game-test context APIs, not a new launcher framework.

- [ ] Create a disposable world, set and observe one block, create a player inventory/chest fixture, and prove a bounded client/server round trip plus screenshot/artifact output.
- [ ] Run `gradlew.bat -p v2 :fabric:runClientGameTest`. Confirm the probe executes and exits with a useful failure when deliberately given the wrong expected server block.
- [ ] Document the environment and exact test-selection mechanism supported by the pinned API. Record graphics/headless limits instead of assuming CI equivalence.
- [ ] Verify test-only classes/resources are absent from the released mod artifact. Never launch the v1 and v2 mod together.
- [ ] Review and commit this feasibility slice.

**Pass:** real world, inventory, client/server access and independent assertions work. If the environment cannot run, resolve that specific prerequisite before calling later scenarios verified.

## Task 6: Build one grounded lane through the production boundary

**Dependencies:** 5. Break this milestone into the following reviewed slices; do not implement it as one large change.

### 6a. Expected-target ledger

**Files:** create `v2/core/src/main/java/com/mapartrunner/core/placement/PlacementLedger.java`, its JUnit test, and `.../placement/PlacementObservation.java`.

**Interface:** `PlacementLedger(BuildPlan plan)`, `observe(PlacementObservation observation)`, `recordAttempt(UUID runId, long actionId, int targetId, long tick)`, `unfinishedTargets()`, and `outcome()`. Observation includes target ID, run/action correlation where applicable, observed state and freshness; unknown/predicted/matching server evidence are distinct.

- [ ] Test immutable expected targets surviving failed/missed attempts; unknown/unloaded observations; stale action/run results; delayed confirmation; and success only when every target is confirmed.
- [ ] Run focused tests red, implement ledger accounting, then run them green and the core suite. Commit.

### 6b. Native lane movement

**Files:** create `.../traversal/LanePlanner.java`, `.../movement/MovementPolicy.java`, and their two core test files.

**Interfaces:** `LanePlanner.plan(BuildPlan plan, int halfWidth, int stride)`; `MovementPolicy.decide(PlayerObservation player, LaneEntry entry, SupportObservation support)` returns immutable movement/yaw intent. Return types are declared beside the producing class and used unchanged by consumers.

- [ ] Port verified v1 geometry/strafe assertions to plain coordinates. Test half-width 2, stride 5, narrow/partial edge lanes, all four correction directions, both entry coordinates, and hold on expected missing/unknown support.
- [ ] Prove the cases red/green against production-used logic, with bounded hold behavior. Do not add Baritone calls. Commit.

### 6c. Tick effects and control ownership

**Files:** create `.../core/run/BuildEngine.java`, `.../fabric/runtime/FabricBuildAdapter.java`, `.../fabric/runtime/ControlArbiter.java`, and focused core/adapter tests (maximum five files per reviewed slice; split adapter tests if needed).

**Interfaces:** `BuildEngine.tick(TickInput input)` returns `TickDecision`; `FabricBuildAdapter.tick()` snapshots game facts, advances the engine once, and applies typed effects. `ControlArbiter` is the only writer of automation keys and Baritone ownership. Inputs/results never expose Minecraft objects to core.

- [ ] Test stop/pause/world loss releasing controls, old effect rejection, native/travel exclusivity, and accepted placement remaining pending.
- [ ] Wire native inputs, item selection and actual placement effects. Preserve correct yaw/head/body behavior and report unavailable observations honestly.
- [ ] Execute a real single-lane game test with sufficient inventory, assert server blocks independently, and deliberately withhold a confirmation to prove it cannot falsely pass. Commit.

### 6d. Import a real schematic into the neutral plan

**Files:** create `v2/fabric/src/main/java/com/mapartrunner/fabric/schematic/SchematicPlanImporter.java`, `.../src/gametest/java/com/mapartrunner/fabric/scenario/SchematicImportGameTest.java`, and the small schematic resource under `v2/test-fixtures/`; update the existing smoke test to consume the imported plan.

**Interface:** `SchematicPlanImporter.importPlan(Path path)` returns the Task 3 `BuildPlan` with stable IDs, dimensions, block specifications and normalized fingerprint. File parsing/block registry conversion remain in Fabric; core has no file or registry access.

- [ ] Port the reference loader's supported flat formats and traversal/material interpretation, with explicit errors for malformed/unsupported content. Exercise a real checked-in schematic, not only a constructed Java plan.
- [ ] Verify target IDs/coordinates/dimensions and material resolution; all-air input must yield a nothing-to-build outcome without controls. Verify a modified input changes the fingerprint.
- [ ] Run the actual single-lane fixture through import and production execution. Review and commit.

**Checkpoint B:** a real imported one-lane schematic builds through v2 production decisions. The ledger, not traversal completion, determines success.

## Task 7: Automate two-lane setup, reset, and rerun

**Dependencies:** 6. **Files:** add `v2/test-fixtures/grounded-two-lane/` data/schematic, `.../gametest/.../ScenarioRunner.java`, `.../GroundedSmokeGameTest.java`, `scripts/test-v2.ps1`, and update `v2/docs/testing.md`. Keep fixture files and executable changes in separately reviewable slices if the file count exceeds five.

**Interfaces:** `ScenarioRunner.reset(ScenarioDefinition definition)`, `run(ScenarioDefinition definition)`, `assertResult(ScenarioResult result)`. Definitions carry bounded region/apron, starting player/inventory/chests, schematic/origin/settings, expected world and finite budget. Definitions are data; the Fabric fixture adapter performs version-specific setup.

- [ ] Restore every part of the starting state and assert its fixture hash before launch; cancel/archive the prior run first. Refuse unmanaged worlds/regions.
- [ ] Build two lanes with sufficient materials through the real production entry point and assert exact server targets, terminal result, and released controls.
- [ ] Implement `scripts/test-v2.ps1 -Scenario grounded-two-lane -Repeat 2`; resolve JDK locally without a hardcoded user path or persistent environment edits.
- [ ] Run twice back-to-back, including a failed-first-run case. Preserve each run's metadata, mismatch list, logs and failure screenshot. Confirm a wrong expected block yields a nonzero outcome.
- [ ] Review and commit.

**Pass:** a developer can reset and repeat the same build without manual world cleanup or command pasting.

## Task 8: Add complete traversal, resume, and reverse cleanup

**Dependencies:** 7. **Likely files:** core traversal policy, core placement ledger query, production engine scheduling, corresponding unit tests, and a dedicated game-test class. Split resume and reverse entry into separate PRs.

**Contracts:** selection may choose a nearby lane, but all other unfinished target IDs remain scheduled. Reverse lane skipping must calculate the full chosen entry position. Reuse the v1 deterministic five-wide serpentine geometry and preserve direction-relative corrections.

- [ ] Add the source-assessment cases as red production-path tests: missing targets in lanes 0 and 20 with player near 20; three forward lanes EAST/WEST/EAST with leftovers only in the middle.
- [ ] Implement correct target retention and entry movement, then verify final server-world contents across narrow/partial lanes and both skipped-lane parities.
- [ ] Check completion never hides unresolved targets, and rerun the baseline two-lane scenario. Review/commit each independent correction.

**Pass:** multi-lane builds, smart resume and selective cleanup cover the entire expected target set.

## Task 9: Provide the persistent inspect workflow

**Dependencies:** 7; may precede 8 if it reduces debugging cost. **Likely files:** test fixture client initializer, `ScenarioCommands.java`, existing scenario runner/environment, script, and testing guide; five or fewer per slice.

- [ ] Add test-only `/maparttest reset`, `run`, `rerun`, and `last` commands to a managed development session launched with `scripts/test-v2.ps1 -Scenario grounded-two-lane -Inspect`.
- [ ] Reuse the reset/setup/assertion implementation from automated mode; do not require the automatic game-test host to stay open after it exits. Configure a distinct dev run with the fixture mod and an integrated server.
- [ ] Prove failure evidence remains inspectable until explicit reset; prove reset refuses ordinary player worlds and the commands are absent from the release artifact.
- [ ] Verify two manual reruns need no schematic/supply/origin command pasting. Review and commit.

## Task 10: Add inventory demand and genuine refill

**Dependencies:** 8. Split into pure demand policy, real navigation/container adapter, then end-to-end return/resume; each slice gets its own tests and review.

**Likely files:** `core/refill/RefillPlan.java`, `core/refill/RefillFlow.java`, Fabric inventory/coarse-travel adapter, corresponding unit/game tests. Reuse established v1 slot reservation and transfer rules after translating types; do not copy its separate simulation algorithm.

**Contract:** `RefillPlan` contains `targetInventoryTotals`; inventory observations exclude reserved slots and describe actual capacity/stack limits. Return initiation always checks its result, and return progress has finite deadlines and a failure outcome. Traversal state is retained by its owner, not reconstructed by refill.

- [ ] Prove 60 required / 40 usable held means target total 60 and collection of 20; include zero stock, mixed materials, reserved slots, full inventory, unavailable screens, and exhausted supply.
- [ ] Pin and validate an actual compatible Baritone build by source/version/hash before accepting the real refill scenario. Test doubles may verify policy but cannot satisfy this gate. No silent artifact substitution or required-test skipping.
- [ ] Execute a stocked-chest refill and resume through real screen transactions; execute rejected/stopped return navigation and assert bounded failure/recovery rather than endless RETURNING.
- [ ] Verify native lane walking and initial smoke behavior are unchanged; review each slice separately.

## Task 11: Add recovery without cross-component resets

**Dependencies:** 10. **Likely files:** `core/recovery/RecoveryPolicy.java`, lifecycle/engine scheduling, Fabric observation mapping, unit test and game-test case; split at five files.

- [ ] Specify stabilization/retry budgets in policy settings and snapshot them with the run. Preserve v1's useful stabilization behavior; treat stalled client ticks separately from simulation progress.
- [ ] Add red cases for a fall/displacement, failed stabilization, pause during recovery, and stop/new-start while recovery is active.
- [ ] Have recovery request a validated resume through lifecycle/traversal owners. It must not clear or reconstruct their fields itself.
- [ ] Prove real recovery/resume plus deterministic failure, including released keys and retained unfinished targets. Review and commit.

## Task 12: Enforce world lifecycle and final completion

**Dependencies:** 11. Basic guards already exist from 3/6; this milestone expands their live interaction coverage. **Likely files:** engine/lifecycle, Fabric connection hooks, final-verification service, unit/game tests, split by responsibility.

- [ ] Disconnect during walking, refill and recovery; replace the world; deliver an old confirmation after a new run starts. Assert no stale action/automatic resume in the new context.
- [ ] Perform bounded final world verification before SUCCESS. Unknown/unloaded targets or exhausted materials must report an explicit incomplete outcome and target reasons.
- [ ] Verify panic/stop is idempotent and releases both native and coarse-travel ownership, including when player/world context disappears.
- [ ] Run all six assessed-defect scenarios and core architecture checks. Review and commit the independently testable pieces.

**Checkpoint C:** complete grounded behavior, genuine refill, recovery, cleanup and safe lifecycle. All six assessed failure paths are covered by production-path tests; record any remaining unrun real-world cases explicitly.

## Task 13: Reach supported feature parity and preserve diagnostics

**Dependencies:** 12. This is a workstream index, not one oversized task. Expand into separate plans for commands/settings/HUD, each working helper feature, and evidence retention using the parity matrix from Task 1.

- [ ] Preserve `/mapart` behavior and retained aliases/settings/keybindings. Port working manual air placement, torch-grid utilities and overlays where the parity matrix requires them; isolate each optional utility from the build engine.
- [ ] Add run-correlated JSONL state/action diagnostics, summaries, and automatic per-scenario archival. Logging settings must not affect scheduling or outcomes.
- [ ] Verify malformed/unsupported schematics, cancelled loads, changed settings mid-run, and stale persisted state fail clearly. Run settings are immutable; new settings apply to the next run unless explicitly designed otherwise.
- [ ] Review every parity row. A feature is retained with evidence or explicitly deferred by the owner; no silent omissions.

**Pass:** v2 is usable for everyday grounded building and has the supported working tools needed to replace v1.

## Task 14: Add CI gates, repeatability, and release evidence

**Dependencies:** start unit/build jobs after 4; add game jobs after 7; full gate follows 13. Use separate workflow tasks/PRs rather than one late infrastructure change.

- [ ] Add PR jobs for core tests, adapter compile/tests and architecture rules; upload failure reports. Keep v1 and v2 jobs independent.
- [ ] Prove the client scenario under the selected headless environment before making it a required CI job. Document graphics/network synchronizer limitations and preserve exact versions/artifacts.
- [ ] Run dedicated-server, background/unfocused and varied-seed/latency scenarios. Measure runtime, choose finite supported budgets, and commit them with rationale; do not claim robustness from a single successful run.
- [ ] Execute repeated complete-build/refill/recovery cycles and inspect target mismatches, leaked controls, state carryover and resource growth. Every required case must finish or fail explicitly within its budget.
- [ ] Review the release artifact for test-only code exclusion and the parity matrix for complete evidence. Prepare a reversible switch from v1; do not delete its reference or migrate user data automatically.

**Checkpoint D:** only now propose v2 as the default. Approval rests on repeatable final-world correctness and preserved behavior, not test totals or a shorter runner file.

## Later work: 1.12.2 feasibility

After Checkpoint D, make a separate port plan. Choose and verify the loader/runtime, map block/item/state semantics and schematic support, identify Java/runtime adjustments, and implement an additional game adapter against the same behavioral fixtures. Passing neutral core tests is necessary but does not prove equivalent legacy physics, placement, inventory or Baritone behavior.

## Implementation discipline and handoff

Implementation will use isolated `codex/` branches/worktrees, reviewed commits and PRs; do not push directly to main. A task changes one behavior or boundary, includes its regression check, and keeps the prior v2 milestone usable. Limit parallel work to components whose contracts are already fixed; lifecycle/control/ledger integration needs one owner.

Expand Tasks 6 onward into focused plans before their execution, using the demonstrated interfaces from the prior checkpoint. Do not turn a workstream index into permission for an unreviewed multi-thousand-line patch. This document is a roadmap plus an actionable foundation, not a claim that later adapters or scenario commands exist today.
