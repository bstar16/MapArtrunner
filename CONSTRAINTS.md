# MapArtRunner v2 constraints

These rules apply to the separate `v2/` implementation and shared infrastructure changes made for it. V1 remains the reference at commit `51ad8a53a578901e5bb83db082e98f8104abbf58`. Its production source, build, settings and valid test expectations must remain unchanged to make v2 work. The [design](docs/superpowers/specs/2026-10-28-v2-reliability-design.md), [parity contract](v2/docs/behavior-parity.md) and [recorded baseline](v2/docs/reference-baseline.json) supply the rationale and acceptance evidence.

## Scope and platform

- Build independently under `v2/` in this repository, using the existing wrapper with `gradlew.bat -p v2 ...`. Root builds remain v1. Do not copy the large grounded runner wholesale or rewrite the old roadmap as v2's plan.
- Initial platform: Minecraft **1.21.11**; Java **21**; Loader **0.19.2**; Fabric API **0.141.1+1.21.11**; Yarn **1.21.11+build.4**; Loom **1.16.1**; Gradle **9.5.0**; JUnit Jupiter **5.10.2**. Review pin changes separately.
- Use two production modules, `core` and `fabric`, with packages `com.mapartrunner.core` and `com.mapartrunner.fabric`. No general plugin framework or event bus. Artifact: `mapartrunner-v2`; public namespace: `/mapart`; mod ID: `mapart`.
- V1 and v2 must use separate game directories and must never load together. Development game directories belong under `v2/fabric/build/run/`; retained automated evidence belongs under `v2/fabric/build/scenario-results/`.
- Preserve working behavior, helpers, settings, aliases, keybindings and overlays before replacing v1. Omissions need an explicit owner decision recorded in the parity contract. Experimental flight/staircasing and the executable 1.12.2 port are deferred. Do not choose the legacy loader/runtime, downgrade Java, or promise shared compiled binaries now.

## Ownership and dependency boundaries

- Each mutable field has exactly one state owner. Other components receive immutable observations/snapshots or explicit results. Document creation, pause retention, reset, terminal cleanup and world-change lifecycle, including clearing order.
- The run coordinator owns run identity, world binding, phase, pause/stop, scheduling and terminal result. Traversal owns lane geometry/entry/schedule/resume selection. The placement ledger owns immutable expected targets, observations, pending attempts, retries/audits and unfinished targets. Refill owns target inventory totals and bounded trip progress; recovery owns stabilization and bounded resume decisions. Neither clears another owner's state.
- Fabric depends on core; core must not depend on Minecraft, Fabric, Mixin, Baritone or a game directory. Core cannot load a Minecraft singleton, send packets, open screens, convert game types or write keys. Effects and version conversions belong in the Fabric adapter; core packages must have no dependency cycles.
- MapArtRunner owns active grounded lane movement, inter-lane transitions and placement. Baritone is only for coarse initial approach, refill travel and future gross recovery/reposition. Never let it place or enable `allowPlace` inside the schematic/build area.
- Exactly one control owner exists: none, native movement or coarse travel. Release the old owner's controls before acquiring the next. Stop, panic, disconnect and world replacement release controls and outstanding actions. Manual air placement must respect control ownership.
- One client tick gathers observations, advances core once and applies intents in a defined order. No hidden extra engine ticks as a speed feature. Separate movement time, confirmation deadlines and wall-clock diagnostics; stalls must not silently consume simulation-time budgets.

## Required behavior contracts

1. The immutable normalized plan defines all required targets, source dimensions, stable target IDs and a content fingerprint. A pending queue never defines expected construction. Unsupported content produces a useful error; empty/all-air plans return nothing-to-build without movement.
2. Every required target stays accounted for until confirmed, explicitly unresolved or cancelled with its run. Smart resume near a later lane retains earlier unfinished work. Failed/missed required floor remains expected floor.
3. Accepted placement and completed traversal cannot establish successful construction. Unknown, unloaded or prediction-only observations cannot confirm completion. `SUCCESS` requires final verification of every required target; unresolved work yields partial/failure with reasons.
4. Before walking any lane, satisfy both entry coordinates and yaw, including after skipping clean lanes. Preserve five-wide serpentine geometry, direction-relative strafe correction and staged yaw/head/body alignment.
5. Refill contracts name **target inventory totals**. Calculate `additionalNeeded = max(0, targetTotal - usableHeld)` once at the inventory boundary. Exclude reserved slots consistently from schematic/refill accounting and automated swap destinations. Preserve the explicit torch-helper exception: an existing reserved-slot torch may be selected/used, without moving items into reserved slots. Preserve targets and traversal across refill/return.
6. Every active wait defines success, failure and timeout/progress handling. Missing/rejected/stopped coarse travel has a bounded explicit outcome, including the refill return leg.
7. Lifecycle phases are `IDLE`, `RUNNING`, `PAUSED`, `TERMINAL`; one running activity is `APPROACH`, `BUILD`, `SHIFT`, `REFILL`, `RECOVER` or `VERIFY`. Pause retains resumable target/traversal data and releases effects. Stop/cancel clears transient attempts, recovery and control ownership.
8. Bind runs and paused snapshots to connection/world identity, dimension, plan fingerprint, origin and settings snapshot. Rejoin never automatically resumes an old run; manual resume after a binding change requires a fresh world scan/validation and a fresh run. Old run/action results cannot mutate a new run; correlate effects and confirmations by run/action IDs.
9. Diagnostics preserve identifiers, transitions and useful failure evidence without changing decisions when enabled. Real gameplay uses client evidence; game tests must independently inspect server world truth.

## Verification and change gates

- Preserve valid tests. Never skip/delete a required failing scenario, weaken assertions, suppress required failures, lower thresholds or claim an interrupted/incomplete scenario as a successful skip to finish work. No arbitrary coverage/mutation target is claimed; measure and retain a meaningful baseline when tooling arrives.
- Add a failing regression for new nontrivial behavior against production-used policies. Include the six [expected corrections](v2/docs/behavior-parity.md#expected-corrections); old defective output is not a compatibility requirement. Separate structural moves from corrections in reviewable changes.
- Use core JUnit tests, Fabric client game tests and managed development scenarios. Confirm actual client/server placement evidence before claiming confirmation works. Real Baritone integration is required for travel/refill acceptance; a fake successful journey cannot replace it. Missing Baritone produces an explicit outcome and must not silently skip required tests.
- Test reset/fault controls live only in the test fixture mod, are absent from released artifacts, and refuse unmanaged worlds/regions. Reset stops the old run, archives evidence, restores bounded fixture/player/inventory/container/plan/origin/settings state and validates it before rerun. Two consecutive baseline runs establish reset acceptance; broader soak budgets must be measured before release.
- Retain fixture hash, seed, versions (including Baritone), settings, run ID, mismatches, transitions, timeout reason, logs and failure screenshot. Label simulated fault injection honestly. Do not silently accept terms or claim headless/clean-machine execution before proving it.
- Run relevant interaction checks. Run v1's suite when shared root build/test infrastructure or its environment changes; isolated docs/core edits do not require rerunning v1. The existing 419-test result is historical evidence, not a new execution or live-game result.
- Keep v1 available until the full parity matrix and replacement gates pass. No automatic installation/data migration or replacement claim at early milestones.

## Enforcement status at Task 4

V2 now builds independently with Java 21 modules. Core currently has no production dependencies; Fabric owns the game dependencies. `gradlew.bat -p v2 :core:test` runs the production model/lifecycle tests and [CoreArchitectureTest](v2/core/src/test/java/com/mapartrunner/core/architecture/CoreArchitectureTest.java), using test-only ArchUnit 1.5.1. Its rules scan compiled `com.mapartrunner.core` production classes, exclude test classes, require the actual `BuildPlan` and `RunLifecycle` classes, and reject empty scans/rule selections.

The architecture checks enforce exactly:

- No bytecode dependencies from core on `net.minecraft..`, `net.fabricmc..`, `org.spongepowered.asm..`, `baritone..` or `com.mapartrunner.fabric..`.
- No dependency cycles between core packages, including its root and nested packages.
- Every field declared in `com.mapartrunner.core.model..` is final. This is a structural check, not proof of deep immutability; existing model tests separately exercise defensive copies for the current collection fields.

A temporary production model class with a non-final field made the focused architecture run fail on that field; the fixture was removed before the passing run and is not shipped. These checks do not detect reflective/string-based platform access, game-directory paths or every adapter-only effect, and do not establish control ownership or gameplay behavior. Scenario, release-artifact exclusion and parity gates remain pending executable evidence. Reviewers still enforce the remaining written requirements; required checks cannot be silently relabeled optional.
