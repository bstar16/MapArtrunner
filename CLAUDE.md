# CLAUDE.md — MapArtRunner Project Instructions

## Repo
https://github.com/bstar16/MapArtrunner
Main branch: `main`

## What this project is
A client-side Fabric Minecraft mod (1.21.11) that automates building mapart 
from a loaded schematic. Core feature is the grounded sweep builder — a 
deterministic print-head that walks fixed centerline lanes and places blocks 
in a 5-wide corridor, serpentining until the schematic is complete.

## Communication style
- Moderate explanation — brief summary of what changed and why, no need to 
  narrate every decision
- If something is ambiguous, make a sensible choice and note it in the PR 
  description
- Direct and practical, no fluff

## Tests
- Only write tests when the prompt explicitly asks for them
- Write tests for new classes and logic
- Do not write tests for small tweaks or bug fixes unless asked

## Commits and PRs
- Commit style doesn't matter, just get it done
- Always open a PR when a task is complete, never push directly to main

## Core doctrine — never violate these

### Architecture
- Client-only Fabric mod
- MapartRunner owns all active grounded sweep movement and placement
- Baritone is only for coarse travel:
  - initial approach to start/resume staging point
  - refill trips to supply containers
  - future gross recovery/reposition
- Baritone must NOT control active lane walking
- Baritone must NOT control inter-lane transitions
- Do not use Baritone allowPlace inside the schematic/build area
- Do not let Baritone place blocks inside the schematic
- Active sweep movement must be native MapartRunner movement only

### Movement
- The lateralStrafeSign() fix in GroundedLaneWalker is sacred — do not undo 
  or bypass it. It fixes strafe direction for WEST/SOUTH lanes. Any correction 
  based on strafe must be interpreted relative to player facing direction, not 
  raw world-axis error.
- Before proposing any movement fix, trace the full execution path — tick 
  method → walker → command application. Do not fix only the surface symptom. 
  Consider how the fix behaves for all four directions: EAST, WEST, NORTH, SOUTH.

### Code changes
- Avoid broad refactors
- Prefer small, scoped fixes
- Do not change placement executor logic unless the prompt explicitly asks
- Do not change lane planning logic unless the prompt explicitly asks
- Do not change smart resume logic unless the prompt explicitly asks
- Do not jump ahead in the roadmap unless explicitly asked
- Before implementing any new system, check whether the existing codebase 
already solves this problem. Reuse before reinvent.

### Commands
- Keep /mapart as the main command namespace
- Debug commands are fine during development but should be cleaned up before release

## Current build system
- Java 21
- Fabric Loader 0.16.5
- Fabric API 0.141.3+1.21.11
- Gradle with fabric-loom
- JUnit Jupiter 5.10.2 for tests
- Build: ./gradlew build
- Test: ./gradlew test

## Current roadmap order
Do not skip ahead unless explicitly told to.
1. ✅ Recovery (Prompt 9)
2. ✅ Refill (Prompt 10)
3. Smart skip/resume polish (Prompt 11)
4. Leftover/reverse cleanup hardening (Prompt 12)
5. Command cleanup and release prep (Prompt 13)
6. Documentation / README / usage guide (Prompt 14)
7. Discord webhook notifications (Prompt 14b — after 14)

## Known fixed bugs — do not reintroduce
- lateralStrafeSign() fix (PR #166) — WEST/SOUTH strafe direction
- Lane transition diagonal movement — caused by stage falling through in same 
  tick before yaw was committed
- Transition support null crash — pendingShiftLane/laneShiftPlan cleared on 
  failure then accessed by tickLaneShift()
