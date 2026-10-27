# MapArtRunner reference reliability baseline

Recorded 28 October 2026 for commit `51ad8a53a578901e5bb83db082e98f8104abbf58`.

The owner wants reliable mapart automation, clearer architecture, and changes that do not destabilize unrelated behavior. Repeated in-game world/setup/command work is a supporting problem to solve. The owner selected a separate v2 with this implementation retained as the reference; the executable 1.12.2 port is later.

## Executed checks

`gradlew.bat test --no-daemon` passed using Microsoft OpenJDK 21.0.10 and Gradle 9.5.0: **419 tests across 31 suites, zero failures, errors, or skips**. The JDK existed locally but required process-level `JAVA_HOME`; no persistent machine configuration was changed. The original XML/test logs remain local generated evidence, excluded from version control.

No live Minecraft or actual Baritone session was run. The following cases are source-verified failure paths, not claims of in-game reproduction. A new implementation should add production-path regressions for them rather than preserve their results as compatibility.

## Required correction cases

| Case | Trigger and expected correction | Source anchors |
|---|---|---|
| Smart resume loses earlier work | Missing blocks in earlier and later lanes; starting near the later lane must retain all unfinished targets for traversal/cleanup | [candidate selection](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSweepResumeScanner.java#L146), [runner selection](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L378) |
| Skipped reverse lanes cause wrong entry | EAST/WEST/EAST forward run with middle-lane leftovers; reverse must reach both entry coordinates before walking that lane | [selection](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L3423), [transition](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L4863) |
| Refill counts stock twice | 60 needed, 40 held; request target total 60 and collect 20, without repeated empty trips | [caller](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L1560), [controller](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedRefillController.java#L923) |
| Return navigation can hang | Rejected/stopped return journey must produce a bounded explicit outcome | [ignored result](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedRefillController.java#L375), [wait](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedRefillController.java#L542) |
| Failed expected floor disappears | A failed/missed required footprint target remains expected and must not be reclassified as acceptable air | [lookup](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L1170) |
| Run survives world change | Disconnect/rejoin must not apply the previous world's movement/placements; manual resume validates a new binding | [tick early return](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedSingleLaneDebugRunner.java#L509), [client initialization](../../src/main/java/com/example/mapart/MapArtClientMod.java) |

## Preserve working behavior and improve evidence

Preserve native five-wide serpentine building, direction-relative strafe correction, staged yaw/head/body alignment, delayed confirmation/audits, reserved hotbar slots, and leftovers retained across refill resume. Inventory the remaining working commands, settings, overlays and helper tools before making v2 a replacement.

The existing tests cover useful decisions, but several inject positions, verification answers or inventory-index sets. In particular, the [refill simulation](../../src/main/java/com/example/mapart/plan/sweep/grounded/GroundedRefillController.java#L569) duplicates a simplified decision path. New tests should exercise the production policies and a small real client/server scenario with independent final-world assertions.

The [only workflow](../../.github/workflows/gradle.yml#L10) triggers on releases. The [background testing guide](../BACKGROUND_TESTING.md) requires repeated manual setup and lists outdated commands. Current [/mapart start](../../src/main/java/com/example/mapart/command/MapArtCommand.java#L123) already invokes the grounded runner; `BuildCoordinator` remains used for plan/session backing and must not be called unused merely because the start route changed.

Seventy of 116 production Java files directly import Minecraft/Fabric/Mixin types; the grounded runner is 6,900 lines. These are coupling inspection signals, not sufficient reasons by themselves for deletion. V2 should establish explicit state owners, game-independent decisions, and tested interaction contracts.

Unchecked areas include live gameplay/Baritone compatibility, actual packet-delay injection, headless game CI, the full older coordinator, experimental flight, detailed torch-grid behavior, and an executable legacy port.
