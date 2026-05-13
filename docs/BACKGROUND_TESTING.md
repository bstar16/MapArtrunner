# Background / Unfocused Operation — Developer Testing Guide

This document explains how to test MapArtRunner while the Minecraft client window is
unfocused, and describes the supported/unsupported modes for background operation.

## Why a dedicated server for background testing?

In singleplayer, Minecraft pauses the game loop when the window loses focus. This means
placement, refill, and movement all stop — which looks like a pass but doesn't actually
test anything.

A locally hosted dedicated server keeps ticking even when the client loses focus, so the
client continues to process game logic (receive packets, tick mixin hooks, fire
`END_CLIENT_TICK`) independently of OS window focus. This makes it a realistic proxy for
running against a remote server while alt-tabbed.

## Setup

1. **Start a local dedicated server** outside the IDE (vanilla or Fabric server jar
   matching the mod's Minecraft version, `localhost` default port 25565).
2. **Start the modded client** using the normal `runClient` Gradle task or IDE run config.
3. **Join the server** from Multiplayer → Direct Connect → `localhost`.
4. Load a mapart plan: `/mapart plan load <name>`
5. Register supplies: `/mapart supply register` (stand next to a chest)
6. Start grounded sweep: `/mapart debug grounded-sweep start`

## What to test

### Alt-tab during lane walking
- Press Alt+Tab after the runner starts. Switch to another window for 30–60 seconds.
- Return and verify:
  - Run is still active (no false failure or recovery trigger).
  - Placement has continued.
  - Player is still in the correct position or has advanced.

### Alt-tab during refill
- Let the runner trigger a refill (inventory runs out).
- Alt-tab once the refill navigation starts.
- Return and verify:
  - Refill completed — player is back near the build area.
  - No false supply-exhaustion failure.
  - Run resumed from correct lane position.

### Minimized window (experimental)
- Minimize the Minecraft window entirely.
- Some JVMs/OS configurations throttle timer resolution when minimized. The
  tick-stall detection (`ClientTickHealthMonitor`) guards against this:
  - If the inter-tick wall-clock gap exceeds 2 seconds, sensitive timeout/retry
    counters are not advanced for that tick.
  - A `CLIENT_TICK_STALL_DETECTED` event is logged to the grounded trace.
- After restoring the window, verify the run resumed cleanly.

## Diagnostics

The `background` section of each grounded diagnostics snapshot (`logs/mapart-diagnostics.log`)
contains:

| Field                        | Meaning                                                    |
|------------------------------|------------------------------------------------------------|
| `lastTickWallClockDeltaMs`   | Wall-clock ms since the previous client tick               |
| `tickStallDetected`          | `true` if that delta exceeded the 2 s stall threshold      |
| `sensitiveCountersSuppressed`| `true` when a stall is active and counters are suppressed  |
| `currentScreenType`          | Class name of the open screen, or `"none"` for in-game    |
| `cursorLocked`               | `true` when cursor is captured (in-game, no screen open)  |

The grounded trace log also emits `CLIENT_TICK_STALL_DETECTED deltaMs=<N>` on the
first tick where a stall is detected.

## Supported levels

| Scenario                          | Support level    |
|-----------------------------------|------------------|
| Unfocused, client still ticking   | **Supported**    |
| Minimized (JVM still ticking)     | **Experimental** |
| OS suspend / sleep                | Not guaranteed   |
| Heavily throttled JVM             | Experimental (stall guard helps) |
| Anti-cheat servers                | Not tested / not a goal |

## What was NOT changed

- Timer implementation (`ClientTimerController`, RenderTickCounter mixin) — untouched.
- Refill inventory pulling strategy — untouched.
- Lane movement algorithm and `lateralStrafeSign()` — untouched.
- Lane transition movement — untouched.
- `BuildCoordinator` — untouched.
- Missed-block lifecycle / PR #224 logic — untouched.
- Smart resume selection — untouched.
- Lane planning — untouched.
- Start-approach retry budget (PR #222) — counters only paused during stall, budget unchanged.
- PR #223 leftover capture logic — untouched.
