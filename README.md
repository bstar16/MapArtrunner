## Milestone B Complete

This release marks the second stable archive point for **MapartRunner**.

### Included in this milestone

* implemented supply point registration and persistence
* added supply management commands:

  * `/mapart supply add`
  * `/mapart supply list`
  * `/mapart supply remove`
  * `/mapart supply clear`
* added `supplies.json` persistence
* implemented the settings system and `settings.json`
* added settings commands:

  * `/mapart settings`
  * `/mapart settings set <key> <value>`
* implemented a functional HUD overlay for active plan/session information
* implemented a basic in-world schematic overlay for alignment and placement verification
* added world-vs-plan comparison logic to classify placement state
* improved supply registration flow so containers can be registered by right-click interaction
* preserved existing load/info/unload functionality

### Notes

* this release is intended as a clean archive checkpoint before continuing into the next milestone
* the HUD is currently function-first and structured for later visual polish
* the schematic overlay is intentionally lightweight and focused on accurate alignment rather than full Litematica-style rendering

### Next focus

* finalize build session flow
* tighten runtime state transitions
* improve manual stepping:

  * `/mapart start`
  * `/mapart pause`
  * `/mapart resume`
  * `/mapart next`
  * `/mapart status`
* persist and restore build session progress
* prepare for later automation and movement integration
