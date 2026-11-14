# Automatic world creation observations

The automatic NARROW fixture failed inside Fabric's world-loading wait before setup or building began. Spawn completed after 122238 ms, after readiness observation expired. Earlier server lag and LLVMPipe fallback do not establish whether GC, computation, I/O or scheduling caused the delay.

Capture the missing observations before tuning anything. This is a routine diagnostic slice of the approved v2 work, settled by agents under Bryce's explicit delegation of small decisions.

## Approach

Existing logs lack actual pre-create options and interval resource measurements. JDK GC/safepoint flags need additional per-JVM output paths and accounting, and cannot supply Minecraft options. Instrumenting the host would add dispatch or identity plumbing. Use the Runner's existing client callback and its existing automatic creation call instead.

Only `ScenarioRunner.java` and `ScenarioHostTest.java` change. After `requireUncreatedClient`, collect raw/clamped view distance and raw simulation distance on the client owner, best effort, without another dispatch. A package-private Runner helper brackets the unchanged `host.createWorld()` call with two bounded stdout observations. Inspect and Host APIs stay unchanged.

Each payload, including `MAPART_AUTOMATIC_CREATION ` and its newline, is at most 2048 UTF-8 bytes. Fixed fields identify automatic entry, invocation, fixture and BEGIN/RETURNED/THREW. Resource endpoints contain JVM uptime, heap used/committed/max, summed GC count/time and optional process CPU time. Encode long metrics/times as decimal strings or null. Read at most 16 collectors; unavailable or overflowing aggregates are null. No exception text, paths, session data, environment or tokens. All2 has at most eight creations and sixteen payloads (32768 bytes), excluding logging decoration/copies for later accounting.

Start native elapsed timing after BEGIN output, immediately before creation; sample elapsed before final diagnostics. Emit the final observation in `finally`. Diagnostic capture, formatting or output failure cannot mask native failure, turn failure into success, block cleanup or grant another allowance. Preserve exactly one creation call, original throwable identity, native handle assignment and existing cleanup. No post-failure client/server dispatch or borrowed ownership.

These are non-authoritative observations. GC sums can overlap; heap endpoints are not allocation rate; process CPU is not host availability. They help distinguish hypotheses without proving complete causation. Missing records remain missing.

## Verification and limits

Exercise the real helper through the existing automatic-host interface doubles: success, builder/create RuntimeException and Error, retained handle after later validation failure, failed settings capture, throwing output, original call order and no post-failure dispatch/close. Validate correlation, bounded output and elapsed values. Run relevant compilation and covering Fabric checks.

Do not change settings, heap, framework waits, timeouts, retry behavior, profile selection, world type, ownership, v1 or port scope. The pre-return partial-world ownership gap is separate. FullAllRepeat2 remains incomplete, requires strict free space greater than 6914128988 plus AdditionalAllocationBytes, startup 600000 ms, overall 12266800 ms and abort floor 2147483648 bytes. Source checks grant no native acceptance; a later separately reviewed/admitted public run and complete retention are required.
