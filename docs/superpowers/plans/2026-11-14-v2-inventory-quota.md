# Unwired inventory quota boundary implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert frozen absolute inventory targets into collection quantities without wiring a refill into the running builder.

**Architecture:** Add one stateless Fabric boundary that consumes the accepted S1 plan and prequalified inventory snapshot. Reuse the core allocator for capped, ordered physical held-stock credit, then subtract that credit once. Existing callers, native capture, transactions and state owners remain unchanged.

**Tech Stack:** Existing Java 21, core/Fabric modules and JUnit Jupiter 5.10.2; no new dependencies.

**Spec:** [CONSTRAINTS](../../../CONSTRAINTS.md), [parent Task10](2026-10-28-v2-reliability-plan.md#task-10-add-inventory-demand-and-genuine-refill), and the [accepted S1 contract](2026-11-13-v2-refill-policy.md).

## Global constraints

- Own exactly the two new source/test files listed below. Any core API, third path, caller, build, configuration, dependency or fixture change returns to Root before editing.
- Fabric depends on core; core must not depend on Minecraft, Fabric, Mixin, Baritone or a game directory.
- `RefillPlan` contains `targetInventoryTotals`; inventory observations exclude reserved slots and describe actual capacity/stack limits.
- Preserve valid tests. Never skip/delete a required failing scenario, weaken assertions, suppress required failures, lower thresholds or claim an interrupted/incomplete scenario as a successful skip to finish work.
- This source-only scheduling exception does not activate S2, all S3, native inventory capture, transactions or a runtime refill. All Task8/9, real Baritone/refill/return, recovery, parity and release gates remain mandatory.
- Stop after the approved v2 scope is complete and verified. Do not begin the Minecraft 1.12.2 port unless the owner explicitly authorizes it as separate work.

## Review focus

- Absolute targets are never replaced with collection quotas or subtracted twice.
- A physical pool eligible for multiple materials is credited once, in material order.
- Credit uses frozen trip objectives rather than larger original demand.
- Destination capacity cannot be mistaken for stock already held.
- Revision strings do not establish native freshness, ownership or transaction success.

### Task 1: Calculate immutable collection quantities

**Files:**

- Create `v2/fabric/src/main/java/com/mapartrunner/fabric/runtime/FabricInventory.java`.
- Create `v2/fabric/src/test/java/com/mapartrunner/fabric/runtime/FabricInventoryTest.java`.

**Interfaces:**

- Consume existing `RefillPlan`, `RefillPlan.InventorySnapshot`, `demand()`, ordered `targetInventoryTotals()` and `RefillPlan.allocate`.
- Produce `public static Map<RefillPlan.Material, Long> FabricInventory.additionalNeeded(RefillPlan plan, RefillPlan.InventorySnapshot inventory)`.
- Root supplies a fresh exact BASE, confirms both paths absent and no existing callers, and checks that no source writer or native freeze overlaps implementation.

Reject null arguments. Return a copied, immutable ordered map containing every frozen target material, including zero quantities, with `max(0, frozenAbsoluteTotal - usableHeldCredit)`. Never mutate the plan, original demand, reservations, unmet work or snapshot, and do not cache a quota.

Project the original demand to one entry per material in existing first-occurrence order. Keep that material's first actual target ID and replace its count with its frozen target inventory total, including zero. Use `new InventorySnapshot(inventory.revision(), inventory.held(), List.of())` for held-only facts, and pass the projection through the accepted allocator. Those temporary allocation totals are held credits, not collection quotas. Subtract them once from the original frozen totals; discard the temporary allocation's unmet/reservation data.

Do not reproduce the shared-stock loop, call `revise`, add destination reservations, resolve items/components, simulate insertion, update native state or infer supply exhaustion. Existing S1 constructors and allocation handle validated facts, reserved pools and material eligibility. The native producer remains responsible for qualification and fresh world/player/slot correlation. Carrying its revision into temporary facts is not a freshness check.

Capping is essential: original demand A96/B32 can yield frozen targets A32/B32. A fresh shared64 pool eligible for both must produce quotas A0/B0, preserving original unmet A64. Crediting against original A96 would consume the pool before B and falsely request B32. Destination capacities in the supplied snapshot must not lower the quota.

- [ ] **Step 1: Write and run behavioral RED.** Use actual S1 plans/snapshots and the new production method. An initial wrong-quantity implementation may demonstrate the regression; a missing-class compilation failure is not behavioral RED. Preserve all existing tests.
- [ ] **Step 2: Implement the minimal boundary and run focused GREEN.** Cover all cases below without changing core or introducing a parallel allocator.
- [ ] **Step 3: Run one covering validation on final source, obtain independent spec/quality review, then let Root commit the reviewed slice.** No implementation authority extends beyond these two paths.

Required focused cases:

- Required60/held40/reserved7 yields target60 unchanged and collection20; zero, exact, excess stock and empty plans.
- Ordered multiple materials, repeated-material demand and shared physical held pools; no double credit, with actual first-target-ID reuse checked during review.
- Frozen A32/B32 from original A96/B32 with a fresh shared64 pool gives quotas0/0; original reservations and unmet work stay unchanged.
- The existing A64/B64, heldA32, one empty main-slot allocation keeps targets A64/B0 and quotas A32/B0; B64 remains capacity-limited work.
- Fresh destination capacity never counts as held stock. Reserved and ineligible pools receive no credit. Component/max-stack eligibility is consumed from accepted facts rather than recreated.
- A changed snapshot changes the quota. No cached result or second subtraction; immutable ordered results and unchanged inputs; null arguments reject.

Run with the existing wrapper and process-local JDK21:

```text
gradlew.bat -p v2 :fabric:test --tests '*FabricInventoryTest'
gradlew.bat -p v2 :core:test :fabric:test :fabric:compileGametestJava
```

Do not rerun native games, v1 or Windows supervisor selftests solely for these two isolated files. These tests establish quantity arithmetic only: they prove neither real stock nor native freshness, click settlement, travel/return or resumed construction. No existing runtime entrypoint uses this class in this slice.
