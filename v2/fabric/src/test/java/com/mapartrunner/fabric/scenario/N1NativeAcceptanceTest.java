package com.mapartrunner.fabric.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.mapartrunner.fabric.scenario.ScenarioRunner.*;
import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-owner evidence only: none of these fixtures authenticates a native RUN2. */
class N1NativeAcceptanceTest {
    @TempDir Path temporary;
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize();
    }
    @Test void fixedN1CasesLeaveResetFaultsUnselected() {
        for (String selected : new String[]{"n1-header", "n1-post-link"}) {
            assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,selected,Command.RESET,1,0,true));
            assertNull(ScenarioRunner.selectResetFault(true,"true","true",null,selected,Command.RESET,3,1,true));
        }
    }

    @Test void automaticPresenceAndEveryOrdinaryGateKeepBothFaultsInert() throws Exception {
        properties("n1-header",() -> {
            assertTrue(ScenarioRunner.n1Selected("n1-header")); assertFalse(ScenarioRunner.n1Selected("n1-post-link"));
            for(String name:List.of("mapart.test.inspect","mapart.test.inspect.acceptance","fabric.client.gametest")) {
                var old=System.getProperty(name); System.setProperty(name,"false");
                assertFalse(ScenarioRunner.n1Selected("n1-header"));
                if(old==null) System.clearProperty(name); else System.setProperty(name,old);
            }
        });
    }

    @Test void realHeaderCreateNewCollisionRetainsCausePathAndNeverGrantsWork() throws Exception {
        var roots=roots(); var host=host(new AtomicLong(1)); var callback=new AtomicInteger();
        var runner=new ScenarioRunner(host,new ScenarioObserver.Watch(),roots,operation -> {
            callback.incrementAndGet();
            try { ScenarioRunner.createN1HeaderCollision(roots,operation); } catch(IOException error) { throw new UncheckedIOException(error); }
        });
        var failure=assertThrows(UncheckedIOException.class,() -> runner.beginInspectOperation(Command.RESET));
        var operation=runner.failedInspectAdmission().orElseThrow();
        assertSame(failure,runner.inspectAdmissionFailure().orElseThrow()); assertEquals(1,callback.get());
        var cause=assertInstanceOf(FileSystemException.class,failure.getCause());
        assertEquals(operation.evidenceDirectory().resolve("operation.json"),Path.of(cause.getFile()));
        assertTrue(Files.isDirectory(operation.evidenceDirectory().resolve("operation.json")));
        var facts=host.captureN1Admission(operation);
        assertFalse(facts.ordinaryScopeIssued()); assertTrue(facts.currentScopeAbsent()); assertTrue(facts.cancelled());
        assertThrows(AssertionError.class,() -> runner.beginInspectOperation(Command.RUN));
        assertNull(runner.observeN1Acceptance(),"A filesystem test never authenticates the native N1 predecessor");
    }

    @Test void realForcedPostLinkFailureKeepsBeginAndExactPrimaryWithoutOrdinaryScopeOrRepair() throws Exception {
        properties("n1-post-link",() -> {
            var roots=roots(); Files.createDirectory(roots.evidence());
            var launchId=UUID.randomUUID(); var launch=Files.createDirectory(roots.evidence().resolve("launch-"+launchId));
            var session=UUID.randomUUID(); var id=UUID.randomUUID();
            var directory=Files.createDirectories(roots.evidence().resolve("two-lane-"+session).resolve("inspect-operations/op-3-"+id));
            var operation=new Operation(session,3,id,Command.RESET,directory); var linked=new AtomicInteger();
            var receipts=new ScenarioLaunchReceipt(launchId.toString(),launch.toString(),path -> linked.incrementAndGet(),roots.evidence());
            receipts.armN1PostLink(operation,UUID.randomUUID(),UUID.randomUUID());
            assertThrows(AssertionError.class,() -> receipts.armN1PostLink(operation,UUID.randomUUID(),UUID.randomUUID()));
            var now=new AtomicLong(7);
            var host=new InspectScenarioHost(now::get,() -> false,() -> false,Runnable::run,Runnable::run,null,receipts,Instant::now);
            var primary=assertThrows(IllegalStateException.class,() -> host.beginWork(operation));
            assertTrue(ScenarioLaunchReceipt.isN1PostLinkFailure(primary,operation));
            assertEquals("SIMULATED_N1_AFTER_BEGIN_LINK_BEFORE_RETURN",primary.getMessage()); assertEquals(1,linked.get());
            var begin=launch.resolve("inspect-op-3.begin.json"); var bytes=Files.readAllBytes(begin);
            assertEquals(operation.operationId().toString(),JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject().get("operationId").getAsString());
            var admission=host.captureN1Admission(operation); assertTrue(admission.cancelled()); assertTrue(admission.currentScopeAbsent()); assertFalse(admission.ordinaryScopeIssued());
            var cleanup=host.beginShutdownCleanup(operation,primary);
            assertEquals(7L,host.observeN1Issue().issuedNanos()); assertEquals(10_000_000_007L,cleanup.deadlineNanos());
            assertEquals(1,host.observeN1Issue().issues());
            assertThrows(IllegalStateException.class,() -> host.beginShutdownCleanup(operation,primary));
            host.settleCleanup(cleanup,new FinalOutcome(operation,Verdict.FAILURE,CleanupStatus.SETTLED));
            assertArrayEquals(bytes,Files.readAllBytes(begin));
            assertFalse(Files.exists(launch.resolve("inspect-op-3.cleanup.json"))); assertFalse(Files.exists(launch.resolve("inspect-op-3.end.json")));
            assertThrows(IllegalStateException.class,() -> host.beginWork(new Operation(session,4,UUID.randomUUID(),Command.CLOSE,directory)));
            try(var files=Files.list(launch)) { assertEquals(List.of(begin),files.toList()); }
            assertFalse(host.nativeN1Owner(),"Portable receipt/source queues supply no native binding");
        });
    }

    @Test void hookOrPreLinkFailureCannotBecomeTheTypedPostLinkTrigger() throws Exception {
        properties("n1-post-link",() -> {
            for(boolean existing:List.of(false,true)) {
                var root=Files.createTempDirectory(temporary,"receipt-"); var launchId=UUID.randomUUID();
                var launch=Files.createDirectory(root.resolve("launch-"+launchId)); var session=UUID.randomUUID(); var id=UUID.randomUUID();
                var op=new Operation(session,3,id,Command.RESET,Files.createDirectories(root.resolve("two-lane-"+session+"/inspect-operations/op-3-"+id)));
                var hookFailure=new IllegalStateException("existing afterLink failed");
                var receipt=new ScenarioLaunchReceipt(launchId.toString(),launch.toString(),path -> { throw hookFailure; },root);
                receipt.armN1PostLink(op,UUID.randomUUID(),UUID.randomUUID());
                if(existing) Files.writeString(launch.resolve("inspect-op-3.begin.json"),"immutable");
                var failure=assertThrows(RuntimeException.class,() -> receipt.operation(op,"WORK",Instant.EPOCH,Instant.EPOCH.plusSeconds(600),null,null));
                assertFalse(ScenarioLaunchReceipt.isN1PostLinkFailure(failure,op)); assertFalse(receipt.n1AfterLink(op));
                if(!existing) assertSame(hookFailure,failure); else assertEquals("immutable",Files.readString(launch.resolve("inspect-op-3.begin.json")));
            }
        });
    }

    @Test void nativeHostArmRefusesSyntheticOwnerWithoutGrantingOrPublishing() throws Exception {
        properties("n1-post-link",() -> {
            var h=host(new AtomicLong()); var operation=new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RESET,temporary);
            assertThrows(IllegalStateException.class,() -> h.armN1PostLink(operation,UUID.randomUUID(),UUID.randomUUID()));
            assertFalse(h.nativeN1Owner()); assertEquals(N1Issue.absent(),h.observeN1Issue());
        });
    }

    @Test void sharedOnceOwnerSeparatesRefusedWrapperAttemptsFromActualInvocationsAndSealsStoppingFacts() {
        var op=new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RESET,temporary); var scope=new Scope(op,Kind.CLEANUP,100);
        var owner=new ScenarioRunner.InspectEffects(); var denied=new IllegalStateException("guard refused");
        var result=owner.attempt(scope,() -> { throw new IllegalStateException("diagnostic read"); },() -> {
            owner.releaseGuard(N1Guard.REFUSED,null,null); throw denied;
        },() -> { owner.freezeGuard(N1Guard.REFUSED); throw denied; },() -> null);
        assertEquals(1,result.releaseAttempts()); assertEquals(1,result.freezeAttempts());
        assertEquals(0,result.n1Release().invocations()); assertEquals(0,result.n1Freeze().invocations());
        assertEquals(N1Guard.REFUSED,result.n1Release().guard()); assertSame(denied,result.releaseFailure());
        assertSame(result,owner.seal(scope,null));
        owner.releaseInvocation(); owner.freezeInvocation(); owner.releaseAfter(0,"NONE"); owner.freezeAfter(true);
        assertSame(result,owner.sealedN1(op));
        assertSame(result,owner.attempt(scope,() -> fail("sealed observation"),() -> fail("duplicate release"),() -> fail("duplicate freeze"),() -> null));
    }

    @Test void sharedStoppingJoinRetainsActualCountsWhenReadsAndActualCallsThrow() {
        var op=new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RESET,temporary); var scope=new Scope(op,Kind.CLEANUP,100);
        var owner=new ScenarioRunner.InspectEffects(); var releases=new AtomicInteger(); var freezes=new AtomicInteger();
        var first=owner.attempt(scope,() -> { throw new IllegalStateException("read failed"); },() -> {
            owner.releaseGuard(N1Guard.PASSED,1,"NATIVE"); owner.releaseInvocation(); releases.incrementAndGet(); throw new IllegalStateException("stop threw");
        },() -> { owner.freezeGuard(N1Guard.PASSED); owner.freezeInvocation(); freezes.incrementAndGet(); owner.freezeAfter(true); },() -> { throw new IllegalStateException("after read failed"); });
        assertSame(first,owner.seal(scope,null)); assertSame(first,owner.sealedN1(op));
        assertEquals(1,releases.get()); assertEquals(1,freezes.get()); assertEquals(1,first.n1Release().invocations());
        assertEquals(1,first.n1Freeze().invocations()); assertTrue(first.n1Freeze().watchSame()); assertNotNull(first.n1Release().error());
        assertNull(first.n1Release().afterKeys());
        assertEquals(2,ScenarioRunner.n1Increment(2)); assertEquals(2,ScenarioRunner.n1Increment(Integer.MAX_VALUE));
    }

    @Test void expiryAfterBeforeDisconnectLeavesActualTeardownCountZero() {
        var now=new AtomicLong(1); var nativeThread=new AtomicBoolean();
        var host=new InspectScenarioHost(now::get,nativeThread::get,() -> false,Runnable::run,Runnable::run,null);
        var operation=new Operation(UUID.randomUUID(),3,UUID.randomUUID(),Command.RESET,temporary);
        var cleanup=host.beginCleanup(host.beginWork(operation),null); var effects=new AtomicInteger();
        nativeThread.set(true);
        assertThrows(ScopeTimeoutException.class,() -> host.disconnectOwnedWorld(cleanup,() -> now.set(cleanup.deadlineNanos()),effects::incrementAndGet,effects::incrementAndGet));
        assertEquals(0,effects.get()); assertEquals(0,host.n1Disconnects());
    }

    @Test void realStopIntentDoesNoNewDiagnosticOutputRetainsFirstCutoffAndRepeatedIntentDoesNotRenew() throws Exception {
        var now=new AtomicLong(123); var host=host(now); var runner=new ScenarioRunner(host,new ScenarioObserver.Watch(),roots());
        var workers=new ArrayDeque<Runnable>(); var commands=new ScenarioCommands(workers::add,host,runner,now::get);
        var output=new CountingStream(); var previous=System.out; System.setOut(output);
        try {
            assertTrue(commands.requestStop()); assertEquals(1,workers.size()); assertNull(commands.observeN1Acceptance());
            now.set(10_000_000_122L); assertTrue(commands.requestStop()); assertEquals(1,workers.size());
            assertFalse(commands.releaseNativeStop()); assertEquals(0,output.calls.get());
            now.set(10_000_000_123L); assertTrue(commands.releaseNativeStop()); assertFalse(commands.requestStop());
            assertEquals(0,output.calls.get());
        } finally { System.setOut(previous); }
    }

    @Test void queuedStopCancelsExistingWorkAndWorkerStillSettlesWithoutInventingN1Evidence() throws Exception {
        var now=new AtomicLong(7); var host=host(now); var runner=new ScenarioRunner(host,new ScenarioObserver.Watch(),roots());
        var workers=new ArrayDeque<Runnable>(); var commands=new ScenarioCommands(workers::add,host,runner,now::get);
        assertEquals(1,commands.enqueue(Command.RESET,message -> {})); assertTrue(commands.requestStop()); assertEquals(1,workers.size());
        workers.remove().run(); assertFalse(commands.observeInspect().busy()); assertTrue(commands.releaseNativeStop());
        assertTrue(runner.failedInspectAdmission().isEmpty()); assertNull(commands.observeN1Acceptance());
    }

    @Test void reentrantStopUsesOriginalCutoffAndDoesNotWaitForContendedHostOrWriteDiagnostics() throws Exception {
        var now=new AtomicLong(41); var host=host(now); var runner=new ScenarioRunner(host,new ScenarioObserver.Watch(),roots());
        var commands=new AtomicReference<ScenarioCommands>(); var workers=new ArrayDeque<Runnable>(); var reentries=new AtomicInteger();
        commands.set(new ScenarioCommands(action -> {
            reentries.incrementAndGet(); assertTrue(commands.get().requestStop()); workers.add(action);
        },host,runner,now::get));
        // Read-only access to the existing monitor tests contention, never fabricates native or owner state.
        var field=InspectScenarioHost.class.getDeclaredField("lock"); field.setAccessible(true);
        var lock=(java.util.concurrent.locks.ReentrantLock)field.get(host);
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var holder=new Thread(() -> { lock.lock(); try { locked.countDown(); release.await(); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); } finally { lock.unlock(); } });
        holder.start(); assertTrue(locked.await(2,java.util.concurrent.TimeUnit.SECONDS));
        var output=new CountingStream(); var previous=System.out; System.setOut(output);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),() -> assertTrue(commands.get().requestStop()));
            assertEquals(1,reentries.get()); assertEquals(1,workers.size()); assertEquals(0,output.calls.get());
            now.set(10_000_000_040L); assertFalse(commands.get().releaseNativeStop());
            now.incrementAndGet(); assertTrue(commands.get().releaseNativeStop()); assertEquals(0,output.calls.get());
        } finally { System.setOut(previous); release.countDown(); holder.join(2000); }
        assertFalse(holder.isAlive());
    }

    private static final class CountingStream extends PrintStream {
        final AtomicInteger calls=new AtomicInteger(); CountingStream() { super(OutputStream.nullOutputStream()); }
        @Override public void write(byte[] b,int offset,int length) { calls.incrementAndGet(); }
        @Override public void flush() { calls.incrementAndGet(); }
        @Override public boolean checkError() { calls.incrementAndGet(); return false; }
    }

    /** Synthetic immutable wire values test predicates/bytes only; they never seed a Runner or native owner. */
    private record Wire(N1Admission admission,N1Intent intent,N1Final close) { }
    private Wire wire(long start) {
        var session=UUID.randomUUID(); var operation=UUID.randomUUID();
        var failure=new N1Failure("java.io.UncheckedIOException","header collision","java.nio.file.FileAlreadyExistsException","operation.json");
        var admission=new N1Admission("n1-header",session,operation,"C:\\source-fixture\\operation",start,3,1,1,
                new N1Trigger(1,true,true,false),new N1Host(false,true,true,2),
                new N1Prior(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),true,true,true,3,1),failure,true,true);
        var intent=new N1Intent("n1-header",session,operation,start+1,start+1+10_000_000_000L,true);
        var close=new N1Final(start+3,new N1Issue(operation,"CLEANUP",1,start+2,start+2+10_000_000_000L),3,1,1,true,true,
                new N1Release(1,N1Guard.PASSED,0,0,"NONE","NONE",null),new N1Freeze(1,N1Guard.PASSED,true,null),
                options(true,false,true,false,true,12,12,1,0,0),new N1Close(1,N1Guard.PASSED,N1Guard.PASSED,true,true,null),failure,true);
        return new Wire(admission,intent,close);
    }
    private static N1Options options(Boolean ownedBefore,Boolean ownedAfter,Boolean saved,Boolean before,Boolean after,
                                      int beforeDistance,int afterDistance,int auto,int view,int send) {
        return new N1Options(1,auto,view,send,N1Guard.PASSED,ownedBefore,ownedAfter,saved,12,before,after,beforeDistance,afterDistance,null);
    }
    private static N1Final withOptions(N1Final c,N1Options options) {
        return new N1Final(c.occurredNanos(),c.shutdown(),c.reservedOrdinal(),c.resetOrdinal(),c.countedStarts(),c.retainedLastSame(),
                c.primaryIdentityRetained(),c.release(),c.freeze(),options,c.close(),c.failure(),c.diagnosticsComplete());
    }
    private static N1Admission withFailure(N1Admission a,N1Failure failure,String directory) {
        return new N1Admission(a.selected(),a.sessionId(),a.operationId(),directory,a.occurredNanos(),a.reservedOrdinal(),a.resetOrdinal(),a.countedStarts(),
                a.trigger(),a.host(),a.prior(),failure,a.primaryIdentityRetained(),a.diagnosticsComplete());
    }
    private static String row(Wire w,int seq,long emitted,boolean checked) {
        return new String(ScenarioRunner.encodeN1Row(w.admission,w.intent,w.close,seq,emitted,checked),StandardCharsets.UTF_8);
    }
    private static com.google.gson.JsonObject json(String row) {
        assertTrue(row.startsWith("MAPART_N1_V1 ")); assertTrue(row.endsWith("\n"));
        assertEquals(1,row.chars().filter(c -> c=='\n').count()); return JsonParser.parseString(row.substring(13)).getAsJsonObject();
    }

    @Test void exactThreeSchemasHaveListedKeyOrderNullsAndSignedLongStrings() {
        var w=wire(9_007_199_254_740_993L);
        var first=List.of("schema","event","seq","case","sessionId","operationId","ordinal","command","occurredNanos","emittedNanos","diagnosticsComplete");
        var additions=List.of(
                List.of("operationDirectory","reservedOrdinal","resetOrdinal","countedStarts","trigger","host","prior","failure","primaryIdentityRetained"),
                List.of("workerIdleAtIntent","exitDeadlineNanos","primaryIdentityRetained"),
                List.of("intentNanos","exitDeadlineNanos","cleanupIssuedNanos","cleanupDeadlineNanos","shutdown","reservedOrdinal","resetOrdinal","countedStarts",
                        "retainedLastSame","primaryIdentityRetained","release","freeze","options","close","failure","priorRowsOutputChecked"));
        for(int seq=1;seq<=3;seq++) {
            var text=row(w,seq,Long.MAX_VALUE,seq==3); var parsed=json(text); var keys=new ArrayList<>(first); keys.addAll(additions.get(seq-1));
            assertEquals(keys,new ArrayList<>(parsed.keySet())); assertEquals(seq,parsed.get("seq").getAsInt());
            assertEquals(Long.toString(Long.MAX_VALUE),parsed.get("emittedNanos").getAsString());
            assertTrue(parsed.get("occurredNanos").getAsJsonPrimitive().isString());
            assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=8192);
        }
        var finalRow=json(row(w,3,w.close.occurredNanos()+1,true));
        var admission=json(row(w,1,w.close.occurredNanos()+1,false));
        assertEquals(List.of("attempts","collisionCreated","headerWriteEntered","afterLinkReached"),new ArrayList<>(admission.getAsJsonObject("trigger").keySet()));
        assertEquals(List.of("ordinaryScopeIssued","currentScopeAbsent","cancelled","lastOrdinal"),new ArrayList<>(admission.getAsJsonObject("host").keySet()));
        assertEquals(List.of("operationId","runId","summarySha256","closedFilesVerified","lastSame","watchSame","samples","witnesses"),new ArrayList<>(admission.getAsJsonObject("prior").keySet()));
        assertEquals(List.of("className","message","causeClassName","causeMessage"),new ArrayList<>(admission.getAsJsonObject("failure").keySet()));
        assertEquals(List.of("operationId","kind","issues"),new ArrayList<>(finalRow.getAsJsonObject("shutdown").keySet()));
        assertEquals(List.of("invocations","guard","beforeKeys","afterKeys","beforeOwner","afterOwner","error"),new ArrayList<>(finalRow.getAsJsonObject("release").keySet()));
        assertEquals(List.of("invocations","guard","watchSame","error"),new ArrayList<>(finalRow.getAsJsonObject("freeze").keySet()));
        assertEquals(List.of("helperInvocations","autoJumpSetCalls","viewDistanceSetCalls","settingsSendCalls","guard","ownedBefore","ownedAfter","savedAutoJump","savedViewDistance",
                "beforeAutoJump","afterAutoJump","beforeViewDistance","afterViewDistance","error"),new ArrayList<>(finalRow.getAsJsonObject("options").keySet()));
        assertEquals(List.of("invocations","guard","serverCheckpoint","returned","worldCleared","error"),new ArrayList<>(finalRow.getAsJsonObject("close").keySet()));
        assertTrue(finalRow.getAsJsonObject("release").get("error").isJsonNull());
        assertEquals("9007199254740993",json(row(w,1,w.close.occurredNanos()+1,false)).get("occurredNanos").getAsString());
    }

    @Test void encoderPreservesNullFailureMessagesAndRejectsHalfPopulatedCause() {
        var w=wire(1); var a=withFailure(w.admission,new N1Failure("java.lang.AssertionError",null,null,null),w.admission.operationDirectory());
        var failure=json(new String(ScenarioRunner.encodeN1Row(a,w.intent,w.close,1,9,false),StandardCharsets.UTF_8)).getAsJsonObject("failure");
        assertTrue(failure.get("message").isJsonNull()); assertTrue(failure.get("causeClassName").isJsonNull()); assertTrue(failure.get("causeMessage").isJsonNull());
        var invalid=withFailure(a,new N1Failure("java.lang.Error",null,null,"unexpected"),a.operationDirectory());
        assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(invalid,w.intent,w.close,1,9,false));
    }

    @Test void stageNullsStayNullAndCannotBecomePositiveCleanup() {
        var w=wire(1); var c=w.close;
        var absent=new N1Final(4,N1Issue.absent(),3,1,1,true,true,new N1Release(0,N1Guard.UNOBSERVED,null,null,null,null,null),
                new N1Freeze(0,N1Guard.UNOBSERVED,null,null),new N1Options(0,0,0,0,N1Guard.UNOBSERVED,null,null,null,null,null,null,null,null,null),
                new N1Close(0,N1Guard.UNOBSERVED,N1Guard.UNOBSERVED,false,false,null),c.failure(),false);
        var parsed=json(new String(ScenarioRunner.encodeN1Row(w.admission,w.intent,absent,3,8,false),StandardCharsets.UTF_8));
        assertTrue(parsed.get("cleanupIssuedNanos").isJsonNull()); assertTrue(parsed.get("cleanupDeadlineNanos").isJsonNull());
        assertTrue(parsed.getAsJsonObject("shutdown").get("operationId").isJsonNull()); assertEquals(0,parsed.getAsJsonObject("shutdown").get("issues").getAsInt());
        assertTrue(parsed.getAsJsonObject("release").get("afterKeys").isJsonNull()); assertFalse(parsed.get("diagnosticsComplete").getAsBoolean());
    }

    @Test void utf8CapsApplyBeforeEscapingAndInvalidSurrogatesNeverGetReplaced() {
        var w=wire(1); var message="é".repeat(256);
        var a=withFailure(w.admission,new N1Failure("java.lang.Error",message,null,null),w.admission.operationDirectory());
        assertEquals(message,json(new String(ScenarioRunner.encodeN1Row(a,w.intent,w.close,1,9,false),StandardCharsets.UTF_8)).getAsJsonObject("failure").get("message").getAsString());
        for(String invalid:List.of("é".repeat(257),"x".repeat(513),"\uD800","\uDC00","a\uD800b")) {
            var bad=withFailure(a,new N1Failure("java.lang.Error",invalid,null,null),a.operationDirectory());
            assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(bad,w.intent,w.close,1,9,false));
        }
        for(var invalid:List.of(new N1Failure("x".repeat(257),null,null,null),new N1Failure("java.lang.Error",null,"x".repeat(257),null),
                new N1Failure("java.lang.Error",null,"java.lang.Error","x".repeat(513)))) {
            var bad=withFailure(a,invalid,a.operationDirectory()); assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(bad,w.intent,w.close,1,9,false));
        }
        var path=withFailure(a,a.failure(),"x".repeat(1025)); assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(path,w.intent,w.close,1,9,false));
    }

    @Test void quotesBackslashesAndAllControlsAreEscapedWithoutPhysicalLineBreaks() {
        var w=wire(1); var message="quote\" slash\\ newline\n carriage\r tab\t zero\0 emoji😀";
        var a=withFailure(w.admission,new N1Failure("java.lang.Error",message,null,null),w.admission.operationDirectory());
        var encoded=new String(ScenarioRunner.encodeN1Row(a,w.intent,w.close,1,9,false),StandardCharsets.UTF_8);
        assertTrue(encoded.contains("\\n")); assertTrue(encoded.contains("\\r")); assertTrue(encoded.contains("\\u0000"));
        assertEquals(message,json(encoded).getAsJsonObject("failure").get("message").getAsString());
    }

    @Test void escapedWholeRowOverflowRejectsEntireSetBeforeAnyOutput() {
        var w=wire(1); var longFailure=new N1Failure("java.lang.Error","\0".repeat(512),"java.lang.Error","\0".repeat(512));
        var c=w.close; var bad=new N1Final(c.occurredNanos(),c.shutdown(),3,1,1,true,true,
                new N1Release(1,N1Guard.PASSED,0,0,"NONE","NONE",longFailure),new N1Freeze(1,N1Guard.PASSED,true,longFailure),
                c.options(),new N1Close(1,N1Guard.PASSED,N1Guard.PASSED,true,true,longFailure),longFailure,true);
        var output=new ByteArrayOutputStream(); var errors=new ByteArrayOutputStream();
        assertFalse(ScenarioRunner.emitN1Rows(w.admission,w.intent,bad,new PrintStream(output),new PrintStream(errors),() -> 9));
        assertEquals(0,output.size()); assertEquals("N1_PROOF_OUTPUT_FAILED",errors.toString(StandardCharsets.UTF_8).strip());
    }

    @Test void outputChecksStickyPrintStreamErrorsAndDoesNotRetryOrEmitFinalAfterPriorFailure() {
        var w=wire(1); var writes=new AtomicInteger(); var errors=new ByteArrayOutputStream();
        var failing=new PrintStream(new OutputStream() {
            @Override public void write(int value) throws IOException { writes.incrementAndGet(); throw new IOException("nonthrowing stream error"); }
        });
        assertFalse(ScenarioRunner.emitN1Rows(w.admission,w.intent,w.close,failing,new PrintStream(errors),() -> 9));
        assertTrue(failing.checkError()); assertEquals(2,writes.get()); assertEquals("N1_PROOF_OUTPUT_FAILED",errors.toString(StandardCharsets.UTF_8).strip());
    }

    @Test void finalRowAttestsOnlyPreviouslyCheckedRowsAndFinalErrorIsRetainedLocally() {
        var w=wire(1); var output=new ByteArrayOutputStream(); var errors=new ByteArrayOutputStream(); var writes=new AtomicInteger();
        var failingFinal=new PrintStream(new OutputStream() {
            @Override public void write(int b) { output.write(b); }
            @Override public void write(byte[] b,int off,int len) throws IOException {
                if(writes.incrementAndGet()==3) throw new IOException("final lost"); output.write(b,off,len);
            }
        });
        assertFalse(ScenarioRunner.emitN1Rows(w.admission,w.intent,w.close,failingFinal,new PrintStream(errors),() -> 9));
        assertEquals(3,writes.get()); assertEquals(2,output.toString(StandardCharsets.UTF_8).lines().count()); assertTrue(failingFinal.checkError());
        var good=new ByteArrayOutputStream();
        assertTrue(ScenarioRunner.emitN1Rows(w.admission,w.intent,w.close,new PrintStream(good),new PrintStream(errors),() -> 9));
        var rows=good.toString(StandardCharsets.UTF_8).lines().toList(); assertEquals(3,rows.size());
        assertTrue(json(rows.get(2)+"\n").get("priorRowsOutputChecked").getAsBoolean());
        assertFalse(json(rows.get(2)+"\n").has("finalOutputChecked"));
    }

    @Test void exactSignedLongAndNanoTimeWrapUseElapsedArithmeticAndOriginalDeadlines() {
        for(long start:new long[]{9_007_199_254_740_993L,Long.MAX_VALUE-2,Long.MIN_VALUE}) {
            var w=wire(start); assertTrue(ScenarioRunner.n1Positive(w.admission,w.intent,w.close,start+4));
            assertEquals(Long.toString(start),json(row(w,1,start+4,false)).get("occurredNanos").getAsString());
            var changed=new N1Intent(w.intent.selected(),w.intent.sessionId(),w.intent.operationId(),w.intent.intentNanos(),w.intent.deadlineNanos()+1,true);
            assertFalse(ScenarioRunner.n1Positive(w.admission,changed,w.close,start+4));
        }
        assertTrue(ScenarioRunner.n1Elapsed(Long.MAX_VALUE,Long.MIN_VALUE));
        assertFalse(ScenarioRunner.n1Elapsed(10,9)); assertFalse(ScenarioRunner.n1Elapsed(0,3_800_000_000_001L));
    }

    @Test void observedOwnedRestorationAndUnownedNoopRejectMissingMismatchAndAnyExtraSetter() {
        assertTrue(ScenarioRunner.n1OptionsPositive(options(true,false,true,false,true,12,12,1,0,0)));
        assertTrue(ScenarioRunner.n1OptionsPositive(options(false,false,true,false,false,12,12,0,0,0)));
        for(var invalid:List.of(
                options(null,false,true,false,true,12,12,1,0,0),options(true,true,true,false,true,12,12,1,0,0),
                options(true,false,true,false,false,12,12,1,0,0),options(true,false,true,false,true,12,13,1,0,0),
                options(true,false,true,false,true,12,12,1,1,0),options(true,false,true,false,true,12,12,1,0,1),
                options(false,false,true,false,true,12,12,0,0,0),options(false,false,true,false,false,12,13,0,0,0),
                options(false,false,true,false,false,12,12,1,0,0),options(false,true,true,false,false,12,12,0,0,0))) {
            assertFalse(ScenarioRunner.n1OptionsPositive(invalid));
            var w=wire(1); assertFalse(ScenarioRunner.n1Positive(w.admission,w.intent,withOptions(w.close,invalid),9));
        }
    }

    @Test void wireAndPositivePredicateRejectDuplicateCountsRefusedGuardsAndIdentityMismatch() {
        var w=wire(1); var c=w.close;
        for(var release:List.of(new N1Release(0,N1Guard.REFUSED,null,null,null,null,null),
                new N1Release(2,N1Guard.PASSED,0,0,"NONE","NONE",null),new N1Release(1,N1Guard.PASSED,null,0,null,"NONE",null))) {
            var invalid=new N1Final(c.occurredNanos(),c.shutdown(),3,1,1,true,true,release,c.freeze(),c.options(),c.close(),c.failure(),true);
            assertFalse(ScenarioRunner.n1Positive(w.admission,w.intent,invalid,9));
        }
        var mismatch=new N1Intent(w.intent.selected(),UUID.randomUUID(),w.intent.operationId(),2,10_000_000_002L,true);
        assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(w.admission,mismatch,c,1,9,false));
        var half=new N1Final(c.occurredNanos(),new N1Issue(c.shutdown().operationId(),"CLEANUP",1,null,10L),3,1,1,true,true,
                c.release(),c.freeze(),c.options(),c.close(),c.failure(),true);
        assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(w.admission,w.intent,half,3,9,false));
        assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(w.admission,w.intent,c,4,9,false));
        var missing=new N1Options(1,1,0,0,N1Guard.PASSED,true,false,true,12,null,true,12,12,null);
        assertFalse(ScenarioRunner.n1OptionsPositive(missing));
        assertFalse(ScenarioRunner.n1OptionsPositive(new N1Options(0,0,0,0,N1Guard.REFUSED,null,null,null,null,null,null,null,null,null)));
        var negative=new N1Admission(w.admission.selected(),w.admission.sessionId(),w.admission.operationId(),w.admission.operationDirectory(),1,-1,1,1,
                w.admission.trigger(),w.admission.host(),w.admission.prior(),w.admission.failure(),true,true);
        assertThrows(AssertionError.class,() -> ScenarioRunner.encodeN1Row(negative,w.intent,c,1,9,false));
    }

    private ScenarioRunner.Roots roots() throws IOException {
        var module=Files.createTempDirectory(temporary,"module-"); var build=Files.createDirectory(module.resolve("build"));
        var game=Files.createDirectories(build.resolve("run/inspect"));
        return new ScenarioRunner.Roots(module,game,build,build.resolve("scenario-results"),game.resolve("saves"));
    }
    private static InspectScenarioHost host(AtomicLong now) {
        return new InspectScenarioHost(now::get,() -> false,() -> false,Runnable::run,Runnable::run,null);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void properties(String selected,Checked action) throws Exception {
        var names=List.of("mapart.test.inspect","mapart.test.inspect.acceptance","fabric.client.gametest","mapart.test.inspect.acceptance.case");
        var old=new HashMap<String,String>(); names.forEach(name -> old.put(name,System.getProperty(name)));
        try {
            System.setProperty(names.get(0),"true"); System.setProperty(names.get(1),"true"); System.clearProperty(names.get(2));
            System.setProperty(names.get(3),selected); action.run();
        } finally { names.forEach(name -> { if(old.get(name)==null) System.clearProperty(name); else System.setProperty(name,old.get(name)); }); }
    }
}
