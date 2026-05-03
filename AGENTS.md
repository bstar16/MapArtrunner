# AGENT.md — MapArtRunner Agent Instructions

Project-specific instructions for AI coding agents working on MapArtRunner.

## Repo

Repository: https://github.com/bstar16/MapArtrunner  
Main branch: `main`

## What this project is

MapArtRunner is a client-side Fabric Minecraft mod for Minecraft 1.21.11.

It automates building mapart from a loaded schematic/plan.

The current core feature is the **grounded sweep builder**: a deterministic print-head-style builder that walks fixed centerline lanes and places blocks in a 5-wide corridor while serpentining until the schematic is complete.

Current focus is grounded mode. Elytra/flying mode is deferred and should not be touched unless explicitly requested.

## User workflow

The project owner does not manually code most changes.

Assume implementation work is performed by AI coding agents such as Codex or Claude Code. Therefore:

- Keep changes easy to review in PR form.
- Explain important behavior changes clearly.
- Include tests run and in-game verification steps.
- Avoid relying on the user to manually patch Java code.
- If a conflict or manual decision is required, explain exactly which side to keep and why.
- Prefer complete, self-contained PRs over instructions that require manual code edits.

## Communication style

- Be direct and practical.
- Give a brief summary of what changed and why.
- Do not narrate every internal decision.
- If something is ambiguous, make a sensible scoped choice and note it in the PR description.
- If a requested fix has a hidden root cause, explain it briefly.

## Core doctrine — do not violate

### Architecture

- Client-only Fabric mod.
- MapArtRunner owns all active grounded sweep movement and placement.
- Baritone is only for coarse travel:
  - initial approach to start/resume staging point
  - refill trips to supply containers
  - future gross recovery/reposition
- Baritone must **not** control active lane walking.
- Baritone must **not** control inter-lane transitions.
- Do not use Baritone `allowPlace` inside the schematic/build area.
- Do not let Baritone place blocks inside the schematic.
- Active sweep movement must be native MapArtRunner movement only.

## Grounded sweep model

The grounded builder works like a deterministic print-head.

Default behavior:

- Schematic/build area is a flat mapart plane.
- Start corner is north-west.
- First lane goes east.
- Lane width is 5 blocks total:
  - 2 blocks left
  - centerline
  - 2 blocks right
- Lane stride is 5.
- Movement is serpentine:
  - lane 0 EAST
  - shift 5 blocks SOUTH
  - lane 1 WEST
  - shift 5 blocks SOUTH
  - lane 2 EAST
  - repeat
- Full forward sweep should complete all lanes first.
- Reverse cleanup pass should handle leftovers after the forward sweep.

## Most important debugging rule

Before proposing any fix, trace the full execution path end to end.

Do not fix only the surface symptom.

Before implementing any new system, check whether the existing codebase 
already solves this problem. Reuse before reinvent.

The codebase currently has two parallel build/refill paths:
- BuildCoordinator (used by /mapart start) — older
- GroundedSingleLaneDebugRunner / GroundedRefillController 
  (used by /mapart debug grounded-sweep start) — current focus

When fixing the grounded sweep, ALWAYS apply changes to the grounded path 
files. Do not modify BuildCoordinator unless explicitly asked. The two 
paths will be unified in a future cleanup pass — for now, fixes go to the 
grounded path only.

For movement bugs, trace at least:

```text
command/tick entry
→ runner state
→ lane/transition/recovery state
→ walker/planner decision
→ command generation
→ key application
→ yaw/head/body handling
→ placement/verification side effects
→ failure/cleanup path
