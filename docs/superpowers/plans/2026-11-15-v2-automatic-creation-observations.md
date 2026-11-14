# Automatic Creation Observations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Obtain bounded creation settings and resource endpoints before diagnosing the automatic world-load delay.

**Architecture:** Add observations at the Runner's existing automatic-only client callback and creation call. Reuse the existing stdout archive and actual Automatic adapter test doubles; preserve transport and ownership.

**Tech Stack:** Java 21, Fabric Minecraft 1.21.11, existing Gson, JDK management/process APIs and JUnit.

**Spec:** [Automatic creation observations](../specs/2026-11-15-v2-automatic-creation-observations-design.md)

## Global Constraints

- Two source/test paths only; no Host/interface/build/provider/public-selector or Inspect changes.
- After the existing authority guard, read settings on its existing synchronous client callback; no added dispatch.
- One unchanged native creation call and original throwable/handle/cleanup semantics.
- Two best-effort payloads per creation, each at most 2048 UTF-8 bytes including fixed prefix/newline; at most 16 collectors, unknown/overflow metrics null; no sensitive strings.
- Native long metrics/times are decimal strings or null. No clock, permission, retry or acceptance authority is added.
- No tuning or ownership repair. All2 floor remains strict >6914128988 + AdditionalAllocationBytes; startup600000, overall12266800, abort2147483648 unchanged.
- Current Header and carried POST_LINK evidence stay scoped; no unrelated replay. Stop after verified v2; no port.

## Review Focus

1. Diagnostic failure masks a native RuntimeException/Error: assert identical native throwable.
2. Output failure changes successful creation/retained handle: assert same call count/order and later handle use.
3. Guard or settings failure is swallowed together: keep authority guard outside diagnostic catch; test actual settings collector failure separately.
4. Extra work after failed native creation: assert no post-failure client/server/save/close route.
5. Invalid resource/output values: unknown metrics remain null, overflow/cap fails closed by dropping diagnostics only, no fallback/retry.

---

### Task 1: Observe automatic creation without changing its behavior

**Files:**
- Modify: `v2/fabric/src/gametest/java/com/mapartrunner/fabric/scenario/ScenarioRunner.java`
- Test: `v2/fabric/src/test/java/com/mapartrunner/fabric/scenario/ScenarioHostTest.java`

**Interfaces:**
- Consumes: existing `ScenarioHost.runOnClient`, `ScenarioHost.createWorld`, invocation UUID and fixture enum.
- Produces: package-private static `createAutomaticWorld(ScenarioHost host, UUID invocation, ScenarioDefinition.Fixture fixture, JsonObject preCreateSettings)` used by the actual automatic branch. Local settings capture returns needed scalars through the existing callback; no new public/interface contract.
- Output: fixed `MAPART_AUTOMATIC_CREATION ` prefix with bounded JSON BEGIN then RETURNED/THREW, as specified above; no parser or sidecar.

- [x] Search all touched callers and inspect existing NativeContext tests. Preserve the independent Inspect branch and guard ordering.
- [x] Add focused RED tests calling the real-used helper through `ScenarioHost.automatic(nativeHost.client)`. Pin success and builder/create RuntimeException/Error identity; two correlated bounded rows; no added transport; retained successful handle after later validation error.
- [x] Add diagnostic-failure regressions: actual settings collector failure still permits creation; RuntimeException/Error from a temporary throwing PrintStream cannot mask native failure or change success. Restore/serialize global stdout safely. Cover unknown/overflow and cap behavior through actual-used helpers where introduced, without a general sampler abstraction.
- [x] Run focused tests and retain meaningful compiled assertion failures; do not mistake missing signatures for behavioral RED. A detached temporary harness or test-local seam may establish old-path behavior before the new signature exists.
- [x] Implement the smallest local settings/resource/output helpers and the automatic wrapper. Use the existing client callback after `requireUncreatedClient`, worker-only JDK metrics, local JSON and stopwatch. Record elapsed before final sampling/output; contain only diagnostic failures. Host assignment remains where it is.
- [x] Run focused `ScenarioHostTest`, gametest compilation and one covering Fabric test aggregate using configured JDK21 and the existing low-memory/single-worker build profile. No native run, unchanged baseline rerun or operational/proof mutation.
- [x] Report commands/results, caller and release-path inspection, final diff and limitations. Root independently reviews, commits and opens a draft stacked PR; author does not perform Git mutations.

Source implementation `30aac6a` passed independent spec and quality review and is available in [draft PR #275](https://github.com/bstar16/MapArtrunner/pull/275). The compiled regression first failed on three missing-record assertions; final focused checks passed 70/70 and the covering Fabric aggregate passed 656/656, with no failures, errors or skips. Native observations and the full automatic suite remain unverified.

### Later runtime gate

Current source/provenance, diagnostic bytes including log decorations/copies, resource/ownership/zero-lock preflight and public all/traversal route require a separate operational review. No isolated NARROW selector or smaller diagnostic budget is created here. FullAll2 stays failed until its real required cases, exits and retention pass. Root must not infer the cause from endpoint correlations alone.
