# MapArtRunner v2 task index

Implementation approved on 28 October 2026, using task-by-task agents and independent reviews. V1 remains the reference. Requirements and verification live in the [canonical plan](../docs/superpowers/plans/2026-10-28-v2-reliability-plan.md); this file only tracks its ordered tasks.

- [x] 1. Pin reference behavior and constraints
- [x] 2. Establish isolated v2 builds and test entry points
- [x] 3. Introduce immutable plan and lifecycle contracts
- [x] 4. Enforce architecture boundaries
- [x] Checkpoint A: independently buildable core with meaningful tests and boundary checks
- [x] 5. Prove the Fabric test environment
- [x] 6a. Implement [expected-target ledger](../docs/superpowers/plans/2026-10-28-v2-placement-ledger.md)
- [x] 6b. Implement [native lane movement](../docs/superpowers/plans/2026-10-28-v2-native-movement.md)
- [ ] 6c. Integrate [tick effects and control ownership](../docs/superpowers/plans/2026-10-28-v2-tick-boundary.md)
- [ ] 6d. Import a real schematic into the neutral plan
- [ ] Checkpoint B: real one-lane build verified against server world
- [ ] 7. Automate two-lane setup, reset and rerun
- [ ] 8. Implement complete traversal, resume and cleanup
- [ ] 9. Add persistent inspect/reset workflow
- [ ] 10. Implement inventory demand and genuine refill
- [ ] 11. Implement isolated recovery
- [ ] 12. Enforce world lifecycle and final completion
- [ ] Checkpoint C: complete grounded workflow and six defect scenarios covered
- [ ] 13. Reach supported feature parity and preserve diagnostics
- [ ] 14. Prove CI, repeatability and release readiness
- [x] 14a. Add the [early unit/build CI gate](../docs/superpowers/plans/2026-10-28-v2-early-ci.md)
- [ ] Checkpoint D: review evidence before making v2 the default
- [ ] Later: create a separate 1.12.2 feasibility and port plan
