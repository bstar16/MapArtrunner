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
        verify(trace,plan,fixture,List.of());
    }

    private static void verify(Sealed trace,com.mapartrunner.core.model.BuildPlan plan,ScenarioDefinition.Fixture fixture,List<Removal> removals) {
        require(trace.complete() && trace.outputs().complete(),"Incomplete capture cannot establish construction success");
        var definition=ScenarioDefinition.load(fixture);
        expectedWorldTargets(definition,plan.targets());
        var required=EnumSet.of(CheckpointKind.INITIAL,CheckpointKind.FIRST_VERIFY,CheckpointKind.TERMINAL,CheckpointKind.RELEASE_END);
        require(trace.checkpoints().keySet().equals(required),"Missing/foreign named full checkpoint/release END");
        requireRemovalRecords(definition,plan,trace.checkpoints().get(CheckpointKind.INITIAL).runId(),removals);
        var lanes=new LanePlanner().plan(plan,2,5);
        var faultIds=definition.removalIds(plan);
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
                    requireFaultFacts(m.fault0(),m.fault1(),run,faultIds,m.tick());
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
                    requireFaultFacts(s.fault0(),s.fault1(),run,faultIds,s.serverTick());
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

    private static void requireFaultFacts(TargetFact first,TargetFact second,UUID run,Set<Integer> declared,long tick) {
        var facts=new ArrayList<TargetFact>(); if(first!=null) facts.add(first); if(second!=null) facts.add(second);
        var seen=new HashSet<Integer>();
        for(var fact:facts) require(fact.runId().equals(run) && declared.contains(fact.targetId()) && seen.add(fact.targetId())
                && fact.actionId()==null && fact.tick()==tick,"Foreign/duplicate/fabricated declared fault observation");
        require(seen.equals(declared),"Missing original declared fault observations");
    }

    static String verifyNative(Sealed trace,com.mapartrunner.core.model.BuildPlan plan,ScenarioDefinition.Fixture fixture,List<Removal> removals) {
        verify(trace,plan,fixture,removals);
        var definition=ScenarioDefinition.load(fixture);
        var run=trace.checkpoints().get(CheckpointKind.INITIAL).runId();
        var lanes=new LanePlanner().plan(plan,2,5); var history=new ForwardHistory(fixture,plan); var pace=new Pace();
        var targetIds=new HashSet<Integer>(); plan.targets().forEach(t->targetIds.add(t.id()));
        var actions=new Actions(run,targetIds); var accepted=new HashMap<Integer,BuildEngine.PlacementEffectResult>();
        var repairs=new HashSet<Integer>(); var removalById=new HashMap<Integer,Removal>();
        for(var target:plan.targets()) {
            var p=target.relativePos(); var o=definition.origin();
            if(!target.block().equals(definition.worldCells().get(new GridPos(o.x()+p.x(),o.y()+p.y(),o.z()+p.z())))) repairs.add(target.id());
        }
        for(var removal:removals) { repairs.add(removal.targetId()); removalById.put(removal.targetId(),removal); }
        var replaced=new HashSet<Integer>(); var observed=new HashSet<Integer>(); var matchedReceipts=new HashSet<Integer>();
        var forward=new LinkedHashSet<Integer>(); var reverse=new ArrayList<Integer>(); var serverEntries=new HashSet<Integer>();
        var hops=new LinkedHashMap<String,Integer>();
        MotionFrame latest=null; String build=null,transit=null; int transitCount=0;
        double clientMinZ=Double.POSITIVE_INFINITY,clientMaxZ=Double.NEGATIVE_INFINITY,serverMinZ=Double.POSITIVE_INFINITY,serverMaxZ=Double.NEGATIVE_INFINITY;
        long lastEligible=0,callbacks=0; int initial=-1;
        try(var reader=trace.openEvents()) {
            Optional<Event> next;
            while((next=reader.next()).isPresent()) {
                var event=next.orElseThrow();
                if(event instanceof MotionFrame m) {
                    latest=m; pace.accept(m); lastEligible=Math.max(lastEligible,m.eligible());
                    clientMinZ=Math.min(clientMinZ,m.pose().z()); clientMaxZ=Math.max(clientMaxZ,m.pose().z());
                    if(m.phase().equals("END")) { history.accept(m); callbacks++; }
                    var t=m.traversal();
                    if(t!=null) {
                        if(Set.of("ENDPOINT","SHIFT","NEXT_ENTRY").contains(t.substage()))
                            hops.merge(t.pass()+":"+t.physicalIndex(),t.transitionTicks(),Math::max);
                        if(m.phase().equals("END") && t.substage().equals("LANE") && Set.of("FORWARD","REVERSE").contains(t.pass())) {
                            String key=t.pass()+":"+t.physicalIndex();
                            if(!key.equals(build)) {
                                if(initial<0) { initial=t.physicalIndex(); require(initial==fixture.initialLane(),"Wrong actual initial selected lane"); }
                                if(t.pass().equals("FORWARD")) {
                                    require(t.physicalIndex()==fixture.initialLane()+forward.size(),"Forward sweep omitted/reordered a canonical lane");
                                    forward.add(t.physicalIndex());
                                } else {
                                    requireForwardBeforeReverse(t,forward.size(),lanes.size()-fixture.initialLane());
                                    requireReverseBuild(fixture,t.physicalIndex()); reverse.add(t.physicalIndex());
                                }
                                build=key;
                            }
                        }
                        if(t.substage().equals("TRANSIT")) {
                            String key=t.pass()+":"+t.physicalIndex()+":"+t.startX()+":"+t.endX();
                            if(!key.equals(transit)) { transitCount++; transit=key; }
                            require(t.enteredBuildIndex()==null && t.startZ()==t.endZ(),"X transit fabricated a BUILD or diagonal leg");
                        } else transit=null;
                    }
                    for(var removal:removals) if(m.ordinal()==removal.frame().ordinal()) {
                        require(m.equals(removal.frame()),"Removal receipt substituted a fabricated original END");
                        history.requireRemoval(m); matchedReceipts.add(removal.targetId());
                    }
                    if(m.effect()!=null) {
                        var effect=m.effect(); actions.accept(effect);
                        if(effect.status()==BuildEngine.EffectStatus.ACCEPTED) {
                            require(repairs.contains(effect.targetId()),"Native action consumed an already-correct/non-target block");
                            var old=accepted.putIfAbsent(effect.targetId(),effect);
                            require(old==null || old.equals(effect),"Duplicate native accepted action for one required repair");
                            var removal=removalById.get(effect.targetId());
                            require(removal==null || m.ordinal()>removal.frame().ordinal(),"Replacement action predates its causal AIR removal");
                        }
                    }
                    for(var fact:new TargetFact[]{m.fault0(),m.fault1()}) if(fact!=null) {
                        var removal=removalById.get(fact.targetId());
                        if(removal!=null && m.ordinal()>removal.frame().ordinal() && fact.evidence()==Evidence.OBSERVED
                                && definition.expectedTargets().get(removal.relative()).equals(fact.state())) observed.add(fact.targetId());
                    }
                } else {
                    var remote=(ServerFrame)event;
                    require(latest!=null && remote.followsClientOrdinal()==latest.ordinal(),"Native profile server span lacks its original client predecessor");
                    history.acceptServer(latest,remote);
                    serverMinZ=Math.min(serverMinZ,remote.z()); serverMaxZ=Math.max(serverMaxZ,remote.z());
                    var t=latest.traversal();
                    if(t!=null && t.pass().equals("REVERSE") && t.substage().equals("LANE")
                            && near(remote.x(),t.startX()+.5) && near(remote.z(),t.startZ()+.5)) serverEntries.add(t.physicalIndex());
                    for(var fact:new TargetFact[]{remote.fault0(),remote.fault1()}) if(fact!=null) {
                        var removal=removalById.get(fact.targetId());
                        if(removal!=null && remote.followsClientOrdinal()>removal.frame().ordinal() && remote.serverTick()>=removal.mutationTick()
                                && fact.evidence()==Evidence.OBSERVED && definition.expectedTargets().get(removal.relative()).equals(fact.state())) replaced.add(fact.targetId());
                    }
                }
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        require(forward.size()==lanes.size()-fixture.initialLane() && reverse.equals(fixture.reverseBuilds())
                && serverEntries.containsAll(reverse),"Selected reverse sequence/full server entry or forward trace incomplete");
        int expectedTransit=switch(fixture) { case REVERSE_ODD -> 1; case REVERSE_MULTI -> 2; default -> 0; };
        require(transitCount==expectedTransit,"Unnecessary/missing X transit or full-width clean-lane detour");
        double zSpan=lanes.getLast().start().z()-lanes.getFirst().start().z();
        require(clientMaxZ-clientMinZ>=Math.max(0,zSpan-.5) && serverMaxZ-serverMinZ>=Math.max(0,zSpan-.5),"Route labels lack actual full client/server Z movement");
        requireRepairEvidence(repairs,accepted.keySet(),removalById.keySet(),matchedReceipts,replaced,observed);
        require(lastEligible<=BuildEngine.runLimit(plan,new BuildEngine.Settings(2,5,false,1))
                && callbacks<= (long)definition.data().getAsJsonObject("budgets").get("runTicks").getAsInt()+20,"Frozen production/fixture callback allowance exceeded");
        require(fixture!=ScenarioDefinition.Fixture.SCALE_128 || lastEligible>1200 && forward.size()==26 && accepted.size()==28,
                "SCALE lacks actual beyond1200/full26-lane/28-native-repair proof");
        return "fixture="+fixture+"; coverage="+(removals.isEmpty()?"prefilled partial-start":"late-server-removal fault")
                +"; run="+run+"; actualEndCallbacks="+callbacks+"; eligible="+lastEligible+"; initial="+initial+"; forward="+forward+"; reverse="+reverse
                +"; nativeAcceptedActions="+accepted.values()+"; perHopMaximum="+hops+"; clientZSpan="+(clientMaxZ-clientMinZ)+"; serverZSpan="+(serverMaxZ-serverMinZ)
                +"\n"+pace.finish(fixture);
    }

    private static void requireRepairEvidence(Set<Integer> repairs,Set<Integer> accepted,Set<Integer> removed,
                                               Set<Integer> receipts,Set<Integer> replaced,Set<Integer> observed) {
        require(accepted.equals(repairs) && receipts.equals(removed) && replaced.equals(removed) && observed.equals(removed),
                "Missing removal receipt/original native accepted action/server replacement/current OBSERVED evidence");
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

    private static void requireReverseBuild(ScenarioDefinition.Fixture fixture,int index) {
        require(fixture.reverseBuilds().contains(index),"Selected clean lane labeled BUILD: "+fixture+" / "+index);
    }

    private static void requireForwardBeforeReverse(Traversal reverse,int forwardCount,int remainingForward) {
        require(reverse.pass().equals("REVERSE"),"Expected an actual reverse traversal");
        require(forwardCount==remainingForward,"Reverse label without the complete remaining forward trace");
    }

    /** Original END route history; the sealed verifier also checks every original START/policy boundary. */
    static final class ForwardHistory {
        private final ScenarioDefinition.Fixture fixture;
        private final Set<Integer> entered=new HashSet<>(),completed=new HashSet<>();
        private final Set<Integer> serverEntered=new HashSet<>(),serverCompleted=new HashSet<>();
        private MotionFrame previous;
        private final List<LanePlanner.Lane> lanes;
        ForwardHistory(ScenarioDefinition.Fixture fixture,com.mapartrunner.core.model.BuildPlan plan) {
            this.fixture=fixture; lanes=new LanePlanner().plan(plan,2,5);
        }
        void accept(MotionFrame frame) {
            if(!frame.phase().equals("END")) return;
            if(previous!=null) require(previous.runId().equals(frame.runId()) && frame.tick()==previous.tick()+1,
                    "Forward history lost a current-run END");
            if(previous!=null) {
                require(frame.eligible()==previous.eligible()+(previous.lifecycle()==Phase.RUNNING?1:0),"END history eligible counter changed");
                checkTraversalPair(previous,frame,lanes,new GridPos(0,65,0));
            }
            var t=frame.traversal();
            if(t!=null) {
                Pace.publicCounter(t.noProgressTicks());
                require(t.entryTicks()>=0 && t.entryTicks()<=52 && t.supportTicks()>=0 && t.supportTicks()<80
                        && t.yawTicks()>=0 && t.yawTicks()<20 && t.transitionTicks()>=0 && t.transitionTicks()<=120,
                        "END admission has invalid original public counters");
            }
            if(t!=null && t.pass().equals("FORWARD")) {
                int index=t.physicalIndex(),center=Math.min(2+5*index,fixture.depth-3);
                if(fixture.depth<=5) center=Math.min(2,fixture.depth-1);
                if(t.substage().equals("LANE")) {
                    require(t.laneId()==index && t.startZ()==center && t.endZ()==center && t.endX()==(index%2==0?fixture.width-1:0)
                            && t.direction().equals(index%2==0?"EAST":"WEST"),"Forward label lacks actual canonical geometry");
                    entered.add(index);
                }
                if(t.substage().equals("SHIFT") && near(frame.pose().x(),(index%2==0?fixture.width-.5:.5))
                        && near(frame.pose().z(),center+.5)) completed.add(index);
            }
            previous=frame;
        }
        void acceptServer(MotionFrame causal,ServerFrame server) {
            var t=causal.traversal();
            if(t==null || !t.pass().equals("FORWARD")) return;
            if(t.substage().equals("LANE") && near(server.x(),t.startX()+.5) && near(server.z(),t.startZ()+.5)) serverEntered.add(t.physicalIndex());
            int center=fixture.depth<=5?Math.min(2,fixture.depth-1):Math.min(2+5*t.physicalIndex(),fixture.depth-3);
            if(t.substage().equals("SHIFT") && near(server.x(),t.physicalIndex()%2==0?fixture.width-.5:.5)
                    && near(server.z(),center+.5)) serverCompleted.add(t.physicalIndex());
        }
        void requireRemoval(MotionFrame frame) {
            int last=fixture.removalLane(); var t=frame.traversal();
            require(last>=0 && frame==previous && frame.lifecycle()==Phase.RUNNING && t!=null && t.pass().equals("FORWARD")
                    && t.physicalIndex()==last && t.substage().equals("LANE") && t.stage().equals("WALK")
                    && fixture.removalX(frame.pose().x()) && entered.contains(last),"Removal lacks actual final FORWARD WALK/X passage");
            for(int index=0;index<last;index++) require(entered.contains(index) && completed.contains(index)
                    && serverEntered.contains(index) && serverCompleted.contains(index),"Missing full client/server forward lane trace: "+index);
        }
    }

    record Removal(UUID runId,int targetId,GridPos relative,MotionFrame frame,ServerFrame server,long mutationTick,long mutationTime,int mutationAge,
                   double eyeX,double eyeY,double eyeZ,BlockSpec before,BlockSpec after) { }

    static double boxDistanceSquared(double x,double y,double z,GridPos target) {
        double dx=Math.max(Math.max(target.x()-x,0),x-(target.x()+1.0));
        double dy=Math.max(Math.max(target.y()-y,0),y-(target.y()+1.0));
        double dz=Math.max(Math.max(target.z()-z,0),z-(target.z()+1.0));
        return dx*dx+dy*dy+dz*dz;
    }

    static void requireRemovalRecords(ScenarioDefinition definition,com.mapartrunner.core.model.BuildPlan plan,UUID run,List<Removal> removals) {
        requireRemovalReceipts(definition,removals.stream().map(Removal::relative).toList());
        for(var removal:removals) {
            var target=plan.targets().stream().filter(t->t.id()==removal.targetId()).findFirst().orElseThrow();
            var m=removal.frame(); var s=removal.server(); var o=definition.origin(); var p=removal.relative();
            var at=new GridPos(o.x()+p.x(),o.y()+p.y(),o.z()+p.z());
            require(removal.runId().equals(run) && m.runId().equals(run) && s.runId().equals(run) && target.relativePos().equals(p)
                    && m.phase().equals("END") && s.followsClientOrdinal()==m.ordinal() && s.serverMatches() && s.playerMatches()
                    && s.originalServer()==s.server() && s.originalPlayer()==s.player()
                    && Double.isFinite(s.interactionReach()) && s.interactionReach()>0
                    && Double.isFinite(m.pose().interactionReach()) && m.pose().interactionReach()>0
                    && removal.mutationTick()>=s.serverTick() && removal.mutationTime()>=s.worldTime() && removal.mutationAge()>=s.age()
                    && target.block().equals(removal.before()) && removal.after().equals(new BlockSpec("minecraft:air",Map.of()))
                    && boxDistanceSquared(m.pose().eyeX(),m.pose().eyeY(),m.pose().eyeZ(),at)>m.pose().interactionReach()*m.pose().interactionReach()
                    && boxDistanceSquared(removal.eyeX(),removal.eyeY(),removal.eyeZ(),at)>s.interactionReach()*s.interactionReach(),
                    "Removal lacks original run/target/full-state/out-of-reach causal facts");
        }
    }

    private static final class Actions {
        private final UUID run;
        private final Set<Integer> targets;
        private final Map<Long,BuildEngine.PlacementEffectResult> seen=new HashMap<>();
        Actions(UUID run,Set<Integer> targets) { this.run=run; this.targets=Set.copyOf(targets); }
        void accept(BuildEngine.PlacementEffectResult effect) {
            require(effect.runId().equals(run) && targets.contains(effect.targetId()),"Foreign original run/action/target");
            var previous=seen.putIfAbsent(effect.actionId(),effect);
            require(previous==null || previous.equals(effect),"Reused run/action with different original tuple");
            require(seen.size()<=3L*targets.size(),"Original action stream exceeds the admitted three attempts/target");
        }
    }

    /** Derived command/physics metrics, never the private policy result or credited coordinate. */
    private static final class Pace {
        private MotionFrame command,start,first,last;
        private Traversal geometry;
        private long callbacks,allCallbacks,longCallbacks,shortCallbacks,entryWaits,walkWaits;
        private double gain,allGain,longGain,shortGain,accelerationGain;
        private long accelerationCallbacks;
        private int segments,longSegments,highwater;
        private long highwaterOrdinal=-1;
        private final List<String> rows=new ArrayList<>();
        private final Map<String,Long> phaseCallbacks=new TreeMap<>();

        static void publicCounter(int ticks) { require(ticks>=0 && ticks<=8,"Original public no-progress approaches40: "+ticks); }
        static String rate(long callbacks,double gain,double remaining,boolean completed) {
            require(callbacks>=0 && Double.isFinite(gain) && Double.isFinite(remaining),"Invalid native pace facts");
            if(callbacks==0) { require(gain==0,"Uncommanded pace gain"); return "N/A"; }
            require(gain>0,"Zero/backward geometric gain with commanded walking");
            if(gain>=1) { require(callbacks<=8*gain,"Native segment exceeds eight callbacks/block"); return "long; rawRate="+(callbacks/gain); }
            require(completed && remaining>0 && remaining<=1 && callbacks<=Math.ceil(8*gain),"Fractional walk lacks bounded genuine completion");
            return "short; rawRate="+(callbacks/gain);
        }
        static void endpoint(MotionFrame command,MotionFrame start,MotionFrame end) {
            require(start!=null && end!=null && command.phase().equals("END") && start.phase().equals("START") && end.phase().equals("END")
                    && start.tick()==command.tick()+1 && end.tick()==start.tick() && command.runId().equals(start.runId()) && command.runId().equals(end.runId())
                    && command.identities().equals(start.identities()) && command.identities().equals(end.identities())
                    && !start.paused() && !end.paused() && start.lifecycle()==Phase.RUNNING && end.lifecycle()!=Phase.PAUSED
                    && start.owner()==command.owner() && start.keys()==command.keys() && start.pose()!=null && end.pose()!=null
                    && start.eligible()==command.eligible() && Objects.equals(start.traversal(),command.traversal()),
                    "Commanded physics interval was censored by pause/replacement/missing boundary");
            requireOrdinaryPhysics(start.pose().physics()); requireOrdinaryPhysics(end.pose().physics());
            // END's new decision may normally release NATIVE at the endpoint; its pose still closes the old command.
        }
        private static boolean same(Traversal a,Traversal b) {
            return a!=null && b!=null && a.pass().equals(b.pass()) && a.substage().equals(b.substage()) && a.physicalIndex()==b.physicalIndex()
                    && a.laneId()==b.laneId() && a.direction().equals(b.direction()) && a.startX()==b.startX() && a.startZ()==b.startZ()
                    && a.endX()==b.endX() && a.endZ()==b.endZ();
        }
        private static boolean commanded(MotionFrame before,MotionFrame after) {
            var a=before.traversal(); var b=after.traversal();
            return before.lifecycle()==Phase.RUNNING && after.lifecycle()==Phase.RUNNING && after.eligible()==before.eligible()+1
                    && same(a,b) && a.stage().equals("WALK") && b.stage().equals("WALK") && Set.of("LANE","TRANSIT").contains(b.substage())
                    && Set.of("EAST","WEST").contains(b.direction()) && after.owner()==BuildEngine.ControlOwner.NATIVE
                    && (after.keys()&1)!=0 && (after.keys()&(2|16|32|64|128|256))==0 && (after.keys()&(4|8))!=(4|8)
                    && b.supportTicks()==0 && b.yawTicks()==0;
        }
        void accept(MotionFrame frame) {
            if(frame.traversal()!=null) {
                int value=frame.traversal().noProgressTicks(); publicCounter(value);
                if(highwaterOrdinal<0 || value>highwater) { highwater=value; highwaterOrdinal=frame.ordinal(); }
            }
            if(frame.phase().equals("START")) { start=frame; return; }
            if(!frame.phase().equals("END")) return;
            if(frame.lifecycle()==Phase.RUNNING && frame.traversal()!=null) {
                var t=frame.traversal();
                phaseCallbacks.merge(t.pass()+":"+t.substage()+":"+t.physicalIndex()+":"+t.stage(),1L,Long::sum);
            }
            if(command!=null) {
                endpoint(command,start,frame);
                double delta=(frame.pose().x()-command.pose().x())*(geometry.direction().equals("WEST")?-1:1);
                callbacks++; gain+=delta; last=frame;
                if(accelerationGain<1) { accelerationCallbacks++; accelerationGain+=delta; }
                command=null;
            }
            if(geometry!=null && (!same(geometry,frame.traversal()) || !frame.traversal().stage().equals("WALK"))) finish(frame);
            if(start!=null && commanded(start,frame)) {
                requireOrdinaryPhysics(frame.pose().physics());
                if(geometry==null) { geometry=frame.traversal(); first=frame; last=frame; }
                command=frame;
            } else if(frame.lifecycle()==Phase.RUNNING && frame.traversal()!=null) {
                if(frame.traversal().stage().equals("WALK")) walkWaits++; else entryWaits++;
            }
        }
        private void finish(MotionFrame completion) {
            int sign=geometry.direction().equals("WEST")?-1:1;
            double remaining=Math.max(0,sign*(geometry.endX()+.5-first.pose().x())-1);
            boolean complete=completion!=null && sign*(geometry.endX()+.5-completion.pose().x())<=1;
            var classification=rate(callbacks,gain,remaining,complete);
            rows.add("segment="+(++segments)+"; pass="+geometry.pass()+"; substage="+geometry.substage()+"; physical="+geometry.physicalIndex()
                    +"; direction="+geometry.direction()+"; C="+callbacks+"; G="+Double.toHexString(gain)+"; R="+Double.toHexString(remaining)
                    +"; eightG="+(8*gain)+"; "+classification+"; accelerationC="+accelerationCallbacks+"; accelerationG="+accelerationGain
                    +"; cruiseTailC="+(callbacks-accelerationCallbacks)+"; cruiseTailG="+(gain-accelerationGain)
                    +"; firstOrdinal="+first.ordinal()+"; endpointOrdinal="+last.ordinal()+"; completionOrdinal="+completion.ordinal());
            allCallbacks+=callbacks; allGain+=gain;
            if(gain>=1) { longSegments++; longCallbacks+=callbacks; longGain+=gain; }
            else { shortCallbacks+=callbacks; shortGain+=gain; }
            geometry=null; first=null; last=null; callbacks=0; gain=0; accelerationCallbacks=0; accelerationGain=0;
        }
        String finish(ScenarioDefinition.Fixture fixture) {
            require(command==null,"Missing physical endpoint for last commanded callback");
            require(geometry==null,"Unfinished measured geometry cannot be called a completed span");
            require(longSegments==0 || longCallbacks<=8*longGain,"Aggregate long native pace exceeds eight callbacks/block");
            require(fixture!=ScenarioDefinition.Fixture.SCALE_128 || longSegments>=26,"SCALE lacks real long-segment calibration");
            return String.join("\n",rows)+"\nallC="+allCallbacks+"; allG="+allGain+"; rawAllRate="+(allCallbacks==0?"N/A":allCallbacks/allGain)
                    +"; longSegments="+longSegments+"; longC="+longCallbacks+"; longG="+longGain+"; calibratedRate="+(longSegments==0?"N/A":longCallbacks/longGain)
                    +"; shortC="+shortCallbacks+"; shortG="+shortGain+"; nonWalkStageCallbacks="+entryWaits+"; nonCommandedXWalkStageCallbacks="+walkWaits
                    +"; rawPhaseCallbacks="+phaseCallbacks
                    +"; originalPublicNoProgressHighwater="+highwater+"; witnessOrdinal="+highwaterOrdinal+"; marginTo40="+(40-highwater);
        }
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
        refused("later successful run substituted for failed run",()->ScenarioRunner.requireResultIdentity(
                new ScenarioRunner.ScenarioResult(UUID.randomUUID(),result.terminal(),definition.definitionHash(),"later",List.of(),artifacts),
                result,Set.of(run),artifacts,definition));
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
        verifyNativeProofGuards();
        System.out.println("TraversalAssertions closed-fixture/oracle self-checks passed; not native traversal evidence");
    }

    private static void verifyNativeProofGuards() {
        require(Pace.rate(1,.1,.1,true).equals("short; rawRate=10.0"),"Fractional native finish lost its raw rate");
        require(Pace.rate(0,0,0,true).equals("N/A"),"Zero movement was claimed as calibration");
        refused("short callbacks cannot round distance up",()->Pace.rate(2,.05,.05,true));
        refused("zero-gain commanded walk",()->Pace.rate(1,0,.1,true));
        refused("slow segment cannot hide in aggregate",()->Pace.rate(9,1,1,true));
        refused("unfinished fractional span",()->Pace.rate(1,.1,.1,false));
        var run=UUID.randomUUID();
        var command=proofFrame(run,1,"END",.5,1,BuildEngine.ControlOwner.NATIVE,Phase.RUNNING,false);
        var start=proofFrame(run,2,"START",.5,1,BuildEngine.ControlOwner.NATIVE,Phase.RUNNING,false);
        var tail=proofFrame(run,2,"END",.6,0,BuildEngine.ControlOwner.NONE,Phase.TERMINAL,false);
        Pace.endpoint(command,start,tail);
        refused("true pause censors physics",()->Pace.endpoint(command,
                proofFrame(run,2,"START",.5,0,BuildEngine.ControlOwner.NONE,Phase.PAUSED,true),tail));
        refused("replacement censors physics",()->Pace.endpoint(command,start,
                proofFrame(UUID.randomUUID(),2,"END",.6,0,BuildEngine.ControlOwner.NONE,Phase.TERMINAL,false)));
        refused("missing endpoint pose",()->Pace.endpoint(command,start,new MotionFrame(tail.ordinal(),tail.tick(),tail.phase(),run,
                tail.identities(),null,0,false,tail.owner(),tail.lifecycle(),null,null,null,2,0,null,0,0,null,null,null,null)));
        refused("public stationary highwater9",()->Pace.publicCounter(9));
        requireRepairEvidence(Set.of(1),Set.of(1),Set.of(1),Set.of(1),Set.of(1),Set.of(1));
        refused("missing causal removal record",()->requireRepairEvidence(Set.of(1),Set.of(1),Set.of(1),Set.of(),Set.of(1),Set.of(1)));
        refused("missing original native replacement action",()->requireRepairEvidence(Set.of(1),Set.of(),Set.of(1),Set.of(1),Set.of(1),Set.of(1)));
        refused("missing server native replacement",()->requireRepairEvidence(Set.of(1),Set.of(1),Set.of(1),Set.of(1),Set.of(),Set.of(1)));
        var actions=new Actions(run,Set.of(0,1));
        actions.accept(new BuildEngine.PlacementEffectResult(run,1,0,91,BuildEngine.EffectStatus.ACCEPTED));
        refused("reused run/action",()->actions.accept(new BuildEngine.PlacementEffectResult(run,1,1,92,BuildEngine.EffectStatus.ACCEPTED)));
        refused("foreign action run",()->actions.accept(new BuildEngine.PlacementEffectResult(UUID.randomUUID(),2,0,93,BuildEngine.EffectStatus.ACCEPTED)));
        refused("selected clean lane labeled BUILD",()->requireReverseBuild(ScenarioDefinition.Fixture.REVERSE_MULTI,2));
        var definition=ScenarioDefinition.load(ScenarioDefinition.Fixture.REVERSE_EVEN);
        var targets=new ArrayList<Target>();
        definition.expectedTargets().forEach((position,state)->targets.add(new Target(targets.size(),position,state)));
        var plan=new com.mapartrunner.core.model.BuildPlan("entry-guards",definition.fixture().dimensions(),targets);
        var history=new ForwardHistory(ScenarioDefinition.Fixture.REVERSE_EVEN,plan);
        refused("removal without retained forward END",()->history.requireRemoval(command));
        history.accept(command);
        refused("missing original forward END",()->history.accept(proofFrame(run,3,"END",.6,1,BuildEngine.ControlOwner.NATIVE,Phase.RUNNING,false)));
        refused("foreign forward END",()->history.accept(proofFrame(UUID.randomUUID(),2,"END",.6,1,BuildEngine.ControlOwner.NATIVE,Phase.RUNNING,false)));
        var lanes=new LanePlanner().plan(plan,2,5);
        var before=proofTraversal(command,new Traversal(1,null,"REVERSE","NEXT_ENTRY",1,0,0,7,11,0,7,"EAST","ENTER_YAW",1,2,1,0,0,20,0,false),0.5,7.5);
        var after=proofTraversal(tail,new Traversal(1,1,"REVERSE","LANE",1,0,0,7,11,0,7,"EAST","WALK",0,0,0,0,0,0,0,false),0.5,7.5);
        requireForwardBeforeReverse(after.traversal(),lanes.size(),lanes.size());
        refused("reverse label without full forward passage",()->requireForwardBeforeReverse(after.traversal(),lanes.size()-1,lanes.size()));
        checkTraversalPair(before,after,lanes,definition.origin());
        refused("correct Z wrong X",()->checkTraversalPair(before,proofTraversal(after,after.traversal(),5.5,7.5),lanes,definition.origin()));
        var one=new Traversal(1,null,"REVERSE","NEXT_ENTRY",1,0,0,7,11,0,7,"EAST","ENTER_YAW",0,1,0,0,0,20,0,false);
        refused("only one aligned yaw sample",()->checkTraversalPair(proofTraversal(before,one,.5,7.5),after,lanes,definition.origin()));
        var hop=new Traversal(1,null,"REVERSE","ENDPOINT",1,0,0,7,0,0,7,"EAST","ENTER_POSITION",0,0,0,0,0,0,0,false);
        refused("hop reset before centered entry",()->checkTraversalPair(proofTraversal(before,before.traversal(),5.5,7.5),
                proofTraversal(after,hop,5.5,7.5),lanes,definition.origin()));
    }

    private static MotionFrame proofFrame(UUID run,long tick,String phase,double x,int keys,BuildEngine.ControlOwner owner,Phase lifecycle,boolean paused) {
        var physics=new Physics(true,false,false,false,false,false,false,false,false,false,false,false,false,false,false,0,.08);
        return new MotionFrame(tick*2,tick,phase,run,new Identities(1,2,3,4,1,2,3,4,true,true,true,true),
                new Pose(x,66,2.5,0,-.0784000015258789,0,x,67.62,2.5,(int)tick,tick,-90,-90,-90,false,false,1,7,128,4.5,physics),
                keys,paused,owner,lifecycle,Activity.BUILD,null,null,phase.equals("START")?tick-1:tick,0,null,0,0,null,1,null,null);
    }

    private static MotionFrame proofTraversal(MotionFrame value,Traversal traversal,double x,double z) {
        var p=value.pose();
        return new MotionFrame(value.ordinal(),value.tick(),value.phase(),value.runId(),value.identities(),
                new Pose(x,p.y(),z,p.vx(),p.vy(),p.vz(),x,p.eyeY(),z,p.age(),p.worldTime(),p.yaw(),p.headYaw(),p.bodyYaw(),
                        false,false,1,7,128,4.5,p.physics()),value.keys(),value.paused(),value.owner(),Phase.RUNNING,value.activity(),null,null,
                value.eligible(),value.verify(),traversal,0,0,null,1,null,null);
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
            MotionFrame prior=null,retainedEnd=null;
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
                    var previous=retainedEnd;
                    capture.append(frame); prior=frame;
                    if(end && previous!=null)
                        refused("stale retained END identity",()->require(capture.isLatestEnd(previous),"Not latest END"));
                    if(end) retainedEnd=frame;
                    if(tick==1 && end) {
                        require(capture.isLatestEnd(frame),"Original retained END refused");
                        var equal=new MotionFrame(frame.ordinal(),frame.tick(),frame.phase(),frame.runId(),frame.identities(),frame.pose(),frame.keys(),
                                frame.paused(),frame.owner(),frame.lifecycle(),frame.activity(),frame.outcome(),frame.reason(),frame.eligible(),frame.verify(),
                                frame.traversal(),frame.pending(),frame.unfinished(),frame.effect(),frame.predictionSequence(),frame.fault0(),frame.fault1());
                        require(equal.equals(frame),"Reference guard did not construct an equal record");
                        refused("equal reconstructed END identity",()->require(capture.isLatestEnd(equal),"Not original END"));
                    }
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
