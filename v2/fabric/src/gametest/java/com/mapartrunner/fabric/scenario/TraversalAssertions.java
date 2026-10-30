package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.core.traversal.LanePlanner;
import com.mapartrunner.fabric.scenario.TraversalCapture.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/** Streaming trace and fixture/oracle checks; no world access or effects. */
final class TraversalAssertions {
    private TraversalAssertions() { }

    static void verify(Sealed trace,com.mapartrunner.core.model.BuildPlan plan,ScenarioDefinition.Fixture fixture) {
        require(trace.complete() && trace.outputs().complete(),"Incomplete capture cannot establish construction success");
        var definition=ScenarioDefinition.load(fixture);
        expectedWorldTargets(definition,plan.targets());
        requireRemovalReceipts(definition,List.of()); // Task13 must supply original causal receipts before these profiles can pass.
        var required=EnumSet.of(CheckpointKind.INITIAL,CheckpointKind.FIRST_VERIFY,CheckpointKind.TERMINAL,CheckpointKind.RELEASE_END);
        require(trace.checkpoints().keySet().equals(required),"Missing/foreign named full checkpoint/release END");
        var lanes=new LanePlanner().plan(plan,2,5);
        var targets=new HashSet<Integer>(); plan.targets().forEach(t->targets.add(t.id()));
        var seen=EnumSet.noneOf(CheckpointKind.class);
        UUID run=null; MotionFrame start=null,lastEnd=null,terminal=null,firstVerify=null;
        MotionFrame latest=null,previous=null,older=null;
        ServerFrame remote=null; BuildEngine.PlacementEffectResult lastEffect=null;
        long clients=0,servers=0,releaseServers=0; boolean entered=false;
        MotionFrame walking=null; ServerFrame serverWalking=null;
        boolean clientTravel=false,serverTravel=false;
        int walkedIndex=-1; String walkedPass=null;
        try(var reader=trace.openEvents()) {
            Optional<Event> next;
            while((next=reader.next()).isPresent()) {
                var event=next.orElseThrow();
                if(run==null) run=event.runId();
                require(run.equals(event.runId()),"Foreign event run");
                if(event instanceof MotionFrame m) {
                    older=previous; previous=latest; latest=m; clients++;
                    var i=m.identities();
                    require(i.worldMatches() && i.playerMatches() && i.handlerMatches() && i.connectionMatches()
                            && i.world()==i.originalWorld() && i.player()==i.originalPlayer() && i.handler()==i.originalHandler()
                            && i.connection()==i.originalConnection() && m.pose()!=null && !m.paused(),"Foreign/missing/paused native client identity");
                    if(previous!=null) require(i.equals(previous.identities()),"Native client identity changed");
                    require(Double.isFinite(m.pose().x()) && Double.isFinite(m.pose().y()) && Double.isFinite(m.pose().z())
                            && Double.isFinite(m.pose().interactionReach()) && m.pose().interactionReach()>0
                            && !m.pose().flying() && !m.pose().sprinting(),"Nonfinite client pose/reach or flying/sprinting");
                    requireOrdinaryPhysics(m.pose().physics());
                    require(m.lifecycle()==Phase.TERMINAL || m.traversal()!=null,"Missing active traversal state");
                    require(m.fault0()==null && m.fault1()==null,"Undeclared replacement fact without causal removal receipts");
                    if(m.effect()!=null) {
                        var e=m.effect();
                        require(e.runId().equals(run) && targets.contains(e.targetId()),"Foreign original action tuple");
                        if(lastEffect!=null) require(e.actionId()>lastEffect.actionId() || e.equals(lastEffect),"Reused/reordered original action identity");
                        lastEffect=e; // Repeated START/END tuples are one action, never placement consumption.
                    }
                    if(m.phase().equals("INITIAL")) require(m.ordinal()==0 && clients==1 && m.tick()==0 && m.eligible()==0,"Noninitial INITIAL event");
                    else if(m.phase().equals("START")) {
                        require(start==null && m.tick()==(lastEnd==null?1:lastEnd.tick()+1),"Reordered/missing START");
                        if(lastEnd!=null) require(m.eligible()==lastEnd.eligible() && m.verify()==lastEnd.verify(),"Clock changed between callbacks");
                        start=m;
                    } else {
                        require(m.phase().equals("END") && start!=null && start.tick()==m.tick(),"Absent/reordered callback START/END");
                        require(m.pose().age()>=start.pose().age() && m.pose().worldTime()>=start.pose().worldTime(),"Client clocks reversed");
                        if(start.lifecycle()==Phase.RUNNING) require(m.eligible()==start.eligible()+1,"Callback did not advance one eligible decision");
                        else require(m.eligible()==start.eligible() && m.verify()==start.verify()
                                && Objects.equals(m.traversal(),start.traversal()),"Paused/terminal callback renewed traversal/clocks");
                        checkTraversalPair(start,m,lanes,definition.origin());
                        if(start.lifecycle()==Phase.RUNNING && start.activity()==Activity.VERIFY)
                            require(m.verify()==start.verify()+1 && m.verify()<=80,"VERIFY clock renewed/exceeded80");
                        if(m.traversal()!=null && m.traversal().enteredBuildIndex()!=null && m.traversal().stage().equals("WALK")) entered=true;
                        start=null; lastEnd=m;
                    }
                    if(m.traversal()!=null) {
                        var t=m.traversal();
                        require(t.physicalIndex()>=0 && t.physicalIndex()<lanes.size() && t.entryTicks()>=0 && t.entryTicks()<=52
                                && t.stageTicks()>=0 && t.alignedTicks()>=0 && t.alignedTicks()<=1 && t.supportTicks()>=0 && t.supportTicks()<80
                                && t.yawTicks()>=0 && t.yawTicks()<20 && t.transitionTicks()>=0 && t.transitionTicks()<=120
                                && t.noProgressTicks()>=0 && t.noProgressTicks()<40,"Movement counter bound violated");
                        if(t.stage().equals("ENTER_POSITION")) require(t.stageTicks()<=40,"Positioning clock exceeded40");
                        if(t.stage().equals("ENTER_YAW")) require(t.stageTicks()<=10,"Entry yaw clock exceeded10");
                        boolean sameWalk=t.physicalIndex()==walkedIndex && Objects.equals(t.pass(),walkedPass) && t.substage().equals("LANE");
                        if(walking!=null && !sameWalk) {
                            var lane=walking.traversal();
                            double length=Math.abs(lane.endX()-lane.startX());
                            if(length>2*walking.pose().interactionReach()+1)
                                require(clientTravel && serverTravel,"Lane labels lack independent beyond-reach native movement");
                            walking=null; serverWalking=null; clientTravel=false; serverTravel=false;
                        }
                        if(m.phase().equals("END") && t.substage().equals("LANE") && t.stage().equals("WALK")
                                && m.lifecycle()==Phase.RUNNING && m.activity()==Activity.BUILD && m.owner()==BuildEngine.ControlOwner.NATIVE && (m.keys()&1)!=0) {
                            require((m.keys()&(2|16|64))==0,"Grounded walk used backward/jump/sprint controls");
                            double yaw=switch(t.direction()) { case "EAST" -> -90; case "WEST" -> 90; case "SOUTH" -> 0; case "NORTH" -> 180; default -> Double.NaN; };
                            require(aligned(m.pose().yaw(),yaw) && aligned(m.pose().headYaw(),yaw) && aligned(m.pose().bodyYaw(),yaw),"Native walk yaw/head/body differs");
                            if(walking==null) { walking=m; walkedIndex=t.physicalIndex(); walkedPass=t.pass(); }
                            int sign=t.direction().equals("WEST")?-1:1;
                            clientTravel|=(m.pose().x()-walking.pose().x())*sign>m.pose().interactionReach();
                        }
                    }
                    if(terminal!=null) require(m.lifecycle()==Phase.TERMINAL && m.outcome()==terminal.outcome()
                            && m.reason()==terminal.reason() && m.eligible()==terminal.eligible() && m.verify()==terminal.verify()
                            && m.pending()==0 && m.unfinished()==0 && m.keys()==0 && m.owner()==BuildEngine.ControlOwner.NONE
                            && Objects.equals(m.predictionSequence(),terminal.predictionSequence()),"Terminal release changed controls/engine/native issuance");
                    for(var kind:required) {
                        var c=trace.checkpoints().get(kind);
                        if(c.eventOrdinal()!=m.ordinal()) continue;
                        require(c.complete() && c.kind()==kind && c.runId().equals(run) && c.tick()==m.tick() && c.phase().equals(m.phase())
                                && c.count()==plan.targets().size() && seen.add(kind),"Fabricated checkpoint provenance");
                        switch(kind) {
                            case INITIAL -> require(m.phase().equals("INITIAL") && seen.size()==1,"Wrong INITIAL boundary");
                            case FIRST_VERIFY -> {
                                require(m.phase().equals("END") && m.lifecycle()==Phase.RUNNING && m.activity()==Activity.VERIFY && terminal==null,"Wrong first VERIFY boundary");
                                firstVerify=m;
                            }
                            case TERMINAL -> {
                                require(m.phase().equals("END") && m.lifecycle()==Phase.TERMINAL && m.outcome()==Outcome.SUCCESS
                                        && m.reason()==BuildEngine.Reason.VERIFIED && m.pending()==0 && m.unfinished()==0 && firstVerify!=null
                                        && m.tick()>firstVerify.tick() && m.eligible()>firstVerify.eligible(),"SUCCESS lacks later full VERIFY decision");
                                terminal=m;
                            }
                            case RELEASE_END -> require(m.phase().equals("END") && terminal!=null && m.tick()-terminal.tick()==20,"Not the actual final release20 END");
                            default -> throw new AssertionError("Unexpected successful checkpoint");
                        }
                    }
                } else {
                    var s=(ServerFrame)event; servers++;
                    MotionFrame causal=null;
                    for(var candidate:new MotionFrame[]{latest,previous,older}) if(candidate!=null && candidate.ordinal()==s.followsClientOrdinal()) causal=candidate;
                    require(causal!=null && s.serverMatches() && s.playerMatches() && s.server()==s.originalServer() && s.player()==s.originalPlayer(),
                            "Server facts lack original causal client event/native identity");
                    require(s.fault0()==null && s.fault1()==null,"Native replacement lacks declared causal receipts");
                    require(Double.isFinite(s.x()) && Double.isFinite(s.y()) && Double.isFinite(s.z())
                            && Double.isFinite(s.interactionReach()) && s.interactionReach()>0 && !s.flying() && !s.sprinting(),"Nonfinite server pose/reach or flying/sprinting");
                    requireOrdinaryPhysics(s.physics());
                    if(walking!=null && causal.traversal()!=null && causal.traversal().physicalIndex()==walkedIndex
                            && causal.traversal().pass().equals(walkedPass) && causal.traversal().substage().equals("LANE")) {
                        if(serverWalking==null) serverWalking=s;
                        int sign=walking.traversal().direction().equals("WEST")?-1:1;
                        serverTravel|=(s.x()-serverWalking.x())*sign>s.interactionReach();
                    }
                    if(remote!=null) require(s.originalServer()==remote.originalServer() && s.originalPlayer()==remote.originalPlayer()
                            && s.serverTick()>=remote.serverTick() && s.worldTime()>=remote.worldTime() && s.age()>=remote.age()
                            && s.followsClientOrdinal()>remote.followsClientOrdinal(),"Server clock/provenance did not advance independently");
                    if(terminal!=null && s.followsClientOrdinal()>terminal.ordinal()) {
                        releaseServers++; require(s.controls()==0 && !s.flying() && !s.sprinting() && remote!=null
                                && s.serverTick()>remote.serverTick() && s.worldTime()>remote.worldTime() && s.age()>remote.age(),"Server release controls/physics changed");
                    }
                    remote=s;
                }
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        require(start==null && terminal!=null && entered && seen.equals(required) && servers>20 && releaseServers==20
                && latest!=null && latest.ordinal()==trace.checkpoints().get(CheckpointKind.RELEASE_END).eventOrdinal()
                && remote!=null && remote.followsClientOrdinal()==latest.ordinal(),"Incomplete movement/release/server stream");
        for(var kind:required) {
            var scan=checkpoint(trace,kind); var ids=new HashSet<Integer>();
            for(var value:scan.observations()) require(value.runId().equals(run) && value.tick()==scan.tick()
                    && targets.contains(value.targetId()) && ids.add(value.targetId()),"Foreign/stale/duplicate full scan");
            require(ids.equals(targets),"Missing full checkpoint targets");
            if(kind==CheckpointKind.TERMINAL || kind==CheckpointKind.RELEASE_END)
                TraversalCapture.requireFullMatch(plan,scan,run,scan.tick());
        }
    }

    private static FullScan checkpoint(Sealed trace,CheckpointKind kind) {
        try { return trace.readCheckpoint(kind); } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static void checkTraversalPair(MotionFrame before,MotionFrame after,List<LanePlanner.Lane> lanes,GridPos origin) {
        var a=before.traversal(); var b=after.traversal();
        if(a==null || b==null || before.lifecycle()!=Phase.RUNNING) return;
        boolean same=a.physicalIndex()==b.physicalIndex() && a.pass().equals(b.pass()) && a.substage().equals(b.substage());
        if(!a.pass().equals("SELECTING") && a.physicalIndex()!=b.physicalIndex())
            require(a.substage().equals("SHIFT") && b.substage().equals("NEXT_ENTRY")
                    && b.physicalIndex()==a.physicalIndex()+(a.pass().equals("REVERSE")?-1:1),"Physical route index changed outside an adjacent SHIFT");
        if(!a.pass().equals("SELECTING")) require(!b.pass().equals("SELECTING"),"Traversal selection/clock restarted");
        boolean hop=Set.of("ENDPOINT","SHIFT","NEXT_ENTRY").contains(a.substage());
        if(hop && a.pass().equals(b.pass()) && !b.pass().equals("COMPLETE")) {
            boolean entered=b.substage().equals("LANE");
            boolean adjacent=a.substage().equals("NEXT_ENTRY") && Set.of("ENDPOINT","TRANSIT").contains(b.substage())
                    && b.pass().equals("REVERSE") && b.transitionTicks()==0 && a.stage().equals("ENTER_YAW") && a.alignedTicks()==1;
            require(a.transitionTicks()<120 && (entered?a.substage().equals("NEXT_ENTRY") && b.transitionTicks()==0:adjacent ||
                    b.transitionTicks()==a.transitionTicks()+1),"Shared adjacent-hop clock renewed");
            require(Math.abs(b.physicalIndex()-a.physicalIndex())<=1,"Hop skipped a physical adjacent index");
            if(a.substage().equals("SHIFT") && b.substage().equals("NEXT_ENTRY"))
                require(b.physicalIndex()==a.physicalIndex()+(a.pass().equals("REVERSE")?-1:1),"SHIFT did not reach the actual adjacent physical index");
        }
        if(same && !b.stage().equals("WALK") && !a.stage().equals("WALK"))
            require(b.entryTicks()==a.entryTicks()+1,"Entry total clock renewed");
        boolean readyBoundary=b.stage().equals("WALK") && !a.stage().equals("WALK")
                || a.substage().equals("ENDPOINT") && b.substage().equals("SHIFT")
                || a.substage().equals("NEXT_ENTRY") && Set.of("ENDPOINT","TRANSIT").contains(b.substage());
        if(readyBoundary) {
            require(a.stage().equals("ENTER_YAW") && a.alignedTicks()==1 && a.stageTicks()>=1
                    && near(after.pose().x(),origin.x()+a.startX()+.5) && near(after.pose().z(),origin.z()+a.startZ()+.5),
                    "WALK lacks full entry/two controller-certified yaw samples");
        }
        boolean handoff=!same || a.startX()!=b.startX() || a.startZ()!=b.startZ() || a.endX()!=b.endX() || a.endZ()!=b.endZ();
        if(a.stage().equals("ENTER_YAW") || a.stage().equals("WALK") && handoff)
            require(b.supportTicks()==a.supportTicks(),"Unevaluated support history cleared during handoff");
        if(same && b.alignedTicks()==1) require(a.stage().equals("ENTER_YAW") && a.alignedTicks()==0 && b.stageTicks()==a.stageTicks()+1,
                "Fabricated first aligned controller input");
        if(b.enteredBuildIndex()!=null) {
            require(b.substage().equals("LANE") && b.enteredBuildIndex()==b.physicalIndex(),"Fabricated BUILD index");
            var canonical=lanes.get(b.physicalIndex());
            boolean reverse=b.pass().equals("REVERSE");
            var end=reverse?canonical.start():canonical.end();
            boolean retainedInitialClip=a.pass().equals("SELECTING") || same && a.startX()==b.startX() && a.startZ()==b.startZ();
            require(b.laneId()==canonical.id() && b.endX()==end.x() && b.endY()==end.y() && b.endZ()==end.z()
                    && b.startY()==canonical.start().y() && b.startZ()==(reverse?canonical.end().z():canonical.start().z()) && b.startX()>=0
                    && b.startX()<=Math.max(canonical.start().x(),canonical.end().x())
                    && (!reverse || b.startX()==canonical.end().x())
                    && (reverse || retainedInitialClip || b.startX()==canonical.start().x())
                    && b.direction().equals(reverse?(canonical.direction()==LanePlanner.Direction.EAST?"WEST":"EAST"):canonical.direction().name()),
                    "Actual entry replaced the immutable canonical lane");
        }
    }

    private static boolean near(double actual,double expected) { return Double.isFinite(actual) && Math.abs(actual-expected)<=.25; }

    private static boolean aligned(double angle,double expected) {
        double delta=(angle-expected)%360; if(delta>=180) delta-=360; if(delta< -180) delta+=360;
        return Double.isFinite(delta) && Math.abs(delta)<=1.5;
    }

    private static void requireOrdinaryPhysics(Physics p) {
        require(p!=null && p.onGround() && !p.noGravity() && !p.noDrag() && !p.jumping() && !p.vehicle() && !p.fluid() && !p.water()
                && !p.submerged() && !p.lava() && !p.climbing() && !p.gliding() && !p.swimming() && !p.sneaking()
                && !p.usingItem() && !p.hasEffects() && p.gravity()==.08 && p.fallDistance()==0,"Nonordinary grounded physics");
    }

    static void verifyGuards(Path directory) {
        ScenarioDefinition.verifyFixtureGuards();
        var definition=ScenarioDefinition.load(ScenarioDefinition.Fixture.NARROW);
        var targets=new ArrayList<Target>();
        definition.expectedTargets().forEach((position,state) -> targets.add(new Target(targets.size(),position,state)));
        var run=UUID.randomUUID();
        var scan=targets.stream().map(t -> new PlacementObservation(run,t.id(),OptionalLong.empty(),7,
                Evidence.OBSERVED,Optional.of(t.block()))).toList();
        requireCurrentScan(run,7,targets,scan);
        expectedWorldTargets(definition,targets);
        refused("missing expected full-state target",() -> expectedWorldTargets(definition,targets.subList(1,targets.size())));
        var malformed=new ArrayList<>(targets);
        malformed.set(0,new Target(targets.getFirst().id(),targets.getFirst().relativePos(),
                new BlockSpec("minecraft:stone",Map.of("axis","x"))));
        refused("malformed full-state target",() -> expectedWorldTargets(definition,malformed));
        var duplicate=new ArrayList<>(targets); duplicate.set(1,duplicate.getFirst());
        refused("duplicate full-state target",() -> expectedWorldTargets(definition,duplicate));
        refused("missing final scan",() -> requireCurrentScan(run,7,targets,List.of()));
        refused("incomplete final scan",() -> requireCurrentScan(run,7,targets,scan.subList(1,scan.size())));
        refused("stale final scan",() -> requireCurrentScan(run,8,targets,scan));
        refused("foreign final run",() -> requireCurrentScan(UUID.randomUUID(),7,targets,scan));
        for (var evidence:List.of(Evidence.PREDICTED,Evidence.UNKNOWN)) {
            var changed=new ArrayList<>(scan);
            changed.set(0,new PlacementObservation(run,targets.getFirst().id(),OptionalLong.empty(),7,evidence,
                    evidence==Evidence.UNKNOWN?Optional.empty():Optional.of(targets.getFirst().block())));
            refused("unobserved final scan "+evidence,() -> requireCurrentScan(run,7,targets,changed));
        }
        var duplicateScan=new ArrayList<>(scan); duplicateScan.set(1,duplicateScan.getFirst());
        refused("duplicate final scan",() -> requireCurrentScan(run,7,targets,duplicateScan));
        var wrongState=new ArrayList<>(scan);
        wrongState.set(0,new PlacementObservation(run,targets.getFirst().id(),OptionalLong.empty(),7,Evidence.OBSERVED,
                Optional.of(new BlockSpec("minecraft:stone",Map.of("axis","x")))));
        refused("wrong observed full state",() -> requireCurrentScan(run,7,targets,wrongState));
        var artifacts=java.nio.file.Path.of("fixture-selfcheck");
        var result=new ScenarioRunner.ScenarioResult(run,new com.mapartrunner.core.run.BuildEngine().snapshot(),
                definition.definitionHash(),"selfcheck",List.of(),artifacts);
        ScenarioRunner.requireResultIdentity(result,result,Set.of(run),artifacts,definition);
        refused("foreign result instance",() -> ScenarioRunner.requireResultIdentity(
                new ScenarioRunner.ScenarioResult(run,result.terminal(),definition.definitionHash(),"selfcheck",List.of(),artifacts),
                result,Set.of(run),artifacts,definition));
        refused("foreign result run",() -> ScenarioRunner.requireResultIdentity(result,result,Set.of(UUID.randomUUID()),artifacts,definition));
        refused("foreign result evidence root",() -> ScenarioRunner.requireResultIdentity(result,result,Set.of(run),artifacts.resolve("foreign"),definition));
        refused("foreign result fixture",() -> ScenarioRunner.requireResultIdentity(result,result,Set.of(run),artifacts,ScenarioDefinition.load()));
        for (var fixture:ScenarioDefinition.Fixture.values()) {
            var admitted=ScenarioDefinition.load(fixture);
            require(admitted.finalUsable()==fixture.finalUsable,"Wrong pinned inventory delta: "+fixture);
            ScenarioRunner.requireStock(inventory(admitted.finalUsable()),inventory(128),fixture.finalUsable);
            requireRemovalReceipts(admitted,admitted.removalTargets().stream().toList());
            if (!admitted.removalTargets().isEmpty()) {
                refused("missing declared removal receipt",() -> requireRemovalReceipts(admitted,List.of()));
                var receipts=new ArrayList<>(admitted.removalTargets()); receipts.add(receipts.getFirst());
                refused("duplicate removal receipt",() -> requireRemovalReceipts(admitted,receipts));
                refused("foreign removal target",() -> requireRemovalReceipts(admitted,List.of(new GridPos(-1,0,-1))));
                require(admitted.finalUsable()!=admitted.initialUsable()-(admitted.initiallyAbsentStoneTargets()-admitted.removalTargets().size()),
                        "Old plus-sign removal oracle survived");
                if(fixture==ScenarioDefinition.Fixture.REVERSE_ODD || fixture==ScenarioDefinition.Fixture.REVERSE_EVEN)
                    refused("old plus-sign final stock",() -> ScenarioRunner.requireStock(inventory(128),inventory(128),admitted.finalUsable()));
            }
            for (int expected:List.of(1,2)) {
                require(invocationSucceeded(expected,expected,expected,expected,true),"Valid traversal invocation refused");
                require(!invocationSucceeded(expected,expected,expected,expected,false),"Missing normal close accepted");
                for (int delta:List.of(-1,1)) require(!invocationSucceeded(expected,expected+delta,expected,expected,true)
                        && !invocationSucceeded(expected,expected,expected+delta,expected,true)
                        && !invocationSucceeded(expected,expected,expected,expected+delta,true),"Missing/extra traversal cardinality accepted");
            }
        }
        var baseline=new JsonArray(); for(int k=0;k<43;k++) baseline.add(new JsonObject());
        baseline.set(0,stack(7)); baseline.set(1,stack(64)); baseline.set(2,stack(64));
        var current=baseline.deepCopy(); current.set(2,stack(63));
        ScenarioRunner.requireStock(current,baseline,127);
        current.get(0).getAsJsonObject().addProperty("count",6);
        refused("wrong reserved stock with correct usable count",() -> ScenarioRunner.requireStock(current,baseline,127));
        verifyCaptureGuards(directory);
        System.out.println("TraversalAssertions closed-fixture/oracle self-checks passed; not native traversal evidence");
    }

    private static void verifyCaptureGuards(Path directory) {
        ScenarioRunner.verifyDiagnosticGuards(directory.resolve("diagnostics"));
        var definition=ScenarioDefinition.load(ScenarioDefinition.Fixture.NARROW);
        var targets=new ArrayList<Target>();
        definition.expectedTargets().forEach((p,b)->targets.add(new Target(targets.size(),p,b)));
        var plan=new com.mapartrunner.core.model.BuildPlan("stream-guard",definition.fixture().dimensions(),targets);
        var trace=guardTrace(directory.resolve("valid"),plan,"valid");
        verify(trace,plan,definition.fixture());
        require(trace.eventCount()>6000,"Stream guard did not exceed old observer admission");
        for(var invalid:List.of("reordered","foreign","absent-release","early-release","absent-full","invalid-replacement")) {
            var broken=guardTrace(directory.resolve(invalid),plan,invalid);
            refused(invalid+" compact stream",()->verify(broken,plan,definition.fixture()));
        }
        var changed=new EnumMap<CheckpointKind,Checkpoint>(CheckpointKind.class); changed.putAll(trace.checkpoints());
        var terminal=changed.get(CheckpointKind.TERMINAL);
        changed.put(CheckpointKind.TERMINAL,new Checkpoint(terminal.kind(),terminal.path(),terminal.tick(),terminal.phase(),
                terminal.eventOrdinal()-1,terminal.count(),terminal.bytes(),terminal.hash(),terminal.runId(),true));
        var fabricated=new Sealed(trace.events(),trace.eventCount(),trace.eventBytes(),trace.eventHash(),changed,trace.metadata(),trace.outputs(),true,null);
        refused("fabricated checkpoint event",()->verify(fabricated,plan,definition.fixture()));
        var full=checkpoint(trace,CheckpointKind.TERMINAL);
        for(var evidence:List.of(Evidence.PREDICTED,Evidence.UNKNOWN)) {
            var values=new ArrayList<>(full.observations()); var first=values.getFirst();
            values.set(0,new PlacementObservation(first.runId(),first.targetId(),first.actionId(),first.tick(),evidence,
                    evidence==Evidence.UNKNOWN?Optional.empty():first.observedState()));
            var invalid=new FullScan(full.runId(),full.tick(),full.phase(),full.eventOrdinal(),values);
            refused("isolated "+evidence+" final observation",()->TraversalCapture.requireFullMatch(plan,invalid,full.runId(),full.tick()));
        }
    }

    /** Real writer/reader fixtures; synthetic physics are oracle guards, never native evidence. */
    private static Sealed guardTrace(Path directory,com.mapartrunner.core.model.BuildPlan plan,String mutation) {
        var run=UUID.randomUUID();
        try {
            var capture=new TraversalCapture(directory,run,Limits.forRun(3100,20,plan.targets().size(),1),
                    mutation.equals("invalid-replacement")?Set.of(0):Set.of());
            var physics=new Physics(true,false,false,false,false,false,false,false,false,false,false,false,false,false,false,0,.08);
            var lane=new LanePlanner().plan(plan,2,5).getFirst();
            var pose=new Pose(lane.start().x()+.5,66,lane.start().z()+.5,0,-.0784000015258789,0,
                    lane.start().x()+.5,67.62,lane.start().z()+.5,0,0,-90,-90,-90,false,false,1,7,64,4.5,physics);
            var ids=new Identities(1,2,3,4,1,2,3,4,true,true,true,true);
            MotionFrame prior=null;
            int pauseTicks=mutation.equals("valid")?3000:0;
            for(int tick=0;tick<=pauseTicks+25;tick++) {
                for(String phase:tick==0?List.of("INITIAL"):List.of("START","END")) {
                    boolean end=phase.equals("END");
                    int decision=Math.max(0,tick==0?0:end?tick-pauseTicks:tick-pauseTicks-1);
                    int eligible=Math.min(decision,5);
                    String stage=decision<1?"ENTER_POSITION":decision<3?"ENTER_YAW":"WALK";
                    var traversal=new Traversal(0,0,decision>=4?"COMPLETE":"FORWARD","LANE",lane.id(),
                            lane.start().x(),lane.start().y(),lane.start().z(),lane.end().x(),lane.end().y(),lane.end().z(),lane.direction().name(),stage,
                            decision==2?1:0,decision==1?1:decision==2?2:0,decision==2?1:0,0,0,0,0,false);
                    boolean terminal=decision>=5;
                    boolean paused=tick>0 && decision==0 && !(tick==pauseTicks+1 && phase.equals("START"));
                    long actualTick=mutation.equals("reordered") && tick==2 && end?1:tick;
                    var identities=mutation.equals("foreign") && tick==2 && end?
                            new Identities(1,2,3,4,9,2,3,4,false,true,true,true):ids;
                    var frame=new MotionFrame(capture.nextOrdinal(),actualTick,phase,run,identities,
                            new Pose(pose.x(),pose.y(),pose.z(),pose.vx(),pose.vy(),pose.vz(),pose.eyeX(),pose.eyeY(),pose.eyeZ(),tick,tick,
                                    pose.yaw(),pose.headYaw(),pose.bodyYaw(),false,false,1,7,64,4.5,physics),0,false,BuildEngine.ControlOwner.NONE,
                            terminal?Phase.TERMINAL:paused?Phase.PAUSED:Phase.RUNNING,decision>=4?Activity.VERIFY:Activity.BUILD,
                            terminal?Outcome.SUCCESS:null,terminal?BuildEngine.Reason.VERIFIED:null,eligible,terminal?1:0,terminal?null:traversal,0,0,null,44,null,null);
                    capture.append(frame); prior=frame;
                    CheckpointKind kind=tick==0?CheckpointKind.INITIAL:!end?null:tick==pauseTicks+4?CheckpointKind.FIRST_VERIFY:
                            tick==pauseTicks+5 && !mutation.equals("absent-full")?CheckpointKind.TERMINAL:
                                    tick==pauseTicks+(mutation.equals("early-release")?24:25) && !mutation.equals("absent-release")?CheckpointKind.RELEASE_END:null;
                    if(kind!=null) capture.checkpoint(kind,new FullScan(run,tick,phase,frame.ordinal(),plan.targets().stream()
                            .map(t->new PlacementObservation(run,t.id(),OptionalLong.empty(),frame.tick(),Evidence.OBSERVED,Optional.of(t.block()))).toList()));
                }
                var fault=mutation.equals("invalid-replacement")?new TargetFact(run,0,null,tick,Evidence.OBSERVED,plan.targets().getFirst().block()):null;
                capture.append(new ServerFrame(capture.nextOrdinal(),run,5,6,5,6,true,true,tick,tick,tick,pose.x(),pose.y(),pose.z(),0,0,0,-90,
                        false,false,1,7,64,0,prior.ordinal(),4.5,physics,fault,null));
            }
            return capture.seal();
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static JsonObject stack(int count) {
        var value=new JsonObject(); value.addProperty("id","minecraft:stone"); value.addProperty("count",count); return value;
    }

    private static JsonArray inventory(int usable) {
        var slots=new JsonArray(); for(int i=0;i<43;i++) slots.add(new JsonObject());
        slots.set(0,stack(7));
        if(usable>0) slots.set(1,stack(Math.min(64,usable)));
        if(usable>64) slots.set(2,stack(usable-64));
        return slots;
    }

    static void requireCurrentScan(UUID run,long frame,List<Target> targets,List<PlacementObservation> scan) {
        var expected=new HashMap<Integer,BlockSpec>();
        for(var target:targets) require(expected.put(target.id(),target.block())==null,"Duplicate imported target ID");
        require(!targets.isEmpty() && scan.size()==targets.size(),"Final scan/import cardinality differs");
        var seen=new HashSet<Integer>();
        for(var value:scan) require(value.runId().equals(run) && value.tick()==frame && seen.add(value.targetId())
                && value.evidence()==Evidence.OBSERVED && Objects.equals(expected.get(value.targetId()),value.observedState().orElse(null)),
                "Final scan stale/duplicate/foreign/predicted/mismatch: "+value);
        require(seen.equals(expected.keySet()),"Final scan target ID set differs");
    }

    static Map<GridPos,BlockSpec> expectedWorldTargets(ScenarioDefinition definition,List<Target> targets) {
        var result=new HashMap<GridPos,BlockSpec>(); var relative=new HashMap<GridPos,BlockSpec>(); var ids=new HashSet<Integer>();
        var origin=definition.origin();
        for(var target:targets) {
            var p=target.relativePos(); var at=new GridPos(origin.x()+p.x(),origin.y()+p.y(),origin.z()+p.z());
            require(ids.add(target.id()) && relative.put(p,target.block())==null && definition.worldCells().containsKey(at),
                    "Duplicate/outside admitted target: "+target);
            result.put(at,target.block());
        }
        require(relative.equals(definition.expectedTargets()),"Imported full-state target map differs from admitted expectations");
        return Map.copyOf(result);
    }

    /** Task13 supplies original causal fault receipts; missing receipts never reduce declared repair cost. */
    static void requireRemovalReceipts(ScenarioDefinition definition,List<GridPos> witnessedTargets) {
        var seen=new HashSet<>(witnessedTargets);
        require(seen.size()==witnessedTargets.size() && seen.equals(definition.removalTargets()),"Missing/duplicate/foreign declared removal receipts");
    }

    static boolean invocationSucceeded(int expected,int ownedStarts,int countedStarts,int asserted,boolean normalClose) {
        return expected>=1 && expected<=2 && normalClose && ownedStarts==expected && countedStarts==expected && asserted==expected;
    }

    private static void refused(String name,Runnable check) {
        var mutations=new int[1];
        try { check.run(); mutations[0]++; }
        catch (RuntimeException | AssertionError expected) {
            require(mutations[0]==0,"Refused check reached mutation callback");
            System.out.println("TraversalAssertions expected refusal ["+name+"]: "+expected); return;
        }
        throw new AssertionError("Expected refusal: "+name);
    }

    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
