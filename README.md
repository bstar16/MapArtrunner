## Milestone A Complete

This release marks the first stable archive point for **MapartRunner**.

### Included in this milestone

* implemented core plan models:

  * `BuildPlan`
  * `Region`
  * `Placement`
* implemented `.nbt` plan loading via `SchemNbtLoader`
* added material counting and chunk-aligned region splitting
* added core commands:

  * `/mapart load`
  * `/mapart unload`
  * `/mapart info`
* added persistence support for milestone-level config/progress data
* added Milestone A acceptance tests
* confirmed the project remains aligned with:

  * Minecraft `1.21.4`
  * Fabric Loader `0.16.12`
  * `fabric-api-0.119.2+1.21.4`

### Notes

* this release is intended as a clean archive checkpoint before continuing into the next milestone
* later runtime/session, HUD, overlay, and automation work will continue on separate development branches

### Next focus

* supply point registration
* settings system
* functional HUD
* basic schematic overlay renderer
