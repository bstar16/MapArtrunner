package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;

import java.util.*;

/** Pure fixture/oracle checks; no world access, effects, or trace API before compact capture exists. */
final class TraversalAssertions {
    private TraversalAssertions() { }

    static void verifyGuards() {
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
        System.out.println("TraversalAssertions closed-fixture/oracle self-checks passed; not native traversal evidence");
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
