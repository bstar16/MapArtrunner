# V2 Native Movement Implementation Plan

Status: focused parent Task 6b plan, 28 October 2026. All six independent plan-review amendments are adopted and targeted incorporation review is approved. Both focused tasks are implemented and independently reviewed: 45 planner cases, 144 movement cases and 252 full-core tests passed with zero failures/errors/skips. Final branch review/publication follows; actual game effects and physics remain Task 6c. Working branch is `codex/v2-native-movement`, starting at `748dc1a`.

Parent: [Task 6b](2026-10-28-v2-reliability-plan.md). Governing contracts: [design](../specs/2026-10-28-v2-reliability-design.md), [constraints](../../../CONSTRAINTS.md), and [behavior parity](../../../v2/docs/behavior-parity.md). The source reference extraction and independent plan review are retained in `.superpowers/sdd/2026-10-28-v2-reliability-plan/`. They are source/planning evidence, not live-game verification.

## Outcome and boundaries

Implement two pure Java production classes and their tests. Preserve five-wide E/W geometry, direction-relative strafing, explicit entry readiness, expected-floor protection and finite support waits. Correct narrow-lane backtracking and expectedness derived from mutable queues. Return immutable decisions; do not apply keys or alter game state.

Final production/test file set:

- `v2/core/src/main/java/com/mapartrunner/core/traversal/LanePlanner.java`
- `v2/core/src/test/java/com/mapartrunner/core/traversal/LanePlannerTest.java`
- `v2/core/src/main/java/com/mapartrunner/core/movement/MovementPolicy.java`
- `v2/core/src/test/java/com/mapartrunner/core/movement/MovementPolicyTest.java`

Use nested immutable types beside the producing class, reused unchanged by consumers. Movement depends on traversal and the existing neutral `PlacementObservation.Evidence`; placement and traversal depend only on model/JDK, so there is no cycle. No new dependencies, framework, scheduler, mutable policy, ledger API edit, or v1 edit. Implement the two complete sequential reviewed tasks below. Full multi-lane selection, resume and reverse cleanup remain Task 8. Actual keys, physics, yaw/head/body effects, ownership, tick routing and real-game proof remain Task 6c. This slice cannot establish successful construction or a working game builder. Keep Java 21 and current v2 build pins; use the root wrapper with `-p v2` and preserve the existing architecture/ledger/lifecycle tests.

## Controller decisions already agreed

1. Full source width/depth define geometry, including air borders. `GroundedSchematicBounds.fromPlan` uses source dimensions; do not trim to target bounds. Empty targets produce an immutable empty list. Nonempty targets must share one relative Y; padded source height is allowed, multiple occupied Ys produce a useful `IllegalArgumentException`.
2. Preserve normal first/final-center behavior. For depth at most `2*halfWidth+1`, create one NW-anchored center `min(halfWidth, depth-1)`. This deliberately corrects v1 depth 2/3/4 centers `[1,0]`, `[2,0]`, `[2,1]` to `[1]`, `[2]`, `[2]`. Reject stride greater than corridor width rather than permitting uncovered bands.
3. All core positions are plan-relative. Fabric translates world position by subtracting the run origin; target/grid conversion adds it back. Build-plane Y is the targets' common relative Y; player standing Y is that Y+1. No world origin, Minecraft conversion or game directory enters these classes.
4. Stateless policy; the future engine owns stored context and one advance per eligible simulation tick. Explicit stages are `ENTER_POSITION`, `ENTER_YAW`, `WALK`. Entry must satisfy both X/Z coordinates and wrapped yaw; WALK must never steer ordinary progressed players back to the lane start.
5. Retain the v1 **off-plan-air exemption**. This is an expected-target guard, not a general physical-support guarantee: a genuinely off-plan cell permits movement even if unknown, predicted or air. Known safe aprons remain permitted. An immutable required target never receives that exemption because it failed, disappeared from pending work or was previously confirmed.
6. Pending target IDs are an immutable owner-supplied context snapshot. Task 6c must source them from placement ownership or correlated outstanding action facts, not invent a second mutable pending registry. Expectedness always comes from the whole immutable plan.

## Exact neutral contracts

### LanePlanner

`public List<Lane> plan(BuildPlan plan, int halfWidth, int stride)`.

Nested types:

```java
enum Direction { EAST, WEST, SOUTH, NORTH }
record Corridor(int minX, int maxX, int minZ, int maxZ) {}
final class ExpectedFloor {
    ExpectedFloor(Map<GridPos, Target> targetsByPosition);
    Map<GridPos, Target> targetsByPosition();
    boolean containsTargetId(int targetId);
    // Value-based equals/hashCode derive only from targetsByPosition.
}
record Lane(int id, Direction direction, GridPos start, GridPos end,
            Corridor corridor, ExpectedFloor expectedFloor) {}
```

`GridPos` and `Target` are the existing `BuildPlan` nested types, not duplicate coordinate/block types. `ExpectedFloor` is a nested immutable final lookup class made once from **all** `plan.targets()` and shared by every lane. Its constructor copies the map and verifies nonnull keys/targets, key equals target position, unique target IDs and one Y. In that construction pass derive a private final immutable target-ID set; do not accept a caller-supplied parallel index. Expose the immutable position map and O(1) `containsTargetId(int)`. Value-based equals/hashCode use only the canonical position map, so equal independent planner calls produce equal Lane/list values. This avoids whole-floor scans or map rebuilding per movement decision. Preserve full `BlockSpec` property equality for expected support. No dependency on `unfinishedTargets()` or pending attempts.

`Direction` exposes fixed yaw, progress-axis, forward-sign and lateral-strafe-sign behavior, implemented with explicit enum cases. Yaws: EAST -90, WEST 90, SOUTH 0, NORTH 180 degrees. Only E/W lanes are emitted; N/S remain necessary to test the already-supported direction-relative policy and later native transitions.

Validation: nulls fail with `NullPointerException` following existing core convention; malformed values fail with useful `IllegalArgumentException`. Validate halfWidth >=0, stride >0, and stride <= `2L*halfWidth+1` with long arithmetic; do not permit overflow to change geometry. Validate arguments even for an empty plan. BuildPlan already validates dimensions, target uniqueness and containment; do not duplicate all of that validation. Generated lane IDs are stable zero-based geometry indices, not target IDs or surviving-lane indices; directly constructed Lane IDs need only be nonnegative, without knowing their list position. Target IDs remain unchanged and may be sparse. A Lane requires cardinal axis alignment, direction-consistent start/end, shared start/end Y, inclusive corridor containing both endpoints, and nonnull immutable floor. If the floor is nonempty, its common Y must equal both endpoint Ys. An empty floor is legal for direct off-plan fixtures, although the planner emits no lanes for an empty plan. Equal start/end is legal for width-one lanes. Corridor min <= max. Directly constructed N/S test lanes obey the same checks. Do not validate namespaced block strings again.

Geometry in source-relative coordinates:

- `minX=minZ=0`, `maxX=width-1`, `maxZ=depth-1`.
- Narrow case as decision 2. Otherwise first=`halfWidth`, final=`depth-1-halfWidth`; emit first, stride increments while next<=final, then append final if different. Use long intermediate arithmetic. The final displacement may be shorter than stride and overlap a previous corridor.
- Each lane corridor is all source X and clamped `[center-halfWidth,center+halfWidth]` in Z. Even ID starts at `(0,planeY,center)` and ends `(width-1,planeY,center)` EAST; odd reverses WEST. List order/IDs do not depend on target input order, observations, pending attempts or completed work.
- Lane start/end are block positions; movement targets use X/Z centers `+.5`. The existing source dimensions and target IDs are never renormalized by traversal.

Required exact geometry examples at halfWidth2/stride5: depths1..5 -> `[0]`, `[1]`, `[2]`, `[2]`, `[2]`; depth9 -> `[2,6]`; depth10 -> `[2,7]`; depth11 -> `[2,7,8]`; depth15 -> `[2,7,12]`. All source Z rows are covered; no duplicate/backtracking centers. Width1 remains valid. Empty plan emits no lane, regardless of nonzero dimensions.

### MovementPolicy

`public MovementIntent decide(PlayerObservation player, LaneEntry entry, SupportObservation support)`.

All types below are nested beside MovementPolicy; consumers use these exact types:

```java
enum Stage { ENTER_POSITION, ENTER_YAW, WALK }
record PlayerObservation(double x, double y, double z, double yawDegrees) {}
record LaneEntry(Lane lane, Stage stage, int stageElapsedTicks, int entryElapsedTicks,
                 int consecutiveYawAlignedTicks, int supportHoldTicks, int yawHoldTicks,
                 Set<Integer> pendingTargetIds, boolean constantSprint) {}
// Evidence is PlacementObservation.Evidence, imported unchanged.
record SupportSample(Evidence evidence, Optional<BlockSpec> state) {}
record SupportObservation(Map<GridPos, SupportSample> cells) {}
enum Status { ENTERING, ALIGNING_YAW, READY_TO_WALK, WALKING,
              HOLDING_SUPPORT, HOLDING_YAW, COMPLETE, FAILED }
enum Failure { NONE, ENTRY_TIMEOUT, YAW_TIMEOUT, OUTSIDE_CORRIDOR,
               BELOW_BUILD_PLANE, SUPPORT_TIMEOUT, YAW_UNALIGNED }
enum Strafe { NONE, LEFT, RIGHT }
record MovementIntent(Status status, Failure failure, double desiredYaw,
                      boolean forward, boolean back, Strafe strafe, boolean sprint,
                      Stage nextStage, int nextStageElapsedTicks, int nextEntryElapsedTicks,
                      int nextYawAlignedTicks, int nextSupportHoldTicks, int nextYawHoldTicks) {}
```

The WALK yaw hold is an explicit conservative interim policy approved for this draft; Task 6c must calibrate its physical behavior. Intent does not include jump/sneak because these policies never request them; the Task 6c complete-control application must explicitly release unrequested controls. No raw keys, player/world objects or calls into Fabric/Baritone.

Input contracts: x/y/z/yaw must be finite (allow negative relative coordinates); counters must be nonnegative; aligned-yaw count <= stageElapsedTicks and nonzero only in ENTER_YAW; WALK requires stageElapsedTicks=0 and entryElapsedTicks=0; entry stages require yawHoldTicks=0. Reject counter values beyond reachable bounded context with IllegalArgumentException: supportHoldTicks>79, yawHoldTicks>19, consecutiveYawAlignedTicks>1, entryElapsedTicks>52, ENTER_POSITION stageElapsedTicks>40, or ENTER_YAW stageElapsedTicks>10. Thus maximum-int counter inputs fail explicit validation without arithmetic. At accepted deadline values use the typed timeout/readiness rules below. Pending IDs must be nonnegative and present in ExpectedFloor's derived ID set, validated in O(number of pending IDs), without a whole-floor scan. All collections copied, null cells/entries rejected. UNKNOWN requires empty state; PREDICTED/OBSERVED require state, using the existing PlacementObservation evidence enum unchanged. Missing sample means UNKNOWN, not observed air. Evidence is a producer claim, not an attestation: Task 6c must establish freshness/current-run correlation and distinguish prediction/unloaded data. Support samples include full BlockSpec; no new authoritative-server capability is claimed.

All counters mean counts from **completed prior eligible decisions**, excluding this call. The pure decision returns next counts; it never reads wall time or stores mutable fields. The owner routes the returned context once, not once per renderer pass or repeated diagnostic call. Calling decide twice with equal inputs gives equal outputs. Increment every bounded counter using compare-before-increment or capped arithmetic, never arbitrary `old+1` before checking: for nonready support, old>=limit-1 yields timeout directly. Apply this to stage, total-entry, support and yaw counters. Counter inputs outside the documented reachable ranges yield IllegalArgumentException; accepted deadline values yield typed timeout or readiness under the decision order below, never wrapped negative output. Support resets only when an actually evaluated footprint is ready or the policy terminates; stage changes and displayed hold status do not reset it.

Input ownership: BuildPlan/ExpectedFloor from normalization and traversal; player/current cell evidence from Fabric; phase/counters/settings snapshot from BuildEngine/traversal coordination; pending IDs from placement ownership. The current PlacementLedger has `recordAttempt`, `observe`, `unfinishedTargets`, `outcome` and no pending query. A focused read-only query or existing correlated outstanding-action snapshot is a **Task 6c integration requirement**, not an API assumed to exist now. This task never edits that ledger or stores independent mutable placement history.

## Decision order and semantics

### Common validation, classifier and terminal rules

Reject malformed inputs as specified above. For every active stage, below build plane (`player.y < planeY`) or outside the v1 lane-corridor envelope (`min-.5 <= x/z <= max+1.5`, inclusive) returns FAILED with no translation/sprint. Coarse approach must arrive in this local entry envelope before invoking native entry; initial approach/route selection remain Task 6c. This envelope is not a general collision or route model.

A footprint's expectedness comes only from immutable ExpectedFloor. A required cell is ready only for OBSERVED evidence matching its full expected BlockSpec. UNKNOWN, PREDICTED, absent samples and observed mismatches/air are nonready. A true off-plan cell retains the agreed exemption even if unknown/predicted/air; it is not a physical-support guarantee. Required nonready cells with a pending ID or UNKNOWN/PREDICTED evidence use an 80-decision support bound; observed mismatch without pending action uses 40. For mixed nonready cells select 40 if any untracked observed mismatch exists, otherwise 80. Changing cell/evidence/pending class never resets the continuous support counter. Ready evaluated footprint sets support count to 0; nonready increments once and fails on held decision 40/80 using overflow-safe comparison. An old count already at or above the selected bound minus one times out immediately.

COMPLETE/FAILED have forward/back=false, strafe NONE, sprint=false and every next counter=0. FAILED carries the typed reason; other statuses carry Failure.NONE. Terminal no-controls intent is a request, not proof that Fabric released controls. The owner stops routing failed context; recovery choices belong later. A terminal traversal result never establishes ledger/run success.

### Entry: guarded commands and cumulative deadline

Selecting a fresh lane initializes every counter to 0. `entryElapsedTicks` counts prior eligible decisions across both entry stages; each nonterminal entry decision advances it once. Drift and stage changes retain it; pause freezes it. Successful WALK admission and terminal output clear it. The total entry budget is **52 eligible decisions**: 40 still-unmet position decisions, one centered transition, 10 unresolved yaw decisions and one deadline readiness decision. Decision 52 may achieve readiness; a subsequent entry call (`entryElapsedTicks>=52`) returns ENTRY_TIMEOUT without translation. At each entry call evaluate bounds and any reached support timeout first, then total entry cap, then stage-specific readiness/timeouts. Use safe comparisons before increments.

ENTER_POSITION targets both start block centers independently with inclusive absolute error <=.25. This deliberately applies v1 lateral .25 to both axes; old progress crossing +/- .2 did not guarantee entry position. When both coordinates meet tolerance, return ALIGNING_YAW, nextStage ENTER_YAW, local stage/yaw counts=0, no translation, preserving support count and advancing total entry count. No candidate footprint is evaluated or invented on that no-translation transition.

When either coordinate is unmet, derive one progress-first candidate command: correct progress axis with forward/back relative to lane direction; only when progress is within .25 correct lateral with LEFT/RIGHT. Exactly one of forward/back/LEFT/RIGHT is selected, never a diagonal or sprint. Derive its required-entry footprint before applying yaw suppression:

- `current=(floor(player.x), planeY, floor(player.z))`.
- Cardinal X/Z forward deltas for EAST/WEST/SOUTH/NORTH are `(1,0)`, `(-1,0)`, `(0,1)`, `(0,-1)`; back negates forward.
- LEFT deltas for EAST/WEST/SOUTH/NORTH are `(0,-1)`, `(0,1)`, `(1,0)`, `(-1,0)`; RIGHT negates LEFT.
- Footprint is exactly `{current,current+delta}` at build-plane Y, deduplicated. Derive delta from fixed lane direction and candidate command, not sampled yaw. Floor negative coordinates and never clamp the footprint to the corridor; consult the full ExpectedFloor.

Evaluate this footprint with the shared classifier even if sampled yaw will suppress the candidate. Unsafe entry support never emits forward, back, strafe or sprint; entry holds do not inherit WALK's lateral-correction exception. If yaw is aligned but support is unsafe, return HOLDING_SUPPORT and retain ENTER_POSITION. If yaw is unaligned, retain ENTERING and desired lane yaw with all translation suppressed, while still advancing/evaluating support count. Aligned means wrapped sampled yaw error <=1.5 degrees. With both yaw and support ready, emit the single candidate command without sprint.

Every still-unmet ENTER_POSITION call advances its local positional count, including support/yaw suppression. Permit 40 such decisions; the 41st unmet decision returns ENTRY_TIMEOUT. Support timeout takes precedence if reached on the same call. Thus entry can end at the positional deadline before an 80-class support wait expires; 80 is a WALK support bound, not an entry entitlement. No unguarded entry-platform precondition substitutes for this policy gate.

ENTER_YAW rechecks both coordinates. If either drifts outside .25, return ENTERING with nextStage ENTER_POSITION, local stage/aligned-yaw counts=0 and no translation. Preserve unresolved support and advance total entry count; drift does not create a fresh whole-entry budget. No candidate footprint is evaluated on this transition, so do not increment/reset support. Otherwise each aligned sample increases consecutiveYawAlignedTicks; each unaligned sample clears it. Require two consecutive aligned eligible samples. Allow 10 unresolved yaw decisions; on the 11th decision readiness may succeed, otherwise YAW_TIMEOUT. Check total cap before stage readiness. READY_TO_WALK returns nextStage WALK with local stage, total-entry, aligned-yaw and WALK yaw-hold counts=0, preserves unresolved support, and emits no forward command. Equivalent yaw angles including 180/-180/multiple turns use the same wrapped comparison.

Entry stages always emit nextYawHoldTicks=0. No-translation stage changes preserve unresolved support until a later evaluated footprint is ready or policy terminates. Do not invent a support increment when no candidate footprint was evaluated. This small current+neighbor entry guard protects required targets; it does not model stopping distance, the whole player body, momentum or routes.

### WALK: independent support and yaw accounting

WALK input/output stageElapsedTicks and entryElapsedTicks are 0. Never compare ordinary progressed positions with lane start to steer back. After bounds, check endpoint: `((endProgress+.5)-playerProgress)*forwardSign <=1.0`. COMPLETE precedes support as in v1; width-one lanes may complete without translation, while placement/final verification remain required separately.

For nonterminal WALK decisions preserve the v1 footprint: at buildPlaneY, next progress block=`floor(playerProgress)+directionSign`; include centerline column, actual floored lateral column, and adjacent lateral column when lateral fraction<.4 or>.6, deduplicated. At exactly .4/.6 add no adjacent cell. Floor negative coordinates rather than truncate. Do not discard an adjacent required target outside this lane's nominal corridor. Check support before every forward intent, including the first WALK call after READY_TO_WALK; no start() bypass exists.

Evaluate footprint readiness and sampled yaw independently on **every** WALK call, with this order:

| Condition | Counter/result |
|---|---|
| Evaluated support ready | nextSupportHoldTicks=0 |
| Evaluated support nonready | Increment continuous count safely; select/check current 40/80 bound |
| Wrapped yaw error <=1.5 degrees | nextYawHoldTicks=0 |
| Wrapped yaw error >1.5 degrees | Increment consecutive yaw hold safely; check 20-decision bound |
| Support expired | FAILED/SUPPORT_TIMEOUT, even if yaw also expires |
| Otherwise yaw expired | FAILED/YAW_UNALIGNED |
| Otherwise yaw unaligned | HOLDING_YAW, desired lane yaw, all translation/sprint suppressed |
| Otherwise support nonready | HOLDING_SUPPORT, no forward/back/sprint, preserve v1 relative lateral correction |
| Both ready | WALKING, forward=true, back=false, sprint from snapshotted setting and relative lateral correction |

Changing hold status, evidence class or footprint position does not reset either unresolved counter. Support becoming ready during yaw hold resets support only; yaw alignment during support hold resets yaw only. Each counter can expire while the other hold is active. The yaw hold changes v1 forced-yaw movement plus >15-degree/20-tick drift detection into conservative translation suppression at >1.5 degrees. It avoids failing on the first small drift but requires Task 6c physics/effect-order calibration; it is not proven gameplay-equivalent timing.

Relative correction: lateralError=playerLateral-(centerline+.5), signedError=lateralError*((alongX?1:-1)*forwardSign). LEFT when signedError>.08, RIGHT when signedError<-.08, otherwise NONE. EAST/NORTH positive error ->LEFT; WEST/SOUTH positive error ->RIGHT; negative reverses it. The fixed lane yaw remains authoritative, not sampled-yaw reinterpretation. HOLDING_SUPPORT in WALK retains this correction as v1 did; HOLDING_YAW and all entry holds do not. Task 6c must continue placement while movement holds so awaited floor can arrive.

### Owner lifecycle and input provenance

The future BuildEngine/traversal owner stores and routes the immutable returned context exactly once per eligible simulation tick, not wall-clock stall or diagnostic call. Fresh lane selection initializes counters; pause retains/freeze context and unfinished targets, releases effects and revalidates observations before resuming. A paused WALK retains progress rather than returning to entry. Stop/world replacement releases effects then discards context. These are Task 6c integration duties; this slice tests deterministic context transitions, not an engine lifecycle that does not yet exist.

Immutable BuildPlan/ExpectedFloor define all required targets. Fabric supplies relative player/current cell evidence with honest unknown/predicted state; placement ownership supplies immutable pending IDs. Existing PlacementLedger has no pending query, so Task 6c needs a focused read-only query or existing correlated outstanding-action facts, without a second mutable pending registry. No ledger source change belongs here. Actual keys, yaw/head/body application, observation provenance, collision/momentum, pause/stop effects, placement confirmation and server-world truth require Task 6c evidence.

## Implementation tasks

Both tasks consume all global decisions, API definitions and semantics above. They are two complete reviewed slices, not incomplete intermediate production policies. The final scope remains four production/test files; use test-first iteration inside each task without promising a fixed test count.

### Task 1: Geometry and expected floor

**Dependencies:** parent Tasks 1-6a; approved geometry/API contract above. **Files:** create LanePlanner and LanePlannerTest at the full v2 paths listed above. No movement source, ledger, build or v1 edits in this task.

- [x] Add failing tests against the declared production API. Port fixed E/W aspect-ratio choice, NW first entry, E/W/E IDs/order, full source width and air borders, five-wide corridors, uneven centers2/7/8. Add narrow depths1..5, depths9/10/11/15, width1, halfWidth0/stride1, smaller positive stride overlap and full row coverage without duplicates/backtracking.
- [x] Add validation tests for negative halfWidth, nonpositive/uncovered stride and overflow-safe geometry arithmetic; empty plan; single occupied Y with padded height; multiple occupied Ys rejected; malformed Lane/corridor; endpoint-floor Y mismatch; sparse target IDs and direct nonzero Lane IDs.
- [x] Test immutable expected index contains all original targets, copies source map, derives ID membership, shares the same index object across one planner call, and has equal/hash-consistent value semantics across independent equal calls. Reordered targets leave geometry/IDs unchanged. Returned map/list cannot be mutated. Empty floor remains legal for direct fixtures, but empty plans emit no lane.
- [x] Run the focused command and record expected failing contract/assertion evidence. Implement the minimum geometry/index contract, then rerun green. Do not copy the algorithm into a test oracle or alter valid assertions.
- [x] Run the focused existing CoreArchitectureTest once for the new-package boundary, inspect actual results, self-review and commit the complete task, then obtain independent spec/quality review and fix findings before proceeding.

Verified: expected missing-contract RED; 45 planner cases and three architecture checks passed. Independent task review approved. The existing ArchUnit/SLF4J no-provider notice remains recorded; no logging dependency or suppression was added.

```powershell
.\gradlew.bat -p v2 :core:test --tests '*LanePlannerTest' --no-daemon
.\gradlew.bat -p v2 :core:test --tests '*CoreArchitectureTest' --no-daemon
```

**Acceptance:** production geometry/index matches all exact examples and invariants, focused geometry and architecture tests pass, two-file review approved. No runtime/gameplay claim.

### Task 2: Complete native movement policy

**Dependencies:** completed/reviewed Task 1. **Files:** create MovementPolicy and MovementPolicyTest at the full v2 paths above, using LanePlanner types and PlacementObservation.Evidence unchanged. No ledger/API/build/v1 edits. This task includes entry, support, yaw and counters as one complete policy; do not accept an intermediate unguarded policy or support stub.

- [x] Add red entry cases: wrong X/right Z, right X/wrong Z, both wrong, both right/wrong yaw, both right/aligned yaw; inclusive .25 boundaries and just outside; progress-first single-axis commands, all forward/back/lateral direction mappings, no entry sprint, yaw-suppressed translation and no movement on stage transitions.
- [x] Add red required-entry footprint cases: missing current cell, forward neighbor, backward neighbor, LEFT/RIGHT neighbors in all four lane directions, a required wrong-side cell that defeats a forward-only guard, negative floor, UNKNOWN/PREDICTED/full-state matching and property mismatch, off-plan exemption, unsafe support while yaw suppresses command, no strafe on entry support hold, full-floor checks outside corridor and first WALK independently guarded.
- [x] Test entry counters: 40 unmet position decisions then fail41, 10 unresolved yaw decisions then fail11 unless ready; two consecutive aligned samples and reset after bad yaw; wrapped angles; coordinate drift preserves total entry/support while resetting local stage/aligned count; repeated position/yaw drift ends at total52, readiness allowed on52, next entry call times out. Pause representation retains counters without advancing them. Maximum-int counters yield typed timeout or documented invalid-combination rejection, never overflow. WALK stage/entry count must be0.
- [x] Add red cardinal WALK cases: ordinary progress never returns to start; all four directions with both strafe signs, zero/deadband .08 boundary and just outside, fixed direction yaw, sprint setting true/false, directional endpoint/overshoot, below-plane/outside inclusive corridor bounds, terminal no-controls/counter clearing.
- [x] Test required WALK support before the first forward intent: UNKNOWN/PREDICTED/absent samples hold, full observed BlockSpec permits movement, mismatched properties hold, known/unknown off-plan air remains exempt. Reuse a real PlacementLedger attempt followed by later wrong/unknown observation to demonstrate plan-derived expectedness survives missed attempts with no pending ID. Never construct expectedness from pending/unfinished views or claim movement permission is terminal build success.
- [x] Test WALK footprint exactly: negative progress for WEST/NORTH, centerline/actual lateral/adjacent positions, fractions .4/.6 and adjacent values, deduplication, required adjacent target outside nominal corridor. Assert precise classifications rather than pending-or-missing alternatives.
- [x] Test support40/80 and yaw20 boundaries: support1..39 then40 failure, pending/unknown1..79 then80 failure, yaw1..19 then20 failure; safe support reset, yaw alignment reset, later hold starts1, changing cell/evidence/pending class never resets counts, 80-class to40-class can fail immediately. Mixed footprint selects stricter bound.
- [x] Test independent accounting: support expires while yaw hold active; yaw expires while support hold active; both expire same decision ->SUPPORT_TIMEOUT; support becomes ready during yaw hold; yaw aligns while support remains unsafe. WALK support hold retains exact relative strafe and suppresses forward/back/sprint; yaw hold and entry hold suppress every translation. Entry stage transition preserves unresolved support without invented increment; entry deadline may precede80-class support timeout.
- [x] Test finite values/null/collection/evidence/counter validation, unknown pending target IDs via the derived set, defensive copies, deterministic replay and no mutable policy state/clock. Keep existing ledger/lifecycle/architecture tests intact.
- [x] Run focused red checks against the production contract, implement the minimum complete policy and iterate the focused command green. Then run full core, self-review and commit only the complete policy, obtain independent spec/quality review and fix findings before completion. No artificial incomplete movement slice or test-count target.

```powershell
.\gradlew.bat -p v2 :core:test --tests '*MovementPolicyTest' --no-daemon
.\gradlew.bat -p v2 :core:test --no-daemon
```

**Acceptance:** all parent Task6b entry/geometry/strafe/required-support/bounded-wait cases and accepted-review regressions pass against production logic, full core/architecture checks pass, complete two-file movement review approved. The engine later consumes these exact immutable types; no keys or Baritone calls are added.

## Verification evidence and recorded costs

Discover Java21 locally and use process-local configuration only. Retain actual red cause, green report counts/failures/errors/skips and review findings; never promise a count or suppress required cases. Geometry runs focused LanePlannerTest red/green, then focused CoreArchitectureTest once; complete movement runs focused MovementPolicyTest red/green, then full core once. Broaden or repeat only for a new fix or unresolved risk. Core architecture tests enforce forbidden dependencies/cycles. Isolated v2 core changes do not require rerunning v1; historical419 tests are not new evidence. No game run is part of6b.

Record these agreed costs in the PR: corrected narrow depth2-4 lane count; rejection of stride wider than corridor; both-axis .25 entry replacing permissive progress crossing; unknown/predicted required support holds with80-budget derived from pending verification; total entry52 prevents drift cycles and may end entry before pending support80; off-plan air remains exempt; WALK support hold permits lateral correction; conservative yaw1.5/20 hold requires Task6c calibration; entry current+command-neighbor footprint is a bounded expected-target check, not full player-body or momentum safety. Source endpoint1.0 and snapshotted sprint remain unchanged rather than adding settings machinery. Pending-fact integration, real effect release, staged yaw/head/body, credible observations, placement/server truth and game verification remain Task6c. Complete traversal/resume/reverse remains Task8. No outstanding broad design choice remains in this plan.
