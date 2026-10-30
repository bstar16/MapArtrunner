package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static com.mapartrunner.core.placement.PlacementObservation.Evidence.*;
import static com.mapartrunner.fabric.scenario.TraversalCapture.*;
import static org.junit.jupiter.api.Assertions.*;

class TraversalCaptureTest {
    @TempDir Path directory;
    private final UUID run = UUID.randomUUID();
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());

    @Test void retainsMoreThan6000EventsWithoutTargetVectors() throws Exception {
        var capture = capture("long", 6001, 0, 1);
        var original = frame(0, 0, "END");
        capture.append(original);
        assertSame(original, capture.latestEnd().orElseThrow());
        assertTrue(capture.isLatestEnd(original));
        assertEquals(original,frame(0,0,"END"));
        assertFalse(capture.isLatestEnd(frame(0, 0, "END")));
        for (int i=1; i<12002; i++) {
            capture.append(frame(i, i/2, "START"));
            assertSame(original, capture.latestEnd().orElseThrow());
        }
        capture.append(server(12002, 12001));
        assertTrue(capture.isLatestEnd(original));
        var next = frame(12003, 6001, "END"); capture.append(next);
        assertFalse(capture.isLatestEnd(original)); assertTrue(capture.isLatestEnd(next));
        var sealed = capture.seal();
        assertTrue(sealed.complete()); assertEquals(12004, sealed.eventCount());
        assertEquals(Files.size(sealed.events()), sealed.eventBytes());
        assertEquals(hash(sealed.events()), sealed.eventHash());
        System.out.println("long events="+sealed.eventCount()+"; bytes="+sealed.eventBytes()+"; sha256="+sealed.eventHash());
        try (var reader=sealed.openEvents()) {
            for (long ordinal=0; ordinal<12004; ordinal++) {
                var event=reader.next().orElseThrow(); assertEquals(ordinal,event.ordinal());
                if (event instanceof MotionFrame m) {
                    assertEquals(run,m.runId()); assertEquals(912,m.effect().actionId());
                    assertEquals(84,m.effect().targetId()); assertEquals(1234,m.effect().tick());
                    assertEquals(frame(0,0,"END").pose(),m.pose());
                    assertEquals(frame(0,0,"END").identities(),m.identities());
                    assertEquals(frame(0,0,"END").traversal(),m.traversal());
                    assertEquals(51,m.predictionSequence()); assertEquals(4.5,m.pose().interactionReach());
                    assertTrue(m.pose().physics().onGround()); assertEquals(.08,m.pose().physics().gravity());
                    assertNull(m.fault0()); assertNull(m.fault1());
                } else {
                    assertEquals(12001,((ServerFrame)event).followsClientOrdinal());
                    assertEquals(4.5,((ServerFrame)event).interactionReach());
                }
            }
            assertTrue(reader.next().isEmpty());
        }
        assertThrows(IllegalStateException.class,capture::latestEnd);
        for (var field:MotionFrame.class.getRecordComponents()) {
            assertFalse(Collection.class.isAssignableFrom(field.getType()),field.getName());
            assertFalse(field.getType().getName().matches(".*(Snapshot|Lane|ExpectedFloor)$"),field.getName());
        }
    }

    @Test void finiteLimitsFailRatherThanDrop() throws Exception {
        assertThrows(IllegalArgumentException.class,()->Limits.forRun(-1,0,0,0));
        var huge=Limits.forRun(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        assertTrue(huge.eventLimit()>Integer.MAX_VALUE);
        var capture=capture("overflow",0,0,1);
        for(int i=0;i<16;i++) capture.append(frame(i,i,"START"));
        var first=assertThrows(IllegalStateException.class,()->capture.append(frame(16,16,"END")));
        assertSame(first,capture.failure().orElseThrow());
        assertThrows(IllegalStateException.class,capture::latestEnd);
        assertThrows(IllegalStateException.class,()->capture.append(frame(17,17,"END")));
        assertThrows(IllegalStateException.class,capture::requireSealedForClear);
        var sealed=capture.seal(); assertFalse(sealed.complete()); assertEquals(16,sealed.eventCount());
        try(var reader=sealed.openEvents()) { for(int i=0;i<16;i++) assertEquals(i,reader.next().orElseThrow().ordinal()); assertTrue(reader.next().isEmpty()); }
        var large=new TargetFact(run,0,null,0,OBSERVED,new BlockSpec("minecraft:stone",Map.of("label","雪".repeat(900))));
        // A declared fault may have a large actual state; the byte cap must reject it whole.
        var declared=new TraversalCapture(directory.resolve("declared"),run,Limits.forRun(2,0,1,1),Set.of(0));
        var m=frame(0,0,"END");
        assertThrows(IllegalStateException.class,()->declared.append(withFault(m,large)));
        assertFalse(declared.seal().complete()); assertEquals(0,Files.size(directory.resolve("declared/events.jsonl")));
        var malformed=new TraversalCapture(directory.resolve("malformed-utf8"),run,Limits.forRun(2,0,1,1),Set.of(0));
        var invalid=new TargetFact(run,0,null,0,OBSERVED,new BlockSpec("minecraft:stone",Map.of("label","\ud800")));
        assertThrows(IllegalStateException.class,()->malformed.append(withFault(m,invalid)));
        assertFalse(malformed.seal().complete());
        var io=capture("io",1,0,1); io.append(frame(0,0,"END"));
        Files.createDirectory(directory.resolve("io/INITIAL.jsonl"));
        assertThrows(IllegalStateException.class,()->io.checkpoint(CheckpointKind.INITIAL,scan(0,0,"END",1)));
        var cause=io.failure().orElseThrow(); var incomplete=io.seal();
        assertFalse(incomplete.complete()); assertSame(cause,io.failure().orElseThrow()); assertEquals(1,incomplete.eventCount());
        assertFalse(incomplete.checkpoints().containsKey(CheckpointKind.INITIAL));
        assertTrue(incomplete.outputs().receipts().stream().noneMatch(r->r.path().equals(directory.resolve("io/INITIAL.jsonl"))));
        var collision=capture("file-collision",1,0,1); collision.append(frame(0,0,"INITIAL"));
        Path unowned=directory.resolve("file-collision/INITIAL.jsonl"); Files.writeString(unowned,"pre-existing foreign bytes");
        assertThrows(IllegalStateException.class,()->collision.checkpoint(CheckpointKind.INITIAL,scan(0,0,"INITIAL",1)));
        var refused=collision.seal(); assertFalse(refused.checkpoints().containsKey(CheckpointKind.INITIAL));
        assertEquals("pre-existing foreign bytes",Files.readString(unowned));
    }

    @Test void fiveCheckpointsAreOriginalAndBounded() throws Exception {
        var capture=capture("scans",20,1,16384);
        int i=0;
        for(var kind:CheckpointKind.values()) {
            String phase=kind==CheckpointKind.INITIAL?"INITIAL":"END";
            capture.append(frame(i,i,phase));
            var scan=scan(i,i,phase,kind==CheckpointKind.TERMINAL?16384:1);
            capture.checkpoint(kind,scan); i++;
        }
        var sealed=capture.seal(); assertTrue(sealed.complete()); assertEquals(5,sealed.checkpoints().size());
        for(var metadata:sealed.checkpoints().values()) {
            assertTrue(metadata.complete()); assertEquals(run,metadata.runId());
            System.out.println("checkpoint="+metadata.kind()+"; tick="+metadata.tick()+"; event="+metadata.eventOrdinal()+"; count="+metadata.count()+"; bytes="+metadata.bytes()+"; sha256="+metadata.hash());
            assertEquals(Files.size(metadata.path()),metadata.bytes()); assertEquals(hash(metadata.path()),metadata.hash());
            assertTrue(metadata.bytes()<=Limits.forRun(20,1,16384,1).scanByteLimit());
            var actual=sealed.readCheckpoint(metadata.kind());
            assertEquals(metadata.tick(),actual.tick()); assertEquals(metadata.tick(),actual.eventOrdinal());
            assertEquals(metadata.kind()==CheckpointKind.INITIAL?"INITIAL":"END",actual.phase()); assertEquals(run,actual.runId());
        }
        var full=sealed.readCheckpoint(CheckpointKind.TERMINAL);
        requireFullMatch(plan(16384),full,run,full.tick());
        assertEquals(16384,full.observations().size());
        var duplicate=capture("duplicate",2,0,1);
        duplicate.append(frame(0,0,"INITIAL"));
        duplicate.checkpoint(CheckpointKind.INITIAL,scan(0,0,"INITIAL",1));
        assertThrows(IllegalStateException.class,()->duplicate.checkpoint(CheckpointKind.INITIAL,scan(1,0,"INITIAL",1)));
        assertFalse(duplicate.seal().complete());
        var bounded=capture("bounded-scan",2,0,1);
        bounded.append(frame(0,0,"INITIAL"));
        var huge=new PlacementObservation(run,0,OptionalLong.empty(),0,OBSERVED,
                Optional.of(new BlockSpec("minecraft:stone",Map.of("label","x".repeat(6000)))));
        assertThrows(IllegalStateException.class,()->bounded.checkpoint(CheckpointKind.INITIAL,new FullScan(run,0,"INITIAL",0,List.of(huge))));
        var boundedSeal=bounded.seal(); assertFalse(boundedSeal.complete());
        var prefix=boundedSeal.checkpoints().get(CheckpointKind.INITIAL); assertNotNull(prefix);
        assertFalse(prefix.complete()); assertEquals(run,prefix.runId());
        assertEquals(directory.resolve("bounded-scan/INITIAL.jsonl"),prefix.path());
        assertEquals(0,prefix.tick()); assertEquals("INITIAL",prefix.phase()); assertEquals(0,prefix.eventOrdinal());
        assertEquals(Files.size(prefix.path()),prefix.bytes()); assertEquals(hash(prefix.path()),prefix.hash());
        assertTrue(prefix.bytes()<=5120);
        String rawMetadata=Files.readString(boundedSeal.metadata());
        assertTrue(rawMetadata.contains(prefix.path().getFileName().toString())); assertTrue(rawMetadata.contains(prefix.hash()));
        assertTrue(rawMetadata.contains(run+"\",false]"));
        System.out.println("failed checkpoint prefix bytes="+prefix.bytes()+"; sha256="+prefix.hash()+"; run="+prefix.runId()+"; tick="+prefix.tick()+"; phase="+prefix.phase()+"; ordinal="+prefix.eventOrdinal()+"; complete="+prefix.complete());
        assertThrows(IOException.class,()->boundedSeal.readCheckpoint(CheckpointKind.INITIAL));
        assertThrows(IllegalStateException.class,bounded::requireSealedForClear);
        var stale=capture("stale-checkpoint",2,0,1);
        stale.append(frame(0,3,"END"));
        assertThrows(IllegalStateException.class,()->stale.checkpoint(CheckpointKind.TERMINAL,scan(4,0,"TEST",1)));
        assertFalse(stale.seal().complete());
    }

    @Test void sealPrecedesClear() throws Exception {
        var capture=capture("seal",2,0,1);
        assertThrows(IllegalStateException.class,capture::requireSealedForClear);
        capture.append(frame(0,0,"END"));
        var sealed=capture.seal(); capture.requireSealedForClear(); assertSame(sealed,capture.seal());
        assertThrows(IllegalStateException.class,()->capture.append(frame(1,1,"START")));
        assertThrows(IllegalStateException.class,()->capture.checkpoint(CheckpointKind.FAILURE,scan(1,1,"FAILURE",1)));
        assertThrows(IllegalStateException.class,()->capture.isLatestEnd(frame(0,0,"END")));
        capture.close(); assertEquals(sealed,capture.seal());
        var abandoned=capture("close",2,0,1); abandoned.close(); assertThrows(IllegalStateException.class,abandoned::requireSealedForClear);
        assertFalse(abandoned.seal().complete());
    }

    @Test void fullFinalScanRejectsMissingDuplicateStalePredictedForeignAndWrongProperty() {
        var plan=plan(2); var valid=scan(9,7,"END",2);
        requireFullMatch(plan,valid,run,9);
        assertThrows(IllegalStateException.class,()->requireFullMatch(plan,new FullScan(run,9,"END",7,valid.observations().subList(0,1)),run,9));
        assertThrows(IllegalStateException.class,()->requireFullMatch(plan,new FullScan(run,9,"END",7,List.of(valid.observations().getFirst(),valid.observations().getFirst())),run,9));
        assertThrows(IllegalStateException.class,()->requireFullMatch(plan,valid,run,10));
        for(var evidence:List.of(UNKNOWN,PREDICTED,OBSERVED)) {
            for(int fault=0;fault<4;fault++) {
                var old=valid.observations().getFirst();
                var changed=new PlacementObservation(fault==0?UUID.randomUUID():run,fault==1?99:0,OptionalLong.of(998),fault==2?8:9,
                        evidence,evidence==UNKNOWN?Optional.empty():Optional.of(fault==3?new BlockSpec(STONE.id(),Map.of("axis","x")):STONE));
                var observations=List.of(changed,valid.observations().get(1));
                assertThrows(IllegalStateException.class,()->requireFullMatch(plan,new FullScan(run,9,"END",7,observations),run,9));
            }
        }
    }

    @Test void declaredFaultsRemainExactAndClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new TraversalCapture(directory.resolve("too-many"),run,Limits.forRun(2,0,3,1),Set.of(0,1,2)));
        var capture=new TraversalCapture(directory.resolve("faults"),run,Limits.forRun(2,0,2,1),Set.of(0,1));
        capture.validatePlan(plan(2));
        var first=new TargetFact(run,0,null,0,UNKNOWN,null);
        var second=new TargetFact(run,1,998L,0,OBSERVED,new BlockSpec("minecraft:oak_log",Map.of("axis","x")));
        var m=frame(0,0,"END");
        capture.append(new MotionFrame(m.ordinal(),m.tick(),m.phase(),m.runId(),m.identities(),m.pose(),m.keys(),m.paused(),m.owner(),m.lifecycle(),m.activity(),m.outcome(),m.reason(),m.eligible(),m.verify(),m.traversal(),m.pending(),m.unfinished(),m.effect(),m.predictionSequence(),first,second));
        capture.append(server(1,0));
        try(var reader=capture.seal().openEvents()) {
            var actual=(MotionFrame)reader.next().orElseThrow();
            assertEquals(first,actual.fault0()); assertEquals(second,actual.fault1());
            assertEquals(second.observation(),actual.fault1().observation());
            assertEquals(0,((ServerFrame)reader.next().orElseThrow()).followsClientOrdinal());
        }
        var undeclared=capture("undeclared",2,0,2);
        assertThrows(IllegalStateException.class,()->undeclared.append(withFault(m,first)));
        assertFalse(undeclared.seal().complete());
        var wrongPlan=new TraversalCapture(directory.resolve("wrong-plan"),run,Limits.forRun(2,0,1,1),Set.of(1));
        assertThrows(IllegalStateException.class,()->wrongPlan.validatePlan(plan(1)));
        assertFalse(wrongPlan.seal().complete());
    }

    @Test void preStartOutputBudgetIsAdoptedWithoutRenewalAndPostSealStaysSeparate() throws Exception {
        var limits=Limits.forRun(4,1,1,0);
        var budget=new OutputBudget(directory.resolve("owned-output"),limits);
        try(var output=budget.open(OutputKind.REGION,"readiness.txt")) { output.write("雪".repeat(1000).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        assertEquals(3000,budget.regionBytes());
        var capture=new TraversalCapture(directory.resolve("run"),run,limits,Set.of(),budget);
        capture.append(frame(0,0,"END"));
        try(var output=budget.open(OutputKind.REGION,"final.txt")) { output.write(new byte[12000]); }
        var sealed=capture.seal(); assertTrue(sealed.complete()); assertEquals(15000,sealed.outputs().regionBytes());
        var snapshot=sealed.outputs().receipts(); int originalCount=snapshot.size();
        assertThrows(UnsupportedOperationException.class,()->snapshot.clear());
        var originalHash=hash(sealed.outputs().manifest()); long scalar=budget.scalarBytes();
        try(var output=budget.openPostSealFailure("later-failure.txt")) { output.write("actual later TEST read follows END0".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        assertEquals(originalCount,sealed.outputs().receipts().size()); assertEquals(originalHash,hash(sealed.outputs().manifest()));
        assertTrue(budget.scalarBytes()>scalar); assertEquals(originalCount+1,budget.receipts().size());
        long actual=0; for(var file:budget.ownedFiles()) actual+=Files.size(file);
        assertEquals(actual,budget.scalarBytes()+budget.regionBytes());
        assertThrows(IllegalStateException.class,()->budget.open(OutputKind.REGION,"late-region.txt"));
        assertThrows(IllegalStateException.class,()->new TraversalCapture(directory.resolve("second-run"),UUID.randomUUID(),limits,Set.of(),budget));
        for(var receipt:budget.receipts()) { assertEquals(Files.size(receipt.path()),receipt.bytes()); assertEquals(hash(receipt.path()),receipt.hash()); }
        budget.close();
    }

    @Test void sharedOutputCapAndOpenFilesPreventSuccessfulSealAndClear() throws Exception {
        var limits=Limits.forRun(2,0,1,0); var budget=new OutputBudget(directory.resolve("bounded-output"),limits);
        try(var output=budget.open(OutputKind.REGION,"before-run.txt")) { output.write(new byte[3000]); }
        var capture=new TraversalCapture(directory.resolve("bounded-run"),run,limits,Set.of(),budget);
        try(var output=budget.open(OutputKind.REGION,"overflow.txt")) {
            output.write("雪".repeat(4444).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class,()->output.write("雪".repeat(18).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        var first=budget.failure().orElseThrow(); assertSame(first,capture.failure().orElseThrow());
        assertEquals(13332,Files.size(budget.root().resolve("overflow.txt")));
        assertFalse(capture.seal().complete()); assertSame(first,capture.failure().orElseThrow());
        assertThrows(IllegalStateException.class,capture::requireSealedForClear);
        var openBudget=new OutputBudget(directory.resolve("open-output"),limits);
        var openCapture=new TraversalCapture(directory.resolve("open-run"),run,limits,Set.of(),openBudget);
        openBudget.open(OutputKind.SCALAR,"never-closed.txt").write(1);
        assertFalse(openCapture.seal().complete()); assertThrows(IllegalStateException.class,openCapture::requireSealedForClear);
        var escape=new OutputBudget(directory.resolve("no-escape"),limits);
        assertThrows(IllegalArgumentException.class,()->escape.open(OutputKind.SCALAR,"../foreign.txt")); escape.close();
    }

    @Test void zeroByteFileReceiptsConsumeTheSameScalarAllowance() throws Exception {
        var budget=new OutputBudget(directory.resolve("receipt-cap"),Limits.forRun(1,0,0,0));
        try(var header=budget.open(OutputKind.SCALAR,"header.txt")) { header.write(new byte[1048576-160]); }
        assertEquals(1,budget.receipts().size());
        var empty=budget.open(OutputKind.SCALAR,"empty.txt");
        assertThrows(IOException.class,empty::close);
        assertEquals(1,budget.receipts().size()); assertTrue(budget.scalarBytes()<=1048576);
        assertFalse(budget.seal().complete());
        assertThrows(IllegalStateException.class,()->budget.openPostSealFailure("cannot-create.txt"));
        assertFalse(Files.exists(budget.root().resolve("cannot-create.txt")));
    }

    @Test void constructionFailureClosesTheAdoptedBudgetWithoutLosingCause() throws Exception {
        var limits=Limits.forRun(1,0,1,0); var budget=new OutputBudget(directory.resolve("constructor-output"),limits);
        var runDirectory=directory.resolve("constructor-run"); Files.createDirectories(runDirectory.resolve("events.jsonl"));
        var first=assertThrows(IOException.class,()->new TraversalCapture(runDirectory,run,limits,Set.of(),budget));
        assertSame(first,budget.failure().orElseThrow()); assertFalse(budget.seal().complete());
    }

    @Test void completeReaderProvesEofWhileIncompleteReaderExposesOnlyCommittedPrefix() throws Exception {
        var capture=capture("reader-eof",2,0,1);
        capture.append(frame(0,0,"START")); capture.append(frame(1,0,"END")); var sealed=capture.seal();
        for(long count:List.of(0L,1L)) {
            var underdeclared=new Sealed(sealed.events(),count,sealed.eventBytes(),sealed.eventHash(),sealed.checkpoints(),sealed.metadata(),sealed.outputs(),true,null);
            try(var reader=underdeclared.openEvents()) {
                for(long i=0;i<count;i++) assertEquals(i,reader.next().orElseThrow().ordinal());
                assertThrows(IOException.class,reader::next);
            }
        }
        try(var reader=sealed.openEvents()) {
            assertEquals(0,reader.next().orElseThrow().ordinal()); assertEquals(1,reader.next().orElseThrow().ordinal()); assertTrue(reader.next().isEmpty());
        }
        Path partial=directory.resolve("explicit-incomplete-prefix.jsonl"); Files.copy(sealed.events(),partial);
        Files.writeString(partial,"{partial suffix",StandardOpenOption.APPEND);
        var incomplete=new Sealed(partial,2,Files.size(partial),hash(partial),sealed.checkpoints(),sealed.metadata(),sealed.outputs(),false,"partial native write");
        try(var reader=incomplete.openEvents()) {
            assertEquals(0,reader.next().orElseThrow().ordinal()); assertEquals(1,reader.next().orElseThrow().ordinal()); assertTrue(reader.next().isEmpty());
        }
        var falseComplete=new Sealed(partial,2,Files.size(partial),hash(partial),sealed.checkpoints(),sealed.metadata(),sealed.outputs(),true,null);
        try(var reader=falseComplete.openEvents()) {
            reader.next(); reader.next(); assertThrows(IOException.class,reader::next);
        }
    }

    private TraversalCapture capture(String name,int ticks,int release,int targets) throws IOException {
        return new TraversalCapture(directory.resolve(name),run,Limits.forRun(ticks,release,targets,1));
    }
    private MotionFrame frame(long ordinal,long tick,String phase) {
        return new MotionFrame(ordinal,tick,phase,run,new Identities(11,12,13,14,11,12,13,14,true,true,true,true),
                new Pose(1,65,2,0,-.0784,0,1,66.62,2,32,100,-90,-90,-90,false,false,1,7,900,4.5,physics()),
                0,false,BuildEngine.ControlOwner.NATIVE,RunLifecycle.Phase.RUNNING,RunLifecycle.Activity.BUILD,null,null,
                tick,0,new Traversal(3,3,"FORWARD","LANE",3,11,0,7,0,0,7,"WEST","ENTER_YAW",2,17,1,39,0,87,23,false),
                1,1,new BuildEngine.PlacementEffectResult(run,912,84,1234,BuildEngine.EffectStatus.ACCEPTED),51,null,null);
    }
    private MotionFrame withFault(MotionFrame m,TargetFact fact) {
        return new MotionFrame(m.ordinal(),m.tick(),m.phase(),m.runId(),m.identities(),m.pose(),m.keys(),m.paused(),m.owner(),m.lifecycle(),m.activity(),m.outcome(),m.reason(),m.eligible(),m.verify(),m.traversal(),m.pending(),m.unfinished(),m.effect(),m.predictionSequence(),fact,null);
    }
    private ServerFrame server(long ordinal,long follows) {
        return new ServerFrame(ordinal,run,21,22,21,22,true,true,23,24,25,1,65,2,0,-.0784,0,-90,false,false,1,7,900,0,follows,4.5,physics(),null,null);
    }
    private Physics physics() { return new Physics(true,false,false,false,false,false,false,false,false,false,false,false,false,false,false,0,.08); }
    private FullScan scan(long tick,long ordinal,String phase,int size) {
        var values=new ArrayList<PlacementObservation>();
        for(int i=0;i<size;i++) values.add(new PlacementObservation(run,i,OptionalLong.empty(),tick,OBSERVED,Optional.of(STONE)));
        return new FullScan(run,tick,phase,ordinal,values);
    }
    private BuildPlan plan(int size) {
        var targets=new ArrayList<Target>(); for(int i=0;i<size;i++) targets.add(new Target(i,new GridPos(i,0,0),STONE));
        return new BuildPlan("capture-test",new GridSize(size,1,1),targets);
    }
    private String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
