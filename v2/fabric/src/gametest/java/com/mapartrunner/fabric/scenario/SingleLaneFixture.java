package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.fabric.schematic.SchematicPlanImporter;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Actual lane input provenance; coordinate lookup self-check remains native-independent. */
final class SingleLaneFixture {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());

    private SingleLaneFixture() { }

    static BuildPlan plan() {
        Path evidence=null;
        try {
            var game=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            var run=game.getParent(); var build=run==null?null:run.getParent();
            require(build!=null && run.getFileName().toString().equals("run") && build.getFileName().toString().equals("build"),
                    "Unmanaged lane import game directory: "+game);
            evidence=build.resolve("scenario-results").resolve("lane-import-"+UUID.randomUUID());
            Files.createDirectories(evidence);
            var source=evidence.resolve("grounded-single-lane.schem");
            try(var stream=SingleLaneFixture.class.getResourceAsStream("/schematic/grounded-single-lane.schem")) {
                require(stream!=null,"Missing classpath lane schematic");
                Files.copy(stream,source);
            }
            var bytes=Files.readAllBytes(source);
            var sha=hash(bytes);
            var input="resource=/schematic/grounded-single-lane.schem; path="+source+"; bytes="+bytes.length+"; rawSha256="+sha;
            Files.writeString(evidence.resolve("import.txt"),input+System.lineSeparator());
            require(sha.equals("7c194b89faf8339bf03b20b122b7b3b17f22064f1fb4e4139e1781bb4664ce0a"),"Changed classpath lane bytes: "+sha);
            var plan=SchematicPlanImporter.importPlan(source);
            var result="semantic="+plan.fingerprint()+"; dimensions="+plan.dimensions()+"; targets="+plan.targets();
            Files.writeString(evidence.resolve("import.txt"),result+System.lineSeparator(),StandardOpenOption.APPEND);
            System.out.println("MapArtRunner lane import: "+input+"; "+result+"; record="+evidence.resolve("import.txt"));
            return plan;
        } catch(IOException error) {
            var primary=new UncheckedIOException("Cannot retain/import lane source at "+evidence,error);
            retainFailure(evidence,primary); throw primary;
        } catch(RuntimeException | Error primary) {
            retainFailure(evidence,primary); throw primary;
        }
    }

    static BuildPlan verifyImportedFactory() {
        var plan=plan();
        require(plan.dimensions().equals(new GridSize(12,1,5)),"Imported factory dimensions must be 12x1x5");
        require(plan.targets().size()==60 && plan.targets().stream().allMatch(target -> target.block().equals(STONE)),
                "Imported factory must contain 60 complete stone states");
        int seed=targetId(plan,new GridPos(0,0,2));
        require(seed==24,"Imported factory seed (0,0,2) expected ID24, got ID"+seed);
        require(targetId(plan,new GridPos(0,0,4))==48,"Imported factory outer (0,0,4) expected ID48");
        require(targetId(plan,new GridPos(11,0,0))==11,"Imported factory omission (11,0,0) expected ID11");
        require(targetId(plan,new GridPos(0,0,0))==0,"Imported factory state (0,0,0) expected ID0");
        return plan;
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

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void retainFailure(Path evidence,Throwable primary) {
        if(evidence==null) return;
        try {
            var text=new StringWriter(); primary.printStackTrace(new PrintWriter(text));
            Files.writeString(evidence.resolve("failure.txt"),text.toString());
        } catch(IOException | RuntimeException | Error logging) { primary.addSuppressed(logging); }
    }

    private static void require(boolean condition,String detail) { if(!condition) throw new AssertionError(detail); }
}
