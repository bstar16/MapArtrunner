## Milestone C Complete

This release marks the third stable archive point for **MapartRunner**.

### Included in this milestone

* finalized the runtime build session/state model
* completed the core build/session classes:

  * `BuildPlanState`
  * `BuildSession`
  * `BuildProgress`
  * `BuildCoordinator`
  * `WorldPlacementResolver`
* finalized origin handling for plan-to-world placement resolution
* implemented the manual stepping flow:

  * `/mapart start`
  * `/mapart pause`
  * `/mapart resume`
  * `/mapart next`
  * `/mapart status`
  * `/mapart stop`
* added validated state transitions and cleaned runtime state handling
* implemented dry-run/manual progression through placements without automatic block placement
* implemented build progress persistence for active sessions
* cleaned the command/runtime path to be client-side aligned
* preserved all prior milestone features:

  * loading and plan inspection
  * supply registration
  * settings persistence
  * HUD
  * schematic overlay

### Notes

* this release is intended as a clean archive checkpoint before moving into automation-focused work
* the current stepping system remains manual/dry-run by design
* no Baritone integration or refill automation is included in this milestone

### Next focus

* begin controlled automation integration
* connect build actions to a real movement/build backend
* prepare supply-assisted workflows
* improve completion handling and run summary/reporting
