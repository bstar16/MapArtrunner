package com.mapartrunner.fabric.scenario;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.ForwardTraversal;
import com.mapartrunner.core.movement.MovementPolicy.Stage;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mapartrunner.fabric.scenario.TraversalCapture.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.concurrent.CompletableFuture;

/** Closed-profile fixture owner. No production reset API and no observation-time repairs. */
final class ScenarioRunner implements AutoCloseable {
    private enum SupportCase {
        ORDINARY, CONTROLLED;
        static SupportCase parse(String value) {
            if(value==null || value.equals("ordinary")) return ORDINARY;
            if(value.equals("controlled")) return CONTROLLED;
            throw new IllegalArgumentException("MAPART_TWO_LANE_SUPPORT_CASE requires exact ordinary or controlled");
        }
        String label() { return name().toLowerCase(Locale.ROOT); }
    }
    private static final List<RegistryEntry<EntityAttribute>> BASELINE_ATTRIBUTES=List.of(
            EntityAttributes.GRAVITY,EntityAttributes.MOVEMENT_SPEED,EntityAttributes.JUMP_STRENGTH,EntityAttributes.SNEAKING_SPEED);
    record ScenarioResult(UUID runId, BuildEngine.Snapshot terminal, String definitionHash,
                          String observedStableStartHash, List<String> mismatches, Path artifacts) {
        ScenarioResult { mismatches = List.copyOf(mismatches); }
    }
    private record Roots(Path module, Path game, Path build, Path evidence, Path saves) { }
    private record Side(JsonObject state, int age, long time, int serverTick, List<String> problems) { }
    private record Checkpoint(Side client, Side server, FabricBuildAdapter.Status status) { }
    private record Prior(FabricBuildAdapter.Status status, List<ScenarioObserver.Sample> samples,
                         PredictionFaults.Snapshot fault, PredictionFaults.StateSnapshot stateFault,
                         TraversalCapture.Sealed traversal) {
        Prior(FabricBuildAdapter.Status status,List<ScenarioObserver.Sample> samples,PredictionFaults.Snapshot fault,PredictionFaults.StateSnapshot stateFault) {
            this(status,samples,fault,stateFault,null);
        }
    }
    private final String supportSelector=System.getenv("MAPART_TWO_LANE_SUPPORT_CASE");
    private final SupportCase supportCase=SupportCase.parse(supportSelector);
    private final ClientGameTestContext context;
    private final ScenarioObserver.Watch watch;
    private final UUID invocation = UUID.randomUUID();
    private final Roots roots;
    private final Path artifacts;
    private final boolean callerAutoJump;
    private final int callerViewDistance;
    private final int expectedCountedRuns;
    private final ScenarioDefinition.Fixture fixture;
    private final ScenarioDefinition profileDefinition;
    private TestSingleplayerContext world;
    private MinecraftServer server;
    private ServerWorld serverWorld;
    private ServerPlayerEntity serverPlayer;
    private ClientWorld clientWorld;
    private ClientPlayerEntity clientPlayer;
    private ClientPlayNetworkHandler handler;
    private ClientConnection connection;
    private Path save;
    private ScenarioDefinition admitted;
    private BuildPlan plan;
    private GridPos origin;
    private BuildEngine.Settings settings;
    private String observedHash;
    private Checkpoint stable;
    private ScenarioResult previous;
    private int resetOrdinal;
    private boolean ready;
    private boolean closed;
    private boolean normalClose;
    private boolean optionsOwned;
    private int countedStarts;
    private final Set<UUID> runs = new HashSet<>();
    private UUID dirtyResponseRun;
    private UUID releasedFailedRun;
    private final List<ScenarioResult> assertedSuccesses = new ArrayList<>();
    private Checkpoint priorCountedStart;
    private PredictionFaults.SupportArmIdentity supportArm;
    private boolean supportReceipt;
    // Constructor diagnostics spend the first pre-UUID budget; first start adopts it without renewal.
    private TraversalCapture capture;
    private OutputBudget outputs;
    private Limits captureLimits;
    private Sealed sealed;
    private boolean compactCommitted;
    private Throwable compactArchiveFailure;
    private Throwable compactOriginalFailure;
    private int compactBudgetOrdinal;
    private CompactOwner compactOwner;
    private Map<String,CompactFile> compactFiles=Map.of();
    private record CompactOwner(OutputBudget budget,Sealed trace,UUID run,UUID invocation,int ordinal) { }
    private record CompactFile(String path,long bytes,String sha256) { }
    private final EnumSet<CheckpointKind> playerCheckpoints=EnumSet.noneOf(CheckpointKind.class);
    private List<GridPos> regionOrder=List.of();
    private Map<GridPos,BuildPlan.BlockSpec> finalTargets=Map.of();
    private int scalarOrdinal;
    // One run owns at most two fault receipts; archive/seal precedes clearing on reset.
    private final List<TraversalAssertions.Removal> removals=new ArrayList<>();
    private TraversalAssertions.ForwardHistory forwardHistory;
    private boolean removalConsumed;
    private ServerFrame lastTraversalServer;

    ScenarioRunner(ClientGameTestContext context, ScenarioObserver.Watch watch, int expectedCountedRuns) {
        this(context,watch,expectedCountedRuns,ScenarioDefinition.Fixture.TWO_LANE);
    }

    ScenarioRunner(ClientGameTestContext context,ScenarioObserver.Watch watch,int expectedCountedRuns,ScenarioDefinition.Fixture fixture) {
        this.fixture=Objects.requireNonNull(fixture);
        profileDefinition=ScenarioDefinition.load(fixture);
        require(validExpectedCount(expectedCountedRuns),"Expected counted-run total must be 1..2");
        this.expectedCountedRuns=expectedCountedRuns;
        this.context = Objects.requireNonNull(context); this.watch = Objects.requireNonNull(watch);
        roots = roots(); // Read-only loaded-module authority precedes evidence/world creation.
        try {
        callerViewDistance=context.computeOnClient(c -> c.options.getViewDistance().getValue());
        callerAutoJump = context.computeOnClient(c -> {
            require(c.world == null && c.getServer() == null && c.player == null,
                    "Runner must create its own world; cannot adopt an open world");
            var fault=PredictionFaults.resetSnapshot(); var status=MapArtClient.status();
            require(!fault.active(),"Preworld release guard requires no owned fault");
            rejected("preworld absent RESPONSE scope",() -> PredictionFaults.releaseResponsesForReset(c,null,null,invocation));
            require(fault.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status())
                            && c.world==null && c.getServer()==null && c.player==null,
                    "Preworld release refusal mutated fault/core/world");
            return c.options.getAutoJump().getValue();
        });
        if(baseline()) {
            verifyResetGuards();
            artifacts=roots.evidence.resolve("two-lane-"+invocation);
            checkedDirectory(roots,artifacts);
        } else {
            artifacts=roots.evidence.resolve("two-lane-"+invocation);
            checkedDirectory(roots,artifacts);
            initializeCompactBudget(1);
            verifyResetGuards();
        }
        System.out.println(baseline()?"MapArtRunner two-lane artifacts: " + artifacts:"MapArtRunner "+fixture+" artifacts: "+artifacts);
        verifyResourceCopies();
        verifyFilesystemGuards();
        } catch(RuntimeException | Error error) {
            if(!baseline() && outputs!=null) retainConstructorFailure(outputs,error);
            throw error;
        }
    }

    private void initializeCompactBudget(int ordinal) {
        var budgets=profileDefinition.data().getAsJsonObject("budgets");
        captureLimits=Limits.forRun(i(budgets,"runTicks"),i(budgets,"releaseTicks"),fixture.targetCount,profileDefinition.worldCells().size());
        var directory=artifacts.resolve("capture-"+ordinal); checkedDirectory(roots,directory);
        try { outputs=new OutputBudget(directory,captureLimits); }
        catch(IOException error) { throw new UncheckedIOException(error); }
        compactBudgetOrdinal=ordinal;
    }

    private boolean baseline() { return fixture==ScenarioDefinition.Fixture.TWO_LANE; }

    /** F4 calls before world creation; these are always-active checks, never Java assert. */
    static void verifyResetGuards() {
        var r = roots();
        var d = ScenarioDefinition.load();
        var token = UUID.randomUUID();
        var count = new int[1];
        Runnable mutation = () -> count[0]++;
        rejected("wrong invocation", () -> guarded(token, UUID.randomUUID(), r, r.game,
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("escaped game", () -> guarded(token, token, r, r.game.resolve("../.."),
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("redirected root", () -> guarded(token, token, r, r.module,
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("wrong definition/hash", () -> guarded(token, token, r, r.game,
                d.definitionHash(), "0".repeat(64), d.data(), mutation));
        var wrongDefinition=d.data(); wrongDefinition.getAsJsonObject("player").addProperty("selectedSlot",2);
        rejected("wrong definition values with copied hash", () -> guarded(token,token,r,r.game,
                d.definitionHash(),d.definitionHash(),wrongDefinition,mutation));
        var badRegion = d.data(); badRegion.getAsJsonObject("world").getAsJsonObject("ownedRegion")
                .getAsJsonObject("max").addProperty("x", 14);
        rejected("outside bounded region", () -> guarded(token, token, r, r.game,
                d.definitionHash(), d.definitionHash(), badRegion, mutation));
        var container = d.data(); container.getAsJsonObject("world").getAsJsonArray("containers").add(new JsonObject());
        rejected("container", () -> guarded(token, token, r, r.game,
                d.definitionHash(), d.definitionHash(), container, mutation));
        require(count[0] == 0, "Refused preflight reached a mutation callback");
        guarded(token,token,r,r.game,d.definitionHash(),d.definitionHash(),d.data(),mutation);
        require(count[0]==1,"Valid preflight did not reach positive-control callback");
        var creations=new int[1];
        rejected("escaped saves parent before world creation", () -> guardedWorldCreation(r,r.game,r.game,() -> creations[0]++));
        rejected("redirected saves parent before world creation", () -> guardedWorldCreation(r,r.saves,r.module,() -> creations[0]++));
        require(creations[0]==0,"Rejected saves parent reached native world-creation callback");
        guardedWorldCreation(r,r.saves,r.saves,() -> creations[0]++);
        require(creations[0]==1,"Valid saves-parent positive control did not reach creation callback");
        var defaults=playerDefaults(BASELINE_ATTRIBUTES);
        require(defaults.getBaseValue(EntityAttributes.GRAVITY)==.08D
                        && defaults.getBaseValue(EntityAttributes.MOVEMENT_SPEED)==(double).1F
                        && defaults.getBaseValue(EntityAttributes.JUMP_STRENGTH)==(double).42F
                        && defaults.getBaseValue(EntityAttributes.SNEAKING_SPEED)==.3D,
                "Pinned supported player attribute defaults changed");
        rejected("unsupported player flying-speed attribute",() -> playerDefaults(List.of(EntityAttributes.FLYING_SPEED)));
        require(new PlayerAbilities().pack().equals(new PlayerAbilities.Packed(false,false,false,false,true,.05F,.1F))
                        && new PlayerAbilities().pack().equals(abilities(d.data().getAsJsonObject("player"))),
                "Default player abilities, including separate fly speed, differ from fixture");
        verifyCausalClockGuards();
        verifySuccessOracleGuards();
        verifySupportGuards();
        verifyCloseGuards();
        verifyInvocationOutcomeGuards();
        var restores=new int[1];
        var check = r.evidence.resolve("archive-self-check-" + token);
        checkedDirectory(r, check);
        TraversalAssertions.verifyGuards(check.resolve("capture-guards"));
        rejected("archive failure aborts restoration", () -> archiveThenRestore(() -> {
            writeNew(r, check.resolve("incomplete.txt"), "retained injected archive failure");
            throw new UncheckedIOException(new IOException("fixture archive-write injection"));
        }, () -> restores[0]++));
        require(restores[0] == 0 && !Files.exists(check.resolve("COMMITTED")),
                "Failed archive permitted reset restoration/commit");
        verifyFailureCaptureGuards(r,check,d);
        System.out.println("ScenarioRunner pre-world guard/archive self-checks passed; artifacts=" + check);
    }

    private void verifyFilesystemGuards() {
        try {
            for (String kind:List.of("missing","existing","redirected","dangling")) {
                var module=artifacts.resolve("filesystem-"+kind);
                var build=module.resolve("build"); var game=build.resolve("run/clientGameTest");
                Files.createDirectories(game);
                var candidate=new Roots(module,game,build,build.resolve("scenario-results"),game.resolve("saves"));
                String nativeOutput="no link";
                if (kind.equals("existing")) Files.createDirectory(candidate.saves);
                if (kind.equals("redirected") || kind.equals("dangling")) {
                    var target=module.resolve("separate-target");
                    if (kind.equals("redirected")) Files.createDirectory(target);
                    var helper=new ProcessBuilder("cmd.exe","/c","mklink","/J",candidate.saves.toString(),target.toString())
                            .redirectErrorStream(true).start();
                    if (!helper.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) {
                        helper.destroyForcibly(); throw new AssertionError("Owned junction helper timeout");
                    }
                    nativeOutput=new String(helper.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                    require(helper.exitValue()==0,"Owned junction creation failed: "+nativeOutput);
                    require(Files.exists(candidate.saves,LinkOption.NOFOLLOW_LINKS),"Native junction absent");
                }
                var callbacks=new int[1]; Runnable creation=() -> callbacks[0]++;
                if (kind.equals("redirected") || kind.equals("dangling")) {
                    rejected("actual Windows "+kind+" saves parent",() -> guardedWorldCreation(candidate,candidate.saves,candidate.saves,creation));
                    require(callbacks[0]==0,"Refused native filesystem reached world creation callback");
                } else {
                    guardedWorldCreation(candidate,candidate.saves,candidate.saves,creation);
                    require(callbacks[0]==1,"Valid native filesystem failed creation-boundary positive control");
                }
                writeHeader(artifacts.resolve("filesystem-"+kind+".txt"),"actualWindows=true; saves="+candidate.saves
                        +"; noFollowExists="+Files.exists(candidate.saves,LinkOption.NOFOLLOW_LINKS)
                        +"; followsDirectory="+Files.isDirectory(candidate.saves)+"; creationBoundaryCallbacks="+callbacks[0]+"; "+nativeOutput);
            }
            verifyEvidenceInventory();
            context.runOnClient(c -> {
                requireUncreatedClient(c);
                writeHeader(artifacts.resolve("native-level-storage-before-world.txt"),c.getLevelStorage().getSavesDirectory().toAbsolutePath().toString());
            });
        } catch (IOException e) { throw new UncheckedIOException(e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private void verifyEvidenceInventory() throws IOException {
        var fixture=artifacts.resolve("inventory-check");
        for (var directory:List.of(fixture,fixture.resolve("left"),fixture.resolve("right"),fixture.resolve("empty-dir")))
            checkedDirectory(roots,directory);
        var mutable=fixture.resolve("mutable.bin");
        var original=new byte[]{0,1,(byte)255,127}; var replacement=new byte[]{0,2,(byte)255,127};
        var sentinel=artifacts.resolve("filesystem-redirected/separate-target/sentinel.bin");
        var files=Map.of(mutable,original,fixture.resolve("left/same.bin"),original,
                fixture.resolve("right/same.bin"),replacement,fixture.resolve("empty.bin"),new byte[0],
                fixture.resolve("hidden.bin"),new byte[]{5},sentinel,new byte[]{3,0,4});
        for (var file:files.entrySet()) Files.write(file.getKey(),file.getValue(),StandardOpenOption.CREATE_NEW);
        Files.setAttribute(fixture.resolve("hidden.bin"),"dos:hidden",true,LinkOption.NOFOLLOW_LINKS);
        var modified=Files.getLastModifiedTime(mutable,LinkOption.NOFOLLOW_LINKS);
        var sentinelMetadata=Files.readAttributes(sentinel,"dos:size,creationTime,lastModifiedTime,attributes",LinkOption.NOFOLLOW_LINKS);
        var first=evidenceFiles(); var second=evidenceFiles();
        require(first.equals(second),"Read-only evidence inventory changed owned archive");
        for (var file:files.entrySet()) {
            var row=evidenceRow(first,file.getKey());
            require(row.kind.equals("FILE") && row.size==file.getValue().length && row.sha256.equals(evidenceHash(file.getValue()))
                            && (row.windowsAttributes&1024)==0,"Missing or incorrect ordinary evidence row: "+row);
        }
        require((evidenceRow(first,fixture.resolve("hidden.bin")).windowsAttributes&2)!=0,"Hidden evidence file lost its DOS flag");
        for (var directory:List.of(fixture,fixture.resolve("left"),fixture.resolve("right"),fixture.resolve("empty-dir"))) {
            var row=evidenceRow(first,directory);
            require(row.kind.equals("DIRECTORY") && row.sha256==null && (row.windowsAttributes&16)!=0,
                    "Ordinary/empty evidence directory omitted: "+row);
        }
        for (String kind:List.of("redirected","dangling")) {
            var link=artifacts.resolve("filesystem-"+kind+"/build/run/clientGameTest/saves");
            var row=evidenceRow(first,link);
            require(row.kind.equals("REPARSE") && row.windowsAttributes==1040 && row.sha256==null,
                    "Owned junction was not inventoried as an opaque no-follow entry: "+row);
            require(first.stream().noneMatch(e -> e.relativePath.startsWith(row.relativePath+link.getFileSystem().getSeparator())),
                    "Evidence inventory followed a junction alias: "+row.relativePath);
            var receipt=artifacts.resolve("filesystem-"+kind+".txt");
            require(evidenceRow(first,receipt).sha256.equals(evidenceHash(Files.readAllBytes(receipt))),
                    "Native junction creation provenance was not hashed");
        }
        require(Arrays.equals(Files.readAllBytes(sentinel),files.get(sentinel))
                        && sentinelMetadata.equals(Files.readAttributes(sentinel,"dos:size,creationTime,lastModifiedTime,attributes",LinkOption.NOFOLLOW_LINKS)),
                "Read-only inventory changed the ordinary redirected-target sentinel");
        Files.write(mutable,replacement,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
        Files.setLastModifiedTime(mutable,modified);
        var changed=evidenceFiles(); var before=evidenceRow(first,mutable); var after=evidenceRow(changed,mutable);
        require(!first.equals(changed) && !before.sha256.equals(after.sha256)
                        && after.equals(new EvidenceEntry(before.relativePath,before.kind,before.size,before.creationTime,
                            before.modifiedTime,before.windowsAttributes,evidenceHash(replacement)))
                        && first.stream().filter(e -> !e.relativePath.equals(before.relativePath)).toList()
                            .equals(changed.stream().filter(e -> !e.relativePath.equals(before.relativePath)).toList()),
                "Same-length evidence byte change was missed or changed unrelated metadata/entries");
        var proof=new JsonObject();
        proof.addProperty("scope","actual no-follow Windows entries; reparse targets remain opaque; creation receipts are provenance");
        proof.addProperty("originalBytesRetainedAt",fixture.resolve("left/same.bin").toString());
        proof.addProperty("finalBytesRetainedAt",fixture.resolve("right/same.bin").toString());
        proof.add("first",new Gson().toJsonTree(first)); proof.add("second",new Gson().toJsonTree(second));
        proof.add("afterSameLengthChange",new Gson().toJsonTree(changed));
        writeHeader(artifacts.resolve("evidence-inventory-selfcheck.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof));
        System.out.println("ScenarioRunner no-follow evidence inventory self-checks passed");
    }

    private EvidenceEntry evidenceRow(List<EvidenceEntry> rows, Path path) {
        var matches=rows.stream().filter(e -> e.relativePath.equals(artifacts.relativize(path).toString())).toList();
        require(matches.size()==1,"Evidence row missing or duplicated: "+path);
        return matches.getFirst();
    }

    private void verifyResourceCopies() {
        for (String name:List.of("definition.json",fixture.sourceName())) {
            try {
                var source=roots.module.getParent().resolve("test-fixtures").resolve(fixture.directory).resolve(name);
                var resource=Path.of(Objects.requireNonNull(ScenarioDefinition.class.getResource(profileDefinition.resourceDirectory()+name)).toURI());
                require(real(resource).equals(roots.build.resolve("resources/gametest").resolve(fixture.directory).resolve(name)),
                        "Loaded fixture resource escaped processed gametest resources");
                var raw=Files.readAllBytes(resource);
                require(Arrays.equals(raw,Files.readAllBytes(source)),"Source/processed/loaded resource differs: "+name);
                if(name.equals("definition.json")) profileDefinition.requireValues(fixture,profileDefinition.definitionHash(),
                        JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject());
                else require(profileDefinition.sourceMatches(raw),"Source differs from immutable admitted bytes");
                writeHeader(artifacts.resolve("resource-"+name+".txt"),"source="+source+"; loaded="+resource
                        +"; size="+raw.length+"; sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            } catch (java.net.URISyntaxException | IOException e) { throw new IllegalStateException(e); }
            catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
    }

    void reset(ScenarioDefinition definition) {
        preflight(definition); // Stale authority cannot invalidate readiness, stop, or archive.
        require(baseline() || compactArchiveFailure==null,"Reset refuses an earlier failed compact archive");
        require(baseline() || outputs.failure().isEmpty() && (resetOrdinal==0 || capture!=null),
                "Reset refuses failed output or renewal of an unadopted compact budget");
        if(baseline() && !supportReceipt) {
            writeNew(roots,artifacts.resolve("support-coverage.txt"),"invocation="+invocation+"; definition="+definition.definitionHash()
                    +"; case="+supportCase.label()+"; selector="+(supportSelector==null?"absent":supportSelector)
                    +"; expectedCounted="+expectedCountedRuns+"; meaning="+supportMeaning());
            supportReceipt=true;
        }
        boolean release = context.computeOnClient(c -> {
            var fault=PredictionFaults.resetSnapshot();
            require(fault.failure().isEmpty(),"Reset refuses failed fault diagnostics");
            if (fault.active()) {
                require(world!=null && dirtyResponseRun!=null && releasedFailedRun==null && previous!=null
                                && previous.runId().equals(dirtyResponseRun) && runs.contains(dirtyResponseRun)
                                && previous.mismatches().equals(List.of("expected dirty FAILED"))
                                && MapArtClient.status().engine().equals(previous.terminal()),
                        "Reset refuses a fault outside the owned expected dirty failure");
                PredictionFaults.validateResponsesForReset(c,server,save,dirtyResponseRun);
                return true;
            }
            require(fault.responses().stream().noneMatch(r -> runs.contains(r.runId()) && !r.runId().equals(releasedFailedRun)),
                    "Reset refuses discarded/partially released owned responses");
            return false;
        });
        ready = false;
        if (world == null) createOwnedWorld();
        preflight(definition);
        Prior prior = context.computeOnClient(c -> {
            requireClient(c);
            MapArtClient.stop();
            requireReleased(c);
            // Copy before clear/arm: both owners retain diagnostics across stop.
            if(!baseline() && capture!=null && sealed==null) sealed=watch.sealTraversal();
            return new Prior(MapArtClient.status(), baseline()?watch.copy():List.of(), PredictionFaults.snapshot(), PredictionFaults.stateSnapshot(),sealed);
        });
        var stage = artifacts.resolve("reset-" + (++resetOrdinal));
        archiveThenRestore(() -> {
            if(baseline() || capture!=null) archive(stage,prior);
            else {
                checkedDirectory(roots,stage);
                scalar("initial-reset.txt","New owned world; no prior owned run/trace to discard; constructor budget remains open for actual start");
            }
        }, () -> {
            int consumed=release ? releaseForReset(stage,prior) : 0;
            context.runOnClient(c -> { requireClient(c); PredictionFaults.clear(); watch.clear(); });
            if(!baseline()) {
                boolean priorRun=capture!=null;
                // Prior references keep their old budget/ordinal until archive validation and owner clear finish.
                capture=null; sealed=null; compactCommitted=false; playerCheckpoints.clear(); scalarOrdinal=0;
                removals.clear(); forwardHistory=null; removalConsumed=false; lastTraversalServer=null;
                if(priorRun) { compactOwner=null; compactFiles=Map.of(); initializeCompactBudget(resetOrdinal); }
                regionOrder=definition.worldCells().keySet().stream().sorted(Comparator.comparingInt(GridPos::x)
                        .thenComparingInt(GridPos::y).thenComparingInt(GridPos::z)).toList();
            }
            restore(definition);
            plan = definition.importPlan(stage.resolve("retained-source.schem"));
            if(baseline()) writeNew(roots,stage.resolve("imported-plan.txt"),"definitionSha256="+definition.definitionHash()
                    +"; dimensions="+plan.dimensions()+"; fingerprint="+plan.fingerprint()+"; targetCount="+plan.targets().size()+"\n"
                    +String.join("\n",plan.targets().stream().map(Object::toString).toList())+"\n");
            else scalar("imported-plan.txt","definition="+definition.definitionHash()+"; dimensions="+plan.dimensions()
                    +"; fingerprint="+plan.fingerprint()+"; targets="+plan.targets().size());
            var source = definition.data().getAsJsonObject("schematic");
            origin = grid(source.getAsJsonObject("origin"));
            var s = definition.data().getAsJsonObject("settings");
            settings = new BuildEngine.Settings(i(s,"halfWidth"), i(s,"stride"), b(s,"constantSprint"), i(s,"reservedHotbarSlots"));
            admitted = definition;
            if(!baseline()) finalTargets=TraversalAssertions.expectedWorldTargets(definition,plan.targets());
            awaitStable(stage,consumed);
            ready = true; // Only two independent advancing post-physics checkpoints grant start authority.
        });
    }

    private UUID start(ScenarioDefinition definition, boolean counted) {
        preflight(definition);
        require(baseline() || compactArchiveFailure==null,"Start refuses an earlier failed compact archive");
        require(ready && admitted != null && definition.definitionHash().equals(admitted.definitionHash()),
                "Run requires this invocation's successful reset");
        require(counted ? countedStarts<i(definition.data().getAsJsonObject("budgets"),"repeatMaximum") : runs.isEmpty(),
                "Repeat bound exceeded or dirty prelude was not first");
        if (counted) countedStarts++;
        ready = false; // Consume before facade reentry; failure cannot reuse readiness.
        UUID started=context.computeOnClient(c -> {
            requireClient(c); requireReleased(c);
            var now = readClient(c, true);
            if(!baseline()) now=withRegion(now,region(c.player,true,"prestart-client"));
            require(now.problems.isEmpty() && now.state.equals(stable.client.state), "Start state changed without physics");
            UUID fresh = MapArtClient.start(plan, origin, settings);
            require(runs.add(fresh), "Facade reused a run UUID");
            var bound = MapArtClient.status().engine().lifecycle().context().orElseThrow();
            require(bound.runId().equals(fresh) && bound.planFingerprint().equals(plan.fingerprint())
                    && bound.origin().equals(origin) && bound.settingsFingerprint().equals(settings.fingerprint())
                    && bound.dimension().equals("minecraft:overworld"), "Facade bound another start request");
            if(baseline()) watch.arm(c, plan);
            else {
                try { capture=new TraversalCapture(outputs.root(),fresh,captureLimits,admitted.removalIds(plan),outputs); }
                catch(IOException error) { throw new UncheckedIOException(error); }
                forwardHistory=new TraversalAssertions.ForwardHistory(fixture,plan);
                watch.armTraversal(c,plan,capture);
            }
            if(baseline() && counted && supportCase==SupportCase.CONTROLLED) {
                supportArm=PredictionFaults.armSupportAvailability(c,server,save,fresh,plan,settings,watch).identity();
                verifySupportArmRefusals(c,fresh);
            }
            return fresh;
        });
        if (counted && countedStarts==1 && releasedFailedRun!=null)
            verifyReleaseRefusal("old-failed-after-fresh-start",server,save,releasedFailedRun);
        return started;
    }

    private String supportMeaning() {
        return supportCase==SupportCase.ORDINARY ? "actual availability unchanged; moving END fresh OBSERVED required-footprint proof"
                : "simulated READY-only one-cell eligibility gate; mandatory hold/native action/ACCEPTED/PREDICTED/later OBSERVED/later SOUTH chain; additional to ordinary";
    }

    private void verifySupportArmRefusals(MinecraftClient c,UUID id) {
        var before=PredictionFaults.resetSnapshot(); var status=MapArtClient.status();
        rejected("support duplicate arm",() -> PredictionFaults.armSupportAvailability(c,server,save,id,plan,settings,watch));
        rejected("support wrong run release",() -> PredictionFaults.releaseSupportAvailability(c,server,save,UUID.randomUUID(),null));
        rejected("support wrong save release",() -> PredictionFaults.releaseSupportAvailability(c,server,save.resolve("foreign"),id,null));
        rejected("support wrong server release",() -> PredictionFaults.releaseSupportAvailability(c,null,save,id,null));
        rejected("support early release",() -> PredictionFaults.releaseSupportAvailability(c,server,save,id,null));
        rejected("support still-running close",() -> PredictionFaults.cancelSupportAvailabilityForClose(c,server,save,id,supportArm));
        require(before.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status()),"Support argument refusal mutated owner/core");
    }

    private record Live(ScenarioObserver.Sample sample, Side client, Side server,
                        PredictionFaults.Snapshot fault, List<String> pending, int nativeSequence,
                        String nativeIdentities) { }

    /** The dirty prelude is separate from the two counted successes; every start retains its UUID. */
    void failedFirst(ScenarioDefinition definition) {
        require(baseline(),"Failed-first RESPONSE prelude belongs only to TWO_LANE");
        UUID id = start(definition,false);
        Live initial = live();
        Live prior = initial;
        Live current = initial;
        boolean armed = false;
        boolean refusals = false;
        long armedEligible=-1;
        for (int tick=0; tick<i(definition.data().getAsJsonObject("budgets"),"runTicks"); tick++) {
            context.waitTick();
            current = live();
            writeNew(roots,artifacts.resolve("dirty-"+tick+".txt"),current.toString());
            var engine=current.sample.status().engine();
            require(current.sample.status().failure().isEmpty(),"Dirty prelude adapter failure: "+current.sample.status());
            if (engine.lifecycle().phase()==Phase.TERMINAL) break;
            if (armed && !refusals && !current.fault.responses().isEmpty()) {
                verifyLiveRefusals();
                refusals=true;
            }
            var samples=context.computeOnClient(c -> watch.copy());
            boolean accepted=samples.stream().flatMap(s -> s.status().engine().effectResults().stream())
                    .anyMatch(e -> e.runId().equals(id) && e.status()==BuildEngine.EffectStatus.ACCEPTED);
            boolean clientMoved=current.sample.pose().x()>initial.sample.pose().x()+.75;
            boolean serverMoved=Double.valueOf(current.server.state.get("x").getAsString())
                    >Double.valueOf(initial.server.state.get("x").getAsString())+.75;
            if (!armed && accepted && clientMoved && serverMoved
                    && usable(current.client)<128 && usable(current.server)<128 && serverStoneCount()>1) {
                writeNew(roots,artifacts.resolve("genuine-work-before-arm.txt"),"accepted native action; independent changed server stone; consumed stock; both-side displacement\n"+current);
                context.runOnClient(c -> { requireClient(c); PredictionFaults.arm(c,server,save,id); });
                armed=true;
                armedEligible=engine.eligibleTicks();
                writeNew(roots,artifacts.resolve("response-arm.txt"),"armedEligible="+armedEligible+"; before="+current+"; after="+live());
            }
            prior=current;
        }
        var terminal=current.sample.status().engine();
        writeNew(roots,artifacts.resolve("dirty-terminal.txt"),"prior="+prior+"\nterminal="+current);
        require(armed && refusals && current.fault.active() && current.fault.failure().isEmpty() && !current.fault.responses().isEmpty(),
                "Dirty prelude lacked actual owned RESPONSE capture: "+current);
        require(terminal.lifecycle().phase()==Phase.TERMINAL && terminal.lifecycle().outcome().orElse(null)==Outcome.FAILED,
                "Dirty prelude did not reach production FAILED: "+terminal);
        var samples=context.computeOnClient(c -> watch.copy());
        var held=current.fault.responses().getFirst();
        var diagnostic=terminal.diagnostic().orElseThrow();
        require(terminal.lifecycle().context().orElseThrow().runId().equals(id)
                        && diagnostic.reason()==BuildEngine.Reason.PENDING_EVIDENCE_TIMEOUT
                        && diagnostic.detail().equals("Pending evidence reached eligible age 80") && terminal.verifyTicks()==0,
                "Unexpected canonical dirty deadline: "+terminal);
        var frames=samples.stream().map(s -> {
            var engine=s.status().engine(); var predicted=new HashSet<Integer>();
            for (var observation:s.observations()) if (observation.runId().equals(id)
                    && observation.evidence()==com.mapartrunner.core.placement.PlacementObservation.Evidence.PREDICTED)
                predicted.add(observation.targetId());
            return new ClockFrame(engine.lifecycle().context().orElseThrow().runId(),s.tick(),s.phase(),engine.lifecycle().phase(),
                    engine.eligibleTicks(),engine.pendingTargetIds(),engine.effectResults(),predicted);
        }).toList();
        var cause=correlateDirtyCause(id,frames,armedEligible,held.eligibleTick(),terminal.eligibleTicks());
        var input=samples.stream().filter(s -> s.tick()==cause.preterminal.observerTick && s.phase().equals(cause.preterminal.observerPhase))
                .findFirst().orElseThrow().status().engine();
        require(input.lifecycle().phase()==Phase.RUNNING && input.lifecycle().context().orElseThrow().runId().equals(id)
                        && input.eligibleTicks()==terminal.eligibleTicks()-1 && input.verifyTicks()==0,
                "Missing immediate current-run preterminal decision: "+input);
        writeNew(roots,artifacts.resolve("dirty-cause.txt"),"winning="+diagnostic+"; subsequentWitness="+cause.subsequentWitness
                +"; firstDeadlineOwner="+cause.deadlineOwner+"; preterminalPendingIds="+cause.preterminal.pending
                +"; outstandingAttemptTable="+cause.outstanding+"; preterminalObserver="+cause.preterminal
                +"; terminalObserver="+cause.terminal+"; terminalEligibleAge="+(terminal.eligibleTicks()-cause.deadlineOwner.issuedEligible)
                +"; predecision="+input+"; terminal="+terminal
                +"; responseDomain=capture eligible/player age/sequence, no action-target bijection; capturedOriginalInvocations="+current.fault.responses());
        previous=new ScenarioResult(id,terminal,definition.definitionHash(),observedHash,List.of("expected dirty FAILED"),artifacts);
        Live released=current;
        for (int tick=0;tick<20;tick++) {
            context.waitTick();
            Live next=live();
            context.runOnClient(ScenarioRunner::requireReleased);
            require(next.sample.status().engine().equals(terminal),"Terminal engine advanced during release20");
            require(next.sample.pose().age()>released.sample.pose().age() && next.client.time>released.client.time
                    && next.server.age>released.server.age && next.server.time>released.server.time
                    && next.server.serverTick>released.server.serverTick,"Release20 lacked independent real physics");
            writeNew(roots,artifacts.resolve("dirty-release-"+tick+".txt"),next.toString());
            released=next;
        }
        var priorArchive=context.computeOnClient(c -> new Prior(MapArtClient.status(),watch.copy(),PredictionFaults.snapshot(),PredictionFaults.stateSnapshot()));
        archive(artifacts.resolve("dirty-failed-before-drain"),priorArchive);
        var beforeDrain=live();
        writeNew(roots,artifacts.resolve("held-response-before-reset.txt"),beforeDrain.toString());
        require(!beforeDrain.pending.isEmpty() && beforeDrain.fault.active() && beforeDrain.fault.failure().isEmpty()
                        && beforeDrain.sample.status().engine().equals(terminal)
                        && beforeDrain.fault.responses().equals(priorArchive.fault.responses()),
                "Reset continuation lacks actual held native pending after canonical FAILED/release20/archive");
        dirtyResponseRun=id;
        verifyReleaseRefusal("failed-wrong-server",null,save,id);
        verifyReleaseRefusal("failed-wrong-save",server,save.resolve("foreign"),id);
        verifyReleaseRefusal("failed-wrong-uuid",server,save,UUID.randomUUID());
    }

    private int releaseForReset(Path stage, Prior prior) {
        Live before=live();
        require(before.fault.equals(prior.fault) && before.sample.status().equals(prior.status)
                        && !before.pending.isEmpty() && Files.isRegularFile(stage.resolve("COMMITTED")),
                "Release lost committed failure/scope/native pending precondition");
        writeNew(roots,stage.resolve("response-release-before.txt"),before.toString());
        try {
            var captured=context.computeOnClient(c -> {
                requireClient(c);
                return PredictionFaults.releaseResponsesForReset(c,server,save,dirtyResponseRun);
            });
            require(captured.equals(prior.fault.responses()),"Native release did not retain original arrival order");
            writeNew(roots,stage.resolve("response-release-result.txt"),"originalInvocations="+captured);
        } catch (RuntimeException | Error error) {
            capture(error,() -> writeNew(roots,stage.resolve("response-release-error.txt"),trace(error)));
            capture(error,() -> writeNew(roots,stage.resolve("response-release-after-error.txt"),live().toString()));
            throw error; // Scope is already consumed on native failure; restoration and another release are forbidden.
        }
        int consumed=0;
        int budget=i(admitted.data().getAsJsonObject("budgets"),"readinessTicks");
        while (true) {
            Live after=live();
            writeNew(roots,stage.resolve("response-release-after-"+consumed+".txt"),after.toString());
            context.runOnClient(ScenarioRunner::requireReleased);
            require(after.sample.status().equals(before.sample.status()) && !after.fault.active() && after.fault.failure().isEmpty()
                            && after.fault.responses().equals(before.fault.responses())
                            && after.nativeSequence==before.nativeSequence
                            && after.nativeIdentities.equals(before.nativeIdentities)
                            && after.server.state.get("blocks").equals(before.server.state.get("blocks"))
                            && after.server.state.get("inventory").equals(before.server.state.get("inventory")),
                    "Native reconciliation changed frozen core/controls/identity/independent server truth");
            if (after.pending.isEmpty() && after.client.state.get("blocks").equals(after.server.state.get("blocks"))) break;
            require(consumed<budget,"Native pending/raw blocks did not reconcile within shared readiness budget");
            context.waitTick(); consumed++;
        }
        releasedFailedRun=dirtyResponseRun;
        verifyReleaseRefusal("repeated-release",server,save,releasedFailedRun);
        writeNew(roots,stage.resolve("response-release-budget.txt"),"consumed="+consumed+"; remaining="+(budget-consumed)+"; sharedReadinessTicks="+budget);
        return consumed;
    }

    private void verifyReleaseRefusal(String label, MinecraftServer requestedServer, Path requestedSave, UUID requestedRun) {
        Live before=live(); var files=evidenceFiles();
        context.runOnClient(c -> {
            requireClient(c);
            rejected(label,() -> PredictionFaults.releaseResponsesForReset(c,requestedServer,requestedSave,requestedRun));
        });
        Live after=live();
        require(before.equals(after) && files.equals(evidenceFiles()),
                "Release refusal mutated core/controls/ledger/fault/native/player/server/archive: "+label+"; before="+before+"; after="+after);
        writeNew(roots,artifacts.resolve("release-refusal-"+label+".txt"),"currentFaultMode="
                +(before.fault.support().filter(s -> s.state()==PredictionFaults.SupportState.ARMED).isPresent()?"SUPPORT_AVAILABILITY":before.fault.active()?"RESPONSE":"inactive")
                +"; unchangedArchive="+files+"\nbefore="+before+"\nafter="+after);
    }

    private record ClockFrame(UUID runId, long observerTick, String observerPhase, Phase lifecycle, long eligible,
                              Set<Integer> pending, List<BuildEngine.PlacementEffectResult> feedback, Set<Integer> predicted) {
        ClockFrame { pending=Set.copyOf(pending); feedback=List.copyOf(feedback); predicted=Set.copyOf(predicted); }
    }
    private record PendingAttempt(BuildEngine.PlacementEffectResult effect, long issuedEligible, long issueObserverTick,
                                  String issueObserverPhase, long feedbackEligible, long feedbackObserverTick, String feedbackObserverPhase) { }
    private record DirtyCause(PendingAttempt subsequentWitness, PendingAttempt deadlineOwner, List<PendingAttempt> outstanding,
                              ClockFrame preterminal, ClockFrame terminal) {
        DirtyCause { outstanding=List.copyOf(outstanding); }
    }

    private static DirtyCause correlateDirtyCause(UUID run, List<ClockFrame> observed, long armEligible, long captureEligible,
                                                  long terminalEligible) {
        int end=0;
        while (end<observed.size() && observed.get(end).lifecycle!=Phase.TERMINAL) end++;
        require(end<observed.size() && (end+1)%2==0,"Missing complete causal terminal boundary");
        var frames=observed.subList(0,end+1);
        for (int i=0;i<frames.size();i+=2) {
            var start=frames.get(i); var finish=frames.get(i+1);
            require(start.runId.equals(run) && finish.runId.equals(run) && start.observerPhase.equals("START")
                            && finish.observerPhase.equals("END") && start.observerTick==finish.observerTick
                            && start.lifecycle==Phase.RUNNING && finish.lifecycle==(i+2==frames.size() ? Phase.TERMINAL : Phase.RUNNING)
                            && finish.eligible==start.eligible+1,"Broken current-run causal callback pair: "+start+" / "+finish);
            if (i==0) require(start.eligible==0 && start.pending.isEmpty(),"Missing initial absent causal boundary");
            else {
                var previous=frames.get(i-1);
                require(start.observerTick==previous.observerTick+1 && start.eligible==previous.eligible
                                && start.pending.equals(previous.pending),"Missing or discontinuous causal callback boundary");
            }
        }
        var terminal=frames.getLast(); var preterminal=frames.get(frames.size()-2);
        require(terminal.eligible==terminalEligible,"Causal trace does not reach this terminal callback");
        var feedback=frames.stream().flatMap(f -> f.feedback.stream()).distinct().toList();
        require(feedback.stream().allMatch(e -> e.runId().equals(run))
                        && feedback.stream().map(BuildEngine.PlacementEffectResult::actionId).distinct().count()==feedback.size(),
                "Foreign or ambiguous causal action identity across feedback statuses");
        var accepted=feedback.stream().filter(e -> e.status()==BuildEngine.EffectStatus.ACCEPTED).toList();
        var attempts=new ArrayList<PendingAttempt>();
        for (int target:accepted.stream().map(BuildEngine.PlacementEffectResult::targetId).distinct().sorted().toList()) {
            var matches=feedback.stream().filter(e -> e.targetId()==target).toList();
            require(matches.size()==1,"Ambiguous feedback target reuse: "+target);
            var effect=matches.getFirst(); int first=0;
            while (first<frames.size() && !frames.get(first).pending.contains(target)) first++;
            require(first>0 && first<frames.size(),"Missing accepted target issuance boundary: "+target);
            var issue=frames.get(first); var absent=frames.get(first-1);
            require(issue.observerPhase.equals("END") && absent.observerPhase.equals("START")
                            && issue.observerTick==absent.observerTick && !absent.pending.contains(target),
                    "Missing absent-to-pending issuance callback: "+target);
            // Synchronous recordIssuedAttempt precedes END; accepted feedback preserves that adapter tick on the NEXT callback.
            var firstFeedback=frames.stream().filter(f -> f.feedback.contains(effect)).findFirst().orElseThrow();
            require(firstFeedback.observerPhase.equals("END") && firstFeedback.eligible==issue.eligible+1,
                    "Accepted feedback does not follow the proven issuance callback: "+effect);
            if (preterminal.pending.contains(target)) for (int i=first;i<frames.size()-1;i++)
                require(frames.get(i).pending.contains(target),"Interrupted outstanding pending trace: "+target);
            attempts.add(new PendingAttempt(effect,issue.eligible,issue.observerTick,issue.observerPhase,
                    firstFeedback.eligible,firstFeedback.observerTick,firstFeedback.observerPhase));
        }
        attempts.sort(Comparator.comparingLong(PendingAttempt::issuedEligible).thenComparingLong(a -> a.effect.actionId()));
        var outstanding=attempts.stream().filter(a -> preterminal.pending.contains(a.effect.targetId())).toList();
        require(outstanding.size()==preterminal.pending.size() && !outstanding.isEmpty(),"Missing current-run accepted outstanding attempt");
        var oldest=outstanding.getFirst();
        require((outstanding.size()==1 || oldest.issuedEligible<outstanding.get(1).issuedEligible)
                        && terminal.eligible-oldest.issuedEligible==80 && preterminal.eligible-oldest.issuedEligible==79,
                "No unique oldest outstanding attempt at the exact 80/79 boundary: "+outstanding);
        var witness=attempts.stream().filter(a -> a.issuedEligible>armEligible)
                .filter(a -> frames.stream().anyMatch(f -> f.eligible>=captureEligible
                        && f.pending.contains(a.effect.targetId()) && f.predicted.contains(a.effect.targetId())))
                .findFirst().orElseThrow(() -> new AssertionError("No subsequent accepted native action/prediction after RESPONSE arm"));
        return new DirtyCause(witness,oldest,outstanding,preterminal,terminal);
    }

    private static void verifyCausalClockGuards() {
        var run=UUID.randomUUID(); var foreign=new UUID(run.getMostSignificantBits(),run.getLeastSignificantBits()^1L);
        var frames=new ArrayList<ClockFrame>(); Set<Integer> previousPending=Set.of();
        List<BuildEngine.PlacementEffectResult> previousFeedback=List.of();
        // Synthetic oracle facts: adapter ticks are a separate domain, not offsets applied to live observer/eligible clocks.
        for (int eligible=1;eligible<=98;eligible++) {
            frames.add(new ClockFrame(run,eligible,"START",Phase.RUNNING,eligible-1,previousPending,previousFeedback,previousPending));
            var pending=new HashSet<Integer>();
            if (eligible>=17 && eligible<=18) pending.add(26);
            if (eligible>=18 && eligible<98) pending.add(45);
            if (eligible>=19 && eligible<98) pending.add(50);
            var feedback=switch (eligible) {
                case 18 -> List.of(new BuildEngine.PlacementEffectResult(run,15,26,1134,BuildEngine.EffectStatus.ACCEPTED));
                case 19 -> List.of(new BuildEngine.PlacementEffectResult(run,16,45,1135,BuildEngine.EffectStatus.ACCEPTED));
                case 20 -> List.of(new BuildEngine.PlacementEffectResult(run,17,50,1136,BuildEngine.EffectStatus.ACCEPTED));
                default -> List.<BuildEngine.PlacementEffectResult>of();
            };
            frames.add(new ClockFrame(run,eligible,"END",eligible==98 ? Phase.TERMINAL : Phase.RUNNING,eligible,pending,feedback,pending));
            previousPending=Set.copyOf(pending); previousFeedback=feedback;
        }
        var cause=correlateDirtyCause(run,frames,18,19,98);
        require(cause.deadlineOwner.effect.actionId()==16 && cause.deadlineOwner.effect.targetId()==45
                        && cause.deadlineOwner.effect.tick()==1135 && cause.deadlineOwner.issuedEligible==18
                        && cause.deadlineOwner.feedbackEligible==19 && cause.subsequentWitness.effect.actionId()==17
                        && cause.subsequentWitness.effect.targetId()==50 && cause.subsequentWitness.effect.tick()==1136
                        && cause.subsequentWitness.issuedEligible==19 && cause.subsequentWitness.feedbackEligible==20
                        && 98-cause.subsequentWitness.issuedEligible==79 && cause.outstanding.size()==2,
                "Subsequent witness was confused with the unique oldest pending deadline");
        var wrongRun=frames.stream().map(f -> new ClockFrame(f.runId,f.observerTick,f.observerPhase,f.lifecycle,f.eligible,f.pending,
                f.feedback.stream().map(e -> e.targetId()==45 ? new BuildEngine.PlacementEffectResult(foreign,e.actionId(),e.targetId(),e.tick(),e.status()) : e).toList(),f.predicted)).toList();
        rejected("foreign causal accepted feedback",() -> correlateDirtyCause(run,wrongRun,18,19,98));
        var missingBoundary=frames.stream().filter(f -> !(f.observerTick==18 && f.observerPhase.equals("START"))).toList();
        rejected("missing causal issuance boundary",() -> correlateDirtyCause(run,missingBoundary,18,19,98));
        var reused=frames.stream().map(f -> {
            var feedback=new ArrayList<>(f.feedback);
            if (f.eligible==20 && f.observerPhase.equals("END")) feedback.add(new BuildEngine.PlacementEffectResult(run,18,45,1137,BuildEngine.EffectStatus.ACCEPTED));
            return new ClockFrame(f.runId,f.observerTick,f.observerPhase,f.lifecycle,f.eligible,f.pending,feedback,f.predicted);
        }).toList();
        rejected("ambiguous causal target reuse",() -> correlateDirtyCause(run,reused,18,19,98));
        var nonacceptedRetry=frames.stream().map(f -> {
            var feedback=new ArrayList<>(f.feedback);
            if (f.eligible==98 && f.observerPhase.equals("END")) feedback.add(new BuildEngine.PlacementEffectResult(run,18,50,1214,BuildEngine.EffectStatus.REJECTED));
            return new ClockFrame(f.runId,f.observerTick,f.observerPhase,f.lifecycle,f.eligible,f.pending,feedback,f.predicted);
        }).toList();
        try {
            correlateDirtyCause(run,nonacceptedRetry,18,19,98);
            throw new AssertionError("Nonaccepted retry was attributed to its old accepted attempt");
        } catch (AssertionError error) {
            require(error.getMessage().equals("Ambiguous feedback target reuse: 50"),"Nonaccepted retry failed outside attempt ambiguity: "+error);
        }
        var youngerOnly=frames.stream().map(f -> {
            var pending=new HashSet<>(f.pending); pending.remove(45); var predicted=new HashSet<>(f.predicted); predicted.remove(45);
            return new ClockFrame(f.runId,f.observerTick,f.observerPhase,f.lifecycle,f.eligible,pending,
                    f.feedback.stream().filter(e -> e.targetId()!=45).toList(),predicted);
        }).toList();
        rejected("only younger pending attempt at terminal",() -> correlateDirtyCause(run,youngerOnly,18,19,98));
        System.out.println("ScenarioRunner distinct subsequent-witness/oldest-deadline oracle self-checks passed");
    }

    private Live live() {
        var local=context.computeOnClient(c -> {
            requireClient(c); enforceActionScope();
            var pending=(PendingPredictionView)((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager();
            var positions=new ArrayList<String>();
            for (var cell:admitted.worldCells().keySet()) {
                var at=new BlockPos(cell.x(),cell.y(),cell.z());
                if (pending.mapart$hasPendingPrediction(at)) positions.add(at.toShortString()+"="+c.world.getBlockState(at));
            }
            return new Live(watch.sample(c,"TEST"),readClient(c,false),null,PredictionFaults.snapshot(),List.copyOf(positions),
                    ((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager().getSequence(),
                    "player="+System.identityHashCode(c.player)+"; world="+System.identityHashCode(c.world)
                            +"; handler="+System.identityHashCode(c.getNetworkHandler())+"; connection="+System.identityHashCode(connection));
        });
        var remote=world.getServer().computeOnServer(s -> { requireServer(s); return readPlayer(serverPlayer,false,s.getTicks()); });
        return new Live(local.sample,local.client,remote,local.fault,local.pending,local.nativeSequence,local.nativeIdentities);
    }

    private record TraversalPoll(MotionFrame end,long ordinal,TraversalCapture.Pose client,int keys,int sequence,
                                 Phase phase,Outcome outcome,BuildEngine.Reason reason,long eligible,long verify,
                                 int pending,int unfinished,BuildEngine.ControlOwner owner,ServerFrame server) { }

    private static Physics physics(PlayerEntity p) {
        return new Physics(p.isOnGround(),p.hasNoGravity(),p.hasNoDrag(),p.isJumping(),p.hasVehicle(),p.isInFluid(),p.isTouchingWater(),
                p.isSubmergedInWater(),p.isInLava(),p.isClimbing(),p.isGliding(),p.isSwimming(),p.isSneaking(),p.isUsingItem(),
                !p.getStatusEffects().isEmpty(),p.fallDistance,p.getFinalGravity());
    }

    private static List<Boolean> keys(MinecraftClient c) {
        var o=c.options;
        return List.of(o.forwardKey.isPressed(),o.backKey.isPressed(),o.leftKey.isPressed(),o.rightKey.isPressed(),o.jumpKey.isPressed(),
                o.sneakKey.isPressed(),o.sprintKey.isPressed(),o.useKey.isPressed(),o.attackKey.isPressed());
    }

    /** TEST polls do not observe targets or advance core; server facts follow a separately observed client ordinal. */
    private TraversalPoll traversalPoll() {
        var local=context.computeOnClient(c->{
            requireClient(c); var p=c.player; var v=p.getVelocity(); var eye=p.getEyePos();
            var status=MapArtClient.status(); var e=status.engine(); var lifecycle=e.lifecycle();
            require(lifecycle.context().orElseThrow().runId().equals(capture.runId()) && status.failure().isEmpty(),"Compact poll lost run/adapter identity");
            int controls=0; var keys=keys(c); for(int k=0;k<keys.size();k++) if(keys.get(k)) controls|=1<<k;
            var pose=new TraversalCapture.Pose(p.getX(),p.getY(),p.getZ(),v.x,v.y,v.z,eye.x,eye.y,eye.z,p.age,c.world.getTime(),
                    p.getYaw(),p.headYaw,p.bodyYaw,p.getAbilities().flying,p.isSprinting(),p.getInventory().getSelectedSlot(),
                    p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount()+p.getInventory().getStack(2).getCount(),
                    p.getBlockInteractionRange(),physics(p));
            return new TraversalPoll(capture.latestEnd().orElse(null),capture.latestClientOrdinal(),pose,controls,
                    ((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager().getSequence(),lifecycle.phase(),lifecycle.outcome().orElse(null),
                    e.diagnostic().map(BuildEngine.Diagnostic::reason).orElse(null),e.eligibleTicks(),e.verifyTicks(),e.pendingTargetIds().size(),e.unfinishedTargetIds().size(),status.controlOwner(),null);
        });
        var remote=world.getServer().computeOnServer(s->sampleTraversalServer(s,local.ordinal));
        var appended=context.computeOnClient(c->{
            requireClient(c);
            var frame=new ServerFrame(capture.nextOrdinal(),remote.runId(),remote.originalServer(),remote.originalPlayer(),remote.server(),remote.player(),
                    remote.serverMatches(),remote.playerMatches(),remote.serverTick(),remote.worldTime(),remote.age(),remote.x(),remote.y(),remote.z(),
                    remote.vx(),remote.vy(),remote.vz(),remote.yaw(),remote.flying(),remote.sprinting(),remote.selectedSlot(),remote.reserved(),remote.usable(),
                    remote.controls(),remote.followsClientOrdinal(),remote.interactionReach(),remote.physics(),remote.fault0(),remote.fault1());
            capture.append(frame); return frame;
        });
        lastTraversalServer=appended;
        return new TraversalPoll(local.end,local.ordinal,local.client,local.keys,local.sequence,local.phase,local.outcome,local.reason,
                local.eligible,local.verify,local.pending,local.unfinished,local.owner,appended);
    }

    private record RegionCheck(int count,long mismatches,String hash) { }

    private ServerFrame sampleTraversalServer(MinecraftServer actual,long clientOrdinal) {
        requireServer(actual); var p=serverPlayer; var v=p.getVelocity();
        var facts=new ArrayList<TargetFact>();
        if(!capture.faultTargets().isEmpty()) for(var target:plan.targets()) if(capture.faultTargets().contains(target.id())) {
            var r=target.relativePos(); var at=new BlockPos(origin.x()+r.x(),origin.y()+r.y(),origin.z()+r.z());
            boolean loaded=serverWorld.getChunkManager().getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16),ChunkStatus.FULL,false)!=null;
            facts.add(new TargetFact(capture.runId(),target.id(),null,actual.getTicks(),loaded?Evidence.OBSERVED:Evidence.UNKNOWN,
                    loaded?WorldEvidenceReader.blockSpec(serverWorld.getBlockState(at)):null));
        }
        require(facts.size()<=2,"Server fault sample exceeded declared bounded targets");
        return new ServerFrame(0,capture.runId(),System.identityHashCode(server),System.identityHashCode(serverPlayer),
                System.identityHashCode(actual),System.identityHashCode(p),actual==server,p==serverPlayer,actual.getTicks(),serverWorld.getTime(),p.age,
                p.getX(),p.getY(),p.getZ(),v.x,v.y,v.z,p.getYaw(),p.getAbilities().flying,p.isSprinting(),p.getInventory().getSelectedSlot(),
                p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount()+p.getInventory().getStack(2).getCount(),
                p.getPlayerInput().equals(PlayerInput.DEFAULT)?0:1,clientOrdinal,p.getBlockInteractionRange(),physics(p),
                facts.isEmpty()?null:facts.getFirst(),facts.size()<2?null:facts.get(1));
    }

    private void requireRemovalFrame(MinecraftClient client,UUID runId,MotionFrame frame) {
        requireClient(client);
        var status=MapArtClient.status(); var p=client.player;
        require(capture!=null && capture.runId().equals(runId) && capture.isLatestEnd(frame)
                && capture.latestClientOrdinal()==frame.ordinal() && frame.phase().equals("END") && frame.runId().equals(runId)
                && status.engine().lifecycle().phase()==Phase.RUNNING && status.engine().lifecycle().context().orElseThrow().runId().equals(runId)
                && status.engine().eligibleTicks()==frame.eligible() && status.failure().isEmpty() && !client.isPaused()
                && frame.lifecycle()==Phase.RUNNING && !frame.paused() && frame.pose()!=null
                && frame.identities().worldMatches() && frame.identities().playerMatches() && frame.identities().handlerMatches() && frame.identities().connectionMatches()
                && frame.identities().originalWorld()==System.identityHashCode(clientWorld) && frame.identities().originalPlayer()==System.identityHashCode(clientPlayer)
                && frame.identities().originalHandler()==System.identityHashCode(handler) && frame.identities().originalConnection()==System.identityHashCode(connection)
                && p.getX()==frame.pose().x() && p.getY()==frame.pose().y() && p.getZ()==frame.pose().z() && p.age==frame.pose().age()
                && client.world.getTime()==frame.pose().worldTime() && p.getBlockInteractionRange()==frame.pose().interactionReach()
                && p.getVelocity().x==frame.pose().vx() && p.getVelocity().y==frame.pose().vy() && p.getVelocity().z==frame.pose().vz()
                && p.getEyePos().x==frame.pose().eyeX() && p.getEyePos().y==frame.pose().eyeY() && p.getEyePos().z==frame.pose().eyeZ()
                && p.getYaw()==frame.pose().yaw() && p.headYaw==frame.pose().headYaw() && p.bodyYaw==frame.pose().bodyYaw()
                && physics(p).equals(frame.pose().physics()),
                "Removal refuses foreign/stale/reconstructed END/native binding");
        int controls=0; var held=keys(client); for(int k=0;k<held.size();k++) if(held.get(k)) controls|=1<<k;
        require(controls==frame.keys() && status.controlOwner()==frame.owner(),"Removal command ownership changed after original END");
    }

    /** END-history admits the fault; the complete original START/END stream must still pass after seal. */
    private void applyTraversalRemoval(UUID runId,MotionFrame frame,Path stage) {
        require(stage.equals(artifacts.resolve("success-"+countedStarts+"-"+runId)) && !removalConsumed && removals.isEmpty()
                && !admitted.removalTargets().isEmpty(),"Wrong/consumed removal scope");
        context.runOnClient(c->{ requireRemovalFrame(c,runId,frame); forwardHistory.requireRemoval(frame); });
        removalConsumed=true; // A failed/partial native fault cannot be retried into another result.
        for(var target:plan.targets()) if(admitted.removalTargets().contains(target.relativePos())) {
            var relative=target.relativePos(); var at=new GridPos(origin.x()+relative.x(),origin.y()+relative.y(),origin.z()+relative.z());
            context.runOnClient(c->{
                requireRemovalFrame(c,runId,frame);
                var fresh=WorldEvidenceReader.placementObservations(WorldEvidenceReader.capture(c,plan,
                        MapArtClient.status().engine().lifecycle().context().orElseThrow(),clientWorld,handler,frame.tick()))
                        .stream().filter(value->value.targetId()==target.id()).findFirst().orElseThrow();
                require(fresh.runId().equals(runId) && fresh.tick()==frame.tick() && fresh.evidence()==Evidence.OBSERVED
                        && fresh.observedState().filter(target.block()::equals).isPresent(),"Removal lacks fresh FULL/OBSERVED client target");
                TargetFact original=frame.fault0()!=null && frame.fault0().targetId()==target.id()?frame.fault0():frame.fault1();
                require(original!=null && original.targetId()==target.id() && original.evidence()==Evidence.OBSERVED && target.block().equals(original.state())
                        && TraversalAssertions.boxDistanceSquared(frame.pose().eyeX(),frame.pose().eyeY(),frame.pose().eyeZ(),at)
                        >frame.pose().interactionReach()*frame.pose().interactionReach(),"Removal lacks original correct/out-of-reach client target");
            });
            var receipt=world.getServer().computeOnServer(s->{
                requireServer(s);
                var position=new BlockPos(at.x(),at.y(),at.z());
                require(serverWorld.getChunkManager().getChunk(Math.floorDiv(at.x(),16),Math.floorDiv(at.z(),16),ChunkStatus.FULL,false)!=null,
                        "Removal target server chunk unavailable");
                var before=WorldEvidenceReader.blockSpec(serverWorld.getBlockState(position)); var eye=serverPlayer.getEyePos();
                double reach=serverPlayer.getBlockInteractionRange();
                require(target.block().equals(before) && serverWorld.getBlockEntity(position)==null && Double.isFinite(reach) && reach>0
                        && fixture.removalX(serverPlayer.getX()-origin.x()) && Math.abs(serverPlayer.getZ()-(origin.z()+frame.traversal().startZ()+.5))<=.25
                        && TraversalAssertions.boxDistanceSquared(eye.x,eye.y,eye.z,at)>reach*reach,"Removal lacks fresh original correct/out-of-reach server passage");
                var original=lastTraversalServer;
                require(original!=null && original.followsClientOrdinal()==frame.ordinal() && original.runId().equals(runId)
                        && original.server()==System.identityHashCode(s) && original.player()==System.identityHashCode(serverPlayer),
                        "Removal lacks the original causal server poll");
                long mutationTick=s.getTicks(),mutationTime=serverWorld.getTime(); int mutationAge=serverPlayer.age;
                require(serverWorld.setBlockState(position,Blocks.AIR.getDefaultState()),"Native AIR removal rejected");
                var after=WorldEvidenceReader.blockSpec(serverWorld.getBlockState(position));
                require(after.equals(new BuildPlan.BlockSpec("minecraft:air",Map.of())),"Native AIR mutation did not produce AIR");
                return new TraversalAssertions.Removal(runId,target.id(),relative,frame,original,mutationTick,mutationTime,mutationAge,eye.x,eye.y,eye.z,before,after);
            });
            removals.add(receipt);
            scalar("removal-"+target.id()+".json",new Gson().toJson(receipt));
            context.runOnClient(c->requireRemovalFrame(c,runId,frame));
        }
        TraversalAssertions.requireRemovalRecords(admitted,plan,runId,List.copyOf(removals));
    }

    private Side withRegion(Side side,RegionCheck region) {
        side.state.add("region",new Gson().toJsonTree(region));
        var errors=new ArrayList<>(side.problems);
        if(region.count!=admitted.worldCells().size() || region.mismatches!=0) errors.add("Owned region incomplete/mismatched: "+region);
        return new Side(side.state,side.age,side.time,side.serverTick,List.copyOf(errors));
    }

    /** No getter may turn an absent FULL chunk into construction evidence. Each mismatch is charged before I/O. */
    private RegionCheck region(PlayerEntity player,boolean initial,String name) {
        var level=player.getEntityWorld(); long mismatches=0; int count=0;
        try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.open(OutputKind.REGION,name+".txt"),StandardCharsets.UTF_8))) {
            var digest=MessageDigest.getInstance("SHA-256");
            writer.write("actual separate "+(player instanceof ServerPlayerEntity?"SERVER":"CLIENT_TEST")+" read; age="+player.age
                    +"; worldTime="+level.getTime()+"; serverTick="+(player instanceof ServerPlayerEntity?server.getTicks():-1)+"\n");
            for(var position:regionOrder) {
                count++; var at=new BlockPos(position.x(),position.y(),position.z());
                var expected=initial?admitted.worldCells().get(position):finalTargets.getOrDefault(position,admitted.worldCells().get(position));
                boolean loaded=level.getChunkManager().getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16),ChunkStatus.FULL,false)!=null;
                String actual="UNKNOWN"; boolean blockEntity=false; boolean matches=false;
                if(loaded) {
                    var state=WorldEvidenceReader.blockSpec(level.getBlockState(at));
                    blockEntity=level.getBlockEntity(at)!=null;
                    actual=state.id()+new TreeMap<>(state.properties()).toString();
                    matches=!blockEntity && state.equals(expected);
                }
                var value=position.x()+","+position.y()+","+position.z()+"="+actual+";loaded="+loaded+";blockEntity="+(loaded?blockEntity:"UNKNOWN")+"\n";
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                if(!matches) { mismatches++; writer.write("mismatch "+value.stripTrailing()+"; expected="+expected.id()+new TreeMap<>(expected.properties())+"\n"); }
            }
            var result=new RegionCheck(count,mismatches,HexFormat.of().formatHex(digest.digest()));
            writer.write("complete="+(count==regionOrder.size())+"; cells="+count+"; mismatches="+mismatches+"; sha256="+result.hash+"\n");
            return result;
        } catch(IOException error) { throw new UncheckedIOException(error); }
        catch(NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private Checkpoint fullPlayerCheckpoint(CheckpointKind kind) {
        require(playerCheckpoints.add(kind),"Duplicate full player checkpoint: "+kind);
        var local=context.computeOnClient(c->{
            requireClient(c); var side=readClient(c,false);
            return new FullLocal(side,MapArtClient.status(),capture==null?-1:capture.latestClientOrdinal());
        });
        var remote=world.getServer().computeOnServer(s->{ requireServer(s); return readPlayer(serverPlayer,false,s.getTicks()); });
        var checkpoint=new Checkpoint(local.side,remote,local.status);
        var json=evidence(checkpoint);
        json.addProperty("requestedKind",kind.name()); json.addProperty("run",capture==null?null:capture.runId().toString());
        json.addProperty("causalClientOrdinal",local.ordinal);
        json.addProperty("actualPhase","separate CLIENT_TEST then SERVER reads; not observer END or simultaneous");
        json.addProperty("originalFullScanPresent",capture!=null && capture.hasCheckpoint(kind));
        scalar("player-"+kind.name()+".json",json.toString());
        return checkpoint;
    }

    private record FullLocal(Side side,FabricBuildAdapter.Status status,long ordinal) { }

    private void verifyFinalCompact(Checkpoint finalState) {
        require(finalState.client.state.get("pendingOwnedCount").getAsInt()==0,"Final owned native predictions remain");
        TraversalAssertions.requireRemovalRecords(admitted,plan,capture.runId(),List.copyOf(removals));
        for(var side:List.of(finalState.client,finalState.server)) {
            var before=side==finalState.client?stable.client:stable.server;
            requireStock(side.state.getAsJsonArray("inventory"),before.state.getAsJsonArray("inventory"),admitted.finalUsable());
            require(side.state.get("mode").getAsString().equals("SURVIVAL") && side.state.get("abilities").equals(before.state.get("abilities"))
                    && side.state.get("cursorAndCrafting").equals(before.state.get("cursorAndCrafting")) && b(side.state,"playerHandler")
                    && side.state.getAsJsonArray("boundedEntities").isEmpty(),"Final complete player/menu/entities differ");
        }
        require(finalState.client.state.get("inventory").equals(finalState.server.state.get("inventory"))
                && finalState.client.state.get("selectedSlot").equals(finalState.server.state.get("selectedSlot")),"Final client/server complete stock differs");
        var local=context.computeOnClient(c->{ requireClient(c); return region(c.player,false,"final-client"); });
        var remote=world.getServer().computeOnServer(s->{ requireServer(s); return region(serverPlayer,false,"final-server"); });
        require(local.count==regionOrder.size() && remote.count==regionOrder.size() && local.mismatches==0 && remote.mismatches==0
                && local.hash.equals(remote.hash),"Independent final full-state region mismatch: "+local+" / "+remote);
    }

    private void verifyLiveRefusals() {
        verifyReleaseRefusal("running",server,save,context.computeOnClient(c -> MapArtClient.status().engine().lifecycle().context().orElseThrow().runId()));
        Live before=live();
        var files=evidenceFiles();
        context.runOnClient(c -> {
            requireClient(c);
            require(MapArtClient.status().engine().lifecycle().phase()==Phase.RUNNING && PredictionFaults.snapshot().active(),
                    "Live refusals require active production/RESPONSE");
            var mutations=new int[1]; Runnable mutation=() -> mutations[0]++;
            var d=admitted.data(); var h=admitted.definitionHash();
            rejected("live wrong token",() -> guarded(invocation,UUID.randomUUID(),roots,roots.game,h,h,d,mutation));
            rejected("live wrong game/save authority",() -> guarded(invocation,invocation,roots,roots.module,h,h,d,mutation));
            rejected("live wrong hash",() -> guarded(invocation,invocation,roots,roots.game,h,"0".repeat(64),d,mutation));
            for (String field:List.of("dimension","region","container","definition")) {
                var altered=admitted.data();
                switch (field) {
                    case "dimension" -> altered.getAsJsonObject("world").addProperty("dimension","minecraft:the_nether");
                    case "region" -> altered.getAsJsonObject("world").getAsJsonObject("ownedRegion").getAsJsonObject("max").addProperty("x",14);
                    case "container" -> altered.getAsJsonObject("world").getAsJsonArray("containers").add(new JsonObject());
                    default -> altered.getAsJsonObject("player").addProperty("selectedSlot",2);
                }
                rejected("live wrong "+field,() -> guarded(invocation,invocation,roots,roots.game,h,h,altered,mutation));
            }
            var originalSave=save; save=save.resolve("foreign");
            try { rejected("live native save mismatch",() -> requireClient(c)); } finally { save=originalSave; }
            var originalWorld=clientWorld; clientWorld=null;
            try { rejected("live native world mismatch",() -> requireClient(c)); } finally { clientWorld=originalWorld; }
            var originalPlayer=clientPlayer; clientPlayer=null;
            try { rejected("live native player mismatch",() -> requireClient(c)); } finally { clientPlayer=originalPlayer; }
            var originalHandler=handler; handler=null;
            try { rejected("live native handler mismatch",() -> requireClient(c)); } finally { handler=originalHandler; }
            var originalConnection=connection; connection=null;
            try { rejected("live native connection mismatch",() -> requireClient(c)); } finally { connection=originalConnection; }
            require(mutations[0]==0,"Refused live authority reached reset mutation boundary");
        });
        Live after=live();
        require(before.equals(after) && files.equals(evidenceFiles()),
                "Live refusal changed production/controls/ledger/fault/player/blocks or archive contents; before="+before+"; after="+after);
        writeNew(roots,artifacts.resolve("live-reset-refusals.txt"),"same quiescent checkpoint; callbacks=0; archive contents unchanged="+files+"\nbefore="+before+"\nafter="+after);
    }

    private record EvidenceEntry(String relativePath, String kind, long size, String creationTime,
                                 String modifiedTime, int windowsAttributes, String sha256) { }

    private List<EvidenceEntry> evidenceFiles() {
        checkRoots(roots);
        require(artifacts.equals(roots.evidence.resolve("two-lane-"+invocation)),"Evidence root escaped invocation");
        var rows=new ArrayList<EvidenceEntry>();
        try {
            evidenceEntries(artifacts,rows);
            rows.sort(Comparator.comparing(EvidenceEntry::relativePath));
            return List.copyOf(rows);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // shortcut: owned quiescent Windows archive; hostile concurrent/raw-target identity audits need another contract.
    private void evidenceEntries(Path path, List<EvidenceEntry> rows) throws IOException {
        var attributes=Files.readAttributes(path,"dos:*",LinkOption.NOFOLLOW_LINKS);
        int flags=(Integer)attributes.get("attributes");
        String kind;
        if ((flags&1024)!=0) kind="REPARSE";
        else {
            boolean directory=(Boolean)attributes.get("isDirectory"), file=(Boolean)attributes.get("isRegularFile");
            require(!(Boolean)attributes.get("isOther") && !(Boolean)attributes.get("isSymbolicLink") && directory!=file,
                    "Unsupported ordinary evidence kind: "+path);
            kind=directory ? "DIRECTORY" : "FILE";
        }
        require(!path.equals(artifacts) || kind.equals("DIRECTORY"),"Evidence root must be an ordinary directory");
        String hash=kind.equals("FILE") ? evidenceHash(Files.readAllBytes(path)) : null;
        rows.add(new EvidenceEntry(artifacts.relativize(path).toString(),kind,(Long)attributes.get("size"),
                attributes.get("creationTime").toString(),attributes.get("lastModifiedTime").toString(),flags,hash));
        if (kind.equals("DIRECTORY")) {
            try (var children=Files.newDirectoryStream(path)) {
                for (var child:children) evidenceEntries(child,rows);
            }
        }
    }

    private static String evidenceHash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static int usable(Side side) {
        var inventory=side.state.get("inventory");
        require(inventory!=null && inventory.isJsonArray() && inventory.getAsJsonArray().size()==43,"Incomplete43-slot usable inventory");
        var slots=inventory.getAsJsonArray();
        return encodedStackCount(slots.get(1))+encodedStackCount(slots.get(2));
    }

    private static int encodedStackCount(JsonElement value) {
        require(value!=null && value.isJsonObject(),"Non-object encoded inventory stack: "+value);
        var stack=value.getAsJsonObject();
        if (stack.size()==0) return 0;
        var id=stack.get("id");
        require(id!=null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                && !id.getAsString().isEmpty(),"Missing/string-invalid encoded inventory stack identity: "+value);
        var identity=Identifier.tryParse(id.getAsString());
        require(identity!=null && Registries.ITEM.containsId(identity) && !identity.toString().equals("minecraft:air"),
                "Invalid encoded inventory stack identity: "+value);
        require(!stack.has("components") || stack.get("components").isJsonObject(),"Invalid encoded stack components: "+value);
        // OPTIONAL_CODEC encodes empty as {} and omits the count of a nonempty singleton.
        if (!stack.has("count")) return 1;
        var count=stack.get("count");
        require(count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber(),"Non-numeric encoded stack count: "+value);
        var number=count.getAsBigDecimal();
        require(number.stripTrailingZeros().scale()<=0 && number.signum()>0 && number.compareTo(java.math.BigDecimal.valueOf(64))<=0,
                "Encoded stack count outside integral1..64: "+value);
        return number.intValue();
    }

    private long serverStoneCount() {
        return world.getServer().computeOnServer(s -> {
            requireServer(s);
            return plan.targets().stream().filter(t -> serverWorld.getBlockState(new BlockPos(origin.x()+t.relativePos().x(),
                    origin.y()+t.relativePos().y(),origin.z()+t.relativePos().z())).equals(Blocks.STONE.getDefaultState())).count();
        });
    }

    void captureFailure(Throwable error) {
        if(!baseline()) { captureCompactFailure(error); return; }
        capture(error,() -> writeNew(roots,artifacts.resolve("failure.txt"),trace(error)));
        capture(error,() -> writeNew(roots,artifacts.resolve("failure-state.json"),failureMetadata(invocation,resetOrdinal,
                world!=null,admitted,plan,origin,settings,ready,runs,previous,observedHash,
                stable==null ? null : stable.status.engine().lifecycle().context().map(c -> c.runId()).orElse(null)).toString()));
        if (world!=null) {
            capture(error,() -> writeNew(roots,artifacts.resolve("failure-support-gate.txt"),
                    context.computeOnClient(c -> PredictionFaults.resetSnapshot().support()).toString()));
            capture(error,() -> {
                context.runOnClient(this::requireClient);
                context.takeScreenshot(TestScreenshotOptions.of("failure").withDestinationDir(artifacts).disableCounterPrefix());
            });
            if (failureLiveAvailable(true,admitted,plan,origin,settings))
                capture(error,() -> writeNew(roots,artifacts.resolve("failure-live.txt"),live().toString()));
            capture(error,() -> writeLinesNew(artifacts.resolve("failure-observer.txt"),
                    context.computeOnClient(c -> watch.copy()).stream().map(ScenarioObserver.Sample::line)::iterator));
        }
        // Capture errors are present here; later try-with-resources close errors remain in the outer log.
        capture(error,() -> writeNew(roots,artifacts.resolve("failure-after-capture.txt"),trace(error)));
    }

    private static boolean failureLiveAvailable(boolean ownedWorld, ScenarioDefinition admitted, BuildPlan plan,
                                                GridPos origin, BuildEngine.Settings settings) {
        return ownedWorld && admitted!=null && plan!=null && origin!=null && settings!=null;
    }

    private static JsonObject failureMetadata(UUID invocation, int resetOrdinal, boolean ownedWorld, ScenarioDefinition admitted,
                                              BuildPlan plan, GridPos origin, BuildEngine.Settings settings, boolean ready,
                                              Set<UUID> runs, ScenarioResult previous, String observedHash, UUID observedFacadeRun) {
        var state=new JsonObject();
        String stage=!ownedWorld ? "NO_OWNED_WORLD" : admitted==null ? "PRE_ADMISSION" : ready ? "READY"
                : "ADMITTED_NOT_READY";
        state.addProperty("invocation",invocation.toString()); state.addProperty("stage",stage);
        state.addProperty("stageProvenance","held admission/readiness only; not-ready cannot distinguish failing readiness, a start that consumed readiness, or a later reset before/after ordinal increment; counters do not establish operation timing");
        state.addProperty("resetOrdinal",resetOrdinal); state.addProperty("ownedWorld",ownedWorld); state.addProperty("ready",ready);
        state.addProperty("admittedDefinitionHash",admitted==null ? null : admitted.definitionHash());
        state.addProperty("admissionProvenance","last assignment after restore/import; may be retained from an earlier reset; nonnull is not readiness");
        state.addProperty("planFingerprint",plan==null ? null : plan.fingerprint());
        state.addProperty("origin",origin==null ? null : origin.toString());
        state.addProperty("settings",settings==null ? null : settings.fingerprint());
        state.add("ownedRunIds",new Gson().toJsonTree(runs.stream().map(UUID::toString).sorted().toList()));
        state.addProperty("previousResult",previous==null ? null : previous.toString());
        state.addProperty("previousResultOwned",previous!=null && runs.contains(previous.runId()));
        state.addProperty("observedStableStartHash",observedHash);
        state.addProperty("observedHashProvenance",observedHash==null ? "no observed checkpoint hash retained"
                : "retained observed checkpoint value; may predate current reset; capture does not establish current reset validation");
        state.addProperty("retainedCheckpointFacadeRunId",observedFacadeRun==null ? null : observedFacadeRun.toString());
        state.addProperty("retainedCheckpointFacadeRunOwned",observedFacadeRun!=null && runs.contains(observedFacadeRun));
        boolean liveAvailable=failureLiveAvailable(ownedWorld,admitted,plan,origin,settings);
        state.addProperty("definitionBasedLiveAvailable",liveAvailable);
        state.addProperty("liveEvidenceMeaning",liveAvailable ? "attempt actual read under unchanged authority; state need not pass readiness"
                : "unavailable: owned world and admitted plan/origin/settings are required; no definition-based native read attempted");
        return state;
    }

    private static void capture(Throwable primary, Runnable attempt) {
        try { attempt.run(); } catch (RuntimeException | Error error) { retain(primary,error); }
    }

    private static String trace(Throwable error) {
        var text=new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(text)); return text.toString();
    }

    private void scalar(String name,String text) {
        try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.open(OutputKind.SCALAR,name),StandardCharsets.UTF_8))) {
            writer.write(text); writer.write('\n');
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private void writeHeader(Path target,String text) {
        if(baseline()) { writeNew(roots,target,text); return; }
        var name="header-"+target.getFileName();
        scalar(name,text);
        copyCompact(outputs.root().resolve(name),target);
    }

    /** Throwable writes fail at the real byte cap; never construct a whole trace or swallow PrintWriter I/O failure. */
    private static void writeDiagnostic(OutputBudget budget,String name,Throwable failure) {
        writeDiagnostic(budget,name,failure,false);
    }

    private static void writeDiagnostic(OutputBudget budget,String name,Throwable failure,boolean postSeal) {
        try(var bytes=postSeal?budget.openPostSealFailure(name):budget.open(OutputKind.SCALAR,name);
            var utf8=new OutputStreamWriter(bytes,StandardCharsets.UTF_8)) {
            var streaming=new Writer() {
                @Override public void write(char[] value,int offset,int length) {
                    try { utf8.write(value,offset,length); } catch(IOException error) { throw new UncheckedIOException(error); }
                }
                @Override public void write(String value,int offset,int length) {
                    for(int end=offset+length; offset<end;) {
                        int count=Math.min(1024,end-offset);
                        try { utf8.write(value,offset,count); } catch(IOException error) { throw new UncheckedIOException(error); }
                        offset+=count;
                    }
                }
                @Override public void flush() {
                    try { utf8.flush(); } catch(IOException error) { throw new UncheckedIOException(error); }
                }
                @Override public void close() { flush(); }
            };
            var printer=new PrintWriter(streaming);
            failure.printStackTrace(printer); printer.flush();
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static void requireCompleteOutputs(OutputBudget budget) {
        require(budget.failure().isEmpty() && budget.seal().complete(),"Incomplete output prefix; reset/rearm/normal close refused");
    }

    private static void retainConstructorFailure(OutputBudget budget,Throwable primary) {
        capture(primary,()->writeDiagnostic(budget,"constructor-failure.txt",primary));
        capture(primary,budget::seal); // No Runner reaches the caller on constructor failure; this owner must close its prefix.
    }

    static void verifyDiagnosticGuards(Path directory) {
        var limits=Limits.forRun(1,0,0,1);
        try {
            var early=new OutputBudget(directory.resolve("pre-facade"),limits);
            var original=new IllegalStateException("owned failure before world admission/facade start");
            writeDiagnostic(early,"early-failure.txt",original);
            var prefix=early.root().resolve("early-failure.txt"); var digest=fileHash(prefix); long spent=early.scalarBytes();
            var adopted=new TraversalCapture(early.root(),UUID.randomUUID(),limits,Set.of(),early);
            require(early.scalarBytes()==spent && fileHash(prefix).equals(digest),"Facade adoption renewed/spoiled early diagnostic bytes");
            var complete=adopted.seal(); adopted.requireSealedForClear();
            require(complete.complete() && complete.outputs().scalarBytes()>=spent
                    && complete.outputs().receipts().stream().anyMatch(r->r.path().equals(prefix) && r.hash().equals(digest)),
                    "Pre-world diagnostic ownership lost at actual capture adoption");
            var constructor=new OutputBudget(directory.resolve("constructor-failure"),limits);
            var beforeWorld=new IllegalStateException("failure before owned world/facade creation");
            retainConstructorFailure(constructor,beforeWorld);
            require(constructor.seal().complete() && constructor.receipts().stream().anyMatch(r->r.path().getFileName().toString().equals("constructor-failure.txt"))
                    && !Files.exists(constructor.root().resolve("COMMITTED")),"Constructor failure lost closed prefix or invented commit");
            rejected("closed constructor failure budget cannot be adopted",()->{
                try { new TraversalCapture(constructor.root(),UUID.randomUUID(),limits,Set.of(),constructor); }
                catch(IOException error) { throw new UncheckedIOException(error); }
            });
            var oversized=new OutputBudget(directory.resolve("oversized-text"),limits);
            var primary=new IllegalStateException("x".repeat(1048577));
            retainConstructorFailure(oversized,primary);
            var outputCause=oversized.failure().orElseThrow();
            require(Arrays.stream(primary.getSuppressed()).anyMatch(e->e==outputCause || e.getCause()==outputCause),
                    "Constructor output failure replaced/lost original primary/suppressed cause");
            verifyRefusedDiagnostic(oversized);

            var pixels=new OutputBudget(directory.resolve("png-pixels"),limits);
            try(var nativeImage=new NativeImage(2,2,false)) {
                int[] colors={0xffff0000,0x8000ff00,0x400000ff,0x00ffffff};
                for(int k=0;k<colors.length;k++) nativeImage.setColorArgb(k%2,k/2,colors[k]);
                var image=imageArgb(nativeImage);
                try {
                    writePng(pixels,"failure.png",image,false);
                    var decoded=ImageIO.read(pixels.root().resolve("failure.png").toFile());
                    require(decoded!=null && decoded.getWidth()==2 && decoded.getHeight()==2,"Owned PNG did not round-trip");
                    try { for(int k=0;k<colors.length;k++) require(decoded.getRGB(k%2,k/2)==colors[k],"PNG ARGB/alpha/orientation changed"); }
                    finally { decoded.flush(); }
                } finally { image.flush(); }
            }
            requireCompleteOutputs(pixels);
            for(var size:List.of(new int[]{854,480,320,179},new int[]{1366,768,320,179},
                    new int[]{1280,720,320,180},new int[]{480,854,101,180},new int[]{2,2,2,2})) {
                try(var nativeImage=new NativeImage(size[0],size[1],true)) {
                    var reduced=imageArgb(nativeImage);
                    try { require(reduced.getWidth()==size[2] && reduced.getHeight()==size[3]
                            && reduced.getWidth()>0 && reduced.getWidth()<=320 && reduced.getHeight()>0 && reduced.getHeight()<=180,
                            "Actual failure-image helper changed bounded geometry/no-upscale: "+Arrays.toString(size)); }
                    finally { reduced.flush(); }
                }
            }
            var noisy=new BufferedImage(768,512,BufferedImage.TYPE_INT_ARGB); var random=new Random(314159);
            for(int y=0;y<noisy.getHeight();y++) for(int x=0;x<noisy.getWidth();x++) noisy.setRGB(x,y,random.nextInt());
            var imageBudget=new OutputBudget(directory.resolve("oversized-png"),limits);
            try { rejected("oversized encoded PNG",()->writePng(imageBudget,"failure.png",noisy,false)); }
            finally { noisy.flush(); }
            verifyRefusedDiagnostic(imageBudget);
            System.out.println("ScenarioRunner same-helper pre-facade/adoption/text/PNG guards passed; not native screenshot evidence");
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static void verifyRefusedDiagnostic(OutputBudget budget) {
        var first=budget.failure().orElseThrow(); var retained=budget.seal();
        require(!retained.complete() && budget.failure().orElseThrow()==first && budget.scalarBytes()>0 && budget.scalarBytes()<=1048576,
                "Oversized diagnostic lost first cause/bounded prefix");
        var restores=new int[1];
        rejected("incomplete diagnostic cannot commit/restore",()->archiveThenRestore(()->{
            var owner=new CompactOwner(budget,null,null,UUID.randomUUID(),1);
            phaseReference(roots(),budget.root(),owner,budget,null,1,"failed-diagnostic",
                    budget.root().resolve("failed-diagnostic"),Map.of(),null,first);
        },()->restores[0]++));
        require(restores[0]==0 && !Files.exists(budget.root().resolve("COMMITTED"))
                        && !Files.exists(budget.root().resolve("failed-diagnostic/COMMITTED")),"Oversized diagnostic reached uncharged COMMITTED/restore");
        rejected("failed pre-facade budget cannot be adopted",()->{
            try { new TraversalCapture(budget.root(),UUID.randomUUID(),Limits.forRun(1,0,0,1),Set.of(),budget); }
            catch(IOException error) { throw new UncheckedIOException(error); }
        });
        require(budget.failure().orElseThrow()==first,"Refused adoption replaced diagnostic first cause");
    }

    private void sealCompact() {
        if(sealed!=null) return;
        if(capture!=null) sealed=context.computeOnClient(c -> { requireClient(c); return watch.sealTraversal(); });
        else if(outputs!=null) outputs.seal();
    }

    private void captureCompactFailure(Throwable primary) {
        compactOriginalFailure=retain(compactOriginalFailure,primary);
        if(world!=null) capture(primary,()->context.runOnClient(c->{ requireClient(c); MapArtClient.stop(); requireReleased(c); }));
        if(outputs!=null && sealed==null && outputs.failure().isEmpty() && admitted!=null && plan!=null) {
            capture(primary,()->fullPlayerCheckpoint(CheckpointKind.FAILURE));
            capture(primary,()->context.runOnClient(c->{ requireClient(c); region(c.player,false,"failure-client"); }));
            capture(primary,()->world.getServer().runOnServer(s->{ requireServer(s); region(serverPlayer,false,"failure-server"); }));
        }
        if(sealed==null && outputs.failure().isEmpty()) capture(primary,()->writeDiagnostic(outputs,"failure-trace.txt",primary));
        capture(primary,this::sealCompact);
        if(outputs!=null) capture(primary,()->{
            try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.openPostSealFailure("failure-"+(++scalarOrdinal)+".txt"),StandardCharsets.UTF_8))) {
                writer.write(compactFailureMetadata(primary).toString()); writer.write('\n');
            } catch(IOException error) { throw new UncheckedIOException(error); }
        });
        if(world!=null) {
            try { captureFailureImage(); }
            catch(RuntimeException | Error error) {
                compactArchiveFailure=retain(compactArchiveFailure,error); retain(primary,error);
            }
            capture(primary,()->archiveCompact(artifacts.resolve("failure-archive-"+resetOrdinal),sealed));
        }
    }

    private static BufferedImage imageArgb(NativeImage image) {
        int sourceWidth=image.getWidth(),sourceHeight=image.getHeight();
        require(sourceWidth>0 && sourceHeight>0,"Failure image dimensions must be positive");
        int width=sourceWidth,height=sourceHeight;
        if(width>320 || height>180) {
            if((long)sourceWidth*180>=(long)sourceHeight*320) { width=320; height=Math.max(1,(int)((long)sourceHeight*320/sourceWidth)); }
            else { height=180; width=Math.max(1,(int)((long)sourceWidth*180/sourceHeight)); }
        }
        var result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        try {
            for(int y=0;y<height;y++) for(int x=0;x<width;x++)
                result.setRGB(x,y,image.getColorArgb((int)((2L*x+1)*sourceWidth/(2L*width)),(int)((2L*y+1)*sourceHeight/(2L*height))));
            return result;
        } catch(RuntimeException | Error error) { result.flush(); throw error; }
    }

    /** Binary PNG explicitly spends the existing SCALAR/failure-diagnostic reserve; no file or disk cache precedes admission. */
    private static void writePng(OutputBudget budget,String name,BufferedImage image,boolean postSeal) {
        var encoders=ImageIO.getImageWritersByFormatName("PNG");
        require(encoders.hasNext(),"PNG failure encoder unavailable"); var encoder=encoders.next();
        try(var bytes=postSeal?budget.openPostSealFailure(name):budget.open(OutputKind.SCALAR,name);
            var output=new MemoryCacheImageOutputStream(bytes)) {
            encoder.setOutput(output); encoder.write(image); output.flush();
        } catch(IOException error) { throw new UncheckedIOException(error); }
        finally { encoder.dispose(); }
    }

    private record CapturedImage(BufferedImage pixels,int nativeWidth,int nativeHeight) { }

    private void captureFailureImage() {
        require(outputs.failure().isEmpty(),"Owned failure PNG unavailable: diagnostic reservation already failed");
        var future=new CompletableFuture<CapturedImage>();
        int[] source=context.computeOnClient(c->{
            requireClient(c);
            var buffer=c.getFramebuffer(); int width=buffer.textureWidth, height=buffer.textureHeight;
            require(width>0 && height>0,"Owned failure framebuffer unavailable");
            c.gameRenderer.render(RenderTickCounter.ONE,true);
            ScreenshotRecorder.takeScreenshot(buffer,image->{
                try(image) {
                    var pixels=imageArgb(image);
                    if(!future.complete(new CapturedImage(pixels,image.getWidth(),image.getHeight()))) pixels.flush();
                } catch(RuntimeException | Error error) { future.completeExceptionally(error); }
            });
            return new int[]{width,height};
        });
        for(int tick=0;!future.isDone() && tick<20;tick++) context.waitTick();
        if(!future.isDone()) { future.cancel(false); throw new IllegalStateException("Owned failure PNG callback timeout after20 ticks"); }
        var captured=future.join(); // Gametest thread; completion was checked without blocking the render/client thread.
        var image=captured.pixels();
        try {
            writePng(outputs,"failure.png",image,true);
            try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.openPostSealFailure("failure-image.json"),StandardCharsets.UTF_8))) {
                writer.write("{\"payload\":\"binary PNG\",\"reservation\":\"existing SCALAR/failure-diagnostic bytes\",\"file\":\"failure.png\","
                        +"\"source\":\"owned framebuffer callback after stop/seal; rendered tickDelta1; not observer END\","
                        +"\"framebufferWidth\":"+source[0]+",\"framebufferHeight\":"+source[1]+",\"downscale\":1"
                        +",\"nativeImageWidth\":"+captured.nativeWidth()+",\"nativeImageHeight\":"+captured.nativeHeight()
                        +",\"reduction\":\"nearest pixel centers; aspect fit320x180; integer floor/minimum1; no upscale\""
                        +",\"width\":"+image.getWidth()+",\"height\":"+image.getHeight()+"}\n");
            } catch(IOException error) { throw new UncheckedIOException(error); }
        } finally { image.flush(); }
    }

    private JsonObject compactFailureMetadata(Throwable primary) {
        var value=new JsonObject();
        value.addProperty("invocation",invocation.toString()); value.addProperty("resetOrdinal",resetOrdinal);
        value.addProperty("ready",ready); value.addProperty("ownedWorld",world!=null);
        value.addProperty("admittedDefinitionHash",admitted==null?null:admitted.definitionHash());
        value.addProperty("planFingerprint",plan==null?null:plan.fingerprint()); value.addProperty("origin",origin==null?null:origin.toString());
        value.addProperty("settings",settings==null?null:settings.fingerprint());
        value.addProperty("observedStableStartHash",observedHash);
        value.addProperty("heldStateProvenance","Held admission/hash may predate this reset; not-ready does not establish operation timing or a current passing scan");
        value.add("ownedRunIds",new Gson().toJsonTree(runs.stream().map(UUID::toString).sorted().toList()));
        value.addProperty("captureRun",capture==null?null:capture.runId().toString());
        value.addProperty("previousResultRun",previous==null?null:previous.runId().toString());
        value.addProperty("sealedEvents",sealed==null?null:sealed.events().toString());
        value.addProperty("sealedHash",sealed==null?null:sealed.eventHash());
        value.addProperty("sealedCount",sealed==null?null:sealed.eventCount()); value.addProperty("sealedBytes",sealed==null?null:sealed.eventBytes());
        value.addProperty("sealedComplete",sealed!=null && sealed.complete());
        value.addProperty("failure",primary.getClass().getName());
        var message=String.valueOf(primary.getMessage()); value.addProperty("message",message.substring(0,Math.min(2048,message.length())));
        value.addProperty("originalMessageChars",message.length()); value.addProperty("messageTruncated",message.length()>2048);
        value.addProperty("imageAvailability",world==null?"No owned world image available":"Owned failure PNG attempted after seal; actual closed-byte/hash receipt required");
        value.addProperty("suppressedCount",primary.getSuppressed().length);
        value.addProperty("readProvenance",sealed==null?"Current guarded pre-seal reads attempted where available":"Immutable sealed prefix; no post-seal scan fabricated");
        return value;
    }

    private void archiveCompact(Path stage,Sealed trace) {
        try { archiveCompactFiles(stage,trace); }
        catch(RuntimeException | Error error) { compactArchiveFailure=retain(compactArchiveFailure,error); throw error; }
    }

    /** Synthetic filesystem guards use the same closed-file/reference boundary as actual archive/reset/close. */
    static void verifyPhaseReferenceGuards(Path provider,OutputBudget budget,Sealed trace,UUID run) {
        var r=roots(); var invocation=UUID.randomUUID();
        var owner=new CompactOwner(budget,trace,run,invocation,1);
        var success=provider.resolve("reference-success");
        var first=phaseReference(r,provider,owner,budget,trace,1,"success",success,Map.of(),null,null);
        require(Files.isRegularFile(success.resolve("COMMITTED")),"Complete reference lacks final marker");
        var priorBytes=trace.outputs().manifestBytes(); var priorHash=trace.outputs().manifestHash();
        writePostSeal(budget,"reference-late-verdict.txt","actual later verdict; same original scalar reservation");
        var postSuccess=new IllegalStateException("synthetic first failure after committed success; not native proof");
        var afterSuccess=new IllegalArgumentException("synthetic later failure after retained verdict");
        capture(postSuccess,()->{ throw afterSuccess; });
        writeDiagnostic(budget,"reference-post-success-failure.txt",postSuccess,true);
        var reset=provider.resolve("reference-reset-2");
        var second=phaseReference(r,provider,owner,budget,trace,1,"reset-2",reset,first,null,postSuccess);
        verifyFailureReference(provider,reset,budget,trace,run,postSuccess,null,budget.root().resolve("reference-post-success-failure.txt"),afterSuccess);
        var afterReset=new AssertionError("synthetic later close failure after reset record");
        capture(postSuccess,()->{ throw afterReset; });
        writeDiagnostic(budget,"reference-before-close-failure.txt",postSuccess,true);
        var close=provider.resolve("reference-close");
        var third=phaseReference(r,provider,owner,budget,trace,1,"close",close,second,null,postSuccess);
        verifyFailureReference(provider,close,budget,trace,run,postSuccess,null,budget.root().resolve("reference-before-close-failure.txt"),afterSuccess,afterReset);
        require(trace.outputs().manifestBytes()==priorBytes && trace.outputs().manifestHash().equals(priorHash),
                "Late phase rewrote the original sealed snapshot");
        rejected("prior reset cannot spend next ordinal",()->phaseReference(r,provider,owner,budget,trace,2,
                "wrong-budget",provider.resolve("reference-wrong-budget"),third,null,null));
        var equal=new Sealed(trace.events(),trace.eventCount(),trace.eventBytes(),trace.eventHash(),trace.checkpoints(),
                trace.metadata(),trace.outputs(),trace.complete(),trace.failure());
        rejected("equal reconstructed seal",()->phaseReference(r,provider,owner,budget,equal,1,
                "equal-seal",provider.resolve("reference-equal-seal"),third,null,null));
        var foreign=new CompactOwner(budget,trace,UUID.randomUUID(),invocation,1);
        rejected("foreign run",()->phaseReference(r,provider,foreign,budget,trace,1,
                "foreign-run",provider.resolve("reference-foreign-run"),third,null,null));
        var foreignPath=new Sealed(provider.resolveSibling("foreign-events.jsonl"),trace.eventCount(),trace.eventBytes(),trace.eventHash(),
                trace.checkpoints(),trace.metadata(),trace.outputs(),true,null);
        rejected("foreign sealed path",()->phaseReference(r,provider,new CompactOwner(budget,foreignPath,run,invocation,1),budget,foreignPath,1,
                "foreign-path",provider.resolve("reference-foreign-path"),third,null,null));
        var restores=new int[1]; var oldFailure=new IllegalStateException("original archive failure");
        capture(postSuccess,()->{ throw oldFailure; });
        writeDiagnostic(budget,"reference-after-archive-failure.txt",postSuccess,true);
        rejected("old archive error prevents reset/close",()->archiveThenRestore(()->phaseReference(r,provider,owner,budget,trace,1,
                "old-failure",provider.resolve("reference-old-failure"),third,oldFailure,postSuccess),()->restores[0]++));
        verifyFailureReference(provider,provider.resolve("reference-old-failure"),budget,trace,run,postSuccess,oldFailure,
                budget.root().resolve("reference-after-archive-failure.txt"),afterSuccess,afterReset,oldFailure);
        require(restores[0]==0 && !Files.exists(provider.resolve("reference-old-failure/COMMITTED")),
                "Previous archive failure reached restoration or a new marker");
        var late=archiveLateSidecars(provider,budget,trace,run,third);
        verifyLateFailureClosure(provider,budget,late,budget.root().resolve("reference-after-archive-failure.txt"));
        require(restores[0]==0 && !Files.exists(provider.resolve("reference-old-failure/COMMITTED"))
                        && compactFile(provider,trace.outputs().manifest()).sha256().equals(priorHash) && trace.outputs().manifestBytes()==priorBytes,
                "Failure/late closure rewrote the original sealed receipt snapshot");
        try {
            var fixtures=provider.resolveSibling("reference-fixtures"); checkedDirectory(r,fixtures);
            var next=new OutputBudget(fixtures.resolve("capture-2"),Limits.forRun(1,0,0,1));
            var preFacade=new IllegalStateException("real pre-facade first failure");
            var beforeSeal=new IllegalArgumentException("distinct pre-facade diagnostic failure");
            capture(preFacade,()->{ throw beforeSeal; });
            writeDiagnostic(next,"pre-facade.txt",preFacade); next.seal();
            var nextOwner=new CompactOwner(next,null,null,invocation,2);
            rejected("prior seal cannot adopt new budget",()->phaseReference(r,fixtures,owner,next,trace,1,
                    "foreign-budget",fixtures.resolve("foreign-budget"),Map.of(),null,null));
            var preFacadeFiles=phaseReference(r,fixtures,nextOwner,next,null,2,"pre-facade",fixtures.resolve("pre-facade"),Map.of(),null,preFacade);
            verifyFailureReference(fixtures,fixtures.resolve("pre-facade"),next,null,null,preFacade,null,next.root().resolve("pre-facade.txt"),beforeSeal);
            require(next.scalarBytes()>0 && budget.scalarBytes()>0 && owner.budget()!=nextOwner.budget(),
                    "Repeat2 reused the prior run's reservation");
            try(var unclosed=next.openPostSealFailure("unclosed-prefix.txt")) {
                unclosed.write("actual open prefix".getBytes(StandardCharsets.UTF_8));
                rejected("unclosed late output",()->archiveThenRestore(()->phaseReference(r,fixtures,nextOwner,next,null,2,"unclosed",
                        fixtures.resolve("unclosed"),preFacadeFiles,null,preFacade),()->restores[0]++));
            }
            require(restores[0]==0 && !Files.exists(fixtures.resolve("unclosed/COMMITTED")),"Unclosed post-seal output obtained a marker/restoration");
            var afterSeal=new AssertionError("distinct pre-facade late closure failure");
            capture(preFacade,()->{ throw afterSeal; });
            writeDiagnostic(next,"pre-facade-late.txt",preFacade,true);
            var preFacadeLate=archiveLateSidecars(fixtures,next,null,null,preFacadeFiles);
            verifyLateFailureClosure(fixtures,next,preFacadeLate,next.root().resolve("pre-facade-late.txt"));
            require(Arrays.equals(preFacade.getSuppressed(),new Throwable[]{beforeSeal,afterSeal})
                            && Files.readString(next.root().resolve("pre-facade-late.txt")).contains(afterSeal.toString()),
                    "Pre-facade late output lost primary/suppressed provenance");
            var backend=provider.resolveSibling("reference-backend"); checkedDirectory(r,backend);
            var originals=new HashMap<String,Path>();
            for(var file:currentCompactFiles(provider,budget,trace,run,third).values()) {
                var path=provider.resolve(file.path());
                if(path.getParent().equals(budget.root())) originals.put(file.sha256(),path);
            }
            // Copy only this labelled new synthetic provider, through the existing copier; no old proof is touched.
            try(var paths=Files.walk(provider)) {
                for(var source:paths.sorted().toList()) {
                    var target=backend.resolve(provider.relativize(source));
                    if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) checkedDirectory(r,target);
                    else copyCompact(r,backend,budget,Objects.requireNonNull(originals.get(compactFile(provider,source).sha256())),target);
                }
            }
            verifyPhaseReference(backend,backend.resolve("reference-close/phase-reference.json"));
            verifyPhaseReference(backend,backend.resolve("reference-reset-2/phase-reference.json"));
            var bulk=new HashSet<Path>(); bulk.add(trace.events()); trace.checkpoints().values().forEach(c->bulk.add(c.path()));
            budget.receipts().stream().filter(receipt->receipt.kind()==OutputKind.REGION).forEach(receipt->bulk.add(receipt.path()));
            try(var files=Files.walk(provider)) {
                var names=files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->p.getFileName().toString()).toList();
                for(var path:bulk) require(names.stream().filter(name->name.equals(path.getFileName().toString())).count()==1,
                        "Phase references duplicated actual bulk event/scan/region bytes");
            }
            var missing=fixtures.resolve("missing-payload"); checkedDirectory(r,missing);
            var missingStage=missing.resolve("reference-close"); checkedDirectory(r,missingStage);
            for(var name:List.of("phase-reference.json","phase-reference.json.receipt.jsonl","COMMITTED","COMMITTED.receipt.jsonl")) {
                var source=close.resolve(name);
                copyCompact(r,missing,budget,Objects.requireNonNull(originals.get(compactFile(provider,source).sha256())),missingStage.resolve(name));
            }
            // Missing real payload bytes and changed bytes both fail while an old successful marker remains.
            rejected("absent relocated payload",()->verifyPhaseReference(missing,missingStage.resolve("phase-reference.json")));
            Files.writeString(backend.resolve("events.jsonl"),"changed synthetic payload",StandardOpenOption.APPEND);
            rejected("old marker cannot hide changed payload",()->verifyPhaseReference(backend,backend.resolve("reference-close/phase-reference.json")));
            var receipt=next.receipts().getFirst().receipt();
            Files.writeString(receipt,"changed synthetic receipt",StandardOpenOption.APPEND);
            rejected("first-reset receipt failure",()->archiveThenRestore(()->phaseReference(r,fixtures,nextOwner,next,null,2,"reset-first",
                    fixtures.resolve("reset-first"),preFacadeLate,null,preFacade),()->restores[0]++));
            rejected("second-reset old receipt failure",()->archiveThenRestore(()->phaseReference(r,fixtures,nextOwner,next,null,2,"reset-second",
                    fixtures.resolve("reset-second"),preFacadeLate,null,preFacade),()->restores[0]++));
            require(restores[0]==0 && !Files.exists(fixtures.resolve("reset-first/COMMITTED"))
                            && !Files.exists(fixtures.resolve("reset-second/COMMITTED")),"Changed failure receipt reached reset restoration/marker");
            var scanBudget=new OutputBudget(fixtures.resolve("scan-prefix"),Limits.forRun(1,0,1,1));
            var scanRun=UUID.randomUUID(); var scanCapture=new TraversalCapture(scanBudget.root(),scanRun,Limits.forRun(1,0,1,1),Set.of(),scanBudget);
            MotionFrame initial;
            try(var reader=trace.openEvents()) { initial=(MotionFrame)reader.next().orElseThrow(); }
            var frame=new MotionFrame(0,initial.tick(),initial.phase(),scanRun,initial.identities(),initial.pose(),initial.keys(),initial.paused(),
                    initial.owner(),initial.lifecycle(),initial.activity(),initial.outcome(),initial.reason(),initial.eligible(),initial.verify(),
                    initial.traversal(),initial.pending(),initial.unfinished(),initial.effect(),initial.predictionSequence(),null,null);
            scanCapture.append(frame);
            var large=new BuildPlan.BlockSpec("minecraft:stone",Map.of("synthetic-prefix","x".repeat(6000)));
            rejected("actual partial scan writer",()->scanCapture.checkpoint(CheckpointKind.FAILURE,new FullScan(scanRun,frame.tick(),frame.phase(),0,
                    List.of(new PlacementObservation(scanRun,0,OptionalLong.empty(),frame.tick(),Evidence.OBSERVED,Optional.of(large))))));
            var scanPrefix=scanCapture.seal(); var scanCause=scanBudget.failure().orElseThrow();
            rejected("incomplete scan cannot commit",()->phaseReference(r,fixtures,new CompactOwner(scanBudget,scanPrefix,scanRun,invocation,1),
                    scanBudget,scanPrefix,1,"scan-prefix",fixtures.resolve("scan-prefix-phase"),Map.of(),null,scanCause));
            require(!Files.exists(fixtures.resolve("scan-prefix-phase/COMMITTED")) && scanBudget.failure().orElseThrow()==scanCause
                            && !scanPrefix.checkpoints().get(CheckpointKind.FAILURE).complete(),"Partial scan gained a commit or lost first cause");
        } catch(IOException error) { throw new UncheckedIOException(error); }
        System.out.println("ScenarioRunner actual shared phase/reference guards passed; synthetic, not native evidence");
    }

    private static void verifyFailureReference(Path provider,Path stage,OutputBudget budget,Sealed trace,UUID run,
                                               Throwable primary,Throwable archiveFailure,Path diagnostic,Throwable... suppressed) {
        try {
            var descriptor=JsonParser.parseString(Files.readString(stage.resolve("phase-reference.json"))).getAsJsonObject();
            require(descriptor.get("originalFailure").getAsString().equals(primary.getClass().getName())
                            && descriptor.get("originalFailureMessage").getAsString().equals(primary.getMessage())
                            && i(descriptor,"suppressedCount")==suppressed.length && Arrays.equals(primary.getSuppressed(),suppressed),
                    "Compact phase lost original primary identity/message or suppressed snapshot");
            require((archiveFailure==null ? descriptor.get("archiveFailure").isJsonNull()
                            : descriptor.get("archiveFailure").getAsString().equals(archiveFailure.getClass().getName()))
                            && b(descriptor,"complete")== (archiveFailure==null)
                            && Files.isRegularFile(stage.resolve("COMMITTED"))==b(descriptor,"complete"),
                    "Failed archive metadata authorized a marker or lost its actual archive failure");
            require(run==null ? descriptor.get("run").isJsonNull() : descriptor.get("run").getAsString().equals(run.toString()),
                    "Failure phase invented/lost actual run identity");
            require(descriptor.get("sealedState").getAsString().equals(trace==null?"NO_STARTED_CAPTURE":"SEALED")
                            && (trace==null ? descriptor.get("sealedReceipt").isJsonNull()
                            : new Gson().fromJson(descriptor.get("sealedReceipt"),CompactFile.class).equals(compactFile(provider,trace.metadata()))),
                    "Failure phase invented/lost original sealed provenance");
            var text=Files.readString(diagnostic);
            require(text.startsWith(primary.toString()),"Closed diagnostic replaced its original primary");
            for(var error:suppressed) require(text.contains("Suppressed: "+error),"Closed diagnostic lost distinct suppressed failure");
            var receipt=budget.receipts().stream().filter(value->value.path().equals(diagnostic)).findFirst().orElseThrow();
            var files=List.of(new Gson().fromJson(descriptor.get("files"),CompactFile[].class));
            require(files.contains(compactFile(provider,diagnostic)) && files.contains(compactFile(provider,receipt.receipt())),
                    "Failure descriptor omitted actual diagnostic/closed receipt bytes");
            verifyCompactFiles(provider,files);
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static void verifyLateFailureClosure(Path provider,OutputBudget budget,Map<String,CompactFile> closure,Path diagnostic) {
        try {
            var summary=JsonParser.parseString(Files.readString(budget.root().resolve("late-manifest.json"))).getAsJsonObject();
            var predecessors=List.of(new Gson().fromJson(summary.get("closedPredecessors"),CompactFile[].class));
            var receipt=budget.receipts().stream().filter(value->value.path().equals(diagnostic)).findFirst().orElseThrow();
            require(predecessors.contains(compactFile(provider,diagnostic)) && predecessors.contains(compactFile(provider,receipt.receipt()))
                            && closure.containsValue(compactFile(provider,budget.root().resolve("late-manifest.json")))
                            && closure.containsValue(compactFile(provider,budget.root().resolve("late-manifest.json.receipt.jsonl"))),
                    "Shared late closure omitted actual failure/summary/receipt provenance");
            verifyCompactFiles(provider,closure.values());
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private void archiveCompactFiles(Path stage,Sealed trace) {
        if(compactOwner==null) compactOwner=new CompactOwner(outputs,sealed,capture==null?null:capture.runId(),invocation,compactBudgetOrdinal);
        compactFiles=phaseReference(roots,artifacts,compactOwner,outputs,trace,compactBudgetOrdinal,
                artifacts.relativize(stage).toString().replace('\\','/'),stage,compactFiles,compactArchiveFailure,compactOriginalFailure);
        compactCommitted=true;
    }

    private static void writePostSeal(OutputBudget budget,String name,String text) {
        try(var writer=new BufferedWriter(new OutputStreamWriter(budget.openPostSealFailure(name),StandardCharsets.UTF_8))) {
            writer.write(text); writer.write('\n');
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static CompactFile compactFile(Path provider,Path path) {
        require(provider.isAbsolute() && provider.normalize().equals(provider) && real(provider).equals(provider)
                        && path.isAbsolute() && path.normalize().equals(path) && path.startsWith(provider)
                        && real(path).equals(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),
                "Phase reference requires an actual canonical provider-relative regular file");
        try {
            try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.WRITE)) { channel.force(true); }
            return new CompactFile(provider.relativize(path).toString().replace('\\','/'),Files.size(path),fileHash(path));
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static void verifyCompactFiles(Path provider,Collection<CompactFile> files) {
        for(var expected:files) {
            var relative=Path.of(expected.path());
            require(!relative.isAbsolute() && !expected.path().isBlank() && relative.normalize().equals(relative)
                            && !relative.startsWith(".."),"Escaped phase reference");
            require(compactFile(provider,provider.resolve(relative)).equals(expected),"Closed phase/output bytes changed");
        }
    }

    private static Map<String,CompactFile> currentCompactFiles(Path provider,OutputBudget budget,Sealed trace,UUID run,
                                                              Map<String,CompactFile> prior) {
        verifyCompactFiles(provider,prior.values());
        var snapshot=budget.seal();
        require(trace==null ? run==null : trace.outputs()==snapshot && run!=null,
                "Sealed snapshot is not this original budget/run");
        var result=new TreeMap<String,CompactFile>(); result.putAll(prior);
        var paths=new LinkedHashSet<Path>(budget.ownedFiles());
        try {
            require(compactFile(provider,snapshot.manifest()).equals(new CompactFile(
                    provider.relativize(snapshot.manifest()).toString().replace('\\','/'),snapshot.manifestBytes(),snapshot.manifestHash())),
                    "Original sealed output manifest changed");
            for(var receipt:budget.receipts()) {
                require(receipt.path().getParent().equals(budget.root()) && receipt.receipt().getParent().equals(budget.root()),
                        "Foreign output/receipt path");
                var actual=compactFile(provider,receipt.path());
                require(actual.bytes()==receipt.bytes() && actual.sha256().equals(receipt.hash()),"Owned closed output changed");
                if(!receipt.receipt().equals(snapshot.manifest())) {
                    var expected=new JsonArray(); expected.add("OUTPUT1"); expected.add(receipt.path().getFileName().toString());
                    expected.add(receipt.kind().name()); expected.add(receipt.bytes()); expected.add(receipt.hash());
                    compactFile(provider,receipt.receipt());
                    require(JsonParser.parseString(Files.readString(receipt.receipt())).equals(expected),"Closed output receipt changed");
                }
            }
            var receipted=new HashSet<Path>(); receipted.add(snapshot.manifest());
            budget.receipts().forEach(receipt->{ receipted.add(receipt.path()); receipted.add(receipt.receipt()); });
            require(receipted.equals(paths),"Current output contains an unclosed/unreceipted prefix");
            if(trace!=null) {
                require(trace.events().getParent().equals(budget.root()) && trace.metadata().getParent().equals(budget.root()),
                        "Foreign sealed payload path");
                var events=compactFile(provider,trace.events());
                require(events.bytes()==trace.eventBytes() && events.sha256().equals(trace.eventHash()),"Original sealed event bytes changed");
                // Reuse the original bounded stream reader; a descriptor never reconstructs a Sealed/native proof.
                if(trace.complete()) try(var reader=trace.openEvents()) {
                    for(var event=reader.next();event.isPresent();event=reader.next())
                        require(event.get().runId().equals(run),"Foreign native event identity");
                }
                paths.add(trace.events()); paths.add(trace.metadata());
                for(var checkpoint:trace.checkpoints().values()) {
                    require(checkpoint.path().getParent().equals(budget.root()) && checkpoint.runId().equals(run),"Foreign checkpoint path/run");
                    var actual=compactFile(provider,checkpoint.path());
                    if(checkpoint.bytes()>=0 && checkpoint.hash()!=null)
                        require(actual.bytes()==checkpoint.bytes() && actual.sha256().equals(checkpoint.hash()),"Original checkpoint prefix changed");
                    paths.add(checkpoint.path());
                }
            }
            for(var path:paths) {
                require(path.getParent().equals(budget.root()),"Output is outside its original reservation");
                var file=compactFile(provider,path); result.put(file.path(),file);
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
        return Map.copyOf(result);
    }

    private static Map<String,CompactFile> phaseReference(Roots r,Path provider,CompactOwner owner,OutputBudget budget,Sealed trace,
            int ordinal,String phase,Path stage,Map<String,CompactFile> prior,Throwable archiveFailure,Throwable originalFailure) {
        checkRoots(r);
        require(owner!=null && owner.budget()==budget && owner.trace()==trace && owner.ordinal()==ordinal,
                "Phase reference lost original budget/seal/ordinal identity");
        require(ordinal>=1 && provider.isAbsolute() && provider.normalize().equals(provider) && real(provider).equals(provider)
                        && stage.isAbsolute() && stage.normalize().equals(stage) && provider.startsWith(r.evidence)
                        && stage.startsWith(provider) && !stage.equals(budget.root())
                        && budget.root().startsWith(provider),"Foreign phase/provider root");
        checkedDirectory(r,stage);
        var files=currentCompactFiles(provider,budget,trace,owner.run(),prior);
        boolean complete=(trace==null || trace.complete()) && budget.failure().isEmpty() && budget.seal().complete() && archiveFailure==null;
        String name="phase-"+provider.relativize(stage).toString().replace('\\','-').replace('/','-');
        var descriptor=new JsonObject(); descriptor.addProperty("schema","mapartrunner-traversal-phase-reference-v1");
        descriptor.addProperty("invocation",owner.invocation().toString());
        descriptor.add("run",owner.run()==null?JsonNull.INSTANCE:new JsonPrimitive(owner.run().toString()));
        descriptor.addProperty("budgetOrdinal",ordinal); descriptor.addProperty("phase",phase);
        descriptor.addProperty("payloadRoot",provider.equals(budget.root())?".":provider.relativize(budget.root()).toString().replace('\\','/'));
        descriptor.addProperty("sealedState",trace==null?"NO_STARTED_CAPTURE":trace.complete()?"SEALED":"INCOMPLETE_PREFIX");
        descriptor.add("sealedReceipt",trace==null?JsonNull.INSTANCE:new Gson().toJsonTree(compactFile(provider,trace.metadata())));
        descriptor.add("outputSnapshot",new Gson().toJsonTree(compactFile(provider,budget.seal().manifest())));
        descriptor.addProperty("complete",complete);
        descriptor.addProperty("originalFailure",originalFailure==null?null:originalFailure.getClass().getName());
        descriptor.addProperty("originalFailureMessage",originalFailure==null?null:boundedMessage(originalFailure));
        descriptor.addProperty("suppressedCount",originalFailure==null?0:originalFailure.getSuppressed().length);
        descriptor.addProperty("archiveFailure",archiveFailure==null?null:archiveFailure.getClass().getName());
        descriptor.addProperty("outputFailure",budget.failure().map(e->e.getClass().getName()).orElse(null));
        descriptor.add("files",new Gson().toJsonTree(new TreeMap<>(files).values()));
        descriptor.add("phaseRecords",new Gson().toJsonTree(new TreeMap<>(files).values().stream()
                .filter(file->!prior.containsKey(file.path())).toList()));
        // The finite predecessor set excludes the descriptor and its not-yet-created receipt.
        writePostSeal(budget,name+".json",descriptor.toString());
        copyCompact(r,provider,budget,budget.root().resolve(name+".json"),stage.resolve("phase-reference.json"));
        copyCompact(r,provider,budget,budget.root().resolve(name+".json.receipt.jsonl"),stage.resolve("phase-reference.json.receipt.jsonl"));
        files=currentCompactFiles(provider,budget,trace,owner.run(),files);
        var predecessor=new TreeMap<>(files);
        for(var path:List.of(stage.resolve("phase-reference.json"),stage.resolve("phase-reference.json.receipt.jsonl"))) {
            var file=compactFile(provider,path); predecessor.put(file.path(),file);
        }
        require(complete,"Incomplete/previously failed archive; reset/rearm/owned close refused");
        requireCompleteOutputs(budget);
        var marker=new JsonObject(); marker.addProperty("invocation",owner.invocation().toString()); marker.addProperty("phase",phase);
        marker.addProperty("meaning","complete closed phase predecessors; later world-close/invocation records are not claimed");
        marker.add("files",new Gson().toJsonTree(predecessor.values()));
        // Descriptor, its receipt, and every predecessor are closed and verified before final marker admission.
        verifyCompactFiles(provider,predecessor.values());
        writePostSeal(budget,name+".COMMITTED",marker.toString());
        var closed=currentCompactFiles(provider,budget,trace,owner.run(),predecessor);
        copyCompact(r,provider,budget,budget.root().resolve(name+".COMMITTED.receipt.jsonl"),stage.resolve("COMMITTED.receipt.jsonl"));
        copyCompact(r,provider,budget,budget.root().resolve(name+".COMMITTED"),stage.resolve("COMMITTED"));
        var result=new TreeMap<>(closed);
        for(var path:List.of(stage.resolve("COMMITTED.receipt.jsonl"),stage.resolve("COMMITTED"))) {
            var file=compactFile(provider,path); result.put(file.path(),file);
        }
        verifyCompactFiles(provider,result.values());
        return Map.copyOf(result);
    }

    private static String boundedMessage(Throwable error) {
        var message=String.valueOf(error.getMessage()); return message.substring(0,Math.min(2048,message.length()));
    }

    private static void verifyPhaseReference(Path provider,Path descriptorPath) {
        require(descriptorPath.startsWith(provider),"Foreign descriptor path");
        compactFile(provider,descriptorPath);
        try {
            var descriptor=JsonParser.parseString(Files.readString(descriptorPath)).getAsJsonObject();
            require(descriptor.get("schema").getAsString().equals("mapartrunner-traversal-phase-reference-v1"),"Unknown phase schema");
            var payload=Path.of(descriptor.get("payloadRoot").getAsString());
            require(!payload.isAbsolute() && !payload.startsWith("..") && (payload.toString().equals(".") || payload.normalize().equals(payload))
                            && real(provider.resolve(payload)).equals(provider.resolve(payload).normalize()),"Foreign payload root");
            verifyCompactFiles(provider,List.of(new Gson().fromJson(descriptor.get("files"),CompactFile[].class)));
            var markerPath=descriptorPath.getParent().resolve("COMMITTED");
            var marker=JsonParser.parseString(Files.readString(markerPath)).getAsJsonObject();
            require(b(descriptor,"complete") && marker.get("invocation").equals(descriptor.get("invocation"))
                            && marker.get("phase").equals(descriptor.get("phase")),"Incomplete/foreign phase marker");
            verifyCompactFiles(provider,List.of(new Gson().fromJson(marker.get("files"),CompactFile[].class)));
            for(var path:List.of(descriptorPath,markerPath)) {
                var actual=compactFile(provider,path);
                var receiptPath=path.resolveSibling(path.getFileName()+".receipt.jsonl");
                compactFile(provider,receiptPath);
                var receipt=JsonParser.parseString(Files.readString(receiptPath)).getAsJsonArray();
                require(receipt.size()==5 && receipt.get(0).getAsString().equals("OUTPUT1")
                                && receipt.get(2).getAsString().equals("SCALAR") && receipt.get(3).getAsLong()==actual.bytes()
                                && receipt.get(4).getAsString().equals(actual.sha256()),"Phase descriptor/marker receipt changed");
                var original=provider.resolve(payload).normalize().resolve(receipt.get(1).getAsString());
                var source=compactFile(provider,original);
                require(source.bytes()==actual.bytes() && source.sha256().equals(actual.sha256()),"Phase copy lost original charged record");
            }
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private void copyCompact(Path from,Path to) {
        try {
            copyCompact(roots,artifacts,outputs,from,to);
            var files=new TreeMap<>(compactFiles); var file=compactFile(artifacts,to); files.put(file.path(),file);
            compactFiles=Map.copyOf(files);
        } catch(RuntimeException | Error error) { compactArchiveFailure=retain(compactArchiveFailure,error); throw error; }
    }

    private static void copyCompact(Roots roots,Path artifacts,OutputBudget outputs,Path from,Path to) {
        checkRoots(roots);
        require(outputs!=null && from.toAbsolutePath().normalize().equals(from) && from.getParent().equals(outputs.root())
                && real(from).equals(from) && Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)
                && real(to.getParent()).equals(to.getParent()) && to.startsWith(artifacts),"Owned trace archive copy escaped");
        try {
            var before=fileHash(from); Files.copy(from,to);
            try(var file=java.nio.channels.FileChannel.open(to,StandardOpenOption.WRITE)) { file.force(true); }
            require(Files.size(from)==Files.size(to) && before.equals(fileHash(to)) && before.equals(fileHash(from)),"Owned archive bytes changed during copy");
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private static String fileHash(Path path) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)) { var bytes=new byte[8192]; int count; while((count=input.read(bytes))!=-1) digest.update(bytes,0,count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    ScenarioResult run(ScenarioDefinition definition) {
        UUID id = start(definition,true);
        var stage=artifacts.resolve("success-"+countedStarts+"-"+id);
        checkedDirectory(roots,stage);
        if(baseline()) {
            var armed=context.computeOnClient(c -> PredictionFaults.resetSnapshot().support());
            require(supportCase==SupportCase.ORDINARY ? armed.isEmpty() : armed.filter(g -> g.state()==PredictionFaults.SupportState.ARMED
                    && g.identity()==supportArm && g.identity().context().runId().equals(id)).isPresent(),"Support receipt lacks actual selected mode authority");
            writeNew(roots,stage.resolve("support-coverage.txt"),"invocation="+invocation+"; run="+id+"; ordinal="+countedStarts
                    +"; definition="+definition.definitionHash()+"; case="+supportCase.label()+"; gateArmed="+(supportCase==SupportCase.CONTROLLED)
                    +"; proof="+(supportCase==SupportCase.ORDINARY?"support-footprint-proof.txt":"support-proof.txt")
                    +"; meaning="+supportMeaning()+(supportCase==SupportCase.CONTROLLED
                        ? "; importedCoordinate=(11,0,5); actualId="+armed.orElseThrow().identity().targetId()
                            +"; arm=support-gate-arm.txt; release=support-gate-release.txt; observer=observer-before-assertions.txt" : ""));
            if(supportCase==SupportCase.CONTROLLED) writeNew(roots,stage.resolve("support-gate-arm.txt"),armed.orElseThrow().toString());
        }
        double clientReach=context.computeOnClient(c -> { requireClient(c); return c.player.getBlockInteractionRange(); });
        double serverReach=world.getServer().computeOnServer(s -> { requireServer(s); return serverPlayer.getBlockInteractionRange(); });
        require(Double.isFinite(clientReach) && clientReach>0 && Double.isFinite(serverReach) && serverReach>0,"Invalid actual interaction reach");
        var startText="run="+id+"; definition="+definition.definitionHash()+"; observedStart="+observedHash
                +"; clientReach="+clientReach+"; serverReach="+serverReach+"; actualStart="+evidence(stable);
        if(baseline()) writeNew(roots,stage.resolve("start.txt"),startText); else scalar("start.txt",startText);
        if (priorCountedStart!=null) {
            var comparison="previousObservedHash="
                +assertedSuccesses.getLast().observedStableStartHash()+"; currentObservedHash="+observedHash
                +"; rawDelta="+deltas(priorCountedStart,stable)+"; both starts independently passed readiness; byte equality is not required";
            if(baseline()) writeNew(roots,stage.resolve("start-comparison.txt"),comparison); else scalar("start-comparison.txt",comparison);
        }
        priorCountedStart=stable;
        List<Live> checkpoints=baseline()?new ArrayList<>():null;
        Live current=baseline()?live():null, shiftStart=null, resumed=null;
        if(baseline()) checkpoints.add(current);
        TraversalPoll poll=baseline()?null:traversalPoll();
        if(!baseline()) fullPlayerCheckpoint(CheckpointKind.INITIAL);
        boolean paused=false;
        int budget = i(definition.data().getAsJsonObject("budgets"),"runTicks");
        var profileText="fixture="+fixture+"; physicsRunTicks="+budget
                +"; productionEligibleAllowance="+BuildEngine.runLimit(plan,settings)+"; targets="+plan.targets().size();
        if(baseline()) writeNew(roots,stage.resolve("fixture-profile.txt"),profileText); else scalar("fixture-profile.txt",profileText);
        for (int tick=0; tick<budget; tick++) {
            context.waitTick();
            if(baseline() && supportCase==SupportCase.CONTROLLED) context.runOnClient(c -> {
                requireClient(c);
                PredictionFaults.supportHold(c,id).ifPresent(held -> {
                    var before=PredictionFaults.resetSnapshot(); var status=MapArtClient.status();
                    var equal=new ScenarioObserver.Sample(held.tick(),held.phase(),held.worldIdentity(),held.originalWorld(),held.pose(),
                            held.keys(),held.paused(),held.status(),held.observations());
                    rejected("equal reconstructed native END release",() -> PredictionFaults.releaseSupportAvailability(c,server,save,id,equal));
                    require(before.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status()),"Equal-END refusal mutated authority");
                    var released=PredictionFaults.releaseSupportAvailability(c,server,save,id,held);
                    var after=PredictionFaults.resetSnapshot();
                    rejected("double native support release",() -> PredictionFaults.releaseSupportAvailability(c,server,save,id,held));
                    rejected("consumed UUID support rearm",() -> PredictionFaults.armSupportAvailability(c,server,save,id,plan,settings,watch));
                    require(after.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status()),"Support release/refusal changed core or rearmed");
                    writeNew(roots,stage.resolve("support-gate-release.txt"),released+"\nactual equal-END/double-release/same-UUID-rearm refusals preserved gate/core");
                });
            });
            if(baseline()) {
                current=live(); checkpoints.add(current);
                writeNew(roots,stage.resolve("physics-"+tick+".txt"),current.toString());
                requireRun(current.sample,id);
            } else {
                poll=traversalPoll();
                forwardHistory.accept(poll.end);
                forwardHistory.acceptServer(poll.end,poll.server);
                var motion=poll.end.traversal();
                if(!admitted.removalTargets().isEmpty() && !removalConsumed && motion!=null && motion.pass().equals("FORWARD")
                        && motion.physicalIndex()==fixture.removalLane() && motion.substage().equals("LANE") && motion.stage().equals("WALK")
                        && fixture.removalX(poll.end.pose().x()-origin.x())) applyTraversalRemoval(id,poll.end,stage);
                if(capture.hasCheckpoint(CheckpointKind.FIRST_VERIFY) && !playerCheckpoints.contains(CheckpointKind.FIRST_VERIFY))
                    fullPlayerCheckpoint(CheckpointKind.FIRST_VERIFY);
            }
            if ((baseline()?current.sample.status().engine().lifecycle().phase():poll.phase)==Phase.TERMINAL) break;
            if (baseline() && substage(current.sample,ForwardTraversal.Substage.SHIFT)) {
                if (shiftStart==null) shiftStart=current;
                if (countedStarts==1 && !paused && nativeForward(current.sample)
                        && moved(shiftStart,current,"z",.25,1) && (supportCase==SupportCase.ORDINARY
                            || context.computeOnClient(c -> findSupportWitness(id,plan,origin,supportArm,
                                    PredictionFaults.resetSnapshot().support(),watch.copy()).isPresent()))) {
                    require(tick+20<budget,"SHIFT pause would exceed the shared run physics budget");
                    resumed=pauseShift(id,current,stage,checkpoints);
                    tick+=20; paused=true;
                }
            }
        }
        var terminal=baseline()?current.sample.status().engine():context.computeOnClient(c->{ requireClient(c); return MapArtClient.status().engine(); });
        List<ScenarioObserver.Sample> samples=baseline()?context.computeOnClient(c -> watch.copy()):List.of();
        if(baseline()) writeLinesNew(stage.resolve("observer-before-assertions.txt"),samples.stream().map(ScenarioObserver.Sample::line)::iterator);
        require(terminal.lifecycle().phase()==Phase.TERMINAL && terminal.lifecycle().outcome().orElse(null)==Outcome.SUCCESS,
                "Counted run did not reach SUCCESS within "+budget+" real ticks"+(baseline()?": "+terminal:"; compact prefix retained"));
        require(terminal.diagnostic().orElseThrow().reason()==BuildEngine.Reason.VERIFIED
                        && terminal.diagnostic().orElseThrow().detail().equals("Later current full-plan OBSERVED scan matched every expected state")
                        && terminal.unfinishedTargetIds().isEmpty() && terminal.pendingTargetIds().isEmpty(),
                "SUCCESS lacks exact VERIFIED/ALL_CONFIRMED export"+(baseline()?": "+terminal:"; compact prefix retained"));
        if(baseline()) {
            require(countedStarts!=1 || paused,"First success never reached the required actual SHIFT pause");
            verifyTraversal(id,checkpoints,samples,clientReach,serverReach,resumed,stage);
            if(supportCase==SupportCase.ORDINARY) verifyOrdinarySupport(id,samples,stage);
            else {
                require(context.computeOnClient(c -> PredictionFaults.resetSnapshot().support().orElseThrow().state())
                        ==PredictionFaults.SupportState.RELEASED,"Controlled gate was not consumed by actual retained hold");
                verifySupport(id,samples,stage);
            }
        }
        if(baseline()) {
        var accepted=samples.stream().flatMap(s -> s.status().engine().effectResults().stream())
                .filter(e -> e.status()==BuildEngine.EffectStatus.ACCEPTED).distinct().toList();
        requireAcceptedFeedback(id,plan.targets(),accepted);
        writeNew(roots,stage.resolve("accepted-actions.txt"),accepted.toString());
        long verifyFrame=samples.stream().filter(s -> s.phase().equals("END")
                && s.status().engine().lifecycle().activity().orElse(null)==Activity.VERIFY
                && s.status().engine().lifecycle().phase()==Phase.RUNNING).mapToLong(ScenarioObserver.Sample::tick).findFirst().orElseThrow();
        var success=samples.stream().filter(s -> s.phase().equals("END") && s.tick()>verifyFrame
                && s.status().engine().lifecycle().outcome().orElse(null)==Outcome.SUCCESS).findFirst().orElseThrow();
        require(success.status().engine().eligibleTicks()>samples.stream().filter(s -> s.phase().equals("END") && s.tick()==verifyFrame)
                .findFirst().orElseThrow().status().engine().eligibleTicks(),"VERIFY did not precede an advancing SUCCESS decision");
        checkCurrentScan(id,current.sample.tick(),plan.targets(),current.sample.observations());
        } else fullPlayerCheckpoint(CheckpointKind.TERMINAL);
        Live released=current;
        TraversalPoll releasedPoll=poll;
        for (int tick=0;tick<20;tick++) {
            if(!baseline() && tick==19) context.runOnClient(c->{ requireClient(c); watch.requestReleaseEnd(); });
            context.waitTick();
            if(baseline()) {
            Live next=live(); requireRun(next.sample,id);
            context.runOnClient(ScenarioRunner::requireReleased);
            require(next.sample.status().engine().equals(terminal) && next.nativeSequence==current.nativeSequence,
                    "Terminal release20 changed engine or issued a new native action");
            requirePhysics(released,next);
            writeNew(roots,stage.resolve("release-"+tick+".txt"),next.toString());
            released=next;
            } else {
                var next=traversalPoll();
                context.runOnClient(ScenarioRunner::requireReleased);
                require(next.phase==Phase.TERMINAL && next.outcome==Outcome.SUCCESS && next.reason==BuildEngine.Reason.VERIFIED
                        && next.eligible==poll.eligible && next.verify==poll.verify && next.pending==0 && next.unfinished==0
                        && next.sequence==poll.sequence && next.keys==0 && next.owner==BuildEngine.ControlOwner.NONE,
                        "Compact terminal release changed engine/controls/native issuance");
                require(next.client.age()>releasedPoll.client.age() && next.client.worldTime()>releasedPoll.client.worldTime()
                        && next.server.age()>releasedPoll.server.age() && next.server.worldTime()>releasedPoll.server.worldTime()
                        && next.server.serverTick()>releasedPoll.server.serverTick(),"Compact release lacks independent real physics");
                releasedPoll=next;
            }
        }
        if(baseline()) {
        require(released.sample.tick()>current.sample.tick(),"Final current scan did not advance observer time");
        checkCurrentScan(id,released.sample.tick(),plan.targets(),released.sample.observations());
        writeNew(roots,stage.resolve("release-summary.txt"),"first="+current+"\nlast="+released);
        if(baseline()) writeLinesNew(stage.resolve("observer-after-release.txt"),context.computeOnClient(c -> watch.copy()).stream()
                .map(ScenarioObserver.Sample::line)::iterator);
        verifyFinalState(released,stage);
        } else {
            verifyFinalCompact(fullPlayerCheckpoint(CheckpointKind.RELEASE_END));
            scalar("engine-terminal-"+id+".txt","run="+id+"; originalEngineOutcome=SUCCESS; nativeProof=PENDING; reason=VERIFIED; definition="+definition.definitionHash()
                    +"; observedStart="+observedHash+"; originalTerminalEligible="+terminal.eligibleTicks()+"; releaseTicks=20");
            context.runOnClient(c->{ requireClient(c); MapArtClient.stop(); requireReleased(c); });
            sealCompact();
            var proof=TraversalAssertions.verifyNative(sealed,plan,fixture,List.copyOf(removals));
            writeInvocationFile(artifacts.resolve("native-proof-"+id+".txt"),proof);
            writeInvocationFile(artifacts.resolve("result-"+id+".txt"),"run="+id+"; outcome=SUCCESS; original sealed START/END/native profile proof accepted; definition="+definition.definitionHash());
            archiveCompact(stage.resolve("sealed"),sealed);
        }
        previous = new ScenarioResult(id, terminal, definition.definitionHash(), observedHash, List.of(), artifacts);
        if(baseline()) writeNew(roots, artifacts.resolve("result-" + id + ".txt"), previous.toString());
        return previous;
    }

    void assertResult(ScenarioResult result) {
        requireResultIdentity(result,previous,runs,artifacts,admitted);
        require(result != null && result==previous && runs.contains(result.runId()) && result.artifacts().equals(artifacts)
                && admitted != null && result.definitionHash().equals(admitted.definitionHash()), "Foreign result");
        require(result.mismatches().isEmpty() && result.terminal().lifecycle().outcome().orElse(null)==Outcome.SUCCESS,
                "Scenario failed: " + (baseline()?result:result.runId()));
        require(assertedSuccesses.stream().noneMatch(r -> r.runId().equals(result.runId())),"Duplicate counted assertion");
        assertedSuccesses.add(result);
    }

    static void requireResultIdentity(ScenarioResult result,ScenarioResult previous,Set<UUID> runs,Path artifacts,ScenarioDefinition admitted) {
        require(result!=null && result==previous && runs.contains(result.runId()) && result.artifacts().equals(artifacts)
                && admitted!=null && result.definitionHash().equals(admitted.definitionHash()),"Foreign result/run/fixture");
    }

    private static void verifyInvocationOutcomeGuards() {
        var dirty=UUID.randomUUID();
        for (int expected:List.of(1,2)) {
            require(invocationSucceeded(expected,expected+1,expected,expected,true,dirty,dirty),
                    "Valid expected counted-run total rejected: "+expected);
            for (int delta:List.of(-1,1)) {
                require(!invocationSucceeded(expected,expected+1+delta,expected,expected,true,dirty,dirty),
                        "Missing/extra owned start accepted");
                require(!invocationSucceeded(expected,expected+1,expected+delta,expected,true,dirty,dirty),
                        "Missing/extra counted start accepted");
                require(!invocationSucceeded(expected,expected+1,expected,expected+delta,true,dirty,dirty),
                        "Missing/extra counted assertion accepted");
            }
            require(!invocationSucceeded(expected,expected+1,expected,expected,false,dirty,dirty),
                    "Invocation without normal close accepted");
            require(!invocationSucceeded(expected,expected+1,expected,expected,true,null,null)
                            && !invocationSucceeded(expected,expected+1,expected,expected,true,dirty,null)
                            && !invocationSucceeded(expected,expected+1,expected,expected,true,dirty,UUID.randomUUID()),
                    "Invocation without exact dirty-response release accepted");
        }
        for (int expected:List.of(-1,0,3))
            require(!invocationSucceeded(expected,expected+1,expected,expected,true,dirty,dirty),
                    "Out-of-range expected counted-run total accepted: "+expected);
        require(!invocationSucceeded(2,2,1,1,true,dirty,dirty),"Requested two counted runs accepted only one");
    }

    private static boolean validExpectedCount(int expectedCountedRuns) {
        return expectedCountedRuns>=1 && expectedCountedRuns<=2;
    }

    private static boolean invocationSucceeded(int expectedCountedRuns, int ownedStarts, int countedStarts,
                                               int assertedSuccesses, boolean normalClose, UUID dirtyResponseRun,
                                               UUID releasedFailedRun) {
        return validExpectedCount(expectedCountedRuns) && normalClose
                && ownedStarts==expectedCountedRuns+1 && countedStarts==expectedCountedRuns
                && assertedSuccesses==expectedCountedRuns
                && dirtyResponseRun!=null && dirtyResponseRun.equals(releasedFailedRun);
    }

    void invocationOutcome(Throwable failure) {
        Throwable outcome=failure;
        boolean succeeded=baseline() ? invocationSucceeded(expectedCountedRuns,runs.size(),countedStarts,assertedSuccesses.size(),
                normalClose,dirtyResponseRun,releasedFailedRun)
                : TraversalAssertions.invocationSucceeded(expectedCountedRuns,runs.size(),countedStarts,assertedSuccesses.size(),normalClose)
                    && dirtyResponseRun==null && releasedFailedRun==null;
        if (outcome==null && !succeeded)
            outcome=new AssertionError("Invocation PASS lacks expected starts/assertions/normal close");
        var result=new JsonObject(); result.addProperty("status",outcome==null?"PASS":"FAIL");
        result.addProperty("invocation",invocation.toString()); result.addProperty("normalClose",normalClose);
        result.addProperty("fixture",fixture.name()); result.addProperty("expectedCountedRuns",expectedCountedRuns);
        result.addProperty("ownedSave",save==null?null:save.toString());
        result.addProperty("definitionHash",admitted==null?null:admitted.definitionHash());
        result.add("ownedRunIds",new Gson().toJsonTree(runs.stream().map(UUID::toString).sorted().toList()));
        result.addProperty("countedStarts",countedStarts); result.addProperty("assertedSuccessCount",assertedSuccesses.size());
        result.addProperty("expectedFailedRunId",dirtyResponseRun==null?null:dirtyResponseRun.toString());
        result.addProperty("expectedFailedReleaseVerified",dirtyResponseRun!=null && dirtyResponseRun.equals(releasedFailedRun));
        var successes=new JsonArray();
        for (var success:assertedSuccesses) {
            var entry=new JsonObject(); entry.addProperty("runId",success.runId().toString()); entry.addProperty("outcome","SUCCESS");
            entry.addProperty("definitionHash",success.definitionHash()); entry.addProperty("observedStableStartHash",success.observedStableStartHash());
            entry.addProperty("resultFile","result-"+success.runId()+".txt"); successes.add(entry);
        }
        result.add("successes",successes);
        if(baseline()) result.addProperty("failure",outcome==null?null:trace(outcome));
        else result.add("failure",outcome==null?JsonNull.INSTANCE:compactFailureMetadata(outcome));
        try { writeInvocationFile(artifacts.resolve("invocation-result.json"),result.toString()); }
        catch (RuntimeException | Error error) { outcome=retain(outcome,error); }
        try { writeInvocationFile(artifacts.resolve("scenario.log"),(outcome==null?"PASS":"FAIL")+" invocation="+invocation
                +"; normalClose="+normalClose+"; results=invocation-result.json\n"+(outcome==null?"":baseline()?trace(outcome):"original failure/prefix retained; bounded details in invocation-result.json and failure-trace.txt")); }
        catch (RuntimeException | Error error) { outcome=retain(outcome,error); }
        if(!baseline() && outputs!=null) {
            try { archiveLateSidecars(); } catch(RuntimeException | Error error) { outcome=retain(outcome,error); }
        }
        if (failure==null && outcome instanceof Error error) throw error;
        if (failure==null && outcome!=null) throw (RuntimeException)outcome;
    }

    private void writeInvocationFile(Path target,String text) {
        if(baseline()) { writeNew(roots,target,text); return; }
        outputs.seal();
        try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.openPostSealFailure(target.getFileName().toString()),StandardCharsets.UTF_8))) {
            writer.write(text);
        } catch(IOException error) { throw new UncheckedIOException(error); }
        copyCompact(outputs.root().resolve(target.getFileName()),target);
    }

    /** Late verdict/failure sidecars spend the same reserve; the original sealed receipt snapshot never changes. */
    private void archiveLateSidecars() {
        compactFiles=archiveLateSidecars(artifacts,outputs,sealed,capture==null?null:capture.runId(),compactFiles);
    }

    private static Map<String,CompactFile> archiveLateSidecars(Path artifacts,OutputBudget outputs,Sealed sealed,UUID run,
                                                             Map<String,CompactFile> prior) {
        var original=sealed==null?outputs.seal():sealed.outputs();
        var current=currentCompactFiles(artifacts,outputs,sealed,run,prior);
        var metadata=new JsonArray();
        for(var receipt:outputs.receipts()) if(!receipt.receipt().equals(original.manifest())) {
            var row=new JsonObject(); row.addProperty("file",receipt.path().getFileName().toString());
            row.addProperty("bytes",receipt.bytes()); row.addProperty("sha256",receipt.hash());
            row.addProperty("receipt",receipt.receipt().getFileName().toString()); metadata.add(row);
        }
        try(var writer=new BufferedWriter(new OutputStreamWriter(outputs.openPostSealFailure("late-manifest.json"),StandardCharsets.UTF_8))) {
            writer.write("{\"originalSealedManifest\":\""+original.manifest().getFileName()+"\",\"lateOutputs\":"+metadata
                    +",\"closedPredecessors\":"+new Gson().toJson(new TreeMap<>(current).values())+"}\n");
        } catch(IOException error) { throw new UncheckedIOException(error); }
        // Close the final finite summary and its own charged receipt before checking the complete current closure.
        return currentCompactFiles(artifacts,outputs,sealed,run,current);
    }

    private Live pauseShift(UUID id, Live before, Path stage, List<Live> checkpoints) {
        require(substage(before.sample,ForwardTraversal.Substage.SHIFT) && nativeForward(before.sample),"Pause checkpoint is not moving SHIFT");
        context.runOnClient(c -> { requireClient(c); MapArtClient.pause(); requireReleased(c); });
        Live paused=live(); requirePaused(before,paused,id);
        writeNew(roots,stage.resolve("pause-before.txt"),"before="+before+"\nimmediate="+paused);
        Live prior=paused;
        for (int tick=0;tick<20;tick++) {
            context.waitTick();
            Live next=live(); checkpoints.add(next);
            requirePhysics(prior,next); requirePaused(paused,next,id);
            context.runOnClient(ScenarioRunner::requireReleased);
            writeNew(roots,stage.resolve("pause-"+tick+".txt"),next.toString());
            prior=next;
        }
        Live last=live(); requirePaused(paused,last,id);
        context.runOnClient(c -> {
            requireClient(c); requireReleased(c);
            require(MapArtClient.status().engine().lifecycle().phase()==Phase.PAUSED,"Not PAUSED immediately before manual resume");
            MapArtClient.resume();
        });
        Live resumed=live(); requireRun(resumed.sample,id);
        require(retainedProgress(paused.sample.status().engine(),resumed.sample.status().engine())
                && resumed.sample.status().engine().lifecycle().phase()==Phase.RUNNING,"Manual resume renewed traversal or clocks");
        writeNew(roots,stage.resolve("manual-resume.txt"),"lastPaused="+last+"\nresumed="+resumed);
        return resumed;
    }

    private static boolean retainedProgress(BuildEngine.Snapshot before, BuildEngine.Snapshot after) {
        // Observation routing precedes the PAUSED return; pending/unfinished/feedback may legitimately change.
        return before.lifecycle().context().equals(after.lifecycle().context())
                && before.lifecycle().activity().equals(after.lifecycle().activity())
                && before.eligibleTicks()==after.eligibleTicks() && before.verifyTicks()==after.verifyTicks()
                && before.traversal().equals(after.traversal());
    }

    private static void requirePaused(Live before, Live after, UUID id) {
        requireRun(after.sample,id);
        require(after.sample.status().engine().lifecycle().phase()==Phase.PAUSED && !after.sample.paused()
                && after.sample.status().controlOwner()==BuildEngine.ControlOwner.NONE && after.sample.keys().stream().noneMatch(Boolean::booleanValue)
                && retainedProgress(before.sample.status().engine(),after.sample.status().engine())
                && after.nativeSequence==before.nativeSequence,"Paused controls/progress/issuance changed: "+after);
    }

    private static void requireRun(ScenarioObserver.Sample sample, UUID id) {
        require(sample.originalWorld() && sample.pose()!=null && sample.status().failure().isEmpty()
                && sample.status().engine().lifecycle().context().orElseThrow().runId().equals(id),"Foreign run/world or adapter failure: "+sample);
    }

    private static void requirePhysics(Live before, Live after) {
        require(advancing(new Checkpoint(before.client,before.server,before.sample.status()),
                new Checkpoint(after.client,after.server,after.sample.status())),"No independently advancing client/server physics");
    }

    private static boolean substage(ScenarioObserver.Sample sample, ForwardTraversal.Substage stage) {
        return sample.status().engine().traversal().map(t -> t.substage()==stage).orElse(false);
    }

    private static boolean nativeForward(ScenarioObserver.Sample sample) {
        return sample.status().engine().lifecycle().phase()==Phase.RUNNING
                && sample.status().controlOwner()==BuildEngine.ControlOwner.NATIVE && sample.keys().getFirst()
                && !sample.keys().get(1) && !sample.keys().get(4) && !sample.keys().get(6);
    }

    private static double coordinate(Side side, String axis) { return Double.valueOf(side.state.get(axis).getAsString()); }
    private static boolean distance(double first, double last, double reach, int sign) {
        return Double.isFinite(first) && Double.isFinite(last) && Double.isFinite(reach) && reach>=0 && (last-first)*sign>reach;
    }
    private static boolean moved(Live first, Live last, String axis, double reach, int sign) {
        return distance(coordinate(first.client,axis),coordinate(last.client,axis),reach,sign)
                && distance(coordinate(first.server,axis),coordinate(last.server,axis),reach,sign);
    }
    private static boolean atEntry(double x, double z, double wantedX, double wantedZ) {
        return x>=wantedX-.25 && x<=wantedX+.25 && z>=wantedZ-.25 && z<=wantedZ+.25;
    }
    private static boolean atEntry(Live sample, double x, double z) {
        return atEntry(coordinate(sample.client,"x"),coordinate(sample.client,"z"),x,z)
                && atEntry(coordinate(sample.server,"x"),coordinate(sample.server,"z"),x,z);
    }

    private static void ordered(int... indices) {
        int prior=-1;
        for (int index:indices) { require(index>prior,"Missing/unordered native traversal witnesses: "+Arrays.toString(indices)); prior=index; }
    }

    private void verifyTraversal(UUID id, List<Live> points, List<ScenarioObserver.Sample> samples,
                                 double clientReach, double serverReach, Live resumed, Path stage) {
        int east=-1,eastTravel=-1,endpoint=-1,shift=-1,south=-1,westEntry=-1,west=-1,westTravel=-1;
        for (int k=0;k<points.size();k++) {
            var point=points.get(k); var sample=point.sample; requireRun(sample,id);
            var traversal=sample.status().engine().traversal().orElse(null);
            if (traversal==null) continue;
            boolean walking=traversal.substage()==ForwardTraversal.Substage.LANE && traversal.entry().stage()==Stage.WALK
                    && sample.status().engine().lifecycle().activity().orElse(null)==Activity.BUILD && nativeForward(sample);
            if (walking && traversal.laneIndex()==0 && traversal.entry().lane().direction().name().equals("EAST")) {
                if (east<0) east=k;
                if (beyondReach(points.get(east),point,clientReach,serverReach,1)) eastTravel=k;
            }
            if (eastTravel>=0 && endpoint<0 && traversal.substage()==ForwardTraversal.Substage.ENDPOINT && atEntry(point,11.5,2.5)) endpoint=k;
            if (endpoint>=0 && traversal.substage()==ForwardTraversal.Substage.SHIFT
                    && traversal.entry().lane().direction().name().equals("SOUTH")) {
                if (shift<0) shift=k;
                if (nativeForward(sample) && Math.abs(MathHelper.wrapDegrees(sample.pose().yaw()))<=1.5
                        && Math.abs(MathHelper.wrapDegrees(sample.pose().headYaw()))<=1.5 && Math.abs(MathHelper.wrapDegrees(sample.pose().bodyYaw()))<=1.5
                        && moved(points.get(shift),point,"z",.25,1)) south=k;
            }
            if (south>=0 && westEntry<0 && traversal.substage()==ForwardTraversal.Substage.NEXT_ENTRY && atEntry(point,11.5,7.5)) westEntry=k;
            if (westEntry>=0 && walking && traversal.laneIndex()==1 && traversal.entry().lane().direction().name().equals("WEST")) {
                if (west<0) west=k;
                if (beyondReach(points.get(west),point,clientReach,serverReach,-1)) westTravel=k;
            }
        }
        ordered(east,eastTravel,endpoint,shift,south,westEntry,west,westTravel);
        var inputs=new ArrayList<WestInput>();
        ScenarioObserver.Sample start=null;
        long previousEnd=-1;
        for (var sample:samples) {
            requireRun(sample,id);
            if (sample.phase().equals("START")) { start=sample; continue; }
            if (!sample.phase().equals("END")) continue;
            require(start!=null && start.tick()==sample.tick() && sample.tick()>previousEnd,"Missing/duplicate callback START/END pair");
            previousEnd=sample.tick();
            var before=start.status().engine(); var after=sample.status().engine();
            if (before.lifecycle().phase()==Phase.RUNNING) {
                require(after.eligibleTicks()==before.eligibleTicks()+1,"Callback did not advance exactly one eligible decision");
                var a=before.traversal().orElse(null); var b=after.traversal().orElse(null);
                if (a!=null && b!=null && a.substage()!=ForwardTraversal.Substage.LANE) {
                    boolean enteredLane=b.substage()==ForwardTraversal.Substage.LANE;
                    require(a.transitionTicks()<120 && (enteredLane ? a.substage()==ForwardTraversal.Substage.NEXT_ENTRY && b.transitionTicks()==0
                                    : b.transitionTicks()==a.transitionTicks()+1 && b.transitionTicks()<=120),
                            "Shared transition clock renewed/exceeded120: before="+a+"; after="+b);
                }
                if (a!=null && b!=null && a.substage()==ForwardTraversal.Substage.NEXT_ENTRY && a.entry().stage()==Stage.ENTER_YAW) {
                    // These outputs certify the actual capture input checks in MovementPolicy.enter; raw START/END yaw is not input yaw.
                    inputs.add(new WestInput(sample.tick(),after.eligibleTicks(),a.entry().consecutiveYawAlignedTicks(),
                            b.entry().consecutiveYawAlignedTicks(),b.substage()==ForwardTraversal.Substage.LANE && b.entry().stage()==Stage.WALK,
                            atEntry(sample.pose().x(),sample.pose().z(),11.5,7.5),a.entry().lane().direction().name().equals("WEST")));
                }
            } else if (before.lifecycle().phase()==Phase.PAUSED) {
                require(retainedProgress(before,after),"Paused callback advanced traversal/clocks");
            }
        }
        WestInput first=null,second=null;
        for (int k=1;k<inputs.size();k++) if (westInputs(inputs.get(k-1),inputs.get(k))) { first=inputs.get(k-1); second=inputs.get(k); break; }
        require(first!=null && second!=null && points.get(westEntry).sample.tick()<=first.frame
                        && second.frame<=points.get(west).sample.tick(),"Missing ordered full-entry/two distinct controller-certified WEST input callbacks: "+inputs);
        if (resumed!=null) require(points.stream().anyMatch(p -> p.sample.tick()>resumed.sample.tick()
                && substage(p.sample,ForwardTraversal.Substage.SHIFT) && nativeForward(p.sample) && moved(resumed,p,"z",0,1)),
                "No actual SOUTH progress after manual resume");
        writeNew(roots,stage.resolve("traversal-proof.txt"),"orderedCheckpointIndices="+List.of(east,eastTravel,endpoint,shift,south,westEntry,west,westTravel)
                +"; clientReach="+clientReach+"; serverReach="+serverReach+"; WEST input decisions="+first+", "+second
                +"; inputProof=controller ENTER_YAW actual capture condition <=1.5 degrees on two adjacent eligible callbacks; raw START/END rotations remain phase-labelled, not capture-boundary readings\n"
                +"eastStart="+points.get(east)+"\neastEnd="+points.get(eastTravel)+"\nendpoint="+points.get(endpoint)
                +"\nshiftStart="+points.get(shift)+"\nsouth="+points.get(south)+"\nwestEntry="+points.get(westEntry)
                +"\nwestStart="+points.get(west)+"\nwestEnd="+points.get(westTravel));
    }

    private static boolean beyondReach(Live first, Live last, double clientReach, double serverReach, int sign) {
        return distance(coordinate(first.client,"x"),coordinate(last.client,"x"),clientReach,sign)
                && distance(coordinate(first.server,"x"),coordinate(last.server,"x"),serverReach,sign);
    }
    private record WestInput(long frame,long eligible,int priorAligned,int aligned,boolean walk,boolean fullPosition,boolean west) { }
    private static boolean westInputs(WestInput first, WestInput second) {
        return first.frame+1==second.frame && first.eligible+1==second.eligible && first.priorAligned==0 && first.aligned==1
                && !first.walk && second.priorAligned==1 && second.walk && first.fullPosition && second.fullPosition && first.west && second.west;
    }

    private void verifyOrdinarySupport(UUID id,List<ScenarioObserver.Sample> samples,Path stage) {
        writeNew(roots,stage.resolve("support-footprint-proof.txt"),ordinarySupport(id,plan,origin,samples));
    }

    private static String ordinarySupport(UUID id,BuildPlan plan,GridPos origin,List<ScenarioObserver.Sample> samples) {
        var proof=new StringBuilder("Actual END client evidence; observer frames are not adapter capture ticks.\n");
        int callbacks=0,targets=0; boolean motion=false;
        ScenarioObserver.Sample prior=null;
        for(var sample:samples) {
            if(!sample.phase().equals("END")) continue;
            var t=sample.status().engine().traversal().orElse(null);
            boolean moving=t!=null && t.substage()==ForwardTraversal.Substage.SHIFT && t.entry().stage()==Stage.WALK
                    && t.entry().lane().direction().name().equals("SOUTH") && sample.status().engine().lifecycle().phase()==Phase.RUNNING
                    && sample.keys().size()==9 && sample.keys().subList(0,4).stream().anyMatch(Boolean::booleanValue);
            if(moving) {
                requireRun(sample,id);
                require(sample.status().controlOwner()==BuildEngine.ControlOwner.NATIVE && !sample.paused()
                        && sample.status().engine().lifecycle().activity().orElse(null)==Activity.SHIFT
                        && Double.isFinite(sample.pose().x()) && Double.isFinite(sample.pose().z()),"Moving support lacks native real SHIFT pose");
                var required=requiredSouthTargets(plan,origin,sample);
                require(!required.isEmpty(),"Moving support has empty on-plan footprint");
                var observations=new ArrayList<PlacementObservation>();
                for(var target:required) {
                    var value=currentObservation(sample,id,target.id());
                    require(value!=null && value.evidence()==Evidence.OBSERVED && value.observedState().equals(Optional.of(target.block())),
                            "Moving required support missing/duplicate/stale/foreign/predicted/mismatched: "+target+"; sample="+sample);
                    observations.add(value); targets++;
                }
                callbacks++;
                if(prior!=null && prior.pose()!=null && prior.originalWorld() && prior.worldIdentity()==sample.worldIdentity()
                        && prior.status().engine().lifecycle().context().map(c -> c.runId().equals(id)).orElse(false)
                        && sample.tick()>prior.tick() && sample.pose().age()>prior.pose().age() && sample.pose().z()>prior.pose().z()) motion=true;
                proof.append("frame=").append(sample.tick()).append("; eligible=").append(sample.status().engine().eligibleTicks())
                        .append("; pose=").append(sample.pose()).append("; keys=").append(sample.keys())
                        .append("; footprint=").append(required).append("; observations=").append(observations).append('\n');
            }
            prior=sample;
        }
        require(callbacks>0 && targets>0 && motion,"Ordinary support lacks actual moving END callbacks/required targets/SOUTH pose progress");
        return proof.toString();
    }

    private static List<BuildPlan.Target> requiredSouthTargets(BuildPlan plan,GridPos origin,ScenarioObserver.Sample sample) {
        var lane=sample.status().engine().traversal().orElseThrow().entry().lane();
        int nextZ=(int)Math.floor(sample.pose().z()-origin.z())+1;
        var targets=new ArrayList<BuildPlan.Target>();
        for(int x:southFootprint(sample.pose().x()-origin.x(),lane.start().x()).stream().sorted().toList()) {
            var target=lane.expectedFloor().targetsByPosition().get(new GridPos(x,lane.start().y(),nextZ));
            if(target!=null) {
                require(plan.targets().contains(target),"Traversal expected floor differs from imported plan");
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    private record SupportWitness(String proof) { }

    private void verifySupport(UUID id, List<ScenarioObserver.Sample> samples, Path stage) {
        var witness=context.computeOnClient(c -> findSupportWitness(id,plan,origin,supportArm,PredictionFaults.resetSnapshot().support(),samples));
        require(witness.isPresent(),"Missing actual SHIFT required-footprint hold/native action/PREDICTED/later OBSERVED/SOUTH chain");
        writeNew(roots,stage.resolve("support-proof.txt"),witness.orElseThrow().proof());
    }

    private static Optional<SupportWitness> findSupportWitness(UUID id,BuildPlan plan,GridPos origin,
            PredictionFaults.SupportArmIdentity issued,Optional<PredictionFaults.SupportAvailabilitySnapshot> authority,
            List<ScenarioObserver.Sample> samples) {
        var released=authority.orElse(null);
        if(issued==null || released==null || released.identity()!=issued || released.state()!=PredictionFaults.SupportState.RELEASED
                || released.heldEnd().isEmpty() || released.firstCause().isPresent() || released.ownsFailure()
                || released.firstReady().isEmpty() || released.latest().isEmpty()
                || !issued.context().runId().equals(id) || !issued.context().planFingerprint().equals(plan.fingerprint())
                || !issued.context().origin().equals(origin)) return Optional.empty();
        var capture=released.latest().orElseThrow();
        if(!capture.context().equals(issued.context()) || capture.targetId()!=issued.targetId()
                || capture.original()!=BuildEngine.PlacementAvailability.READY
                || capture.returned()!=BuildEngine.PlacementAvailability.NO_VALID_HIT) return Optional.empty();
        var heldLine=released.heldEnd().orElseThrow();
        // The consumed release names one exact retained END; an earlier qualifying hold is not its authority.
        if(samples.stream().filter(s -> s.phase().equals("END") && s.line().equals(heldLine)).count()!=1) return Optional.empty();
        var retained=samples.stream().filter(s -> (s.phase().equals("START") || s.phase().equals("END")) && s.originalWorld()
                && s.worldIdentity()==issued.world() && s.status().engine().lifecycle().context().filter(issued.context()::equals).isPresent()).toList();
        for (int h=0;h<retained.size();h++) {
            var held=retained.get(h); var t=held.status().engine().traversal().orElse(null);
            if (!held.line().equals(heldLine) || !held.phase().equals("END") || held.pose()==null || held.paused()
                    || held.status().engine().lifecycle().phase()!=Phase.RUNNING || t==null || t.substage()!=ForwardTraversal.Substage.SHIFT
                    || !t.entry().lane().direction().name().equals("SOUTH")
                    || t.entry().stage()!=Stage.WALK || t.entry().supportHoldTicks()<=0 || held.keys().size()!=9 || held.keys().stream().anyMatch(Boolean::booleanValue)
                    || capture.eligibleBefore()+1!=held.status().engine().eligibleTicks() || !capture.pose().equals(PredictionFaults.SupportPose.of(held.pose()))
                    || held.status().controlOwner()!=BuildEngine.ControlOwner.NATIVE || Math.abs(MathHelper.wrapDegrees(held.pose().yaw()))>1.5) continue;
            // SOUTH WALK requires next-Z support at center X, actual X and the same .4/.6 neighboring footprint as the policy.
            int nextZ=(int)Math.floor(held.pose().z()-origin.z())+1;
            var requiredX=southFootprint(held.pose().x()-origin.x(),t.entry().lane().start().x());
            for (var target:requiredSouthTargets(plan,origin,held)) {
                if (!target.relativePos().equals(new GridPos(11,0,5)) || target.id()!=issued.targetId() || !target.block().equals(issued.expected())
                        || !issued.target().equals(new GridPos(origin.x()+target.relativePos().x(),origin.y()+target.relativePos().y(),origin.z()+target.relativePos().z()))
                        || held.status().engine().pendingTargetIds().contains(target.id())) continue;
                var original=retained.subList(0,h).stream().filter(s -> s.tick()<held.tick()).map(s -> currentObservation(s,id,target.id()))
                        .filter(o -> o!=null && o.evidence()==Evidence.OBSERVED
                                && o.observedState().equals(Optional.of(new BuildPlan.BlockSpec("minecraft:air",Map.of())))).findFirst().orElse(null);
                if (original==null) continue;
                var atHold=currentObservation(held,id,target.id());
                if (atHold==null || atHold.evidence()!=Evidence.OBSERVED
                        || !atHold.observedState().equals(Optional.of(new BuildPlan.BlockSpec("minecraft:air",Map.of())))) continue;
                var predicted=retained.subList(h,retained.size()).stream().filter(s -> {
                    var value=currentObservation(s,id,target.id());
                    return s.phase().equals("END") && s.tick()>held.tick() && substage(s,ForwardTraversal.Substage.SHIFT)
                            && value!=null && value.evidence()==Evidence.PREDICTED
                            && value.observedState().orElseThrow().equals(target.block()) && s.status().engine().pendingTargetIds().contains(target.id());
                }).findFirst().orElse(null);
                if (predicted==null) continue;
                var observed=retained.stream().filter(s -> {
                    var value=currentObservation(s,id,target.id());
                    return s.phase().equals("END") && s.tick()>predicted.tick() && value!=null && value.evidence()==Evidence.OBSERVED
                            && value.observedState().orElseThrow().equals(target.block());
                }).findFirst().orElse(null);
                if (observed==null) continue;
                var effects=retained.stream().filter(s -> s.tick()>=held.tick() && s.tick()<=observed.tick())
                        .flatMap(s -> s.status().engine().effectResults().stream()).filter(e -> e.runId().equals(id)
                                && e.targetId()==target.id() && e.status()==BuildEngine.EffectStatus.ACCEPTED).distinct().toList();
                if (effects.isEmpty()) continue;
                var motion=retained.stream().filter(s -> s.phase().equals("END") && s.originalWorld() && s.pose()!=null
                        && s.status().engine().lifecycle().context().map(c -> c.runId().equals(id)).orElse(false)
                        && s.tick()>observed.tick() && substage(s,ForwardTraversal.Substage.SHIFT)
                        && nativeForward(s) && s.pose().z()>held.pose().z()).findFirst().orElse(null);
                if (motion==null) continue;
                return Optional.of(new SupportWitness("requiredFootprintX="+requiredX+"; nextZ="+nextZ+"; target="+target
                        +"; acceptedNativeEffects="+effects+"; observationClock=observer START tick; effect.tick remains adapter domain, not equated\n"
                        +"initialUnbuilt="+original+"\nheld="+held.line()+"\npredictedNativePending="+predicted.line()
                        +"\nlaterObserved="+observed.line()+"\nlaterSouthMotion="+motion.line()));
            }
        }
        return Optional.empty();
    }

    private static PlacementObservation currentObservation(ScenarioObserver.Sample sample,UUID id,int target) {
        var values=sample.observations().stream().filter(o -> o.targetId()==target).toList();
        if(values.size()!=1) return null;
        var value=values.getFirst();
        return sample.originalWorld() && sample.status().engine().lifecycle().context().map(c -> c.runId().equals(id)).orElse(false)
                && value.runId().equals(id) && value.tick()==sample.tick() ? value : null;
    }

    static Set<Integer> southFootprint(double x,int center) {
        int actual=(int)Math.floor(x); var result=new HashSet<Integer>(Set.of(center)); result.add(actual);
        if (x<actual+.4) result.add(actual-1);
        if (x>actual+.6) result.add(actual+1);
        return result;
    }

    private void checkCurrentScan(UUID id,long observerTick,List<BuildPlan.Target> targets,List<PlacementObservation> scan) {
        if(baseline()) requireCurrentScan(id,observerTick,targets,scan);
        else TraversalAssertions.requireCurrentScan(id,observerTick,targets,scan);
    }

    private static void requireCurrentScan(UUID id,long observerTick,List<BuildPlan.Target> targets,List<PlacementObservation> scan) {
        var expected=new HashMap<Integer,BuildPlan.BlockSpec>();
        for (var target:targets) require(expected.put(target.id(),target.block())==null,"Duplicate imported target ID");
        require(targets.size()==120 && scan.size()==120,"Final scan/import must contain exactly120 targets");
        var seen=new HashSet<Integer>();
        for (var value:scan) require(value.runId().equals(id) && value.tick()==observerTick && seen.add(value.targetId())
                && value.evidence()==Evidence.OBSERVED && Objects.equals(expected.get(value.targetId()),value.observedState().orElse(null)),
                "Final scan stale/duplicate/foreign/predicted/mismatch: "+value);
        require(seen.equals(expected.keySet()),"Final scan target ID set differs");
    }

    private void verifyFinalState(Live finalState,Path stage) {
        writeNew(roots,stage.resolve("independent-final.txt"),finalState.toString());
        require(finalState.pending.isEmpty(),"Final owned native predictions remain");
        // No reverse profile may pass until Task13 supplies original causally validated removal receipts.
        TraversalAssertions.requireRemovalReceipts(admitted,List.of());
        int expectedUsable=admitted.finalUsable();
        for (var side:List.of(finalState.client,finalState.server)) {
            var baseline=side==finalState.client?stable.client:stable.server;
            if(baseline()) {
                requireStock(side.state.getAsJsonArray("inventory"),baseline.state.getAsJsonArray("inventory"));
                require(usable(side)==9,"Baseline final usable stock must remain literal9");
            } else requireStock(side.state.getAsJsonArray("inventory"),baseline.state.getAsJsonArray("inventory"),expectedUsable);
            require(usable(side)==expectedUsable && side.state.get("mode").getAsString().equals("SURVIVAL")
                    && side.state.get("abilities").equals(baseline.state.get("abilities"))
                    && side.state.get("cursorAndCrafting").equals(baseline.state.get("cursorAndCrafting"))
                    && b(side.state,"playerHandler") && side.state.getAsJsonArray("boundedEntities").isEmpty(),
                    "Final inventory/player/entities differ: "+side);
        }
        require(finalState.client.state.get("inventory").equals(finalState.server.state.get("inventory"))
                && finalState.client.state.get("selectedSlot").equals(finalState.server.state.get("selectedSlot")),
                "Final client/server stack distribution or selected slot is not synchronized");
        var mismatches=world.getServer().computeOnServer(s -> {
            requireServer(s); var errors=new ArrayList<String>();
            var targets=TraversalAssertions.expectedWorldTargets(admitted,plan.targets());
            if(baseline()) require(targets.size()==120,"Independent server target coordinates are not120 unique positions");
            for(var cell:admitted.worldCells().entrySet()) {
                var p=cell.getKey(); var at=new BlockPos(p.x(),p.y(),p.z());
                var expected=targets.getOrDefault(p,cell.getValue());
                if(baseline() && "1".equals(System.getenv("MAPART_TWO_LANE_WRONG_EXPECTED_BLOCK")) && at.equals(new BlockPos(0,65,0)))
                    expected=WorldEvidenceReader.blockSpec(Blocks.GOLD_BLOCK.getDefaultState());
                var actual=serverWorld.getBlockState(at);
                if (!WorldEvidenceReader.blockSpec(actual).equals(expected) || serverWorld.getBlockEntity(at)!=null
                        || serverWorld.getChunkManager().getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16),ChunkStatus.FULL,false)==null)
                    errors.add("Final server mismatch: "+at.toShortString()+"; expected="+expected+"; actual="+actual
                            +"; blockEntity="+serverWorld.getBlockEntity(at));
            }
            if (!serverWorld.getOtherEntities(serverPlayer,regionBox(),e -> true).isEmpty()) errors.add("Unauthorized bounded entities");
            return errors;
        });
        writeNew(roots,stage.resolve("independent-mismatches.txt"),mismatches.toString());
        require(mismatches.isEmpty(),"Independent final world assertion: "+mismatches);
        require(finalState.client.state.get("blocks").equals(finalState.server.state.get("blocks")),"Final client/server owned block states differ");
    }

    private static void requireStock(JsonArray current,JsonArray baseline) { requireStock(current,baseline,9); }

    static void requireStock(JsonArray current,JsonArray baseline,int expectedUsable) {
        require(current!=null && baseline!=null && current.size()==43 && baseline.size()==43,"Incomplete43-slot inventory/components");
        int total=0;
        for (int slot=0;slot<43;slot++) {
            var value=current.get(slot);
            if (slot!=1 && slot!=2) require(value.equals(baseline.get(slot)),"Final reserved/other inventory slot changed: "+slot);
            else if (!value.equals(baseline.get(3))) {
                int count=encodedStackCount(value);
                var kind=value.getAsJsonObject().deepCopy(); var expected=baseline.get(1).getAsJsonObject().deepCopy();
                kind.remove("count"); expected.remove("count");
                require(count>0 && count<=64 && kind.equals(expected),"Unauthorized item/components in usable slot: "+slot);
                total+=count;
            }
        }
        require(total==expectedUsable,"Final usable slots1+2 must total"+expectedUsable+": "+current);
        if(expectedUsable==9) require(total==9,"Final usable slots1+2 must total9: "+current);
    }

    private static void requireAcceptedFeedback(UUID id,List<BuildPlan.Target> targets,List<BuildEngine.PlacementEffectResult> feedback) {
        // Pause can discard still-private prior-tick feedback; physical placement count comes from world/stock/OBSERVED checks.
        var actions=new HashSet<Long>();
        for (var effect:feedback) require(effect.runId().equals(id) && effect.status()==BuildEngine.EffectStatus.ACCEPTED
                && targets.stream().anyMatch(t -> t.id()==effect.targetId())
                && actions.add(effect.actionId()),
                "Invalid/reused accepted feedback identity: "+effect);
    }

    private static void verifySupportGuards() {
        require(SupportCase.parse(null)==SupportCase.ORDINARY && SupportCase.parse("ordinary")==SupportCase.ORDINARY
                && SupportCase.parse("controlled")==SupportCase.CONTROLLED,"Closed support selector routing");
        for(String invalid:List.of(""," ","ordinary "," controlled","ORDINARY","CONTROLLED","default","true"))
            rejected("explicit invalid support selector",() -> SupportCase.parse(invalid));
        var stone=new BuildPlan.BlockSpec("minecraft:stone",Map.of()); var air=new BuildPlan.BlockSpec("minecraft:air",Map.of());
        var targets=new ArrayList<BuildPlan.Target>();
        for(int k=0;k<120;k++) targets.add(new BuildPlan.Target(k,new GridPos(k%12,0,k/12),stone));
        var plan=new BuildPlan("support-selfcheck",new BuildPlan.GridSize(12,1,10),targets);
        var run=new Context(UUID.randomUUID(),"synthetic","synthetic","minecraft:overworld",plan.fingerprint(),new GridPos(0,65,0),"synthetic");
        var off=Collections.nCopies(9,false); var on=new ArrayList<>(off); on.set(0,true);
        int chosen=SingleLaneFixture.targetId(plan,new GridPos(11,0,5));
        var before=supportSample(plan,run,9,11.3,4.1,0,on,supportScan(plan,run.runId(),9),Set.of(),List.of(),"END");
        var moving=supportSample(plan,run,10,11.3,4.3,0,on,supportScan(plan,run.runId(),10),Set.of(),List.of(),"END");
        ordinarySupport(run.runId(),plan,run.origin(),List.of(before,moving)); // Ahead-built, no hold required.
        rejected("ordinary zero motion",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(moving,moving)));
        var stationary=supportSample(plan,run,9,11.3,4.3,0,on,supportScan(plan,run.runId(),9),Set.of(),List.of(),"END");
        rejected("ordinary advancing frames with zero displacement",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(stationary,moving)));
        for(String phase:List.of("START","TEST","SHIFT")) {
            var staticLabel=supportSample(plan,run,10,11.3,4.3,0,on,supportScan(plan,run.runId(),10),Set.of(),List.of(),phase);
            rejected("ordinary non-END stage label",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(staticLabel)));
        }
        var offPlan=supportSample(plan,run,10,11.3,12.3,0,on,supportScan(plan,run.runId(),10),Set.of(),List.of(),"END");
        rejected("ordinary empty footprint",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(before,offPlan)));
        int neighbor=SingleLaneFixture.targetId(plan,new GridPos(10,0,5));
        var missingNeighbor=supportScan(plan,run.runId(),10).stream().filter(o -> o.targetId()!=neighbor).toList();
        var missing=supportSample(plan,run,10,11.3,4.3,0,on,missingNeighbor,Set.of(),List.of(),"END");
        rejected("ordinary .4 footprint neighbor",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(before,missing)));
        var right=supportSample(plan,run,10,10.7,4.3,0,on,supportScan(plan,run.runId(),10).stream().filter(o -> o.targetId()!=chosen).toList(),Set.of(),List.of(),"END");
        rejected("ordinary .6 footprint neighbor",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(before,right)));
        var cases=new ArrayList<List<PlacementObservation>>();
        var duplicate=new ArrayList<>(supportScan(plan,run.runId(),10)); duplicate.add(duplicate.get(chosen)); cases.add(duplicate);
        cases.add(supportScan(plan,UUID.randomUUID(),10)); cases.add(supportScan(plan,run.runId(),9));
        cases.add(supportScan(plan,run.runId(),10).stream().filter(o -> o.targetId()!=chosen).toList());
        for(var evidence:Evidence.values()) {
            var scan=new ArrayList<>(supportScan(plan,run.runId(),10));
            scan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),10,evidence,
                    evidence==Evidence.UNKNOWN?Optional.empty():Optional.of(evidence==Evidence.OBSERVED
                        ? new BuildPlan.BlockSpec("minecraft:stone",Map.of("unexpected","state")):stone)));
            cases.add(scan);
        }
        for(var scan:cases) {
            var invalid=supportSample(plan,run,10,11.3,4.3,0,on,scan,Set.of(),List.of(),"END");
            rejected("ordinary duplicate/foreign/stale/unknown/predicted/full-state/missing",() -> ordinarySupport(run.runId(),plan,run.origin(),List.of(before,invalid)));
        }
        var directScan=new ArrayList<>(supportScan(plan,run.runId(),0));
        directScan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),0,Evidence.OBSERVED,Optional.of(air)));
        var directInitial=supportSample(plan,run,0,11.5,2.5,0,off,directScan,Set.of(),List.of(),"TEST");
        var initialScan=new ArrayList<>(supportScan(plan,run.runId(),1));
        initialScan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),1,Evidence.OBSERVED,Optional.of(air)));
        var initial=supportSample(plan,run,1,11.5,2.5,0,off,initialScan,Set.of(),List.of(),"START");
        var holds=new ArrayList<ScenarioObserver.Sample>();
        for(int frame=122;frame<=125;frame++) {
            var scan=new ArrayList<>(supportScan(plan,run.runId(),frame));
            scan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),frame,Evidence.OBSERVED,Optional.of(air)));
            holds.add(supportSample(plan,run,frame,11.5,4.1+(frame-122)*.08,frame-121,off,scan,Set.of(),List.of(),"END"));
        }
        var held=holds.getLast();
        var predictedScan=new ArrayList<>(supportScan(plan,run.runId(),126));
        predictedScan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),126,Evidence.PREDICTED,Optional.of(stone)));
        var predicted=supportSample(plan,run,126,11.5,4.34,5,off,predictedScan,Set.of(chosen),List.of(),"END");
        var accepted=new BuildEngine.PlacementEffectResult(run.runId(),87,chosen,1341,BuildEngine.EffectStatus.ACCEPTED);
        var actionScan=new ArrayList<>(supportScan(plan,run.runId(),127));
        actionScan.set(chosen,new PlacementObservation(run.runId(),chosen,OptionalLong.empty(),127,Evidence.PREDICTED,Optional.of(stone)));
        var action=supportSample(plan,run,127,11.5,4.34,6,off,actionScan,Set.of(chosen),List.of(accepted),"START");
        var observed=supportSample(plan,run,128,11.5,4.34,0,on,supportScan(plan,run.runId(),128),Set.of(),List.of(),"END");
        var south=supportSample(plan,run,129,11.5,4.48,0,on,supportScan(plan,run.runId(),129),Set.of(),List.of(),"END");
        var chain=new ArrayList<>(List.of(initial)); chain.addAll(holds); chain.addAll(List.of(predicted,action,observed,south));
        var identity=new PredictionFaults.SupportArmIdentity(UUID.randomUUID(),run,"synthetic-save",1,held.worldIdentity(),2,3,4,
                chosen,new GridPos(11,65,5),stone,0,0);
        var first=new PredictionFaults.SupportCapture(run,chosen,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,
                PredictionFaults.SupportPose.of(holds.getFirst().pose()),121);
        var latest=new PredictionFaults.SupportCapture(run,chosen,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,
                PredictionFaults.SupportPose.of(held.pose()),124);
        var released=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                125,Optional.of(first),Optional.of(latest),Optional.of(held.line()));
        var authority=Optional.of(released);
        require(currentObservation(directInitial,run.runId(),chosen).observedState().equals(Optional.of(air))
                && !chain.contains(directInitial),"Direct TEST readiness fixture must remain separate from retained native rows");
        var proof=findSupportWitness(run.runId(),plan,run.origin(),identity,authority,chain).orElseThrow().proof().lines().toList();
        require(proof.size()==6 && proof.get(0).contains("acceptedNativeEffects="+List.of(accepted))
                && proof.get(1).equals("initialUnbuilt="+initialScan.get(chosen)) && proof.get(2).equals("held="+held.line())
                && proof.get(3).equals("predictedNativePending="+predicted.line()) && proof.get(4).equals("laterObserved="+observed.line())
                && proof.get(5).equals("laterSouthMotion="+south.line()),"Proof must bind released125 and retained START1, not earlier holds or TEST0");
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,authority,List.of(before,moving)).isEmpty(),"Controlled substituted ordinary ahead-built proof");
        for(var remove:List.of(held,predicted,action,observed,south)) {
            var incomplete=new ArrayList<>(chain); incomplete.remove(remove);
            require(findSupportWitness(run.runId(),plan,run.origin(),identity,authority,incomplete).isEmpty(),"Controlled missing-chain element accepted: "+remove.tick());
        }
        var onlyDirect=List.of(directInitial,held,predicted,action,observed,south);
        var staleInitial=supportSample(plan,run,1,11.5,2.5,0,off,directScan,Set.of(),List.of(),"START");
        var duplicateHeld=new ArrayList<>(chain); duplicateHeld.add(held);
        var substituted=new ArrayList<>(chain);
        substituted.set(substituted.indexOf(held),supportSample(plan,run,125,11.5,4.35,4,off,held.observations(),Set.of(),List.of(),"END"));
        for(var invalid:List.of(onlyDirect,List.of(staleInitial,held,predicted,action,observed,south),duplicateHeld,substituted))
            require(findSupportWitness(run.runId(),plan,run.origin(),identity,authority,invalid).isEmpty(),"Controlled nonretained/stale initial/duplicate/substituted release accepted");
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.empty(),chain).isEmpty(),"Missing release authority accepted");
        var reconstructed=new PredictionFaults.SupportArmIdentity(identity.arm(),run,identity.save(),identity.server(),identity.world(),identity.player(),
                identity.handler(),identity.connection(),identity.targetId(),identity.target(),identity.expected(),identity.armedAge(),identity.armedEligible());
        require(reconstructed.equals(identity) && reconstructed!=identity
                && findSupportWitness(run.runId(),plan,run.origin(),reconstructed,authority,chain).isEmpty(),"Reconstructed issued authority accepted");
        for(var state:PredictionFaults.SupportState.values()) if(state!=PredictionFaults.SupportState.RELEASED) {
            var invalid=new PredictionFaults.SupportAvailabilitySnapshot(identity,state,false,Optional.empty(),false,125,
                    Optional.of(first),Optional.of(latest),Optional.of(held.line()));
            require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(invalid),chain).isEmpty(),"Nonreleased authority accepted: "+state);
        }
        var noAnchor=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                125,Optional.of(first),Optional.of(latest),Optional.empty());
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(noAnchor),chain).isEmpty(),"Missing release heldEnd accepted");
        var substitutedAnchor=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                125,Optional.of(first),Optional.of(latest),Optional.of(holds.getFirst().line()));
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(substitutedAnchor),chain).isEmpty(),"Earlier retained hold substituted for actual released capture");
        var staleCapture=new PredictionFaults.SupportCapture(run,chosen,BuildEngine.PlacementAvailability.READY,BuildEngine.PlacementAvailability.NO_VALID_HIT,
                latest.pose(),latest.eligibleBefore()-1);
        var staleAuthority=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                125,Optional.of(first),Optional.of(staleCapture),Optional.of(held.line()));
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(staleAuthority),chain).isEmpty(),"Stale release capture accepted");
        var contexts=List.of(new Context(UUID.randomUUID(),run.connectionId(),run.worldId(),run.dimension(),run.planFingerprint(),run.origin(),run.settingsFingerprint()),
                new Context(run.runId(),"foreign",run.worldId(),run.dimension(),run.planFingerprint(),run.origin(),run.settingsFingerprint()),
                new Context(run.runId(),run.connectionId(),"stale",run.dimension(),run.planFingerprint(),run.origin(),run.settingsFingerprint()),
                new Context(run.runId(),run.connectionId(),run.worldId(),"foreign",run.planFingerprint(),run.origin(),run.settingsFingerprint()),
                new Context(run.runId(),run.connectionId(),run.worldId(),run.dimension(),"foreign",run.origin(),run.settingsFingerprint()),
                new Context(run.runId(),run.connectionId(),run.worldId(),run.dimension(),run.planFingerprint(),new GridPos(1,65,0),run.settingsFingerprint()),
                new Context(run.runId(),run.connectionId(),run.worldId(),run.dimension(),run.planFingerprint(),run.origin(),"foreign"));
        var identities=new ArrayList<PredictionFaults.SupportArmIdentity>();
        for(var foreign:contexts) identities.add(new PredictionFaults.SupportArmIdentity(identity.arm(),foreign,identity.save(),1,held.worldIdentity(),2,3,4,
                chosen,identity.target(),stone,0,0));
        identities.add(new PredictionFaults.SupportArmIdentity(identity.arm(),run,identity.save(),1,72,2,3,4,chosen,identity.target(),stone,0,0));
        identities.add(new PredictionFaults.SupportArmIdentity(identity.arm(),run,identity.save(),1,held.worldIdentity(),2,3,4,chosen+1,identity.target(),stone,0,0));
        identities.add(new PredictionFaults.SupportArmIdentity(identity.arm(),run,identity.save(),1,held.worldIdentity(),2,3,4,chosen,new GridPos(10,65,5),stone,0,0));
        identities.add(new PredictionFaults.SupportArmIdentity(identity.arm(),run,identity.save(),1,held.worldIdentity(),2,3,4,chosen,identity.target(),air,0,0));
        for(var foreign:identities) {
            var foreignCapture=new PredictionFaults.SupportCapture(foreign.context(),foreign.targetId(),BuildEngine.PlacementAvailability.READY,
                    BuildEngine.PlacementAvailability.NO_VALID_HIT,latest.pose(),latest.eligibleBefore());
            var invalid=new PredictionFaults.SupportAvailabilitySnapshot(foreign,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                    125,Optional.of(first),Optional.of(foreignCapture),Optional.of(held.line()));
            require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(invalid),chain).isEmpty()
                    && findSupportWitness(run.runId(),plan,run.origin(),foreign,Optional.of(invalid),chain).isEmpty(),"Foreign/stale context/world/target authority accepted");
        }
        var noHold=supportSample(plan,run,125,11.5,held.pose().z(),0,off,held.observations(),Set.of(),List.of(),"END");
        var noHoldChain=new ArrayList<>(chain); noHoldChain.set(noHoldChain.indexOf(held),noHold);
        var noHoldAuthority=new PredictionFaults.SupportAvailabilitySnapshot(identity,PredictionFaults.SupportState.RELEASED,false,Optional.empty(),false,
                125,Optional.of(first),Optional.of(latest),Optional.of(noHold.line()));
        require(findSupportWitness(run.runId(),plan,run.origin(),identity,Optional.of(noHoldAuthority),noHoldChain).isEmpty(),"Controlled zero-hold accepted");
        var noAction=supportSample(plan,run,127,11.5,4.34,6,off,actionScan,Set.of(chosen),List.of(),"END");
        var sameFrame=supportSample(plan,run,126,11.5,4.34,0,on,supportScan(plan,run.runId(),126),Set.of(),List.of(accepted),"END");
        var motionOnly=supportSample(plan,run,129,11.5,4.48,0,on,List.of(),Set.of(),List.of(),"END");
        for(var invalid:List.of(List.of(initial,held,predicted,noAction,observed,south),List.of(initial,held,predicted,sameFrame,motionOnly)))
            require(findSupportWitness(run.runId(),plan,run.origin(),identity,authority,invalid).isEmpty(),"Controlled missing-action/non-later observation accepted");
        PredictionFaults.verifySupportGuards(plan,held);
        System.out.println("ScenarioRunner support-case same-helper synthetic guards passed; native coverage remains separate");
    }

    private static List<PlacementObservation> supportScan(BuildPlan plan,UUID run,long frame) {
        return plan.targets().stream().map(t -> new PlacementObservation(run,t.id(),OptionalLong.empty(),frame,Evidence.OBSERVED,Optional.of(t.block()))).toList();
    }

    private static ScenarioObserver.Sample supportSample(BuildPlan plan,Context run,long frame,double x,double z,int hold,
            List<Boolean> keys,List<PlacementObservation> observations,Set<Integer> pending,List<BuildEngine.PlacementEffectResult> effects,String phase) {
        var floor=new com.mapartrunner.core.traversal.LanePlanner.ExpectedFloor(plan.targets().stream().collect(
                java.util.stream.Collectors.toMap(BuildPlan.Target::relativePos,t -> t)));
        var lane=new com.mapartrunner.core.traversal.LanePlanner.Lane(0,com.mapartrunner.core.traversal.LanePlanner.Direction.SOUTH,
                new GridPos(11,0,2),new GridPos(11,0,7),new com.mapartrunner.core.traversal.LanePlanner.Corridor(9,13,0,9),floor);
        var entry=new com.mapartrunner.core.movement.MovementPolicy.LaneEntry(lane,Stage.WALK,0,0,0,hold,0,pending,false);
        var traversal=new ForwardTraversal.Snapshot(0,ForwardTraversal.Substage.SHIFT,entry,20,Optional.of(lane.corridor()),ForwardTraversal.Pass.FORWARD,0);
        var engine=new BuildEngine.Snapshot(new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.RUNNING,Optional.of(run),Optional.of(Activity.SHIFT),Optional.empty()),
                frame,0,Optional.of(traversal),plan.targets().stream().map(BuildPlan.Target::id).toList(),pending,Optional.empty(),effects);
        var pose=new ScenarioObserver.Pose(x,66,z,Vec3d.ZERO,new Vec3d(x,67.62,z),(int)frame,frame,0,0,0,false,false,1,7,64);
        return new ScenarioObserver.Sample(frame,phase,71,true,pose,List.copyOf(keys),false,
                new FabricBuildAdapter.Status(engine,BuildEngine.ControlOwner.NATIVE,Optional.empty()),List.copyOf(observations));
    }

    private static void verifySuccessOracleGuards() {
        ordered(0,1,2,3,4,5,6,7);
        rejected("unordered traversal",() -> ordered(0,2,1));
        rejected("stage-only or missing travel",() -> ordered(0,0,2));
        require(!distance(1,1,0,1) && !distance(1,5,4,1) && distance(1,5.1,4,1)
                && distance(8,3,4,-1) && !distance(8,9,0,-1),"Travel delta/reach/sign oracle");
        require(atEntry(11.25,7.75,11.5,7.5) && !atEntry(11.5,7.8,11.5,7.5)
                && !atEntry(11.8,7.5,11.5,7.5),"Full X/Z entry oracle");
        require(southFootprint(11.5,11).equals(Set.of(11)) && southFootprint(11.3,11).equals(Set.of(10,11))
                && southFootprint(10.7,11).equals(Set.of(10,11)),"Required SOUTH support footprint oracle");
        var first=new WestInput(8,7,0,1,false,true,true); var second=new WestInput(9,8,1,0,true,true,true);
        require(westInputs(first,second) && !westInputs(first,first)
                && !westInputs(first,new WestInput(9,7,1,0,true,true,true))
                && !westInputs(first,new WestInput(9,8,1,0,true,false,true)),"Duplicate eligible/yaw/missing-axis oracle");
        var id=UUID.randomUUID(); var stone=new BuildPlan.BlockSpec("minecraft:stone",Map.of());
        var targets=new ArrayList<BuildPlan.Target>(); var scan=new ArrayList<PlacementObservation>();
        for (int k=0;k<120;k++) {
            targets.add(new BuildPlan.Target(k,new GridPos(k%12,0,k/12),stone));
            scan.add(new PlacementObservation(id,k,OptionalLong.empty(),7,Evidence.OBSERVED,Optional.of(stone)));
        }
        requireCurrentScan(id,7,targets,scan);
        rejected("incomplete current scan",() -> requireCurrentScan(id,7,targets,scan.subList(0,119)));
        rejected("stale current scan",() -> requireCurrentScan(id,8,targets,scan));
        var duplicate=new ArrayList<>(scan); duplicate.set(119,scan.getFirst());
        rejected("duplicate current target",() -> requireCurrentScan(id,7,targets,duplicate));
        var predicted=new ArrayList<>(scan); predicted.set(0,new PlacementObservation(id,0,OptionalLong.empty(),7,Evidence.PREDICTED,Optional.of(stone)));
        rejected("predicted current target",() -> requireCurrentScan(id,7,targets,predicted));
        var inventory=new JsonArray(); for (int k=0;k<43;k++) inventory.add(new JsonObject());
        inventory.set(0,JsonParser.parseString("{\"id\":\"minecraft:stone\",\"count\":7}"));
        inventory.set(1,JsonParser.parseString("{\"id\":\"minecraft:stone\",\"count\":64}")); inventory.set(2,inventory.get(1).deepCopy());
        var stockState=new JsonObject(); stockState.add("inventory",inventory);
        var stockSide=new Side(stockState,0,0,0,List.of()); var initialStock=inventory.deepCopy();
        require(usable(stockSide)==128,"Initial usable slots1+2 must exclude reserved7");
        var finalInventory=inventory.deepCopy(); finalInventory.set(1,new JsonObject());
        finalInventory.set(2,JsonParser.parseString("{\"id\":\"minecraft:stone\",\"count\":9}")); requireStock(finalInventory,inventory);
        stockState.add("inventory",finalInventory); var finalStock=finalInventory.deepCopy();
        require(usable(stockSide)==9,"Final empty slot1/stone9 slot2 codec count");
        require(inventory.equals(initialStock) && finalInventory.equals(finalStock),"Stock readers mutated captured JSON");
        var singleton=finalInventory.deepCopy(); singleton.set(1,JsonParser.parseString("{\"id\":\"minecraft:stone\"}"));
        singleton.get(2).getAsJsonObject().addProperty("count",8);
        stockState.add("inventory",singleton); var singletonStock=singleton.deepCopy();
        requireStock(singleton,inventory); require(usable(stockSide)==9,"Omitted singleton count must default to1");
        require(singleton.equals(singletonStock),"Singleton stock readers mutated captured JSON");
        singleton.get(1).getAsJsonObject().addProperty("count",1);
        requireStock(singleton,inventory); require(usable(stockSide)==9,"Explicit singleton count differs from omitted count");
        var emptyStock=finalInventory.deepCopy(); emptyStock.set(2,new JsonObject()); stockState.add("inventory",emptyStock);
        require(usable(stockSide)==0,"Empty usable slots must exclude reserved7");
        var emptySecond=finalInventory.deepCopy(); emptySecond.set(1,emptySecond.get(2)); emptySecond.set(2,new JsonObject());
        stockState.add("inventory",emptySecond); requireStock(emptySecond,inventory);
        require(usable(stockSide)==9,"Empty usable slot2 codec count");
        for(var malformed:List.of("null","1","[]","{\"count\":9}","{\"id\":null}","{\"id\":7}",
                "{\"id\":\"\"}","{\"id\":\"bad id\"}","{\"id\":\"mapart:missing_selfcheck_item\"}","{\"id\":\"air\"}","{\"id\":\"minecraft:air\"}",
                "{\"id\":\"minecraft:stone\",\"components\":[]}")) {
            var badStock=finalInventory.deepCopy(); badStock.set(2,JsonParser.parseString(malformed)); stockState.add("inventory",badStock);
            rejected("malformed usable stack "+malformed,() -> usable(stockSide));
            rejected("malformed final stock "+malformed,() -> requireStock(badStock,inventory));
        }
        for(var count:List.of("null","\"9\"","true","[]","{}","0","-1","65","1.5","4294967305","9.000000000000000001")) {
            var badStock=finalInventory.deepCopy(); badStock.get(2).getAsJsonObject().add("count",JsonParser.parseString(count));
            stockState.add("inventory",badStock);
            rejected("invalid usable count "+count,() -> usable(stockSide));
            rejected("invalid final count "+count,() -> requireStock(badStock,inventory));
        }
        for(var invalidInventory:List.of(JsonNull.INSTANCE,new JsonObject(),new JsonArray(),JsonParser.parseString("[{},{}]"))) {
            stockState.add("inventory",invalidInventory);
            rejected("malformed usable inventory "+invalidInventory,() -> usable(stockSide));
        }
        stockState.remove("inventory"); rejected("missing usable inventory",() -> usable(stockSide));
        rejected("null final inventory",() -> requireStock(null,inventory));
        var reserveUsed=finalInventory.deepCopy(); reserveUsed.get(0).getAsJsonObject().addProperty("count",6);
        rejected("reserve consumed but usable total9",() -> requireStock(reserveUsed,inventory));
        var foreignItem=finalInventory.deepCopy(); foreignItem.get(2).getAsJsonObject().addProperty("id","minecraft:gold_block");
        rejected("usable item substitution",() -> requireStock(foreignItem,inventory));
        var changedComponents=finalInventory.deepCopy(); changedComponents.get(2).getAsJsonObject().add("components",new JsonObject());
        rejected("usable components changed",() -> requireStock(changedComponents,inventory));
        var wrongTotal=finalInventory.deepCopy(); wrongTotal.get(2).getAsJsonObject().addProperty("count",8);
        rejected("wrong final usable total",() -> requireStock(wrongTotal,inventory));
        var feedback=new ArrayList<>(targets.stream().filter(t -> t.id()!=24).map(t -> new BuildEngine.PlacementEffectResult(
                id,t.id(),t.id(),t.id()+1,BuildEngine.EffectStatus.ACCEPTED)).toList());
        feedback.remove(54); // Synthetic omitted private pre-pause result; no replacement record is invented.
        requireCurrentScan(id,7,targets,scan); requireStock(finalInventory,inventory); requireAcceptedFeedback(id,targets,feedback);
        var duplicateAction=new ArrayList<>(feedback); var firstEffect=feedback.getFirst(); var secondEffect=feedback.get(1);
        duplicateAction.set(1,new BuildEngine.PlacementEffectResult(id,firstEffect.actionId(),secondEffect.targetId(),secondEffect.tick(),BuildEngine.EffectStatus.ACCEPTED));
        rejected("accepted action identity reused",() -> requireAcceptedFeedback(id,targets,duplicateAction));
        // Native8 target33 was OBSERVED air between these distinct accepted attempts; acceptance is not confirmation.
        requireAcceptedFeedback(id,targets,List.of(
                new BuildEngine.PlacementEffectResult(id,64,33,1292,BuildEngine.EffectStatus.ACCEPTED),
                new BuildEngine.PlacementEffectResult(id,68,33,1296,BuildEngine.EffectStatus.ACCEPTED)));
        rejected("foreign accepted run",() -> requireAcceptedFeedback(id,targets,List.of(new BuildEngine.PlacementEffectResult(
                UUID.randomUUID(),1,1,1,BuildEngine.EffectStatus.ACCEPTED))));
        rejected("unknown accepted target",() -> requireAcceptedFeedback(id,targets,List.of(new BuildEngine.PlacementEffectResult(
                id,1,999,1,BuildEngine.EffectStatus.ACCEPTED))));
        var context=new Context(id,"selfcheck","selfcheck","minecraft:overworld","selfcheck",new GridPos(0,65,0),"selfcheck");
        var lifecycle=new com.mapartrunner.core.run.RunLifecycle.Snapshot(Phase.PAUSED,Optional.of(context),Optional.of(Activity.SHIFT),Optional.empty());
        var plan=new BuildPlan("selfcheck",new BuildPlan.GridSize(12,1,10),targets);
        var lane=new com.mapartrunner.core.traversal.LanePlanner().plan(plan,2,5).getFirst();
        var entry=new com.mapartrunner.core.movement.MovementPolicy.LaneEntry(lane,Stage.WALK,0,0,0,3,0,Set.of(2),false);
        var traversal=new ForwardTraversal.Snapshot(0,ForwardTraversal.Substage.SHIFT,entry,21,Optional.of(lane.corridor()),ForwardTraversal.Pass.FORWARD,0);
        var before=new BuildEngine.Snapshot(lifecycle,33,0,Optional.of(traversal),List.of(2),Set.of(2),Optional.empty(),List.of());
        var after=new BuildEngine.Snapshot(lifecycle,33,0,Optional.of(traversal),List.of(),Set.of(),Optional.empty(),
                List.of(new BuildEngine.PlacementEffectResult(id,1,2,31,BuildEngine.EffectStatus.ACCEPTED)));
        require(retainedProgress(before,after),"Legitimate paused ledger confirmation rejected");
        require(!retainedProgress(before,new BuildEngine.Snapshot(lifecycle,34,0,Optional.of(traversal),List.of(),Set.of(),Optional.empty(),List.of()))
                && !retainedProgress(before,new BuildEngine.Snapshot(lifecycle,33,0,Optional.of(new ForwardTraversal.Snapshot(0,
                        ForwardTraversal.Substage.SHIFT,entry,0,Optional.of(lane.corridor()),ForwardTraversal.Pass.FORWARD,0)),List.of(),Set.of(),Optional.empty(),List.of())),
                "Pause clock/traversal renewal accepted");
        System.out.println("ScenarioRunner success-oracle same-helper self-checks passed; native scenario remains separate");
    }

    private void createOwnedWorld() {
        checkRoots(roots);
        context.runOnClient(this::requireUncreatedClient);
        // WorldCreator's constructor already probes save names: guard the entire builder entry.
        try {
            if (!Files.exists(roots.saves,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(roots.saves);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        guardedWorldCreation(roots,roots.saves,real(roots.saves),() -> world=context.worldBuilder().create());
        server = world.getServer().computeOnServer(s -> {
            require(s.getOverworld().getSeed()==1 && s.getOverworld().getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator,
                    "Owned world must be seed1/flat");
            require(s.getPlayerManager().getPlayerList().size()==1, "Fixture requires exactly one player");
            serverWorld=s.getOverworld();
            serverPlayer = s.getPlayerManager().getPlayerList().getFirst();
            return s;
        });
        save = real(world.getWorldSave().getSaveDirectory());
        require(save.getParent().equals(roots.saves)
                && real(server.getSavePath(WorldSavePath.ROOT)).equals(save), "Framework-created save escaped game authority");
        context.runOnClient(c -> {
            require(c.getServer()==server && c.world!=null && c.world.getRegistryKey().equals(World.OVERWORLD)
                    && c.player!=null && c.getNetworkHandler()!=null, "Missing creation binding");
            clientWorld=c.world; clientPlayer=c.player; handler=c.getNetworkHandler(); connection=handler.getConnection();
            requireClient(c);
        });
        writeHeader(artifacts.resolve("authority.txt"), "invocation=" + invocation + "; roots=" + roots + "; save=" + save
                + "; server=" + System.identityHashCode(server) + "; serverPlayer=" + System.identityHashCode(serverPlayer)
                + "; clientWorld=" + System.identityHashCode(clientWorld) + "; clientPlayer=" + System.identityHashCode(clientPlayer)
                + "; handler=" + System.identityHashCode(handler) + "; connection=" + System.identityHashCode(connection));
    }

    private void preflight(ScenarioDefinition d) {
        Objects.requireNonNull(d);
        profileDefinition.requireSame(d);
        guarded(invocation, invocation, roots, FabricLoader.getInstance().getGameDir(),profileDefinition,fixture,
                profileDefinition.definitionHash(), d.definitionHash(), d.data(), () -> { });
        require(!closed, "Invocation closed");
        if (admitted!=null) require(admitted.definitionHash().equals(d.definitionHash()), "Repeated start changed definition");
        if (world!=null) {
            context.runOnClient(this::requireClient);
            requireServerCheckpoint();
        } else context.runOnClient(this::requireUncreatedClient);
    }

    private void requireUncreatedClient(MinecraftClient c) {
        require(c.isOnThread() && !closed && world==null,"Invalid pre-creation invocation"); checkRoots(roots);
        require(c.world==null && c.getServer()==null && c.player==null,"Cannot create into a foreign open world");
        require(c.getLevelStorage().getSavesDirectory().toAbsolutePath().equals(roots.saves),"Foreign native saves directory");
    }

    private void requireClient(MinecraftClient c) {
        require(c.isOnThread(), "Not client thread"); checkSave();
        require(c.getServer()==server && c.world==clientWorld && c.player==clientPlayer && c.getNetworkHandler()==handler
                && handler.getConnection()==connection && connection.isOpen() && handler.getWorld()==clientWorld
                && clientPlayer.getEntityWorld()==clientWorld && clientWorld.getRegistryKey().equals(World.OVERWORLD)
                && c.getLevelStorage().getSavesDirectory().toAbsolutePath().equals(roots.saves), "Stale client authority");
    }

    private void requireServer(MinecraftServer s) {
        checkSave();
        require(s==server && s.getOverworld()==serverWorld && serverWorld==serverPlayer.getEntityWorld() && serverWorld.getRegistryKey().equals(World.OVERWORLD)
                && s.getPlayerManager().getPlayerList().size()==1 && s.getPlayerManager().getPlayerList().getFirst()==serverPlayer
                && serverPlayer.getUuid().equals(clientPlayer.getUuid()), "Stale server/player/dimension authority");
    }

    private void requireServerCheckpoint() { world.getServer().runOnServer(this::requireServer); }

    private void checkSave() {
        require(!closed, "Invocation closed"); checkRoots(roots);
        require(real(world.getWorldSave().getSaveDirectory()).equals(save)
                && real(server.getSavePath(WorldSavePath.ROOT)).equals(save)
                && real(roots.saves).equals(roots.saves) && save.getParent().equals(roots.saves), "Save authority redirected");
    }

    private void archive(Path stage, Prior prior) {
        preflight(admitted == null ? profileDefinition : admitted);
        checkedDirectory(roots, stage);
        if(!baseline()) { archiveCompact(stage,prior.traversal); return; }
        writeNew(roots, stage.resolve("prior-result.txt"), String.valueOf(previous));
        writeNew(roots, stage.resolve("prior-status-transitions.txt"), prior.status.toString());
        writeLinesNew(stage.resolve("prior-observer.txt"),prior.samples.stream().map(ScenarioObserver.Sample::line)::iterator);
        writeNew(roots, stage.resolve("prior-fault.txt"), prior.fault + "\n" + prior.stateFault);
        writeNew(roots, stage.resolve("prior-mismatches.txt"), previous==null ? "no previous result" : previous.mismatches.toString());
        Path screenshot = context.takeScreenshot(TestScreenshotOptions.of("before-reset").withDestinationDir(stage).disableCounterPrefix());
        require(real(screenshot).startsWith(real(stage)) && Files.isRegularFile(screenshot), "Archive screenshot absent/escaped");
        copyNew(roots, roots.game.resolve("logs/latest.log"), stage.resolve("minecraft-latest.log"));
        // COMMITTED is last; any archive exception leaves observer/fault/world untouched after stop.
        writeNew(roots, stage.resolve("COMMITTED"), "archive complete; invocation=" + invocation);
    }

    private static void archiveThenRestore(Runnable archive, Runnable restore) { archive.run(); restore.run(); }

    private void restore(ScenarioDefinition d) {
        preflight(d);
        var data=d.data(); var player=data.getAsJsonObject("player");
        world.getServer().runOnServer(s -> {
            requireServer(s);
            var level=s.getOverworld();
            var entities=level.getOtherEntities(serverPlayer, regionBox(), e -> true);
            // This fixture never breaks blocks or drops items; no arbitrary entity gets reset ownership.
            require(entities.isEmpty(), "Unowned bounded entities: " + entities);
            for (var cell:data.getAsJsonObject("world").getAsJsonArray("blocks")) {
                var value=cell.getAsJsonObject(); level.setBlockState(pos(value.getAsJsonObject("position")), block(value.getAsJsonObject("block")));
            }
            var p=serverPlayer;
            p.closeHandledScreen(); p.changeGameMode(GameMode.SURVIVAL);
            restorePlayer(p, player, true);
            p.setPlayerInput(PlayerInput.DEFAULT);
            var xyz=player.getAsJsonObject("position"); var rotation=player.getAsJsonObject("rotation");
            require(p.teleport(level,n(xyz,"x"),n(xyz,"y"),n(xyz,"z"),Set.of(),(float)n(rotation,"yaw"),(float)n(rotation,"pitch"),false), "Reset teleport rejected");
            p.sendAbilitiesUpdate(); p.getInventory().markDirty(); p.playerScreenHandler.syncState();
        });
        context.runOnClient(c -> {
            requireClient(c);
            optionsOwned=true;
            c.options.getAutoJump().setValue(b(player,"autoJump"));
            if(!baseline()) {
                c.options.getViewDistance().setValue(fixture.viewDistance());
                c.options.sendClientSettings(); // Ordinary server propagation must pass the same readiness200.
            }
            // Reset owns private local hunger/menu/input/angles; subsequent readiness performs reads only.
            c.setScreen(null);
            restorePlayer(c.player, player, false);
            c.player.getInventory().setSelectedSlot(i(player,"selectedSlot"));
            c.player.input.playerInput=PlayerInput.DEFAULT;
        });
    }

    private static DefaultAttributeContainer playerDefaults(List<RegistryEntry<EntityAttribute>> attributes) {
        var defaults=PlayerEntity.createPlayerAttributes().build();
        for (var attribute:attributes) require(defaults.has(attribute),"Unsupported baseline player attribute " + attribute);
        return defaults;
    }

    private static void restorePlayer(PlayerEntity p, JsonObject expected, boolean serverOwned) {
        p.dismountVehicle(); p.stopGliding(); p.clearStatusEffects(); p.setSwimming(false); p.setSprinting(false); p.setSneaking(false);
        p.setNoGravity(false); p.setNoDrag(false); p.setJumping(false); p.setVelocity(Vec3d.ZERO); p.setOnGround(true);
        p.fallDistance=0; p.stopUsingItem();
        if (serverOwned) { p.setHealth((float)n(expected,"health")); p.getAbilities().unpack(abilities(expected)); }
        var defaults=playerDefaults(BASELINE_ATTRIBUTES);
        for (var attr:BASELINE_ATTRIBUTES) {
            var instance=p.getAttributeInstance(attr);
            require(instance!=null, "Missing baseline player attribute " + attr);
            instance.clearModifiers(); instance.setBaseValue(defaults.getBaseValue(attr));
        }
        for (var instance:p.getAttributes().getAttributesToSend()) {
            require(defaults.has(instance.getAttribute()),"Unknown tracked player attribute " + instance.getAttribute());
            instance.clearModifiers(); instance.setBaseValue(defaults.getBaseValue(instance.getAttribute()));
        }
        var reporter=new ErrorReporter.Impl(); var hunger=new NbtCompound();
        hunger.putInt("foodLevel",i(expected,"hunger")); hunger.putInt("foodTickTimer",0);
        hunger.putFloat("foodSaturationLevel",(float)n(expected,"saturation")); hunger.putFloat("foodExhaustionLevel",(float)n(expected,"exhaustion"));
        p.getHungerManager().readData(NbtReadView.create(reporter,p.getEntityWorld().getRegistryManager(),hunger));
        require(reporter.isEmpty(), "Hunger restore codec error: " + reporter.getErrorsAsString());
        require(p.currentScreenHandler==p.playerScreenHandler, "Reset must close non-player handler");
        p.playerScreenHandler.setCursorStack(ItemStack.EMPTY); p.playerScreenHandler.getCraftingInput().clear();
        p.playerScreenHandler.getOutputSlot().setStack(ItemStack.EMPTY);
        if (serverOwned) {
            p.getInventory().clear();
            for (var slot:expected.getAsJsonArray("inventory")) {
                var value=slot.getAsJsonObject(); p.getInventory().setStack(i(value,"slot"),stack(value));
            }
        }
        p.getInventory().setSelectedSlot(i(expected,"selectedSlot"));
        var r=expected.getAsJsonObject("rotation");
        p.setYaw((float)n(r,"yaw")); p.lastYaw=p.getYaw(); p.setPitch((float)n(r,"pitch")); p.lastPitch=p.getPitch();
        p.setHeadYaw((float)n(r,"headYaw")); p.lastHeadYaw=p.headYaw;
        p.setBodyYaw((float)n(r,"bodyYaw")); p.lastBodyYaw=p.bodyYaw;
    }

    private void awaitStable(Path stage, int consumed) {
        Checkpoint previousCheckpoint=null;
        int budget=i(admitted.data().getAsJsonObject("budgets"),"readinessTicks");
        for (int tick=consumed+1; tick<=budget; tick++) {
            context.waitTick();
            // waitTick returns in Fabric TEST; each callback finishes before the other is requested.
            final int attempt=tick;
            var client=context.computeOnClient(c -> {
                requireClient(c); var side=readClient(c,true);
                return baseline()?side:withRegion(side,region(c.player,true,"readiness-"+attempt+"-client"));
            });
            var status=context.computeOnClient(c -> { requireClient(c); return MapArtClient.status(); });
            var remote=world.getServer().computeOnServer(s -> {
                requireServer(s); var side=readPlayer(serverPlayer,true,s.getTicks());
                return baseline()?side:withRegion(side,region(serverPlayer,true,"readiness-"+attempt+"-server"));
            });
            var checkpoint=new Checkpoint(client,remote,status);
            if(baseline()) writeNew(roots,stage.resolve("readiness-"+tick+".json"), evidence(checkpoint).toString());
            else scalar("readiness-"+tick+".json","clientAge="+client.age+"; clientTime="+client.time+"; serverAge="+remote.age
                    +"; serverTime="+remote.time+"; serverTick="+remote.serverTick+"; clientProblems="+client.problems+"; serverProblems="+remote.problems);
            boolean passing=client.problems.isEmpty() && remote.problems.isEmpty();
            if (passing && previousCheckpoint!=null && advancing(previousCheckpoint,checkpoint)) {
                var first=checkpointJson(previousCheckpoint); var second=checkpointJson(checkpoint);
                observedHash=hash(second.toString()); stable=checkpoint;
                var stableText="{\"firstHash\":\""+hash(first.toString())+"\",\"secondHash\":\""+observedHash
                        +"\",\"byteEqual\":"+first.equals(second)+",\"rawDelta\":"+deltas(previousCheckpoint,checkpoint)+",\"first\":"+evidence(previousCheckpoint)+",\"second\":"+evidence(checkpoint)+"}";
                var budgetText="releaseConsumed="+consumed+"; stableConsumed="+(tick-consumed)
                        +"; totalConsumed="+tick+"; remaining="+(budget-tick)+"; sharedReadinessTicks="+budget;
                if(baseline()) { writeNew(roots,stage.resolve("stable-start.json"),stableText); writeNew(roots,stage.resolve("readiness-budget.txt"),budgetText); }
                else { scalar("stable-start.json",stableText); scalar("readiness-budget.txt",budgetText); }
                return;
            }
            previousCheckpoint=passing ? checkpoint : null;
        }
        throw new AssertionError("Readiness failed within " + budget + " actual physics ticks; retained " + stage);
    }

    private Side readClient(MinecraftClient c, boolean initial) {
        var side=readPlayer(c.player,initial,-1); var errors=new ArrayList<>(side.problems);
        var state=side.state;
        var keys=baseline()?watch.sample(c,"TEST").keys():keys(c);
        state.add("keys",new Gson().toJsonTree(keys));
        state.addProperty("paused",c.isPaused()); state.addProperty("screenOpen",c.currentScreen!=null);
        state.addProperty("autoJump",c.options.getAutoJump().getValue());
        if(!baseline()) {
            int raw=c.options.getViewDistance().getValue(),clamped=c.options.getClampedViewDistance();
            state.addProperty("viewDistanceRaw",raw); state.addProperty("viewDistanceClamped",clamped);
            if(initial && (raw!=fixture.viewDistance() || clamped!=fixture.viewDistance())) errors.add("Client view distance not propagated");
        }
        state.addProperty("mode",c.interactionManager==null ? "missing" : c.interactionManager.getCurrentGameMode().name());
        state.addProperty("input",c.player.input.playerInput.toString());
        state.addProperty("lastInput",c.player.getLastPlayerInput().toString());
        state.addProperty("movementInput",c.player.input.getMovementInput().toString());
        if (c.currentScreen!=null || c.isPaused() || c.options.getAutoJump().getValue() || keys.stream().anyMatch(Boolean::booleanValue)
                || c.interactionManager==null || c.interactionManager.getCurrentGameMode()!=GameMode.SURVIVAL
                || !c.player.input.playerInput.equals(PlayerInput.DEFAULT) || !c.player.getLastPlayerInput().equals(PlayerInput.DEFAULT)
                || c.player.input.getMovementInput().lengthSquared()!=0) errors.add("Client screen/options/mode/input/keys not neutral");
        var status=MapArtClient.status();
        state.addProperty("controlOwner",status.controlOwner().name());
        state.addProperty("productionActive",status.engine().lifecycle().phase()==Phase.RUNNING || status.engine().lifecycle().phase()==Phase.PAUSED);
        if (status.controlOwner()!=BuildEngine.ControlOwner.NONE || b(state,"productionActive")) errors.add("Production active/controls owned");
        var fault=PredictionFaults.snapshot(); var stateFault=PredictionFaults.stateSnapshot();
        state.addProperty("faultActive",fault.active() || stateFault.active());
        state.addProperty("faultFailure",fault.failure().orElse("")+stateFault.failure().orElse(""));
        if (fault.active() || stateFault.active() || fault.failure().isPresent() || stateFault.failure().isPresent()) errors.add("Fixture fault active/failed");
        Object pending=((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager();
        require(pending instanceof PendingPredictionView, "Read-only scoped pending view unavailable");
        var predicted=new JsonArray(); int predictionCount=0;
        for (var cell:admitted.worldCells().keySet()) {
            var position=new BlockPos(cell.x(),cell.y(),cell.z());
            if (((PendingPredictionView)pending).mapart$hasPendingPrediction(position)) {
                predictionCount++; if(baseline()) predicted.add(position.toShortString());
            }
        }
        state.add("pendingOwnedPositions",predicted);
        state.addProperty("predictionScope","all "+admitted.worldCells().size()+" authorized region positions; fixture-only interactions, sole player; no global queue claim");
        if(!baseline()) state.addProperty("pendingOwnedCount",predictionCount);
        if (predictionCount!=0) errors.add("Owned predictions have not drained naturally");
        return new Side(state,side.age,side.time,-1,List.copyOf(errors));
    }

    private Side readPlayer(PlayerEntity p, boolean initial, int serverTick) {
        var errors=new ArrayList<String>(); var actual=new JsonObject();
        var expected=admitted.playerData(); var level=p.getEntityWorld();
        actual.addProperty("dimension",level.getRegistryKey().getValue().toString());
        var xyz=expected.getAsJsonObject("position"); var rot=expected.getAsJsonObject("rotation");
        String[] components={"x","y","z","vx","vy","vz","yaw","headYaw","bodyYaw","pitch"};
        double[] values={p.getX(),p.getY(),p.getZ(),p.getVelocity().x,p.getVelocity().y,p.getVelocity().z,p.getYaw(),p.headYaw,p.bodyYaw,p.getPitch()};
        for (int k=0;k<components.length;k++) {
            actual.addProperty(components[k],Double.toHexString(values[k]));
            if (!Double.isFinite(values[k])) errors.add("Nonfinite " + components[k]);
        }
        for (var axis:List.of("x","y","z")) if (Math.abs(Double.valueOf(actual.get(axis).getAsString())-n(xyz,axis))>1e-6) errors.add("Position " + axis);
        for (var angle:List.of("yaw","headYaw","bodyYaw","pitch")) if (Math.abs(MathHelper.wrapDegrees(Double.valueOf(actual.get(angle).getAsString())-n(rot,angle)))>1e-4) errors.add("Rotation " + angle);
        if (Math.abs(p.getVelocity().x)>1e-7 || Math.abs(p.getVelocity().z)>1e-7
                || Double.doubleToRawLongBits(p.getVelocity().y)!=0xBFB41205C28F5C29L) errors.add("Post-physics velocity (exact Y required)");
        actual.addProperty("health",p.getHealth()); actual.addProperty("abilities",p.getAbilities().pack().toString());
        if (p.getHealth()!=(float)n(expected,"health") || !p.getAbilities().pack().equals(abilities(expected))) errors.add("Health/complete abilities");
        var reporter=new ErrorReporter.Impl(); var hunger=NbtWriteView.create(reporter,level.getRegistryManager()); p.getHungerManager().writeData(hunger);
        require(reporter.isEmpty(),"Hunger observation codec error: "+reporter.getErrorsAsString());
        var hungerData=hunger.getNbt(); actual.addProperty("completeHunger",hungerData.toString());
        if (hungerData.getInt("foodLevel",-1)!=i(expected,"hunger") || hungerData.getInt("foodTickTimer",-1)!=0
                || hungerData.getFloat("foodSaturationLevel",-1)!=(float)n(expected,"saturation")
                || hungerData.getFloat("foodExhaustionLevel",-1)!=(float)n(expected,"exhaustion")) errors.add("Complete hunger");
        var inventory=new JsonArray();
        require(p.getInventory().size()==43,"Pinned native inventory is not 43 slots");
        for (var slot:expected.getAsJsonArray("inventory")) {
            var value=slot.getAsJsonObject(); var found=p.getInventory().getStack(i(value,"slot"));
            inventory.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),found).getOrThrow());
            if (!ItemStack.areEqual(found,stack(value))) errors.add("Full inventory/components slot " + i(value,"slot"));
        }
        actual.add("inventory",inventory); actual.addProperty("selectedSlot",p.getInventory().getSelectedSlot());
        if (p.getInventory().getSelectedSlot()!=i(expected,"selectedSlot")) errors.add("Selected slot");
        var menu=new JsonArray();
        menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.currentScreenHandler.getCursorStack()).getOrThrow());
        for (int k=0;k<p.playerScreenHandler.getCraftingInput().size();k++) menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.playerScreenHandler.getCraftingInput().getStack(k)).getOrThrow());
        menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.playerScreenHandler.getOutputSlot().getStack()).getOrThrow());
        actual.add("cursorAndCrafting",menu); actual.addProperty("playerHandler",p.currentScreenHandler==p.playerScreenHandler);
        if (p.currentScreenHandler!=p.playerScreenHandler || !p.currentScreenHandler.getCursorStack().isEmpty()
                || !p.playerScreenHandler.getCraftingInput().isEmpty() || !p.playerScreenHandler.getOutputSlot().getStack().isEmpty()) errors.add("Player cursor/crafting/handler");
        var guards=new JsonObject();
        guards.addProperty("onGround",p.isOnGround()); guards.addProperty("noGravity",p.hasNoGravity()); guards.addProperty("noDrag",p.hasNoDrag());
        guards.addProperty("jumping",p.isJumping()); guards.addProperty("vehicle",p.hasVehicle()); guards.addProperty("fluid",p.isInFluid());
        guards.addProperty("water",p.isTouchingWater()); guards.addProperty("submerged",p.isSubmergedInWater()); guards.addProperty("lava",p.isInLava());
        guards.addProperty("climbing",p.isClimbing()); guards.addProperty("climbingPos",p.getClimbingPos().toString()); guards.addProperty("gliding",p.isGliding());
        guards.addProperty("swimming",p.isSwimming()); guards.addProperty("sprinting",p.isSprinting()); guards.addProperty("flying",p.getAbilities().flying);
        guards.addProperty("sneaking",p.isSneaking()); guards.addProperty("usingItem",p.isUsingItem()); guards.addProperty("fallDistance",p.fallDistance);
        guards.addProperty("effects",p.getStatusEffects().toString());
        guards.addProperty("gravityBase",p.getAttributeBaseValue(EntityAttributes.GRAVITY)); guards.addProperty("gravityValue",p.getAttributeValue(EntityAttributes.GRAVITY));
        guards.addProperty("finalGravity",p.getFinalGravity());
        // Pinned effective getter differs only with slow-falling; all effects are independently absent here.
        guards.addProperty("sourceDerivedEffectiveGravity",p.getStatusEffects().isEmpty() && !p.hasNoGravity() ? Double.toHexString(p.getFinalGravity()) : "guards failed: unknown");
        var attrs=new TreeMap<String,String>();
        var defaults=playerDefaults(BASELINE_ATTRIBUTES);
        for (var attr:p.getAttributes().getAttributesToSend()) {
            attrs.put(attr.getAttribute().getIdAsString(),"base="+Double.toHexString(attr.getBaseValue())+";value="+Double.toHexString(attr.getValue())
                    +";modifiers="+attr.getModifiers().stream().map(Object::toString).sorted().toList());
            if (!defaults.has(attr.getAttribute()) || attr.getBaseValue()!=defaults.getBaseValue(attr.getAttribute())
                    || attr.getValue()!=defaults.getValue(attr.getAttribute()) || !attr.getModifiers().isEmpty()) errors.add("Tracked attribute/modifier " + attr.getAttribute());
        }
        guards.add("trackedAttributes",new Gson().toJsonTree(attrs));
        for (var attr:BASELINE_ATTRIBUTES) {
            if (p.getAttributeBaseValue(attr)!=defaults.getBaseValue(attr) || p.getAttributeValue(attr)!=defaults.getValue(attr)
                    || p.getAttributes().getAttributesToSend().stream().anyMatch(a -> a.getAttribute().equals(attr) && !a.getModifiers().isEmpty())) errors.add("Motion attribute/modifier " + attr);
        }
        if (!p.isOnGround() || p.hasNoGravity() || p.hasNoDrag() || p.isJumping() || p.hasVehicle() || p.isInFluid() || p.isTouchingWater()
                || p.isSubmergedInWater() || p.isInLava() || p.isClimbing() || p.isGliding() || p.isSwimming() || p.isSprinting()
                || p.getAbilities().flying || p.isSneaking() || p.isUsingItem() || p.fallDistance!=0 || !p.getStatusEffects().isEmpty() || p.getAttributeBaseValue(EntityAttributes.GRAVITY)!=.08
                || p.getAttributeValue(EntityAttributes.GRAVITY)!=.08 || p.getFinalGravity()!=.08) errors.add("Ordinary physics guards");
        var support=baseline()?new BlockPos((int)Math.floor(n(xyz,"x")),(int)Math.floor(n(xyz,"y"))-1,(int)Math.floor(n(xyz,"z"))):p.getBlockPos().down();
        boolean loaded=level.getChunkManager().getChunk(Math.floorDiv(support.getX(),16),Math.floorDiv(support.getZ(),16),ChunkStatus.FULL,false)!=null;
        if(!baseline() && !loaded) {
            guards.addProperty("supportLoaded",false); guards.addProperty("support","UNKNOWN: unloaded FULL chunk");
            errors.add("Loaded full-cube support/clearance unavailable");
        } else {
        guards.addProperty("supportLoaded",loaded); guards.addProperty("support",level.getBlockState(support).toString());
        guards.addProperty("supportFullCube",level.getBlockState(support).isFullCube(level,support));
        guards.addProperty("supportingBlock",p.supportingBlockPos.toString()); guards.addProperty("supportedByStone",p.isSupportedBy(support));
        if (!loaded || !level.getBlockState(support).equals(Blocks.STONE.getDefaultState()) || !level.getBlockState(support).isFullCube(level,support)
                || !p.isSupportedBy(support) || !level.getBlockState(support.up()).isAir() || !level.getBlockState(support.up(2)).isAir()) errors.add("Loaded full-cube support/clearance");
        }
        actual.add("physicsGuards",guards);
        var blocks=new JsonArray();
        if(baseline()) for (var cell:admitted.worldCells().entrySet()) {
            var p0=cell.getKey(); var at=new BlockPos(p0.x(),p0.y(),p0.z());
            boolean chunk=level.getChunkManager().getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16),ChunkStatus.FULL,false)!=null;
            var found=level.getBlockState(at); blocks.add(at.toShortString()+"="+found+";loaded="+chunk+";blockEntity="+(level.getBlockEntity(at)!=null));
            if (!chunk || level.getBlockEntity(at)!=null || (initial && !WorldEvidenceReader.blockSpec(found).equals(cell.getValue()))) errors.add("Owned block/loaded/block entity " + at);
        }
        actual.add("blocks",blocks); actual.add("containers",new JsonArray());
        var entities=level.getOtherEntities(p,regionBox(),e -> true);
        actual.add("boundedEntities",new Gson().toJsonTree(entities.stream().map(e -> e.getType().toString()+":"+e.getUuid()).sorted().toList()));
        if (!entities.isEmpty()) errors.add("Unexpected bounded non-player entities");
        if (p instanceof ServerPlayerEntity sp) {
            if(!baseline()) {
                int request=sp.getViewDistance(),manager=server.getPlayerManager().getViewDistance();
                actual.addProperty("viewDistanceRequested",request); actual.addProperty("viewDistanceManager",manager);
                if(initial && (request!=fixture.viewDistance() || manager!=fixture.viewDistance())) errors.add("Server view distance not propagated");
            }
            actual.addProperty("mode",sp.getGameMode().name()); actual.addProperty("input",sp.getPlayerInput().toString());
            if (sp.getGameMode()!=GameMode.SURVIVAL || !sp.getPlayerInput().equals(PlayerInput.DEFAULT)) errors.add("Server mode/input");
        }
        return new Side(actual,p.age,level.getTime(),serverTick,List.copyOf(errors));
    }

    private JsonObject checkpointJson(Checkpoint cp) {
        var value=new JsonObject(); value.add("client",cp.client.state); value.add("server",cp.server.state);
        var request=new JsonObject(); request.addProperty("provenance","actual retained/imported runner-held request; production binding asserted once after start");
        request.addProperty("plan",plan.fingerprint()); request.addProperty("origin",origin.toString()); request.addProperty("settings",settings.fingerprint());
        value.add("startRequest",request); value.addProperty("definitionHash",admitted.definitionHash());
        return sorted(value).getAsJsonObject();
    }

    private JsonObject evidence(Checkpoint cp) {
        var out=new JsonObject(); out.add("equalityState",checkpointJson(cp));
        for (String side:List.of("client","server")) {
            var sample=side.equals("client")?cp.client:cp.server; var clocks=new JsonObject();
            clocks.addProperty("age",sample.age); clocks.addProperty("worldTime",sample.time); clocks.addProperty("serverTick",sample.serverTick);
            clocks.add("problems",new Gson().toJsonTree(sample.problems)); out.add(side+"Checkpoint",clocks);
        }
        out.addProperty("invocation",invocation.toString());
        out.addProperty("productionStatus",baseline()?cp.status.toString():"phase="+cp.status.engine().lifecycle().phase()
                +"; owner="+cp.status.controlOwner()+"; eligible="+cp.status.engine().eligibleTicks()+"; verify="+cp.status.engine().verifyTicks());
        return out;
    }

    private static boolean advancing(Checkpoint a, Checkpoint b) {
        return b.client.age>a.client.age && b.client.time>a.client.time && b.server.age>a.server.age
                && b.server.time>a.server.time && b.server.serverTick>a.server.serverTick;
    }

    private static JsonObject deltas(Checkpoint a, Checkpoint b) {
        var result=new JsonObject();
        for (String side:List.of("client","server")) {
            var first=side.equals("client")?a.client:a.server; var second=side.equals("client")?b.client:b.server;
            var delta=new JsonObject();
            for (String key:List.of("x","y","z","vx","vy","vz","yaw","headYaw","bodyYaw","pitch"))
                delta.addProperty(key,Double.toHexString(Double.valueOf(second.state.get(key).getAsString())-Double.valueOf(first.state.get(key).getAsString())));
            delta.addProperty("ageDelta",second.age-first.age); delta.addProperty("timeDelta",second.time-first.time);
            delta.addProperty("serverTickDelta",second.serverTick-first.serverTick); result.add(side,delta);
        }
        return result;
    }

    private void enforceActionScope() {
        for (var sample:watch.copy()) for (var effect:sample.status().engine().effectResults()) {
            var target=plan.targets().stream().filter(t -> t.id()==effect.targetId()).findFirst().orElseThrow();
            var p=target.relativePos(); require(regionBox().contains(origin.x()+p.x()+.5,origin.y()+p.y()+.5,origin.z()+p.z()+.5), "Issued action escaped prediction scope");
        }
    }

    private static void requireReleased(MinecraftClient c) {
        var o=c.options;
        require(List.of(o.forwardKey,o.backKey,o.leftKey,o.rightKey,o.jumpKey,o.sneakKey,o.sprintKey,o.useKey,o.attackKey)
                .stream().noneMatch(k -> k.isPressed()) && MapArtClient.status().controlOwner()==BuildEngine.ControlOwner.NONE,
                "Stop did not release nine keys/NONE");
    }

    @Override public void close() {
        if (closed) return;
        if(!baseline()) { closeCompact(); return; }
        closeOwned(world!=null,
                () -> preflight(admitted==null?profileDefinition:admitted),
                () -> {
                    ready=false;
                    context.runOnClient(c -> {
                        requireClient(c); MapArtClient.stop(); requireReleased(c);
                        if(supportArm!=null) {
                            var before=PredictionFaults.resetSnapshot(); var status=MapArtClient.status(); var issued=supportArm;
                            closeSupport(() -> {
                                writeNew(roots,artifacts.resolve("support-gate-before-close.txt"),before.support().toString());
                                var equal=new PredictionFaults.SupportArmIdentity(issued.arm(),issued.context(),issued.save(),issued.server(),issued.world(),
                                        issued.player(),issued.handler(),issued.connection(),issued.targetId(),issued.target(),issued.expected(),issued.armedAge(),issued.armedEligible());
                                rejected("equal reconstructed close identity",() -> PredictionFaults.cancelSupportAvailabilityForClose(c,server,save,issued.context().runId(),equal));
                                rejected("wrong close UUID",() -> PredictionFaults.cancelSupportAvailabilityForClose(c,server,save,UUID.randomUUID(),issued));
                                rejected("wrong close save",() -> PredictionFaults.cancelSupportAvailabilityForClose(c,server,save.resolve("foreign"),issued.context().runId(),issued));
                                rejected("wrong close server",() -> PredictionFaults.cancelSupportAvailabilityForClose(c,null,save,issued.context().runId(),issued));
                                require(before.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status()),"Support close refusal mutated authority/core");
                            },() -> { requireClient(c); requireReleased(c); },() -> {
                                PredictionFaults.cancelSupportAvailabilityForClose(c,server,save,issued.context().runId(),issued);
                                var after=PredictionFaults.resetSnapshot();
                                PredictionFaults.cancelSupportAvailabilityForClose(c,server,save,issued.context().runId(),issued);
                                require(after.equals(PredictionFaults.resetSnapshot()) && status.equals(MapArtClient.status()),"Repeated owned support close mutated authority/core");
                                writeNew(roots,artifacts.resolve("support-gate-close.txt"),after.support()+"\nactual exact-identity/native authority refusals and idempotent owned cleanup");
                            });
                        }
                    });
                },
                () -> context.runOnClient(c -> {
                    requireClient(c);
                    restoreCallerOptions(c);
                }),
                () -> context.runOnClient(c -> { requireClient(c); watch.closeWorld(); }),
                () -> {
                    world.close(); closed=true;
                    writeNew(roots,artifacts.resolve("world-closed.txt"),"WORLD CLOSED normally; invocation="+invocation+"; save="+save+"; runs="+runs);
                }); // Keep identities through native close, even if an earlier cleanup failed.
        closed=true;
        normalClose=true;
    }

    private void restoreCallerOptions(MinecraftClient c) {
        if(!optionsOwned) return;
        c.options.getAutoJump().setValue(callerAutoJump);
        if(!baseline()) { c.options.getViewDistance().setValue(callerViewDistance); c.options.sendClientSettings(); }
        optionsOwned=false;
    }

    private void closeCompact() {
        if(world==null) {
            sealCompact(); archiveCompact(artifacts.resolve("close-archive-"+resetOrdinal),sealed);
            closed=true; normalClose=true; return;
        }
        preflight(admitted==null?profileDefinition:admitted);
        Throwable first=null; boolean archived=false;
        try {
            ready=false;
            context.runOnClient(c->{ requireClient(c); MapArtClient.stop(); requireReleased(c); });
            sealCompact();
            archiveCompact(artifacts.resolve("close-archive-"+resetOrdinal),sealed);
            archived=compactCommitted;
        } catch(RuntimeException | Error error) { first=retain(first,error); }
        // Caller-owned options can be restored even when evidence must retain world/watch identities.
        try {
            context.runOnClient(c->{ requireClient(c); restoreCallerOptions(c); });
        } catch(RuntimeException | Error error) { first=retain(first,error); }
        if(archived) {
            try {
                preflight(admitted==null?profileDefinition:admitted);
                context.runOnClient(c->{ requireClient(c); PredictionFaults.clear(); watch.clear(); watch.closeWorld(); });
                world.close(); closed=true;
                writeInvocationFile(artifacts.resolve("world-closed.txt"),"WORLD CLOSED normally; invocation="+invocation+"; save="+save+"; runs="+runs);
            } catch(RuntimeException | Error error) { first=retain(first,error); }
        }
        if(first instanceof Error error) throw error;
        if(first!=null) throw (RuntimeException)first;
        require(closed,"Compact close did not reach committed owned world close");
        normalClose=true;
    }

    private static void closeSupport(Runnable evidence,Runnable authority,Runnable cancel) {
        Throwable error=null;
        try { evidence.run(); } catch(RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        // Evidence failure cannot strand the live support owner while native cleanup authority is still available.
        try { authority.run(); cancel.run(); } catch(RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        if(error instanceof Error fatal) throw fatal;
        if(error!=null) throw (RuntimeException)error;
    }

    private static void closeOwned(boolean created, Runnable authority, Runnable stop, Runnable options,
                                   Runnable observer, Runnable close) {
        if (!created) return; // An unused invocation never owned an option mutation or a world.
        authority.run(); // Initial foreign authority forbids every cleanup callback.
        Throwable error=null;
        try { authority.run(); stop.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); options.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); observer.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); close.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        if (error instanceof Error fatal) throw fatal;
        if (error!=null) throw (RuntimeException)error;
    }

    private static Throwable retain(Throwable first, Throwable next) {
        if (first==null) return next;
        if (first!=next) {
            for (var prior:first.getSuppressed()) if (prior==next) return first;
            first.addSuppressed(next);
        }
        return first;
    }

    private static void verifyCloseGuards() {
        var mutations=new int[1]; Runnable mutation=() -> mutations[0]++;
        Runnable foreign=() -> { throw new AssertionError("changed close authority"); };
        closeOwned(false,foreign,mutation,mutation,mutation,mutation);
        require(mutations[0]==0,"Unused close reached an option/native mutation under changed authority");
        rejected("foreign close authority",() -> closeOwned(true,foreign,mutation,mutation,mutation,mutation));
        require(mutations[0]==0,"Foreign close reached a cleanup mutation");
        closeOwned(true,() -> { },mutation,mutation,mutation,mutation);
        require(mutations[0]==4,"Valid close positive control did not attempt all four cleanup steps");
        var steps=new ArrayList<String>();
        var first=new IllegalStateException("test-owned stop/release failure");
        var later=new AssertionError("test-owned observer cleanup failure");
        Throwable observed=null;
        try {
            closeOwned(true,() -> { },() -> { steps.add("stop"); throw first; },
                    () -> steps.add("options restored"),() -> { steps.add("observer"); throw later; },() -> steps.add("world close attempted"));
        } catch (RuntimeException | Error error) { observed=error; }
        require(observed==first && Arrays.equals(first.getSuppressed(),new Throwable[]{later})
                && steps.equals(List.of("stop","options restored","observer","world close attempted")),
                "Close lost primary/suppressed failure or skipped independently safe cleanup");
        var valid=new boolean[]{true}; var attempts=new int[1]; var writes=new int[1];
        Runnable changing=() -> { attempts[0]++; require(valid[0],"close authority changed between steps"); };
        rejected("changed authority between cleanup steps",() -> closeOwned(true,changing,
                () -> valid[0]=false,() -> writes[0]++,() -> writes[0]++,() -> writes[0]++));
        require(attempts[0]==5 && writes[0]==0,"Cleanup failed to recheck changed authority before remaining writes");
        for(boolean cancellationFails:List.of(false,true)) {
            var order=new ArrayList<String>();
            var cause=new IOException("injected before-close receipt failure");
            var receipt=new UncheckedIOException(cause);
            var cancellation=new IllegalStateException("injected support cancellation failure");
            Throwable caught=null;
            try {
                closeOwned(true,() -> { },() -> closeSupport(
                        () -> { order.add("before-close receipt"); throw receipt; },
                        () -> order.add("support authority rechecked"),
                        () -> { order.add("support disposal attempted"); if(cancellationFails) throw cancellation; }),
                        () -> order.add("options"),() -> order.add("observer"),() -> order.add("world close attempted"));
            } catch(RuntimeException | Error error) { caught=error; }
            require(caught==receipt && caught.getCause()==cause
                            && Arrays.equals(receipt.getSuppressed(),cancellationFails?new Throwable[]{cancellation}:new Throwable[0])
                            && order.equals(List.of("before-close receipt","support authority rechecked","support disposal attempted",
                                "options","observer","world close attempted")),
                    "Before-close receipt failure skipped support/world disposal or lost primary/suppressed error");
        }
        var disposal=new int[1];
        var receipt=new UncheckedIOException(new IOException("receipt failure before foreign support cleanup"));
        var changedAuthority=new IllegalStateException("support authority changed before cancellation");
        Throwable caught=null;
        try { closeSupport(() -> { throw receipt; },() -> { throw changedAuthority; },() -> disposal[0]++); }
        catch(RuntimeException | Error error) { caught=error; }
        require(caught==receipt && Arrays.equals(receipt.getSuppressed(),new Throwable[]{changedAuthority}) && disposal[0]==0,
                "Receipt failure bypassed fresh support authority or hid its primary error");
        System.out.println("ScenarioRunner unused/failed/foreign close self-checks passed");
    }

    private static void verifyFailureCaptureGuards(Roots r, Path check, ScenarioDefinition definition) {
        var owner=UUID.randomUUID(); var foreign=new UUID(owner.getMostSignificantBits(),owner.getLeastSignificantBits()^1L);
        // Pure diagnostic values, never a started run, import or native world.
        var plan=new BuildPlan("failure-capture-selfcheck",new BuildPlan.GridSize(1,1,1),List.of());
        var origin=new GridPos(0,65,0); var settings=BuildEngine.Settings.defaults();
        var noWorld=failureMetadata(owner,0,false,null,null,null,null,false,Set.of(),null,null,foreign);
        require(noWorld.get("stage").getAsString().equals("NO_OWNED_WORLD") && !b(noWorld,"definitionBasedLiveAvailable")
                        && noWorld.get("admittedDefinitionHash").isJsonNull() && noWorld.get("observedStableStartHash").isJsonNull()
                        && !b(noWorld,"retainedCheckpointFacadeRunOwned"),"No-world metadata invented admission, observation or run ownership");
        var beforeAdmission=failureMetadata(owner,1,true,null,null,null,null,false,Set.of(),null,null,foreign);
        require(beforeAdmission.get("stage").getAsString().equals("PRE_ADMISSION") && !b(beforeAdmission,"definitionBasedLiveAvailable"),
                "Pre-admission failure selected definition-based live capture");
        var notReady=failureMetadata(owner,1,true,definition,plan,origin,settings,false,Set.of(),null,null,foreign);
        require(notReady.get("stage").getAsString().equals("ADMITTED_NOT_READY") && b(notReady,"definitionBasedLiveAvailable")
                        && !b(notReady,"ready"),"Failing readiness lost actual admitted live evidence");
        var ready=failureMetadata(owner,1,true,definition,plan,origin,settings,true,Set.of(),null,"checkpoint-selfcheck",foreign);
        require(ready.get("stage").getAsString().equals("READY") && b(ready,"definitionBasedLiveAvailable")
                        && !b(ready,"retainedCheckpointFacadeRunOwned"),"Ready metadata adopted a prior provider's facade UUID");
        var prior=new ScenarioResult(owner,new BuildEngine().snapshot(),definition.definitionHash(),"checkpoint-selfcheck",List.of(),check);
        var started=failureMetadata(owner,1,true,definition,plan,origin,settings,false,Set.of(owner),prior,"checkpoint-selfcheck",foreign);
        require(started.get("stage").getAsString().equals("ADMITTED_NOT_READY") && b(started,"definitionBasedLiveAvailable")
                        && b(started,"previousResultOwned") && !b(started,"retainedCheckpointFacadeRunOwned"),
                "Consumed readiness suppressed owned-run live evidence or adopted a foreign facade run");
        // The next reset clears ready and may fail before incrementing ordinal: exactly the same held tuple as after start.
        var earlyNextReset=failureMetadata(owner,1,true,definition,plan,origin,settings,false,Set.of(owner),prior,"checkpoint-selfcheck",foreign);
        require(earlyNextReset.equals(started) && i(earlyNextReset,"resetOrdinal")==1
                        && earlyNextReset.getAsJsonArray("ownedRunIds").size()==1
                        && earlyNextReset.get("stageProvenance").getAsString().contains("later reset before/after ordinal increment"),
                "Equal owned-run/reset counters invented start-versus-next-reset operation timing");
        var retained=failureMetadata(owner,2,true,definition,plan,origin,settings,false,Set.of(owner),prior,"checkpoint-selfcheck",owner);
        require(retained.get("stage").getAsString().equals("ADMITTED_NOT_READY") && b(retained,"definitionBasedLiveAvailable")
                        && retained.get("observedStableStartHash").getAsString().equals("checkpoint-selfcheck")
                        && retained.get("observedHashProvenance").getAsString().contains("may predate current reset") && !b(retained,"ready"),
                "Later reset relabelled retained admission/checkpoint as fresh validated readiness");
        require(!failureLiveAvailable(true,definition,null,origin,settings)
                        && !failureLiveAvailable(true,definition,plan,null,settings)
                        && !failureLiveAvailable(true,definition,plan,origin,null),"Missing request prerequisite permitted live capture");
        var primary=new IllegalStateException("failure-capture-primary");
        var first=new IllegalStateException("failure-capture-metadata-injection");
        var second=new AssertionError("failure-capture-screenshot-injection");
        var closeFirst=new IllegalStateException("failure-capture-later-close-injection");
        var closeSecond=new AssertionError("failure-capture-later-observer-close-injection");
        var completed=new ArrayList<String>();
        var initialFile=check.resolve("failure-selfcheck-initial.txt"); var afterFile=check.resolve("failure-selfcheck-after-capture.txt");
        capture(primary,() -> writeNew(r,initialFile,trace(primary)));
        capture(primary,() -> { throw first; });
        capture(primary,() -> { throw second; });
        capture(primary,() -> completed.add("independent observer"));
        capture(primary,() -> writeNew(r,afterFile,trace(primary)));
        capture(primary,() -> closeOwned(true,() -> { },() -> { throw closeFirst; },
                () -> completed.add("owned options"),() -> { throw closeSecond; },() -> completed.add("owned close")));
        require(retain(primary,first)==primary && Arrays.equals(primary.getSuppressed(),new Throwable[]{first,second,closeFirst})
                        && Arrays.equals(closeFirst.getSuppressed(),new Throwable[]{closeSecond})
                        && completed.equals(List.of("independent observer","owned options","owned close")),
                "Capture/close failure replaced primary or prevented independent evidence/cleanup attempts");
        try {
            var initial=Files.readString(initialFile); var after=Files.readString(afterFile);
            require(!initial.contains(first.getMessage()) && after.contains(first.getMessage()) && after.contains(second.getMessage())
                            && !after.contains(closeFirst.getMessage()),"Failure files misrepresented capture/late-close serialization order");
            rejected("initial failure file is immutable",() -> writeNew(r,initialFile,"overwrite refused"));
            require(Files.readString(initialFile).equals(initial),"Initial primary failure file was overwritten");
        } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("ScenarioRunner failure-selection/metadata/capture self-checks passed");
    }

    private static Roots roots() {
        try {
            var source=Path.of(ScenarioRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(Files.isDirectory(source) && source.endsWith(Path.of("build","classes","java","gametest")), "Unsupported loaded fixture origin: " + source);
            require(source.equals(source.toRealPath()), "Loaded module origin redirected");
            var module=source.getParent().getParent().getParent().getParent().toRealPath();
            require(module.endsWith(Path.of("v2","fabric")) && Files.isRegularFile(module.resolve("build.gradle")), "Unsupported module root");
            var definition=Path.of(ScenarioDefinition.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(source.equals(definition), "Definition loaded from foreign module");
            var build=module.resolve("build").toRealPath(); var game=build.resolve("run/clientGameTest").toRealPath();
            var actual=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            require(actual.equals(game) && actual.toRealPath().equals(game) && game.equals(build.resolve("run/clientGameTest")), "Unmanaged/redirected game root");
            return new Roots(module,game,build,build.resolve("scenario-results"),game.resolve("saves"));
        } catch (java.net.URISyntaxException | IOException e) { throw new IllegalStateException("Cannot establish loaded-module authority",e); }
    }

    private static void guarded(UUID owner,UUID token,Roots r,Path game,String expectedHash,String hash,JsonObject d,Runnable mutation) {
        guarded(owner,token,r,game,ScenarioDefinition.load(),ScenarioDefinition.Fixture.TWO_LANE,expectedHash,hash,d,mutation);
    }

    private static void guarded(UUID owner,UUID token,Roots r,Path game,ScenarioDefinition authority,ScenarioDefinition.Fixture fixture,
                                String expectedHash,String hash,JsonObject d,Runnable mutation) {
        require(owner.equals(token),"Wrong invocation token"); checkRoots(r);
        require(game.toAbsolutePath().normalize().equals(r.game) && real(game).equals(r.game),"Wrong/redirected game path");
        require(expectedHash.equals(authority.definitionHash()) && expectedHash.equals(hash),"Wrong definition hash");
        authority.requireValues(fixture,hash,d);
        var w=d.getAsJsonObject("world"); var region=w.getAsJsonObject("ownedRegion");
        var min=authority.ownedMin(); var max=authority.ownedMax();
        require(grid(region.getAsJsonObject("min")).equals(min) && grid(region.getAsJsonObject("max")).equals(max),"Region exceeds authority");
        require(w.getAsJsonArray("containers").isEmpty(),"Containers unsupported");
        for(var cell:w.getAsJsonArray("blocks")) {
            var at=grid(cell.getAsJsonObject().getAsJsonObject("position"));
            require(at.x()>=min.x() && at.x()<=max.x() && at.y()>=min.y() && at.y()<=max.y()
                    && at.z()>=min.z() && at.z()<=max.z(),"Block outside authority");
        }
        mutation.run();
    }

    private static void checkRoots(Roots r) {
        require(real(r.module).equals(r.module) && real(r.build).equals(r.build) && real(r.game).equals(r.game)
                && r.build.equals(r.module.resolve("build")) && r.game.equals(r.build.resolve("run/clientGameTest")),"Redirected invocation roots");
        if (Files.exists(r.evidence)) require(real(r.evidence).equals(r.evidence),"Redirected evidence root");
        require(r.saves.equals(r.game.resolve("saves")),"Saves parent escaped authorized game root");
        if (Files.exists(r.saves,LinkOption.NOFOLLOW_LINKS))
            require(Files.isDirectory(r.saves) && real(r.saves).equals(r.saves),"Redirected saves parent");
    }
    private static void guardedWorldCreation(Roots r, Path requested, Path resolved, Runnable create) {
        checkRoots(r);
        require(requested.toAbsolutePath().normalize().equals(r.saves) && resolved.equals(r.saves),"Wrong/redirected saves parent before native creation");
        create.run();
    }
    private static void checkedDirectory(Roots r, Path path) {
        checkRoots(r); require(path.toAbsolutePath().normalize().startsWith(r.evidence),"Evidence path escape");
        try {
            // Validate each existing parent before creating the next directory, including reparse resolution.
            Path current=r.build;
            for (var segment:r.build.relativize(path)) {
                current=current.resolve(segment);
                if (!Files.exists(current)) Files.createDirectory(current);
                require(real(current).equals(current),"Redirected archive directory: "+current);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void writeNew(Roots r, Path file, String text) {
        checkRoots(r); require(real(file.getParent()).equals(file.getParent()) && file.startsWith(r.evidence),"Archive write escaped");
        try { Files.writeString(file,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void writeLinesNew(Path file,Iterable<String> lines) {
        checkRoots(roots); require(real(file.getParent()).equals(file.getParent()) && file.startsWith(roots.evidence),"Archive write escaped");
        try(var writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            for(var line:lines) { writer.write(line); writer.write('\n'); }
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
    private static void copyNew(Roots r, Path from, Path to) {
        checkRoots(r); require(real(to.getParent()).equals(to.getParent()) && to.startsWith(r.evidence)
                && real(from).startsWith(r.game),"Archive copy escaped");
        try { Files.copy(from,to); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static Path real(Path path) { try { return path.toRealPath(); } catch (IOException e) { throw new UncheckedIOException(e); } }
    private Box regionBox() {
        var min=profileDefinition.ownedMin(); var max=profileDefinition.ownedMax();
        return new Box(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1);
    }
    private static GridPos grid(JsonObject p) { return new GridPos(i(p,"x"),i(p,"y"),i(p,"z")); }
    private static BlockPos pos(JsonObject p) { return new BlockPos(i(p,"x"),i(p,"y"),i(p,"z")); }
    private static int i(JsonObject o,String key) { return o.get(key).getAsInt(); }
    private static double n(JsonObject o,String key) { return o.get(key).getAsDouble(); }
    private static boolean b(JsonObject o,String key) { return o.get(key).getAsBoolean(); }
    private static ItemStack stack(JsonObject slot) {
        int count=i(slot,"count"); if (count==0) return ItemStack.EMPTY;
        var id=Identifier.of(slot.get("item").getAsString()); require(Registries.ITEM.containsId(id),"Unknown fixture item");
        return new ItemStack(Registries.ITEM.get(id),count);
    }
    private static BlockState block(JsonObject value) {
        require(value.getAsJsonObject("properties").isEmpty(),"Fixed fixture block properties unsupported");
        return switch (value.get("id").getAsString()) {
            case "minecraft:air" -> Blocks.AIR.getDefaultState(); case "minecraft:stone" -> Blocks.STONE.getDefaultState();
            case "minecraft:bedrock" -> Blocks.BEDROCK.getDefaultState(); default -> throw new AssertionError("Unsupported fixed fixture block");
        };
    }
    private static PlayerAbilities.Packed abilities(JsonObject p) {
        var a=p.getAsJsonObject("abilities"); return new PlayerAbilities.Packed(b(a,"invulnerable"),b(a,"flying"),b(a,"allowFlying"),
                b(a,"creativeMode"),b(a,"allowModifyWorld"),(float)n(a,"flySpeed"),(float)n(a,"walkSpeed"));
    }
    private static JsonElement sorted(JsonElement e) {
        if (e.isJsonObject()) { var o=new JsonObject(); new TreeMap<>(e.getAsJsonObject().asMap()).forEach((k,v) -> o.add(k,sorted(v))); return o; }
        if (e.isJsonArray()) { var a=new JsonArray(); e.getAsJsonArray().forEach(v -> a.add(sorted(v))); return a; } return e;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void rejected(String name,Runnable action) {
        try { action.run(); } catch (RuntimeException | AssertionError expected) {
            System.out.println("ScenarioRunner expected refusal ["+name+"]: "+expected); return;
        } throw new AssertionError("Expected refusal: " + name);
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
