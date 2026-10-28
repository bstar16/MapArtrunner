package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Test fixture data and coordinate lookup, independent of native registry initialization. */
final class SingleLaneFixture {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());

    private SingleLaneFixture() { }

    static BuildPlan plan() {
        var targets=new ArrayList<Target>();
        for(int x=0;x<12;x++) for(int z=0;z<5;z++) targets.add(new Target(x*5+z,new GridPos(x,0,z),STONE));
        return new BuildPlan(hash("single-lane-v1;12,1,5;"+targets),new GridSize(12,1,5),targets);
    }

    static int targetId(BuildPlan plan, GridPos position) {
        var matches=plan.targets().stream().filter(target -> target.relativePos().equals(position)).toList();
        if(matches.size()!=1) throw new IllegalArgumentException("Expected exactly one target at "+position+", found "+matches.size());
        return matches.getFirst().id();
    }

    static void verifyCoordinateLookup() {
        var plan=new BuildPlan("coordinate-lookup-self-check",new GridSize(12,1,5),List.of(
                new Target(91,new GridPos(0,0,4),STONE),new Target(7,new GridPos(11,0,0),STONE),
                new Target(33,new GridPos(0,0,2),STONE)));
        require(targetId(plan,new GridPos(11,0,0))==7,"Coordinate lookup (11,0,0) must resolve assigned ID7, not formula55");
        require(targetId(plan,new GridPos(0,0,4))==91,"Coordinate lookup outer (0,0,4) must resolve assigned ID91");
        require(targetId(plan,new GridPos(0,0,2))==33,"Coordinate lookup seed (0,0,2) must resolve assigned ID33");
        try { targetId(plan,new GridPos(1,0,1)); }
        catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Coordinate lookup accepted an absent coordinate");
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void require(boolean condition,String detail) { if(!condition) throw new AssertionError(detail); }
}
