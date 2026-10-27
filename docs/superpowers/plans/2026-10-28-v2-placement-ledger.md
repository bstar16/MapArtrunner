# Task 6a Expected-Target Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Account for every immutable plan target through attempts and trustworthy observations without confusing target confirmation with final run success.

**Architecture:** One small, single-threaded core ledger per run retains the existing BuildPlan and target-local accounting. RunLifecycle remains the sole owner of world/run lifecycle; its caller explicitly binds the ledger to the already selected run UUID. Immutable observations carry neutral block state, evidence, correlation and logical sample tick.

**Tech Stack:** Java 21, existing JUnit Jupiter 5.10.2, existing Gradle wrapper; no new dependencies.

**Spec:** [parent Task 6a](2026-10-28-v2-reliability-plan.md#6a-expected-target-ledger), [approved design](../specs/2026-10-28-v2-reliability-design.md#core-contracts-and-invariants), and [constraints](../../../CONSTRAINTS.md).

**Status / prerequisite:** Approved parent Task 6a expanded into this reviewed focused plan on 28 October 2026. Task 5 passed its local environment acceptance and independent live review; evidence is in PR #248 and v2/docs/testing.md. Task 6a implementation and independent task review passed: expected missing-contract RED, 19 focused ledger cases and 63 full-core tests, zero failures/errors/skips. The four decisions below are adopted. Production evidence provenance, gameplay integration and terminal success remain later gates.

## Global Constraints

- Separate v2 implementation; keep v1 production code/build/test expectations unchanged.
- Minecraft 1.21.11; Java 21; Loader 0.19.2; Fabric API 0.141.1+1.21.11; Yarn 1.21.11+build.4; Loom 1.16.1; Gradle 9.5.0; JUnit Jupiter 5.10.2.
- Native active lane/transition movement and placement; Baritone only coarse travel.
- Core has no Minecraft, Fabric, Mixin, Baritone, or game-directory dependency.
- Preserve required target accounting, explicit total-count refill contracts, bounded waits, world/run identity, and exclusive control ownership.
- An accepted placement or a completed traversal cannot establish successful construction by itself.
- Test-only reset/fault controls must not be shipped or operate on unmanaged worlds.
- Preserve valid tests. Never suppress a required failing scenario or weaken the bar to finish a task.
- Keep the 1.12.2 loader/runtime and executable port deferred.

## Review Focus

- First callback belongs to an old run: bind explicitly before inputs; test `requiresExplicitRunBindingBeforeMutation`.
- Noncontiguous target IDs [7, 2]: preserve original order through retries; test `expectedTargetsSurviveFailedAndMissedAttempts`.
- Already-correct world blocks without placement: permit fresh actionless observation; test `existingWorldMatchNeedsNoAction`.
- Duplicate attempt delivered after confirmation: do not erase confirmation or reset freshness; test `repeatedAttemptIsIdempotentAndCannotRewriteItsTick`.
- Equal/out-of-order ticks and later uncertainty: prevent stale overwrites; tests `sampleTicksOrderEvidencePerTarget` and `freshUnknownOrPredictionReopensConfirmation`.

## Explicit decisions for this slice

1. **Explicit identity addition:** add `void bindRun(UUID runId)` alongside every parent-required signature. Binding is one-time: same UUID is an idempotent no-op; different UUID throws IllegalStateException. No implicit binding by attempts/observations. The caller checks BuildPlan fingerprint against RunLifecycle.Context and uses its runId; only RunLifecycle owns connection/world/dimension/origin/settings binding. Cost: one initialization call and a fresh ledger for a fresh run; no duplicate lifecycle or registry.
2. **Evidence / uncertainty:** use UNKNOWN, PREDICTED, OBSERVED. OBSERVED means client-available world evidence that the producer has established is not speculative local placement prediction; only its exact BlockSpec match confirms. This is a semantic contract, not a packet/acknowledgement promise. A newer UNKNOWN or PREDICTED observation reopens a previously confirmed target, just as an OBSERVED mismatch does. Cost: unloading/uncertainty requires re-observation. This is an explicit conservative policy, not a claim about existing gameplay behavior.
3. **Ordering:** logical ticks are nonnegative run-local client simulation ticks of sampling/attempt issuance, never arrival time or wall-clock time. At most one observation per target per tick is accepted, strictly newer than its previous observation and latest attempt. Attempts require a tick at least as new as that target's last observation/attempt. Action IDs are nonnegative and strictly increasing **per target** for new attempts; correlation is (runId, targetId, actionId), so IDs need not be contiguous or globally allocated. Cost: same-tick post-attempt evidence waits for a later sample; future producers submit one selected observation per target per tick. No deadlines, timers, tick advancement or automatic retry policy here.
4. **Accounting result:** `outcome()` returns NOTHING_TO_BUILD, INCOMPLETE or ALL_CONFIRMED; never RunLifecycle.Outcome.SUCCESS. ALL_CONFIRMED is reversible when newer evidence arrives. Final verification and terminal success remain later coordinator work. Cost: consumers must not treat this enum as a terminal result or simultaneous final-world snapshot.

These decisions bind this slice and their costs are recorded in the parent ledger. Task 5 proves environment feasibility and independent assertions; mapping production observations to nonspeculative evidence still requires the later Task 6c client/server proof. No production confirmation source or weaker test is invented here.

## Task 6a: Ledger and immutable observation, one focused test cycle

**Files — create exactly:**

- `v2/core/src/main/java/com/mapartrunner/core/placement/PlacementObservation.java`: immutable evidence value and nested Evidence enum.
- `v2/core/src/main/java/com/mapartrunner/core/placement/PlacementLedger.java`: expected-target accounting and nested Outcome enum.
- `v2/core/src/test/java/com/mapartrunner/core/placement/PlacementLedgerTest.java`: all focused tests below.

**Read-only inputs:** `v2/core/src/main/java/com/mapartrunner/core/model/BuildPlan.java`, `v2/core/src/main/java/com/mapartrunner/core/run/RunLifecycle.java`, and `v2/core/src/test/java/com/mapartrunner/core/architecture/CoreArchitectureTest.java`. BuildPlan already rejects duplicate IDs/positions, keeps noncontiguous IDs and caller order, and defensively copies collections. Do not change it, RunLifecycle, architecture tests, builds, dependencies or v1.

**Interfaces:**

Consumes `BuildPlan.targets(): List<BuildPlan.Target>`, `Target.id(): int`, `Target.block(): BuildPlan.BlockSpec`, and full BlockSpec value equality (ID and properties). No registry lookup or property normalization.

Produces in `com.mapartrunner.core.placement`:

~~~java
public record PlacementObservation(
    UUID runId, int targetId, OptionalLong actionId, long tick,
    Evidence evidence, Optional<BuildPlan.BlockSpec> observedState)
public enum PlacementObservation.Evidence { UNKNOWN, PREDICTED, OBSERVED }

public final class PlacementLedger
public PlacementLedger(BuildPlan plan)
public void bindRun(UUID runId)
public boolean observe(PlacementObservation observation)
public boolean recordAttempt(UUID runId, long actionId, int targetId, long tick)
public List<BuildPlan.Target> unfinishedTargets()
public Outcome outcome()
public enum PlacementLedger.Outcome { NOTHING_TO_BUILD, INCOMPLETE, ALL_CONFIRMED }
~~~

**Value and mutation contract:**

- Observation requires non-null UUID, Optional containers and Evidence. Target ID, tick and present action ID are nonnegative. UNKNOWN requires empty observedState (includes unloaded/unavailable); PREDICTED/OBSERVED require present state. Nulls throw NullPointerException; other malformed values throw IllegalArgumentException. Invalid inputs never partially mutate ledger state.
- Constructor rejects null plan and initially leaves every target unfinished. Queries work before binding (empty plan => NOTHING_TO_BUILD, otherwise INCOMPLETE); attempts/observations before binding throw IllegalStateException. Validate nulls/malformed scalar values first. Unknown nonnegative target IDs and different run UUIDs return false without mutation or advancing freshness/action history.
- A new accepted attempt marks its target unfinished, never changes expectations or confirms. Keep only latest action ID/tick per target. Lower action ID, equal ID/different tick, or older attempt tick returns false. An exact repeat of latest (run, target, action, tick) returns true as a no-op even after confirmation; it cannot reopen work. Different targets have independent histories.
- Observation tick must be strictly greater than both last accepted observation tick and latest attempt tick, if either exists (initial tick 0 is valid). A present actionId must equal that target's latest attempt; correlation without an attempt is rejected. A fresh actionless observation is a world sample, valid before or after attempts. Never strip stale action correlation to relabel a callback as an actionless sample. Rejected observations return false with no mutation; accepted observations return true.
- Accepted OBSERVED state confirms only on exact expected BlockSpec equality. Known mismatch (including air) reopens work. UNKNOWN/PREDICTED never confirms and clears prior confirmation. Retain latest attempt metadata after confirmation to reject superseded callbacks; new evidence changes confirmation state, never expected targets. No observation has no effect: this is supplied target evidence, not a globally current world snapshot; producers must not synthesize UNKNOWN for unvisited targets. Final-world scan policy remains later coordinator work.
- `unfinishedTargets()` returns a fresh immutable list of unconfirmed original Target values in plan order; prior returned lists remain unchanged. Nonempty plan reaches ALL_CONFIRMED exactly when none remain. Empty plan yields NOTHING_TO_BUILD without inventing targets.
- Keep O(number of targets) state: immutable plan, target ID lookup, per-target latest attempt, latest observation tick and confirmation flag. No attempt log, queue-as-truth, I/O, clock, registry, asynchronous execution or RunLifecycle dependency. No engine, adapter, movement, world-scan implementation, scenario framework or protocol abstraction.
- Lifecycle comments: create unbound; bind before inputs; retain instance/data across same-context pause; later coordinator stops routing inputs and discards the ledger on stop/cancel/world change before installing a new bound ledger. No reset/rebind/phase API or second lifecycle owner. This slice has no active wait/scheduling: retries/deadlines and terminal cleanup orchestration are not implemented here.

- [x] **Step 1: Write PlacementLedgerTest before production implementation.** Use explicit UUID constants A/B and a two-target plan in order [7, 2] at distinct valid positions. Helpers construct values but must not reproduce ledger logic. Pin these test names/assertions:

| Test method | Assertions |
|---|---|
| `expectedTargetsSurviveFailedAndMissedAttempts` | Attempt 7, observe air/mismatch, then retry; never attempt 2. Both original targets remain unfinished in [7, 2] order, plan unchanged, INCOMPLETE. Covers failed/missed required floor without inventing failure-action APIs. |
| `unknownAndUnloadedNeverConfirm` | UNKNOWN/empty state before and after attempt remains unfinished; confirming the other target cannot hide it. Unloaded is UNKNOWN, not air. |
| `predictedMatchNeverConfirms` | Matching PREDICTED state after accepted attempt remains unfinished, INCOMPLETE. |
| `staleRunCannotMutateBoundLedger` | Bind B first; A attempt/observation returns false before any B input. Confirm with B; later A mismatch cannot reopen. Rejected A evidence cannot block valid same-tick B evidence. |
| `staleActionCannotConfirmOrReopen` | Action 10/tick 5 then 11/tick 6 for target 7. Action 10 match/tick 7 => false; 11 match/tick 7 confirms. Old action 10 mismatch/tick 8 => false; 11 evidence/tick 8 still accepted. Correlation without recorded attempt => false. |
| `delayedConfirmationSurvivesWithoutInventedTimeout` | Attempt tick 5; PREDICTED at 6 and UNKNOWN at 7 remain unfinished; current-action OBSERVED match at 100 confirms. No sleeps or fake time advancement. |
| `allTargetsRequiredForAllConfirmed` | Confirm only 7 => [2], INCOMPLETE; attempt 2 alone stays INCOMPLETE; confirm 2 => empty, ALL_CONFIRMED. |
| `existingWorldMatchNeedsNoAction` | Actionless OBSERVED match at tick 0 confirms with no attempt. After a later attempt, actionless matching sample at a strictly later tick reconfirms. |
| `knownMismatchReopensConfirmedTarget` | Confirm both; later OBSERVED wrong block/air/property on 7 => [7], INCOMPLETE; later exact match restores ALL_CONFIRMED. Expected BlockSpec unchanged. |
| `freshUnknownOrPredictionReopensConfirmation` | Parameterize UNKNOWN/PREDICTED: newer evidence reopens confirmed target; later OBSERVED match reconfirms. |
| `sampleTicksOrderEvidencePerTarget` | After tick 10, tick 9/equal 10 observations => false, cannot confirm/reopen; tick 11 can. Another target independently accepts tick 0. Observation at latest attempt tick rejected; next tick accepted. |
| `repeatedAttemptIsIdempotentAndCannotRewriteItsTick` | Exact latest attempt repeat after confirmation => true, stays confirmed. Same ID/different tick, lower ID, higher ID/older tick => false without mutation; higher ID/valid tick => true, reopens. Same action ID allowed independently on another target. |
| `requiresExplicitRunBindingBeforeMutation` | Both mutations throw before binding; bind A twice succeeds; bind B throws, identity unchanged. First stale callback cannot establish binding. |
| `unfinishedSnapshotsKeepPlanOrderAndCannotBeMutated` | Mutation throws UnsupportedOperationException; old [7, 2] list stays unchanged when new list becomes [2]; reopening 7 yields [7, 2]. |
| `emptyPlanReportsNothingToBuild` | Empty list and NOTHING_TO_BUILD before/after binding; unknown target input => false; no lifecycle effects. |
| `invalidInputsAreRejectedWithoutMutation` | Null plan/run/observation/Optional/evidence => NPE; negative scalar IDs/ticks and inconsistent evidence/state => IAE; unknown positive target in both mutation APIs => false; valid known-target input works afterward. |

- [x] **Step 2: Run focused RED from repository root after Task 5 acceptance.**

Run: `./gradlew.bat -p v2 :core:test --tests '*PlacementLedgerTest' --no-daemon`.
Expected: nonzero with missing ledger/observation contracts (or failing behavior if minimum declarations were necessary); retain actual log. Infrastructure failure is not RED evidence.

- [x] **Step 3: Implement the two production files against the API and contract above.** Use immutable Target/BlockSpec and target-ID lookup, never ID-as-list-index. Validate identity/correlation/freshness before changing state. Document evidence limits and lifecycle in production comments.

- [x] **Step 4: Run focused GREEN with the identical command.**

Expected: exit 0, BUILD SUCCESSFUL, PlacementLedgerTest actually executes with zero failures/errors/skips. Retain XML/log evidence; UP-TO-DATE alone is not execution evidence.

- [x] **Step 5: Run the complete core suite once.**

Run: `./gradlew.bat -p v2 :core:test --no-daemon`.
Expected: exit 0; focused, BuildPlan, RunLifecycle and CoreArchitecture tests execute with zero failures/errors/skips. Existing architecture scan automatically includes placement classes. Do not modify architecture checks to pass. Java setup, if necessary, uses approved local JDK discovery/process-local configuration, not permanent environment edits.

- [x] **Step 6: Self-review scope, retained expectations and evidence; commit, then obtain the controller's independent task review before completion.**

Planned implementation commands: `git diff --check`; `git add v2/core/src/main/java/com/mapartrunner/core/placement/PlacementLedger.java v2/core/src/main/java/com/mapartrunner/core/placement/PlacementObservation.java v2/core/src/test/java/com/mapartrunner/core/placement/PlacementLedgerTest.java`; `git commit -m "feat: account for expected v2 placement targets"`. Do not stage unrelated work; the focused plan is committed separately.

## Acceptance and limits

All five parent-required categories remain explicit: failed/missed expectations; unknown/unloaded evidence; stale run/action; delayed confirmation; every required target. Completion requires agreed rulings, real Task 5 acceptance, focused RED/GREEN, unchanged core suite and required review evidence.

These unit tests exercise only the declared input contract. They do not prove an adapter can distinguish prediction from non-predicted world evidence, that blocks exist on a server, or that a run passed final verification. Gameplay remains client-only; independent server-world truth belongs exclusively to test assertions. No real-mod integrated-server access or later final-world-scan/movement implementation is proposed.
