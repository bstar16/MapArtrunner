# V2 Tick Effects and Control Ownership Implementation Plan

> **For agentic workers:** Implement through the already authorized task-agent and independent-review workflow, one complete reviewed capability at a time. Task 6b is complete, independently reviewed and verified in PR #250; follow this focused plan without redispatching completed parent work.

**Goal:** Build a real single grounded lane through production core decisions, native client controls and vanilla placement, with honest client evidence and independently checked server results.

**Architecture:** `BuildEngine` coordinates existing lifecycle, ledger and movement owners; it does not reimplement their policies. A single `FabricBuildAdapter` snapshots client facts, calls `BuildEngine.tick(TickInput)` once per client tick and applies typed decisions. `ControlArbiter` alone writes automation keys and switches control ownership; production never inspects a server.

**Tech stack:** Existing independent Java 21 core/Fabric modules and pinned Minecraft 1.21.11, Yarn 1.21.11+build.4, Loader 0.19.2, Fabric API 0.141.1+1.21.11, Loom 1.16.1, Gradle 9.5.0 and JUnit 5.10.2. No added framework/dependency.

**Spec:** [approved design](../specs/2026-10-28-v2-reliability-design.md), [parent Task 6c](2026-10-28-v2-reliability-plan.md#6c-tick-effects-and-control-ownership), [constraints](../../../CONSTRAINTS.md), [behavior parity](../../../v2/docs/behavior-parity.md), and [reviewed native movement](2026-10-28-v2-native-movement.md).

## Status and scope

Adopted focused plan, 28 October 2026. Task 6c execution is in progress: capabilities C1-C7 are implemented and independently reviewed; C8 onward remains pending. Task 6b passed task/final reviews and both hosted checks with 252 core tests and actual gametest-source compilation, published in PR #250 at aa3245df46aea99704e7a33dc97c7de038de1d56. These are prerequisite results, not Task 6c execution. Read the retained pinned evidence research or reproduce its official-source checks when needed. Its actual `LanePlanner.Direction` methods are `yawDegrees()`, `alongX()`, `progressCoordinate(double,double)`, `lateralCoordinate(double,double)`, `forwardSign()` and `lateralStrafeSign()`. Reuse them unchanged. All seven independent plan-review amendments, including the mixed-clock high-water clarification, are adopted and incorporation review is approved. Source contracts and their costs below bind each implementation slice.

The programmatic one-lane fixture is appropriate to 6c; real schematic import remains 6d, and Checkpoint B remains **an imported schematic** building through the production boundary. Full traversal/resume/reverse, genuine travel/refill, configuration/commands/HUD and inspect/reset remain their parent tasks. An unsupported multi-lane request must return a useful explicit refusal before effects, never discard lanes or report partial work as success. This milestone does not approve removal of pending parity features.

## Global constraints

- V1 source/build/settings/tests remain unchanged. Do not touch `BuildCoordinator`, copy the grounded runner or reference v1 production classes from v2.
- Maximum **five changed source/test files per reviewed slice**; the sets below also stay within five total files including resources/docs unless explicitly split. Setup belongs with the capability that needs it. If a real dependency exceeds a set, revise/split that capability before implementation; do not conceal extra files.
- Core has no Minecraft/Fabric/Mixin/Baritone types or filesystem/game-directory access. No plugin framework/event bus, second placement ledger, parallel pending registry or duplicated movement geometry/footprint classifier.
- All expected targets and full `BlockSpec` properties remain accounted for. Accepted effect, elapsed time, completion of movement and acknowledgement alone never confirm construction.
- Production `OBSERVED` means loaded client-world evidence with no outstanding vanilla prediction at that position, **not a fresh server receipt per attempt**. This narrower meaning is consistent with the existing `PlacementObservation` contract and confirmed by the controller. Independent server checks are test-only.
- Native movement owns all active lane/shift controls. Baritone is not integrated here; unavailable coarse travel is an explicit bounded outcome, never a fake successful journey.
- One real client tick -> coherent snapshot -> one core advance -> bounded ordered effects. No renderer-driven or hidden extra ticks. Pause freezes movement/deadline budgets; a long wall-clock stall must not consume multiple simulation ticks.
- Every run binds connection session, client-world identity, dimension, plan fingerprint, origin and immutable settings. Release/invalidate before clearing owner state; never automatically resume on rejoin.
- EULA acceptance already covers the user's disposable local/CI instances. Use the existing finite external process supervisor and retain evidence; this does not claim CI/headless equivalence.

## Sources and decisions that must travel with implementation

1. `.superpowers/sdd/2026-10-28-v2-reliability-plan/task-6c-evidence-research.md` contains pinned-bytecode evidence, official Yarn anchors and the exact candidate-2 bridge. **Do not repeat full source forensics.** `ClientWorld.getPendingUpdateManager()` is package-private; its manager's pending-position map is private. Two client Mixins expose only the manager and a boolean `containsKey(pos.asLong())` query. `PendingUpdateManager.hasPendingUpdate(pos,state)` **mutates** the stored reconciliation state and is forbidden as a query. `hasPendingSequence()` is not per-position pending evidence. The manager belongs to the current world and must never be cached across world replacement.
2. Current ledger APIs are `PlacementLedger(BuildPlan)`, `bindRun(UUID)`, `recordAttempt(UUID,long,int,long)`, `observe(PlacementObservation)`, `unfinishedTargets()`, `outcome()`. Fresh observations require `tick > attemptTick` and `tick > priorObservationTick`. Actionless samples are supported. `ALL_CONFIRMED` is reversible accounting. A new attempt reopens a target. No pending/retry query exists today.
3. `RunLifecycle.start(Context)` enters APPROACH. `pause/resume/stop/updateActivity/snapshot` do not own effects. `resume` requires equal full context; stop preserves its first terminal result. `BuildPlan` supplies immutable dimensions/targets/fingerprint and stable potentially sparse IDs; nested positions/blocks must be reused.
4. Task 6b supplies whole-plan `ExpectedFloor`, lane geometry, complete `LaneEntry` counters and stateless `MovementPolicy.decide`. The engine stores **all returned context fields** once. It must not derive support from unfinished/pending subsets or rerun a second movement simulation. Movement holds must permit placement to fill the awaited floor. WALK support hold can retain lateral correction; entry/yaw holds cannot. Endpoint tolerance is not build completion.
5. Reuse understood v1 semantics, translated rather than linked: `GroundedSweepPlacementExecutor.select` ranks current cross-section, forward lookahead, then trivial behind cleanup; defaults in `GroundedSweepPlacementExecutorSettings` are half-width2, lookahead1, behind1 and failed-placement grace4. `ServerPlacementHitResultFactory.build` uses a real adjacent support and `center(support)+side*0.5`, with `support.offset(side)==target`; a generic click cannot build a five-wide section. `HotbarSlotReservations` excludes first N slots, N in0..8. `GroundedSingleLaneDebugRunner.forcePlayerYaw` aligns yaw/head/body/interpolation fields, but its reflection is not an instruction to silently ignore unavailable mapped fields in v2.
6. Task 5's `ScenarioEnvironment` proves ordinary interactBlock, independent server checks and screenshots. It is not a provenance classifier, builder or withheld-confirmation test. `v2/docs/testing.md` documents exact thread marshalling, mod-ID-only game-test selection, AWT-dialog bypass, supervisor and artifact limits. There is currently no production client entrypoint.

Only a few focused platform checks remain before implementing their owning slices: verify mapped chunk availability, full block-property conversion, key/yaw fields, usable slot selection, survival reach/placement legality and tick callback order against the local pinned artifact/official Yarn. Record those specific signatures in slice evidence, compile them and test them. Do not invent a packet, property, command, selector or runtime capability. Existing research already resolves prediction mapping.

## Review focus

- Matching prediction or acknowledgement without matching state: classifier and real fault cases must keep targets unfinished.
- A support hold deadlocking placement: engine must select required reachable floor during entry/WALK holds; real fixture must show this resolves.
- Old decisions after pause/stop/world replacement: immediate invalidation and release must defeat placement, slot and key effects, not only stale observations.
- Stationary placement masquerading as a native builder: fixture must require measured client/server movement beyond initial placement reach, with real controls/physics.
- Earlier confirmed floor changing or disappearing: final fresh full-plan scan must reopen wrong/unknown targets and prevent terminal SUCCESS.

## Ownership, proposed contracts and tick order

Names in this section are **proposed 6c APIs**, not existing capabilities. Keep types nested beside their owner unless a concrete size/reuse reason justifies a separate file.

### Ledger addition

Preserve `boolean recordAttempt(UUID runId, long actionId, int targetId, long tick)` and its existing acceptance/freshness/duplicate behavior. Add the named production issuance API and immutable queries:

```java
boolean recordIssuedAttempt(UUID runId, long actionId, int targetId,
                            long sampleTick, long issuedEligibleTick);
Set<Integer> pendingTargetIds();
Optional<AttemptSnapshot> latestAttempt(int targetId);
record AttemptSnapshot(long actionId, long tick,
                       OptionalLong issuedEligibleTick,
                       long count, boolean awaitingObservation) {}
```

`tick` remains the sample freshness coordinate. A genuinely new legacy four-argument attempt has absent eligible metadata; never infer it from sample tick, default it to zero or store a second engine history map. Both issuance methods share one private atomic validation/update path. A four-argument exact repeat preserves all metadata, including eligible time from an original named issuance. A five-argument exact repeat is idempotent only when all recorded issuance coordinates match; conflicting eligible metadata or enriching a legacy duplicate is rejected without mutation. Validate nonnegative eligible time and no backwards eligible history where prior eligible history exists; do not numerically compare clocks. Preserve a private per-target timed-issuance eligible high-water mark across new legacy attempts, even while the latest legacy AttemptSnapshot.issuedEligibleTick is empty; the field belongs to the ledger, is constant-size per target, and is never inferred from sampleTick. Include timed-to-legacy-to-timed tests: a backwards timed value is rejected without mutation, an equal or later value follows the remaining new-attempt rules, and the legacy snapshot stays empty. Checked count overflow fails before any mutation. BuildEngine uses **only recordIssuedAttempt**; absent eligible metadata in its issued history is an invariant failure, never a fallback.

Queries require a bound ledger; a known unattempted target returns Optional.empty, while an unknown target ID fails usefully. `pendingTargetIds` contains only attempts awaiting a later accepted OBSERVED sample:

| Input | Pending/attempt history |
|---|---|
| New valid attempt | increment owner-held count; latest action/tick replace; pending true; confirmation false |
| Exact latest duplicate | no history/count/pending change, even after resolution |
| Fresh accepted UNKNOWN/PREDICTED | remain pending if an attempt is pending; confirmation false |
| Fresh accepted OBSERVED match | pending false; confirmed true |
| Fresh accepted OBSERVED mismatch | pending false; confirmed false; eligible for later bounded retry |
| New valid retry | count increments; pending true again |
| Stale tick/run/action or unknown target | no mutation |
| Actionless prediction after a resolved attempt | confirmation reopens; does not invent a new owned attempt; evidence itself provides movement's predicted hold class |

Pause retains the ledger/history; stop/world change discards it only after routing and effects are invalidated. The query is placement accounting, not a query into Minecraft's prediction manager. Attempt count/deadlines are read from this owner, not copied into engine maps. Checked count increment must not wrap. Return copied immutable sets/snapshots.

### Engine boundary

`BuildEngine.start(BuildPlan plan, RunLifecycle.Context context, Settings settings)`, `pause()`, `resume(Context context)`, `stop()`, `snapshot()`, and `TickDecision tick(TickInput input)`.

Nested immutable contract shapes:

- `TickInput`: nonnegative monotonic run-local sample tick; optional current binding and player; existing `SupportObservation`; actual sampled `List<PlacementObservation>`; immutable target-ID -> `PlacementAvailability` map; previous effects' correlated `PlacementEffectResult` list. There is **no caller eligibility flag**.
- `PlacementAvailability`: READY, OUT_OF_REACH, NO_USABLE_ITEM, NO_VALID_HIT, UNSUPPORTED. These are observed adapter capabilities, not core Minecraft objects or a claim every target is presently reachable.
- `PlacementIntent(UUID runId, long actionId, int targetId, long tick)`; `PlacementEffectResult` has the same identity and ACCEPTED/REJECTED/UNAVAILABLE/STALE. Acceptance does not set ledger evidence. Do not route fabricated success when an effect was not applied.
- `TickDecision`: run identity plus unique decision/sample tick; desired control owner NONE/NATIVE/TRAVEL; optional existing `MovementIntent`; optional `PlacementIntent`; immutable lifecycle/result diagnostics. No mutable collection or Minecraft value escapes.
- `Settings`: immutable halfWidth/stride, constantSprint and explicit finite internal attempt/run budgets. Snapshot a fingerprint consistently with `Context`; mutable user settings must not leak into a started run. Reserved-slot configuration is part of the adapter's same snapshotted settings, without inventing a CLI/config system.

Adopted milestone-internal budgets: one placement intent per active eligible callback, three attempts per target, four eligible issuance-to-reissuance spacing, pending evidence80, VERIFY80 and total run1200. These are explicit safety limits to calibrate through the live fixture, **not new public settings or exact v1 timing parity**; do not weaken 6b to rescue a demonstration.

One accepted fresh adapter callback advances the sample coordinate once. Each actual active, nonpaused callback advances the engine eligible counter exactly once, including missing chunks/items/hits or temporarily unavailable player facts. Unavailable facts suppress unsafe effects, not the global deadline. Missing/replaced world or connection terminates immediately. Paused callbacks may collect genuine evidence but freeze movement/deadlines. Actual absent callbacks consume no simulation ticks; no wall-clock catch-up or arbitrary UNKNOWN eligibility override exists. Store eligible issuance only in the ledger's explicit metadata.

| Limit | Exact boundary |
|---|---|
| Three attempts | Per target across the run including pause/resume. Third issuance still receives its evidence wait; resolved third OBSERVED mismatch is exhausted. Never issue attempt four. |
| Four ticks | Retry only when prior attempt is no longer pending, current usable OBSERVED mismatch exists, and eligible age since issuance is at least4. REJECTED/UNAVAILABLE feedback alone never resolves evidence or permits retry. A late mismatch uses issuance spacing, not an invented resolution clock. |
| Pending80 | At eligible age80 process current genuine observation first. If still pending, terminate unresolved; never retry/promote due to age. |
| VERIFY80 | Count eligible VERIFY ticks after traversal completion; completion tick is not the later final scan. At boundary permit a genuine valid full scan, otherwise terminate unresolved. |
| Run1200 | Count all active ticks including entry/holds/VERIFY, never reset at activity changes. At boundary permit an already valid final result, otherwise fail without another effect. |

Every owner keeps its own deadline. Do not reset 6b support/yaw/entry counters because an attempt was issued, accepted or changed evidence class. Continue placement during nonterminal holds. A 6b failure ends native movement without an added80 movement grace. Simultaneous failure precedence is lifecycle invalidation, 6b failure, pending-evidence timeout, VERIFY timeout, then total-run timeout. Route observations before unresolved checks; preserve the governing reason and unresolved IDs. Terminal/budget checks precede any new effect.

Core sequence per tick: reject duplicate/stale routing; process binding/lifecycle cancellation first; validate and route current observations/correlated effects; honor paused no-effect state; advance the active eligible counter once; use stored lane context and ledger pending snapshot with existing movement policy when facts permit; resolve terminal/budget checks in the stated order; rank still-required READY targets; recordIssuedAttempt before returning its intent; return complete controls/effects. Every routed observation has this run ID, exactly the current sample tick and a known unique target ID; reject duplicate, unknown or future entries rather than poisoning freshness. Effect results must match latest issued run/action/target/sample tick; stale results cannot alter ledger/lifecycle. Native local entry starts only within its envelope; otherwise existing FAILED with COARSE_TRAVEL_UNAVAILABLE diagnostic precedes acquisition. Empty plan uses existing SUCCESS plus engine diagnostic NOTHING_TO_BUILD, without effects. Multi-lane plans use existing FAILED with an unsupported-plan reason before effects; no lifecycle enum change.

Rank placement using existing direction methods, whole-plan target IDs and v1 current/forward1/behind1 buckets; lateral order is left2,left1,center,right1,right2 then stable target ID. Do not copy geometry/support-footprint computation. Current and immediate-forward floor must remain eligible during movement holds; scheduling is independent of whether translation is allowed. Never retry a pending attempt merely because a wait elapsed. If normal selection cannot finish reachable leftovers, VERIFY may attempt remaining READY targets in stable distance/ID order with movement released; unreachable leftovers remain expected and finish explicitly PARTIAL/FAILED with IDs/reasons. Full reverse cleanup is later.

Movement COMPLETE transitions to VERIFY and releases movement. SUCCESS requires a later full observation phase whose unique ID set **equals the immutable expected target-ID set**, every sample is current OBSERVED exact full-state matching and accepted by the ledger, and ledger outcome is ALL_CONFIRMED. Its tick is strictly later than entering VERIFY and each relevant issuance. No new effect is issued on that decision. Sparse IDs work; list length or duplicate entries cannot stand in for set equality. Same-tick evidence cannot confirm; later valid evidence can. Partial scan, stale cached confirmation, missing producer sample, future tick, wrong property, unloaded or PREDICTED state cannot pass. VERIFY continues bounded work; unresolved timeout preserves IDs/reasons and releases controls. Terminal summaries remain immutable diagnostics after mutable state disposal.

### Adapter/effects boundary

`FabricBuildAdapter.tick()` is registered once at end-client-tick; no test drives the engine in addition to that callback. Minimal production initializer owns the adapter instance and exposes a narrow Java start/stop/pause/resume API for future command wiring and the fixture. Do not add public test commands, a fake import command or UI.

Bind using actual client network-handler/connection and `ClientWorld` object identities plus dimension; adapter-generated session/world tokens carry those identities into core. Never use integrated-server objects or a dimension string alone. Revalidate binding immediately before each effect. New world/rejoin invalidates the old run and requires a new explicit start; paused runs cannot auto-resume.

Order: (1) recognize invalidation and release old controls; (2) sample loaded client state and prediction status coherently on the client game thread, outside sequenced/reconciliation stacks; (3) call engine once; (4) validate decision identity; (5) release old control owner before acquiring new; (6) align native yaw/head/body/interpolation values; (7) stabilize/select usable item and call real placement; (8) apply the complete native control mask for the next physics tick. If placement needs a conflicting rotation, do not move on that frame: preserve staged lane alignment and reacquire readiness on actual later observations. Prefer legal support-face placement that needs no conflicting rotation for the first fixture. Never fabricate a changed yaw observation in core.

ControlArbiter writes forward/back/left/right/jump/sneak/sprint/use/attack automation masks, explicitly releasing unrequested controls. It releases only when it owns automation, so idle ticking does not erase ordinary user input. A narrow key/travel sink supports recording tests; the real implementation writes Minecraft keys. TRAVEL acquisition is explicitly unavailable until a real backend exists; recording travel is not actual navigation. Stop/cancel never clears vanilla pending maps.

Capture the adapter's generation **before snapshot/engine work** and retain that captured value beside the decision in private nested adapter state. Before each slot, placement, yaw or control effect require unchanged generation, RUNNING phase, current binding/current decision identity and not-already-applied decision tick. Increment/invalidate before release on pause, stop, world loss or terminal transition; pause/resume cannot relabel an old decision even though UUID is retained. VERIFY owner NONE does not exempt placement from these checks. Wrap active snapshot/effect application in exception-safe cleanup: invalidate, release all owned controls, preserve an explicit failure diagnostic and issue no later effect. This must work for throws after acquisition and between slot/placement/control stages, without waiting for another callback.

## Ordered reviewed capabilities

All paths below are repository-relative. Each task writes its named regression first, records focused red cause, implements the smallest production behavior, runs focused green, self-reviews and commits the complete slice; **then the controller obtains independent review** and resolves findings before the next dependent capability. No new test-count target or assertion weakening. Sequential Task numbers below retain parent capability IDs C1-C11, including mandatory C10b.

**Production size budget:** nine new Java files total, plus one edited existing ledger. They are BuildEngine, ControlArbiter, FabricBuildAdapter, two exact-version prediction bridge Mixins, their tiny external PendingPredictionView interface, one evidence converter, one placement executor and the minimal client initializer. Contracts/settings/diagnostics stay nested with their owners except this one leaf prediction interface: the pinned Mixin loader prohibits an ordinary nested public interface in its owned Mixin package. The explicit increase from eight to nine adds no state owner or framework. No factory, config framework, generic platform port, action bus or target registry. Existing LanePlanner, MovementPolicy, BuildPlan, RunLifecycle and PlacementObservation remain unchanged. Six new focused JUnit files and six game fixture/fault Java files are planned, including mandatory C10b. Small slices do not authorize further classes.

### Task 1: C1 — Placement owner exposes truthful pending/retry facts

**Depends:** final 6b review and accepted 6c plan. **Files (2):** modify `v2/core/src/main/java/com/mapartrunner/core/placement/PlacementLedger.java` and its existing `src/test/.../PlacementLedgerTest.java`.

- [x] Implement the frozen named five-argument issuance API, OptionalLong eligible metadata, legacy compatibility and pending/count lifecycle above, using one atomic internal update path.
- [x] Test unattempted, duplicate-before/after-resolution, predicted/unknown retention, OBSERVED match/mismatch clearing, retry, actionless samples, stale run/action/tick rejection, immutable views and pause-retained history. Include mixed legacy/new duplicates, absent eligible metadata, clocks separated by a long pause, backwards/conflicting eligible metadata and checked count overflow with no partial mutation. No `!confirmed` shortcut for pending.
- [x] Run focused `:core:test --tests '*PlacementLedgerTest'`; preserve all existing ledger semantics and architecture constraints.

Verified: 30 ledger cases and the complete 263-test core suite passed with zero failures/errors/skips; independent task review approved. No game behavior is claimed by this accounting change.

**Done:** later movement and bounded retry decisions can read the one owner without an independent pending map.

### Task 2: C2 — Complete single-lane core coordinator

**Depends:** C1 and reviewed 6b APIs. **Files (3):** create `v2/core/src/main/java/com/mapartrunner/core/run/BuildEngine.java`, `v2/core/src/test/java/com/mapartrunner/core/run/BuildEngineTest.java`, and `BuildEngineLifecycleTest.java` beside it.

- [x] Implement the complete boundary above using existing `RunLifecycle`, `PlacementLedger`, `LanePlanner`, `MovementPolicy` and nested values; document state creation, retained pause context, stop invalidation/clearing order and terminal diagnostic retention.
- [x] Prove actual production ticks preserve every returned movement counter, require both entry coordinates/yaw, keep placement active on support hold, retain all targets, rank current/forward/behind candidates and have deterministic bounded retries/waits. Missing material/hit/support never disappears from expectations.
- [x] Prove accepted effect does not confirm; same-tick evidence cannot confirm and later valid evidence can; traversal completion cannot succeed; final fresh full scan can. Test exact unique expected-ID-set equality with sparse IDs, duplicate/missing/unknown/future producer entries, and effect identity including issuance sample tick. Cover missing evidence for an unconfirmed issued target and a missing final sample after prior confirmation, changed properties, UNKNOWN and prediction. Empty plan is SUCCESS/NOTHING_TO_BUILD without effects; multi-lane/travel refusal is FAILED with diagnostics before acquisition.
- [x] Prove no fourth attempt, third issuance retains its evidence wait, resolved third mismatch exhausts, no pending retry at any age, and late mismatch follows issuance-based4 spacing. Test support40 preceding confirmation80, pending support80, earlier entry failure, simultaneous failure precedence, genuine resolution/final success at boundary80/80/1200, pause at boundaries, and continuous unavailable facts reaching a finite outcome with no post-exhaustion effect. No 6b deadline resets or added grace.
- [x] Lifecycle tests cover duplicate tick/no second movement advance; stale/foreign observations/effect results; pause retaining WALK progress/counters, immediate no-effects, resumed fresh sampling; stop from BUILD/VERIFY; world replacement/rejoin; malformed context/fingerprint; terminal outcomes idempotent; bounded failure with reasons and IDs.
- [x] Focused engine tests red/green; then full `:core:test` including architecture. This is one coherent coordinator capability, not a new generic state-machine framework.

**Done:** deterministic production core can drive a full one-lane decision trace, but no live gameplay claim.

Verified: the initial full core run passed 324 tests. Independent review found two issues; the composed uncertainty regression reproduced the failure, the fixes passed 64 focused engine/lifecycle cases, and scoped re-review approved both. The 324-test run predates those fixes; no post-fix full-suite execution is claimed here.

### Task 3: C3 — Exclusive automation control ownership

**Depends:** C2 typed decisions. **Files (2):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/runtime/ControlArbiter.java` and `v2/fabric/src/test/java/com/mapartrunner/fabric/runtime/ControlArbiterTest.java`.

- [x] Implement sole key writer with nested complete control mask, current owner/run/generation, narrow recording sink seam and actual Minecraft key sink; verify pinned fields before coding them.
- [x] Use the arbiter's opaque Binding as the single control/effect generation authority, captured before adapter snapshot/core work. Bind/invalidate only on trusted lifecycle paths; callbacks cannot fetch a newer binding. Unique opaque acquisition Lease identity rejects old native apply/release after native -> travel -> native within the same binding. The adapter still independently checks running phase, world/run/decision/tick before every effect.
- [x] Handle the pinned StickyKeyBinding semantics for sneak, sprint, use and attack: write only if desired differs from actual; if a false request remains pressed, one true request clears the toggle. On owned false cleanup, also consume pending screen-close restoration through the public per-key consumer even when already visibly false; otherwise closing a screen can restore old automation. Idle/unowned manual restoration stays untouched. Do not change user toggle settings, reflect into protected reset methods or globally unpress unrelated keys. Clear actual sprint state only on the captured owned player, including cleanup. Test real base/hold/toggle keys and saved screen restoration; compile is not live physics proof.
- [x] Test release-before-acquire event order native->travel->native, failed travel acquire leaves NONE, idempotent release, all nine unrequested controls released, idle manual keys untouched, pause/stop/world replacement preventing stale reacquisition, and exception cleanup after acquisition. Recording travel proves exclusivity only; production returns UNAVAILABLE.
- [x] Run focused `:fabric:test --tests '*ControlArbiterTest'`; compile real key sink. No active lane Baritone path or `allowPlace` exists.

**Done:** ownership and key cleanup are testable in isolation with a real mapped implementation.

Verified: real-key and ownership tests passed 63 focused cases after reproducing and fixing saved screen-restoration failures; mapped client compilation and scoped re-review passed. Actual gameplay screen transitions and player physics remain later gates.

### Task 4: C4 — Read-only access to vanilla prediction state

**Depends:** accepted evidence research and reviewed C4 layout amendment. The ordinary predicate API must live outside the configured Mixin package; the pinned loader bans unregistered ordinary nested types there. Preserve the five-file review cap through these two substeps. C4 completes only after both reviews and the functional live evidence; no renumbering of Tasks 5-12.

#### Task 4.1: External read-only predicate contract

**Files (1):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/prediction/PendingPredictionView.java`.

- [x] Declare the public `boolean mapart$hasPendingPrediction(BlockPos pos)` interface outside the Mixin-owned package, with its read-only/client-thread/current-manager contract. This leaf depends only on the game coordinate type, not runtime, Mixins or a registry. It owns no cache or mutable state. Do not place unrelated prediction API in ControlArbiter or create a runtime-to-mixin-to-runtime package cycle.
- [x] Compile the real Fabric source set with `:fabric:compileJava`, self-review/commit and obtain independent review. A trivial abstract method contract needs no artificial unit test. Compile/review here proves only the declarative API, not an applied Mixin or prediction behavior.

Verified: the external one-method contract is committed and independently reviewed. Current-input JDK 21 compile-task validation succeeded with outputs up to date, and the actual compiled signature was inspected. No functional bridge or live behavior is claimed by Task 4.1.

#### Task 4.2: Registered bridge and real probe

**Files (5):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/mixin/ClientWorldPendingUpdateAccessor.java`, `PendingUpdateManagerMixin.java` beside it, `v2/fabric/src/main/resources/mapart.client.mixins.json`; modify main `fabric.mod.json` and existing gametest `ScenarioEnvironment.java`. Consume reviewed PendingPredictionView unchanged.

- [x] Write the real probe assertions first, retain expected missing-accessor compile RED, then implement/register both exact client-only Mixins. The accessor is a registered public accessor; the manager Mixin implements the external predicate. Keep its exact `Long2ObjectOpenHashMap<?>` shadow private and read only `containsKey(pos.asLong())`. No ordinary nested predicate in the Mixin package, exposed map getter, concrete-Mixin consumer, pending-map modification, scope-only `hasPendingSequence` shortcut or mutating `hasPendingUpdate` query.
- [x] Inside the real client interaction lambda, after `interactBlock` returns, require actual pending key and matching predicted stone; repeat the predicate read without changing the visible state. Preserve the existing independent server assertion, including expected-gold wrong-mode failure. Later bounded client sampling requires key disappearance and actual matching client stone. No server-derived production evidence. This positive case does not prove preservation of the hidden reconciliation baseline; mandatory C10b still supplies the distinct ack-only/missing-state proof.
- [x] Compile `:fabric:compileGametestJava :fabric:remapJar`, inspect exact registration/remapped accessor-shadow selectors/interface/predicate and release exclusion, then run the finite supervised real positive and wrong-expected probes and inspect evidence. Use the production Mixin config; failure must not silently become not-pending/OBSERVED. No guessed refmap, legacy-AP/pin change or claim that development launch proves remapped-release runtime. Preserve all previous probe assertions, screenshots/logs/world closure and wrong-mode normal nonzero exit without outer timeout.

Verified: missing-accessor compile RED preceded successful compilation/remapping. Independent source/artifact/live-evidence review approved the exact five-file slice: two actual positives and a normal child-Java-1 expected-gold failure, with screenshots, independent server checks and world closure. An earlier negative's native shutdown status and an extra pre-probe startup stall remain unresolved test-host concerns; the stalled attempt was explicitly aborted and supplies no passing evidence. No successful positive after the normal-negative retry, host stability, release-runtime launch or C10b proof is claimed.

**Done:** smallest source-supported hook works without rewriting vanilla prediction. This alone is not 6c acceptance.

### Task 5: C5 — Coherent client evidence snapshots

**Depends:** C4 and C2. **Files (3):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/runtime/WorldEvidenceReader.java`, `v2/fabric/src/test/java/com/mapartrunner/fabric/runtime/WorldEvidenceReaderTest.java`; extend existing `ScenarioEnvironment.java` to exercise the real reader.

API ruling: use a stateless `capture(MinecraftClient, BuildPlan, RunLifecycle.Context, ClientWorld, ClientPlayNetworkHandler, long)` producing a privately constructed immutable nested `CapturedEvidence`. The existing context and retained actual world/handler references bind the client-thread capture; the adapter remains the identity/generation owner. Static `placementObservations(CapturedEvidence)` is the actual production conversion seam, while `CapturedEvidence.supportObservation()` exposes the same captured immutable samples without another world query. C10 targets that real static method; its exact test injection remains a later compile/live gate. Verify loadedness with mapped `getChunk(x,z,FULL,false)`, serialize all actual properties, and use checked origin translation. The mandatory test-only unload/reload uses bounded ordinary server teleport/tracking with finally restoration and validation before placement; production never reads a server.

- [x] Reader converts loaded current-world block ID and **all properties** to existing BlockSpec. Query and sample occur in one client-thread phase. Missing world/chunk/query/binding is UNKNOWN; actual pending key is PREDICTED even on exact state; nonpending loaded state is OBSERVED including air/mismatch. Use the same captured facts for ledger observations and existing SupportObservation, not two conflicting world reads. Sample every requested target; do not invent UNKNOWN for a target never visited.
- [x] Name the production observation-list seam `WorldEvidenceReader.placementObservations(...)` when defining its exact parameter signature in this slice; C10 targets this actual list-returning method after compilation, while separately captured honest support facts remain intact. This is the normal production conversion boundary, not a dummy producer or test mode.
- [x] Test full-state/property conversion, negative origin translation, genuinely unloaded chunk/default-empty distinction, current binding and actionless freshness. Real probe confirms actual pending/nonpending classification and existing correct blocks without inventing attempts. Read-only queries cannot make an acknowledgement look successful.
- [x] Assign repeated prediction/latest reconciliation to C9/C10b's actual fault fixture and the reader's focused assertions; a repeated position must remain PREDICTED until its latest real reconciliation. Exercise real client chunk unavailability in the owned fixture through a bounded legitimate load/unload transition, assert UNKNOWN and reopening, and restore/validate the fixture before building. Distinguish loader-backed conversion checks from actual live chunk/prediction results in evidence; neither is silently omitted.
- [x] Focused loader-backed unit tests and compile; use C4's live probe only as extended when the reader changes it. No production server access or global sequence heuristic.

Verified: eight loader-backed reader cases and actual gametest compilation passed after normal test-only registry bootstrap. The changed live positive proved real chunk unavailability, UNKNOWN reopening, validated reload/fixture restoration before interaction, genuine predicted/nonpending classification, independent server stone and normal world closure. Independent source/live-evidence review approved all three files. The retained descriptor matches C10's actual static seam; its future injection and C9/C10b's latest-reconciliation faults remain unexecuted.

**Done:** core receives meaningful evidence and full expected-floor observations.

### Task 6: C6 — Real item selection and placement effects

**Depends:** C2/C3/C5. **Files (2):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/runtime/FabricPlacementEffects.java` and its `src/test/.../FabricPlacementEffectsTest.java`.

API ruling: stateless static `availability(client,plan,context,settings)` returns immutable per-target facts; guarded `apply(client,plan,context,settings,intent,arbiter,capturedBinding,currentAction)` returns the existing correlated effect result plus useful detail. A production-used nested recording seam verifies revalidation/selection/interaction order. The arbiter remains the sole generation authority and the adapter owns the current decision/action/tick guard. This milestone faithfully previews exact ordinary `BlockItem` through verified public block/context/legality APIs, including existing stack block-state components; specialized subclasses or unexpressible full states return explicit UNSUPPORTED. Their support remains pending parity work before v1 replacement, without a new permanent deferral. No default-state substitution, reflection, additional Mixin, packet, state registry or dependency is authorized.

- [x] Snapshot placement availability from actual loaded target/support, full expected state, usable hotbar material, player reach/collision and a valid clicked face. Apply only after current run/action/binding/control validation. Recheck volatile slot/support/reach before real `interactBlock`; report result next tick without confirming.
- [x] Translate the verified support-face rule, first-N reservation rule and stable selected-slot behavior. Test left/right two-block offsets and center separately, not one generic click. Use actual pinned reach APIs; do not hardcode creative reach into survival placement. A legal target-state placement that cannot be expressed by the implemented vanilla route returns UNSUPPORTED, never silently substitutes a default block state.
- [x] For 6c use sufficient **usable hotbar inventory**; selection is real. Automated inventory swaps/full inventory demand/refill remain pending parent work, not a claim that those preserved features are removed. Test reserved slot0 matching block remains untouched when slot1 is usable, no usable item, no support, out-of-reach, wrong properties and stale action/binding rejection. No packet fabrication or utility key bypass.
- [x] Focused `:fabric:test --tests '*FabricPlacementEffectsTest'` and compile actual Minecraft calls. C8 supplies live proof of item selection/physics/server placement; unit fakes are not that proof.

Verified: 68 focused loader-backed cases and actual mapped compilation passed; dropping the production stack-component projection caused two meaningful regression failures before exact restoration and final verification. Independent task review approved both files and checked the pinned ordinary-item/reach/slot-sync semantics. The private live player/world route is compiled and source-reviewed, with C8 still required for actual native item/context/multi-offset/physics/server proof.

**Done:** typed intent has a bounded real effect route with useful rejection reasons.

### Task 7: C7 — Once-per-tick production adapter and entrypoint

**Depends:** C2-C6. **Files (4):** create `v2/fabric/src/main/java/com/mapartrunner/fabric/runtime/FabricBuildAdapter.java`, its `src/test/.../FabricBuildAdapterTest.java`, `v2/fabric/src/main/java/com/mapartrunner/fabric/MapArtClient.java`; modify main `fabric.mod.json`.

API ruling: one concrete adapter owns the real engine, retained actual run binding, one in-flight decision envelope and next-callback feedback. The initializer exposes only Java start/pause/resume/stop/status and registers exactly one engine-driving END callback plus verified lifecycle hooks. A narrow nested five-operation client recording seam may carry opaque identity references locally; no Object enters core and no generic platform port is added. `Status(engine,controlOwner,failure)` samples actual `arbiter.owner()` directly without another owner field, enabling real ownership checks. Adapter failure diagnostics retain immutable trace text and the original error is rethrown after strict cleanup; existing core cancellation remains honestly CANCELLED, without a second outcome or hidden error tick. VERIFY/NONE releases the Lease while retaining current Binding. Synchronous nonnull world-change setup releases old ownership before new-world physics; safely marshalled disconnect cleanup correlates the event's handler and connection so old events cannot cancel new runs.

Milestone pause policy: any screen opening or actual simulation pause invokes existing PAUSED and requires explicit same-binding resume after the screen/pause ends. Missing active player/chunk/material facts still consume eligible time. This conservative Java-API-only limit needs preservation-aware chat/config/owned refill-screen integration before those later acceptance gates and v1 replacement; it is not a permanent parity change. Record that upgrade boundary, retain idle manual input, and reuse the arbiter's saved sticky-restoration cleanup.

- [x] Register exactly one callback and expose the minimal Java lifecycle entrypoint. Bind current client object identities/tokens and snapshot immutable settings. Implement the ordered phase/effect boundary above, using the real reader, placement effects and arbiter.
- [x] Test one engine call per callback, no extra tick for immediate interaction result, result delivery on later tick and no caller-driven timeout freeze. Capture generation before snapshot; test pause then resume before stale application, rejecting old slot/placement/yaw/control effects despite unchanged run UUID. Throw during snapshot, after acquisition and between slot/placement/control stages: invalidate/release immediately, retain failure and forbid later effects. Cover every release path and rejoin auto-resume rejection. Fake snapshots test orchestration only; keep conversions in their owners.
- [x] Verify pinned yaw/head/body/previous interpolation field APIs and apply staged alignment before movement controls. Tests preserve the reviewed bounded, supported, yaw-aligned ENTER_POSITION approach; gate lane WALK on both entry coordinates and actual staged yaw readiness, obey zero-mask alignment/holds and suppress conflicting-placement-aim translation. Real C8 calibrates the Task6b 1.5-degree/20-decision yaw hold; do not broaden thresholds or fabricate observations to hide failed staging.
- [x] Focused adapter tests then `:core:test :fabric:test :fabric:compileGametestJava :fabric:remapJar`. Inspect release classes/metadata for only intended production entrypoints/Mixins. No public command/UI/parity expansion.

Verified: 48 focused adapter tests and the combined 327 core/187 Fabric tests passed with zero failures/errors/skips; both suites and remapping executed, while unchanged gametest compilation validated current inputs with outputs up to date. Independent source/evidence review approved all four files, exact callback/lifecycle wiring and release exclusion. Actual motion, survival placement, physical release and lifecycle scheduling remain C8 acceptance; C9-C11 faults and the final focused review remain required.

**Done:** the production callback owns the actual runtime path; a fixture can start it without driving hidden engine ticks.

### Task 8: C8 — Single-lane real physics and independent final-world proof

**Depends:** C7. **Files (3):** create `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/GroundedSingleLaneGameTest.java`, `SingleLaneScenario.java` beside it; modify gametest `fabric.mod.json`.

Lifecycle-fixture ruling: after the separate baseline succeeds, a fresh owned active-run world-replacement case may use one real server dimension transfer to exercise genuine ClientWorld replacement. Prepare and validate its bounded destination standing pad before start; label the transfer a simulated lifecycle fault. It supplies no baseline movement/reach/building or no-teleport safety proof. Require actual old-run termination/control release and 20 available new-world idle physics ticks afterward, with no velocity reset or subsequent position correction. Every baseline and other active safety measurement retains the post-start teleport prohibition; do not substitute close-and-rejoin for an active differing-world test.

- [ ] Build a bounded disposable fixture with a programmatic neutral plan, width12/depth5/height1, origin `(0,65,0)`, stone targets at relativeY0, seed target `(0,0,2)` already stone, remaining targets air. Bedrock at worldY64 and bounded plane-height off-plan apron support legal placement/standing. Start survival player on the seed at `(0.5,66,2.5)` with yaw0 (requiring EAST alignment), sufficient unreserved hotbar stone, reserved slot0 sentinel and known inventory/settings. Record every fixture coordinate/hash; use constantSprint=false for this initial physical baseline and explicitly record it.
- [ ] Validate server/client initial state before starting the actual adapter once. After start, no teleport, fixture motion, flying, jumping, target editing, injected key-success observation or manual tick. Record client and independent server positions through terminal cleanup; measured positive-X displacement must exceed the initially measured real survival reach. Require feetY within0.05 of intended standingY66 and positions within the recorded lane/apron at each sampled physics phase; falling onto lower bedrock fails even if blocks succeed. Assert actual placements at both outer columns via individual legal hits, and a real support hold followed by placement, OBSERVED evidence and resumed motion.
- [ ] Require terminal production SUCCESS, all exact independent server blocks, inventory/reservation protection and final verification after movement completion. Check all nine forward/back/left/right/jump/sneak/sprint/use/attack controls released and owner NONE on success/failure/pause/stop/disconnect/world replacement. Observe20 actual post-release ticks where a world remains available, with no renewed automation, unsafe coasting, standing-height loss or fixture escape; never zero velocity or teleport to manufacture safety. For disconnect/world replacement check release immediately and through20 subsequent available callbacks/new-world idle ticks. Server truth never supplies production observations.
- [ ] Finite gameplay waits use predicates and a stated budget; outer process supervision remains mandatory for startup/screenshot/shutdown. Preserve fixture hash/seed/versions/settings/run/action/target IDs, actual samples/pending classifications, movement positions/stages, terminal result/mismatches/timeouts, logs and inspected screenshot. Baritone is recorded absent/unavailable, not tested. Close world before PASS.

**Done:** one programmatic lane actually builds via native controls. No schematic/import/reset/full-parity claim.

### Task 9: C9 — Real prediction fault cannot turn matching client blocks into success

**Depends:** C8. **Files (5):** create `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/PredictionFaults.java`, `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/mixin/WithheldActionResponseMixin.java`, `v2/fabric/src/gametest/resources/mapart-test.mixins.json`; modify gametest `fabric.mod.json` and `SingleLaneScenario.java`.

- [ ] Fixture-only gate binds exact owned disposable world/connection/run and a bounded interval; refuse unmanaged activation. Gate the actual invocation after main-thread dispatch, using `onPlayerActionResponse(Lnet/minecraft/network/packet/s2c/play/PlayerActionResponseS2CPacket;)V` and target `Lnet/minecraft/client/world/ClientWorld;handlePlayerActionResponse(I)V`, source-verified in the independent review. Hold later cumulative responses too: these packets have no region coordinate, so the temporary gate covers the owned dedicated connection, not fictional position filtering. No cancellable handler-HEAD dispatch bypass, manual response invocation, fabricated ack, map writes or blocking thread sleeps. Require compiled selector resolution.
- [ ] Observe a real matching predicted target/pending key from actual interactBlock and independently establish server truth. While holding responses, assert production PREDICTED, unfinished target and never SUCCESS for a bounded interval. Also let a dedicated run remain held to its explicit unresolved timeout: it must fail/partial with released controls even when server blocks are correct. Treat this as simulated network fault injection.
- [ ] Optional in-world recovery requires a genuinely READY, reachable unattempted target when releasing the gate, so its later real vanilla action response can cumulatively reconcile. Merely having unreachable remaining targets is insufficient. Otherwise omit this recovery claim and close the failed world; never manufacture a response. A normal fresh-world baseline remains mandatory after fault cleanup. Include actual repeated prediction/latest-reconciliation evidence in this fixture or mandatory C10b and identify its real events.
- [ ] Dispose gates in finally/world teardown and prove subsequent ordinary fixture is unaffected. Inspect production release JAR/nested core JAR to prove no fault classes/config/flags/test entrypoints are shipped.

**Done:** source-supported prediction distinction is exercised under actual vanilla prediction. A raw matching world wait cannot satisfy this test.

### Task 10: C10 — Withheld producer evidence and lifecycle effects fail honestly

**Depends:** C8/C9. **Files (4):** modify `SingleLaneScenario.java`, `PredictionFaults.java`, `mapart-test.mixins.json`; create `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/mixin/WithheldObservationMixin.java`.

- [ ] Target the actual C5 `WorldEvidenceReader.placementObservations(...)` list-returning seam, verifying its compiled selector. Choose a real attempted near-end outer-column target outside the centered walking footprint; arm before its first post-attempt OBSERVED sample and omit it from **every ledger/final-scan observation phase through terminal timeout**. Preserve canonical plan/expected IDs and separately sampled honest support facts; never synthesize UNKNOWN, expected state or server-derived evidence. Independently prove its server block built. Require actual movement COMPLETE/VERIFY before the explicit missing-evidence outcome; record a timing arrangement within unchanged budgets so pending80 cannot expire first. A transient one-sample omission or SUPPORT_TIMEOUT before VERIFY is the wrong proof.
- [ ] Exercise actual native active and paused states: pause releases controls and retains lane/targets, resume uses fresh samples; stop after decision generation prevents old slot/placement/control application; disconnect/world replacement releases keys and rejects prior evidence/results, rejoin stays idle until fresh start. Use test-only barrier hooks if necessary to expose the exact stale-effect window, in this file budget or a separately reviewed capability.
- [ ] The live stale-effect barrier uses the same captured-generation check, including pause/resume between computation and application, and all-nine-control/post-release assertions from C8. Fit test hooks into the named files; if a genuinely additional barrier file is required, specify a separate reviewed <=5-file slice before dispatch rather than quietly expanding this task. Ack-only proof is separate mandatory C10b below.
- [ ] Require a deliberate externally launched negative expected-success mode to exit nonzero for the withheld-confirmation reason, `timedOut=False`, retained FAIL/screenshot/logs; an outer timeout or unrelated crash is not the proof. Clear fault flags and retain one positive recovery run.

**Done:** false-positive construction and stale lifecycle effects are blocked through the production path. Test faults cannot leak to ordinary worlds or release artifacts.

### Task 11: C10b — Mandatory missing-state and ack-only reconciliation proof

**Depends:** C9/C10. **Files (4):** create `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/mixin/WithheldBlockStateMixin.java`; modify gametest `PredictionFaults.java`, `SingleLaneScenario.java` and `mapart-test.mixins.json`.

The independent review performed a focused `javap -p -s` check against the already identified pinned named artifact. These are source-verified selectors, **not compiled or live-tested hooks**:

```text
Single-block host:
onBlockUpdate(Lnet/minecraft/network/packet/s2c/play/BlockUpdateS2CPacket;)V
Chunk-delta consumer host, after its existing main-thread dispatch:
method_34007(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V
Actual state-application target in both hosts:
Lnet/minecraft/client/world/ClientWorld;handleBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)V
Full-chunk host:
loadChunk(IILnet/minecraft/network/packet/s2c/play/ChunkData;)V
Actual full-chunk import target:
Lnet/minecraft/client/world/ClientChunkManager;loadChunkFromPacket(IILnet/minecraft/network/PacketByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)Lnet/minecraft/world/chunk/WorldChunk;
```

- [ ] Use one test-only ClientPlayNetworkHandler Mixin to gate the two actual state-application **invocations** after existing thread dispatch. Pass unrelated positions through unchanged. No cancellable packet-handler HEAD bypass. Withhold every inbound state application for the owned target during the interval, recording actual suppressed state and route; require at least one suppressed matching state. Do not replace arguments with air or expected state.
- [ ] At the verified private loadChunk path, reject the fixture's no-reload precondition for the target chunk while armed. Record a **sticky fatal fixture failure** so later matching data cannot rescue the run. This explicitly excludes full-chunk reload from the controlled proof; it is not a full-chunk fault simulator. World/connection replacement or lost target-chunk availability also invalidate this case. Demonstrate that a forced precondition violation yields a failed test, never a skip or eventual PASS.
- [ ] Bind gates to exact managed disposable world/connection/run, owned target and bounded interval. Arm after initial air is independently established and the client chunk fully loaded, before real production placement. Require real matching prediction/pending key, repeated read-only queries, an actual suppressed matching update and an actual **non-withheld** response boundary. Then require pending-key removal, restored original air/wrong state, production OBSERVED mismatch and unfinished ledger target; capture before legitimate retry obscures it. The gate may remain armed through retries. Do not manually invoke handlers, write pending maps, fake states/acks or use server truth in production observations.
- [ ] Complete real repeated-prediction/latest-reconciliation coverage assigned by C5/C9, using actual normal interactions and recorded pending behavior; no fabricated sequence. Require selector resolution and record actual single/delta route counts. A route not exercised live has source/compile coverage only, never a claim of a real delta test. Dispose all gates in finally/teardown; retain normal positive recovery evidence and prove no test hooks/config/flags ship in the release/nested core JAR.

**Done:** acknowledgement without matching state cannot manufacture confirmation; repeated pending queries preserve vanilla's original reconciliation baseline. Passing requires this actual proof, not held-ack or suppressed-producer substitutes.

### Task 12: C11 — Evidence and focused completion review

**Depends:** all above including mandatory Task11/C10b. **Files (1):** update `v2/docs/testing.md` with actual implemented registration/launch/results/limitations. Controller may separately promote the reviewed focused plan and update parent checkboxes; never mark planned checks executed.

- [ ] Run final relevant core/adapter tests and compile/remap once after changes settle; run final required game variants only as changed behavior or unresolved risk warrants. Read actual report counts/failures/errors/skips and inspect screenshots. Never reuse historical Task5 or 419-v1 counts as new results.
- [ ] Document the actual finite supervisor invocation from the existing testing guide, including `fabric.client.gametest.modid=mapart-test` (selects the mod's registered entrypoints, not a class), `-Djava.awt.headless=true`, operator-approved `-PmapartTestEula=true`, unique external launch evidence and actual negative outcome. No invented test-method filtering property. If several entrypoints are registered, document that all run or implement an explicit test-only validated scenario selector as its own needed change.
- [ ] Verify release artifact exclusion and exact production Mixin registration again after test hooks. Independent whole-6c review checks architecture/ownership and the real command/tick->engine/context->movement->yaw/keys->placement/prediction->observation->verification->cleanup path. No broad parity row becomes complete from this single-lane result.

## Verification commands and test discipline

Use an existing JDK21 through process-local setup in `v2/docs/testing.md`; keep wrapper working directory at repository root. Focused examples:

```powershell
.\gradlew.bat -p v2 :core:test --tests '*PlacementLedgerTest' --no-daemon
.\gradlew.bat -p v2 :core:test --tests '*BuildEngine*Test' --no-daemon
.\gradlew.bat -p v2 :fabric:test --tests '*ControlArbiterTest' --no-daemon
.\gradlew.bat -p v2 :core:test :fabric:test :fabric:compileGametestJava :fabric:remapJar --no-daemon --console=plain
```

Real launches use the documented supervised `:fabric:runClientGameTest -PmapartTestEula=true --no-daemon --console=plain` worker, not an unsupervised shell command. Its outer600000ms budget is infrastructure only; test predicates/engine deadlines independently bound simulation. Retain logs before Loom clears the next run. Failure screenshot capture can block in pinned Fabric, so do not remove the external process deadline. Unit recording sinks prove policies/order only; live independent server observations prove resulting blocks. No new root/v1 test infrastructure is planned, so isolated v2 work does not require rerunning v1 unless that scope changes.

## Adopted review decisions and handoff gate

The controller adopted all seven findings from `task-6c-plan-review.md`: exact named issuance API/legacy optional metadata; issuance-based retry and finite deadline boundaries; mandatory C10b exact inbound routes with sticky full-chunk precondition failure; sustained outer-column observation withholding through a terminal result after VERIFY; captured generations and exception cleanup; measured walking/standing height/all controls/post-release physics; and exact unique final scan plus existing lifecycle outcomes. No additional user permission is requested.

This incorporation remains planning evidence. The controller obtains a fresh incorporation rereview before Task1/C1. Final Task6b documentation review/publication also precedes dependent implementation. Exact mapped platform effects and test-hook selectors still require compile/runtime verification in their owning slices; neither source research nor this plan establishes those results. No concrete broad design question remains open. If a needed file would exceed a slice budget, define and review a separate complete capability before dispatch rather than expand silently.

## Exact future human-check milestone

After C8-C10b automated positive/negative evidence is complete, notify the user that the production single-lane controls/evidence boundary is now working and identify any visual feel issue that would benefit from observation. Do not ask them to manually code, patch Java or invent setup.

The first useful normal **human build check is Checkpoint B, after Task6d**: provide a concrete isolated v2 launch/setup using the checked-in real one-lane schematic and implemented entrypoint, already independently tested, with clear start/stop steps and expected final blocks. Ask them to watch lane entry, yaw/head/body stability, side placement and pause/stop release. Do not promise a command/inspect mode that has not been implemented; if that launch still needs automation, finish it before requesting the check. This is feedback, not a prerequisite to continue already-authorized independent work. The repeatable two-lane/reset check follows Task7, and persistent inspect commands follow Task9. No v1 replacement claim before the full parity/replacement gates.
