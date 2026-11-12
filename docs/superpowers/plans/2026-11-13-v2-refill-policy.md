# MapArtRunner v2 — pure refill producer only

This is Task10 S1 only: four new unwired core files. It may precede Task8 **native acceptance** under the parent plan's explicit scheduling exception. Task8/9 acceptance and all later integration/native/refill/recovery/parity/release gates stay open. No existing runtime caller, configuration, build input or gameplay behavior changes here. Stop after approved verified v2; the port still needs separate owner authorization.

## Scope and ownership

Create exactly:

- `v2/core/src/main/java/com/mapartrunner/core/refill/RefillPlan.java`
- `v2/core/src/main/java/com/mapartrunner/core/refill/RefillFlow.java`
- `v2/core/src/test/java/com/mapartrunner/core/refill/RefillPlanTest.java`
- `v2/core/src/test/java/com/mapartrunner/core/refill/RefillFlowTest.java`

Use ordinary immutable nested records for neutral payloads and existing `BuildPlan.BlockSpec`, `GridPos` and target identities. Keep dependencies `run -> refill -> model`; refill imports no `core.run`, game, Fabric, Baritone, filesystem or singleton. RefillFlow owns only one request's bounded trip progress. Engine later owns IDs/correlation and construction clocks; traversal owns its bookmark; ledger owns unfinished targets; the native inventory/cleanup owners retain transactions and release authority. Do not recreate these owners inside the policy.

Root supplies exact final BASE, validates these four paths are absent, and ensures no overlapping source writer/native freeze before activation. The previously reconciled core pin was574667; equivalence at any later BASE needs its own recorded source comparison. The independent scheduling review permits this exception, not blanket Task10 activation or adoption of its later fifteen-slice candidate.

## One capacity-honest allocation

`RefillPlan.allocate(orderedDemand, inventoryFacts)` is the single pure allocation. Demand uses full-state BlockSpec, resolved item identifier and absolute required count. Combine equal material keys in first-traversal-occurrence order with stable target-ID tie breaking. The future traversal/ledger boundary supplies canonical unfinished order; capacity limits collection, never target accounting.

Inventory facts are native-prequalified usable-held, component/merge/insertion, real stack-limit, slot-capacity and revision/eligibility observations. Keep those identities opaque; do not serialize components or invent a native placement/merge simulator. Exclude reserved destinations and unusable stock consistently. Credit each physical held unit and slot capacity only once across all materials. Preserve destination preference: empty main, empty unreserved hotbar, compatible partial main, compatible partial unreserved hotbar. Do not evict unrelated or reserved items or assume stack limit64.

Return immutable copied `targetInventoryTotals`, exact ordered `DestinationReservation` entries with slot/amount/revision/eligibility/capacity, and capacity-limited unmet demand/reasons. These totals are absolute: required60, usable40 and reserved7 yields target60. The planned native `FabricInventory.additionalNeeded` boundary alone will later calculate collection20 from a fresh revision; do not subtract again or pass collection20 back as target20.

Exact shared-capacity regression: A requires64, has compatible32/max64; B requires64, has0; there is one legal empty main slot and every other slot is occupied. A-first preference reserves32 A into that empty slot. Promise A total64 and B total0/omitted, retaining B64 as capacity-limited unfinished demand. Do not independently pack A into its existing partial stack to promise B64 from the same physical capacity. Cover differing components, max16, mixed demands and shared eligible held units. Capacity limitation is distinct from exhausted supply.

Later reservation revision must use this same allocator against fresh facts with frozen trip totals and retained consumed assignments. It cannot increase targets, resurrect consumed reservations, visit a supply twice, renew a budget or silently switch destination policy. No native transaction or acknowledgement is simulated here.

## Immutable configuration and checked flow

`RefillFlow.Config` snapshots ordered Supplies, inventory click pacing and Limits. Supplies have positive unique IDs, dimension, immutable world position and name; preserve order. Validate pacing0..10/default4 and copied inputs/results. The future configured-start boundary rejects wrong dimensions and binds Config to the run; no existing start or Settings fingerprint changes in S1.

Flow consumes run identity, bound Config, frozen plan/reservations and a neutral traversal-anchor value. It consumes fresh neutral inventory/additional-needed facts, checked action results and one admitted callback identity, and emits neutral decisions. It never mints engine action IDs, calls effects, clears another owner's state or becomes the native clock/cleanup owner. Required stages cover outbound/release, opening, transferring, closing/transaction release, return/release and done/failed, including the initial native-release reserve. Every return initiation checks its admission result: rejected, stopped or unavailable yields bounded explicit failure, not endless RETURNING or new recovery behavior.

Initial preflight is once; later requests require an actual actionable shortage after future main-stock staging. Freeze absolute totals for the request. Useful partial stock may resume executable retained work; remember exhaustion for that request and fail explicitly when required stock is exhausted. Consumption alone cannot trigger a top-up loop.

## Fixed limits and accounting

| Budget | Default contract |
|---|---|
| Outbound per admitted supply / return |400 unique eligible callbacks each; visit each supply once |
| Navigation progress |200 eligible callbacks, at least1.0 block net Euclidean decrease from the admitted entry/last window distance; initialize the first window correctly |
| Opening / each click acknowledgement-reconciliation / closing |40 callbacks each; elapsed time never proves a click succeeded |
| Entire transfer per visited supply |1200 callbacks including pacing and acknowledgements; new clicks/revisions never renew it |
| Native pacing |0..10/default4; at most one actual click per eligible callback in future effect consumer |
| Physical cleanup |80 actual unique cleanup callbacks OR10000ms monotonic elapsed time, whichever expires first, including PAUSED/TERMINAL |

Use checked long arithmetic: `Limits.maxTripCallbacks(n) = 1840*n + 640`, reject negative counts and overflow; n1 gives2480. This includes initial release, each supply's outbound release and transaction release, return release and terminal cleanup. Per-click40 waits are inside transfer1200. Charge a unique callback once to total even across transitions. Extra pause/cancel cleanup spends the existing reserve, never new allowance. Ordinary paused work does not age, but actual cleanup callbacks do. Preserve all consumption across pause/resume and reservation revisions; resumed authority uses only remaining budgets. Every stage/total expiry has a useful failure.

Existing pending80/VERIFY80/attempt spacing4/max attempts3 and construction/traversal clocks remain unchanged and outside this policy's ownership. Future integration must drain pending work under its original ages before entering refill, then freeze construction clocks during the trip and resume them unchanged. S1 does not wire or claim that behavior yet. Outer native supervision remains a separate bound when callbacks disappear.

## Verification and handoff

Use actual new-policy behavioral RED/GREEN, not a missing-class compile failure. Required cases: absolute60/held40/reserved7; zero/mixed/full inventory; shared held units/slot capacity/component/max16 and exact A32/B64 example; copied immutable inputs/results; arithmetic invalid/overflow; every return-admission outcome; first200 progress window; duplicate callback accounting; every stage/total and cleanup callback-or-wall expiry; retained pause/revision allowances; no revisits, revived reservations or consumption top-up; truthful partial/exhaustion/capacity outcomes.

Run focused RefillPlan/RefillFlow tests, then full core suite including architecture checks because types/dependencies change. Preserve existing valid tests and thresholds. No Fabric/v1/Windows/native repeat is required for these four unwired pure files without new reach. Independent source/spec/quality review precedes source acceptance. Record unit policy evidence honestly: it establishes neither a real trip nor a native transfer, configured runtime behavior or Task10 completion.

No fifth-path shim, new dependency, inventory/traversal simulation, runtime caller, provider/fixture registration, public setting, compatibility layer or port work belongs in this slice. A concrete additional dependency or contract gap returns to Root before editing. S2 and later work still require accepted dependencies and fresh reviewed briefs.
