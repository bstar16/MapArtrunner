package com.example.mapart.command;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.sweep.LeftoverTracker;
import com.example.mapart.plan.sweep.SingleLaneSweepDebugRunner;
import com.example.mapart.plan.sweep.SweepPassResult;
import com.example.mapart.plan.sweep.grounded.GroundedSingleLaneDebugRunner;
import com.example.mapart.plan.sweep.grounded.GroundedSweepLeftoverTracker;
import com.example.mapart.plan.sweep.grounded.GroundedSweepSettings;
import com.example.mapart.runtime.ClientTimerController;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.plan.state.RefillStatus;
import com.example.mapart.supply.SupplyInteractionTracker;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import com.example.mapart.util.MaterialCountFormatter;

public final class MapArtCommand {
    public static final String PRIMARY_COMMAND = "mapart";
    public static final String LEGACY_ALIAS = "maprunner";
    public static final String MOD_NAME_ALIAS = "mapartrunner";

    private MapArtCommand() {
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> create(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker,
            BaritoneFacade baritoneFacade
    ) {
        return createForName(PRIMARY_COMMAND, planService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> createAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker,
            BaritoneFacade baritoneFacade
    ) {
        return createForName(LEGACY_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> createRunnerAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker,
            BaritoneFacade baritoneFacade
    ) {
        return createForName(MOD_NAME_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createForName(
            String commandName,
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker,
            BaritoneFacade baritoneFacade
    ) {
        return ClientCommandManager.literal(commandName)
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String rawPath = StringArgumentType.getString(context, "path");
                                    Path path = Path.of(rawPath).toAbsolutePath().normalize();
                                    try {
                                        BuildPlan plan = planService.load(path);
                                        context.getSource().sendFeedback(Text.literal("Loaded plan " + path.getFileName() + " ("
                                                + plan.placements().size() + " placements, "
                                                + plan.regions().size() + " regions)."));
                                        return 1;
                                    } catch (Exception exception) {
                                        context.getSource().sendError(Text.literal("Failed to load plan: " + exception.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(ClientCommandManager.literal("unload")
                        .executes(context -> {
                            if (!planService.unload()) {
                                context.getSource().sendFeedback(Text.literal("No build plan loaded."));
                                return 0;
                            }

                            context.getSource().sendFeedback(Text.literal("Unloaded current build plan and cleared session progress."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("info")
                        .executes(context -> showPlanInfo(planService, commandName, context.getSource())))
                .then(ClientCommandManager.literal("setorigin")
                        .executes(context -> setOrigin(commandName, planService, context.getSource(), null))
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> setOrigin(
                                                        commandName,
                                                        planService,
                                                        context.getSource(),
                                                        new BlockPos(
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z")
                                                        )))))))
                .then(ClientCommandManager.literal("status")
                        .executes(context -> showStatus(planService, context.getSource())))
                .then(ClientCommandManager.literal("start")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().start();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }

                            context.getSource().sendFeedback(Text.literal("Build session started."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("pause")
                        .executes(context -> pauseBuild(context.getSource(), planService)))
                .then(ClientCommandManager.literal("resume")
                        .executes(context -> resumeBuild(context.getSource(), planService)))
                .then(ClientCommandManager.literal("stop")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().stop();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }

                            context.getSource().sendFeedback(Text.literal("Build session stopped and progress reset."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("panic")
                        .executes(context -> panic(planService, context.getSource())))
                .then(ClientCommandManager.literal("next")
                        .executes(context -> {
                            BuildCoordinator.StepResult result = planService.coordinator().next(context.getSource().getClient());
                            if (!result.actionable() && !result.done()) {
                                context.getSource().sendError(Text.literal(result.message()));
                                return 0;
                            }

                            if (result.done()) {
                                planService.coordinator().unload();
                                context.getSource().sendFeedback(Text.literal("Build completed and schematic unloaded."));
                                return 1;
                            }

                            Placement placement = result.placement();
                            context.getSource().sendFeedback(Text.literal(
                                    "Next placement: " + Registries.BLOCK.getId(placement.block())
                                            + " at " + result.targetPos().toShortString()
                            ));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("supply")
                        .then(ClientCommandManager.literal("add")
                                .executes(context -> addSupply(context.getSource(), supplyInteractionTracker, null))
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> addSupply(context.getSource(), supplyInteractionTracker, StringArgumentType.getString(context, "name")))))
                        .then(ClientCommandManager.literal("list")
                                .executes(context -> listSupplies(context.getSource(), supplyStore)))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                        .executes(context -> removeSupply(context.getSource(), supplyStore, IntegerArgumentType.getInteger(context, "id")))))
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> clearSupplies(context.getSource(), supplyStore))))
                .then(ClientCommandManager.literal("settings")
                        .executes(context -> showSettings(context.getSource(), settingsStore))
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("key", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> setSetting(
                                                        context.getSource(),
                                                        settingsStore,
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value")
                                                ))))))
                .then(ClientCommandManager.literal("clienttimerspeed")
                        .then(ClientCommandManager.argument("multiplier", DoubleArgumentType.doubleArg(ClientTimerController.MIN_MULTIPLIER))
                                .executes(context -> setClientTimerSpeed(
                                        context.getSource(),
                                        DoubleArgumentType.getDouble(context, "multiplier")
                                ))))
                .then(ClientCommandManager.literal("debug")
                        .then(ClientCommandManager.literal("goto")
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> debugGoto(
                                                                context.getSource(),
                                                                baritoneFacade,
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "z")
                                                        ))))))
                        .then(ClientCommandManager.literal("cancel")
                                .executes(context -> debugCancel(context.getSource(), baritoneFacade)))
                        .then(ClientCommandManager.literal("busy")
                                .executes(context -> debugBusy(context.getSource(), baritoneFacade)))
                        .then(ClientCommandManager.literal("secondlast")
                                .executes(context -> {
                                    Optional<String> error = planService.coordinator().debugSkipToSecondLastPlacement();
                                    if (error.isPresent()) {
                                        context.getSource().sendError(Text.literal(error.get()));
                                        return 0;
                                    }

                                    context.getSource().sendFeedback(Text.literal("Debug: moved progress to the second last placement. Use /"
                                            + commandName + " next to continue."));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("sweep-single-lane")
                                .then(ClientCommandManager.literal("start")
                                        .then(ClientCommandManager.argument("lane", IntegerArgumentType.integer(0))
                                                .executes(context -> debugSweepSingleLaneStart(
                                                        context.getSource(),
                                                        planService,
                                                        IntegerArgumentType.getInteger(context, "lane")
                                                ))))
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> debugSweepSingleLaneStatus(context.getSource())))
                                .then(ClientCommandManager.literal("stop")
                                        .executes(context -> debugSweepSingleLaneStop(context.getSource()))))
                        .then(ClientCommandManager.literal("grounded-single-lane")
                                .then(ClientCommandManager.literal("start")
                                        .then(ClientCommandManager.argument("lane", IntegerArgumentType.integer(0))
                                                .executes(context -> debugGroundedSingleLaneStart(
                                                        context.getSource(),
                                                        planService,
                                                        settingsStore,
                                                        IntegerArgumentType.getInteger(context, "lane")
                                                ))))
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> debugGroundedSingleLaneStatus(context.getSource())))
                                .then(ClientCommandManager.literal("stop")
                                        .executes(context -> debugGroundedSingleLaneStop(context.getSource()))))
                );
    }


    private static int pauseBuild(FabricClientCommandSource source, BuildPlanService planService) {
        Optional<String> error = planService.coordinator().pause();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Build session paused."));
        return 1;
    }

    private static int resumeBuild(FabricClientCommandSource source, BuildPlanService planService) {
        Optional<String> error = planService.coordinator().resume();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Build session resumed."));
        return 1;
    }

    public static int panic(BuildPlanService planService, FabricClientCommandSource source) {
        BuildCoordinator.PanicResult result = triggerPanic(planService);
        if (!result.didAnything()) {
            source.sendFeedback(Text.literal("Panic button pressed, but nothing was active."));
            return 0;
        }

        StringBuilder message = new StringBuilder("Panic button pressed: cancelled active automation");
        if (result.unloadedPlan()) {
            message.append(", unloaded the current build plan");
        }
        message.append(".");
        source.sendFeedback(Text.literal(message.toString()));
        return 1;
    }

    public static BuildCoordinator.PanicResult triggerPanic(BuildPlanService planService) {
        return planService.coordinator().panicUnload();
    }

    private static int debugGoto(FabricClientCommandSource source, BaritoneFacade baritoneFacade, int x, int y, int z) {
        BaritoneFacade.CommandResult result = baritoneFacade.goTo(new BlockPos(x, y, z));
        if (!result.success()) {
            source.sendError(Text.literal(result.message()));
            return 0;
        }

        source.sendFeedback(Text.literal(result.message()));
        return 1;
    }

    private static int debugCancel(FabricClientCommandSource source, BaritoneFacade baritoneFacade) {
        BaritoneFacade.CommandResult result = baritoneFacade.cancel();
        if (!result.success()) {
            source.sendError(Text.literal(result.message()));
            return 0;
        }

        source.sendFeedback(Text.literal(result.message()));
        return 1;
    }

    private static int debugBusy(FabricClientCommandSource source, BaritoneFacade baritoneFacade) {
        source.sendFeedback(Text.literal("Baritone busy: " + (baritoneFacade.isBusy() ? "yes" : "no")));
        return 1;
    }

    private static int debugSweepSingleLaneStart(FabricClientCommandSource source, BuildPlanService planService, int laneIndex) {
        SingleLaneSweepDebugRunner runner = MapArtRuntime.singleLaneSweepDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Sweep debug runner is unavailable."));
            return 0;
        }

        Optional<BuildSession> session = planService.currentSession();
        if (session.isEmpty()) {
            source.sendError(Text.literal("No build plan loaded."));
            return 0;
        }

        Optional<String> error = runner.start(session.get(), laneIndex);
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }
        source.sendFeedback(Text.literal("Started debug single-lane elytra sweep on lane " + laneIndex + "."));
        return 1;
    }

    private static int debugSweepSingleLaneStatus(FabricClientCommandSource source) {
        SingleLaneSweepDebugRunner runner = MapArtRuntime.singleLaneSweepDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Sweep debug runner is unavailable."));
            return 0;
        }

        Optional<SweepPassResult> result = runner.activeResult();
        if (result.isEmpty()) {
            result = runner.lastResult();
        }
        if (result.isEmpty()) {
            source.sendFeedback(Text.literal("No single-lane debug sweep run has been started yet."));
            return 0;
        }

        SweepPassResult sweep = result.get();
        source.sendFeedback(Text.literal("Single-lane sweep state=" + sweep.finalState()
                + ", ticks=" + sweep.ticksElapsed()
                + ", success=" + sweep.successCount()
                + ", missed=" + sweep.missedCount()
                + ", deferred=" + sweep.deferredCount()
                + ", leftovers=" + sweep.leftoverPlacementIndices().size()));
        if (!sweep.flightFailureReason().isEmpty()) {
            source.sendFeedback(Text.literal("Flight failure reason: " + sweep.flightFailureReason().get()));
        }
        if (!sweep.leftoverRecords().isEmpty()) {
            LeftoverTracker.LeftoverRecord record = sweep.leftoverRecords().getFirst();
            source.sendFeedback(Text.literal("Example leftover #" + record.placementIndex() + " reasons=" + record.reasons()));
        }
        return 1;
    }

    private static int debugSweepSingleLaneStop(FabricClientCommandSource source) {
        SingleLaneSweepDebugRunner runner = MapArtRuntime.singleLaneSweepDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Sweep debug runner is unavailable."));
            return 0;
        }
        Optional<String> error = runner.stop();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }
        source.sendFeedback(Text.literal("Stopped debug single-lane elytra sweep."));
        return 1;
    }


    private static int debugGroundedSingleLaneStart(FabricClientCommandSource source,
                                                     BuildPlanService planService,
                                                     MapartSettingsStore settingsStore,
                                                     int laneIndex) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }

        Optional<BuildSession> session = planService.currentSession();
        if (session.isEmpty()) {
            source.sendError(Text.literal("No build plan loaded."));
            return 0;
        }

        MapartSettings settings = settingsStore.current();
        GroundedSweepSettings groundedSettings = new GroundedSweepSettings(
                settings.preferLongerAxis(),
                settings.sweepHalfWidth(),
                settings.sweepTotalWidth(),
                settings.laneStride(),
                settings.forwardLookaheadSteps(),
                settings.trivialBehindCleanupSteps(),
                settings.groundedSweepConstantSprint(),
                1.0
        );

        Optional<String> error = runner.start(session.get(), laneIndex, groundedSettings);
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Started debug grounded single-lane sweep on lane " + laneIndex + "."));
        return 1;
    }

    private static int debugGroundedSingleLaneStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        source.sendFeedback(Text.literal((status.active() ? "Grounded (active)" : "Grounded (last)") + " lane=" + status.laneIndex()
                + ", state=" + status.walkState()
                + ", awaitingStartApproach=" + status.awaitingStartApproach()
                + ", ticks=" + status.ticksElapsed()
                + ", leftovers=" + status.leftovers().size()));
        status.failureReason().ifPresent(reason -> source.sendFeedback(Text.literal("Failure reason: " + reason)));
        if (!status.leftovers().isEmpty()) {
            GroundedSweepLeftoverTracker.GroundedLeftoverRecord leftover = status.leftovers().getFirst();
            source.sendFeedback(Text.literal("Example leftover #" + leftover.placementIndex() + " reasons=" + leftover.reasons()));
        }
        if (!status.active()) {
            return 0;
        }
        return 1;
    }

    private static int debugGroundedSingleLaneStop(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }

        Optional<String> error = runner.stop();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Stopped debug grounded single-lane sweep."));
        return 1;
    }

    private static int setOrigin(
            String commandName,
            BuildPlanService planService,
            FabricClientCommandSource source,
            BlockPos requestedOrigin
    ) {
        Optional<BuildSession> session = planService.currentSession();
        if (session.isEmpty()) {
            source.sendError(Text.literal("No build plan loaded. Use /" + commandName + " load <path> first."));
            return 0;
        }

        BlockPos origin = requestedOrigin == null ? BlockPos.ofFloored(source.getPosition()) : requestedOrigin;
        Optional<String> error = planService.coordinator().setOrigin(origin);
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Origin set to " + origin.toShortString()));
        return 1;
    }

    private static int showPlanInfo(BuildPlanService planService, String commandName, FabricClientCommandSource source) {
        BuildPlan plan = planService.currentPlan().orElse(null);
        if (plan == null) {
            source.sendError(Text.literal(
                    "No build plan loaded. Use /" + commandName + " load <path> first."
            ));
            return 0;
        }

        source.sendFeedback(Text.literal("Plan format: " + plan.sourceFormat() + ", source: " + plan.sourcePath()));
        source.sendFeedback(Text.literal("Dimensions: " + plan.dimensions().getX() + "x"
                + plan.dimensions().getY() + "x" + plan.dimensions().getZ()
                + ", placements: " + plan.placements().size()
                + ", chunk regions: " + plan.regions().size()));

        source.sendFeedback(Text.literal("Required materials:"));
        plan.materialCounts().entrySet().stream()
                .sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(entry -> source.sendFeedback(Text.literal("- "
                        + Registries.BLOCK.getId(entry.getKey()) + ": "
                        + MaterialCountFormatter.formatCount(entry.getValue(), entry.getKey().asItem()))));

        if (plan.materialCounts().size() > 10) {
            int remainder = plan.materialCounts().size() - 10;
            source.sendFeedback(Text.literal("... and " + remainder + " more materials."));
        }

        return 1;
    }

    private static int showStatus(BuildPlanService planService, FabricClientCommandSource source) {
        Optional<BuildCoordinator.SessionStatus> statusOptional = planService.coordinator().sessionStatus();
        if (statusOptional.isEmpty()) {
            source.sendFeedback(Text.literal("State: IDLE (no plan loaded)."));
            return 1;
        }

        BuildCoordinator.SessionStatus status = statusOptional.get();
        source.sendFeedback(Text.literal("Plan: " + status.planId()));
        source.sendFeedback(Text.literal("State: " + status.state()));
        source.sendFeedback(Text.literal("Origin: " + (status.origin() == null ? "not set" : status.origin().toShortString())));
        source.sendFeedback(Text.literal("Region: " + status.currentRegionIndex() + " / " + status.totalRegions()));
        source.sendFeedback(Text.literal("Placement: " + status.currentPlacementIndex() + " / " + status.totalPlacements()));
        source.sendFeedback(Text.literal("Completed placements: " + status.totalCompletedPlacements()));

        if (status.refillStatus().isPresent()) {
            RefillStatus refillStatus = status.refillStatus().get();
            if (refillStatus.supplyPoint() == null) {
                source.sendFeedback(Text.literal("Refill target: none in current dimension"));
            } else {
                source.sendFeedback(Text.literal("Refill target: #" + refillStatus.supplyPoint().id() + " @ "
                        + refillStatus.supplyPoint().pos().toShortString()));
            }
            source.sendFeedback(Text.literal("Missing materials:"));
            refillStatus.missingMaterials().entrySet().stream()
                    .sorted(Map.Entry.<net.minecraft.util.Identifier, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(entry -> {
                        Item item = Registries.ITEM.get(entry.getKey());
                        source.sendFeedback(Text.literal("- " + entry.getKey() + ": "
                                + MaterialCountFormatter.formatCount(entry.getValue(), item)));
                    });
        }

        if (status.nextTarget().isPresent()) {
            BuildCoordinator.NextTarget nextTarget = status.nextTarget().get();
            source.sendFeedback(Text.literal("Next block: " + Registries.BLOCK.getId(nextTarget.placement().block())));
            source.sendFeedback(Text.literal("Next target: " + nextTarget.absolutePos().toShortString()));
        } else {
            source.sendFeedback(Text.literal("Next block: none"));
            source.sendFeedback(Text.literal("Next target: none"));
        }

        return 1;
    }

    private static int addSupply(FabricClientCommandSource source, SupplyInteractionTracker supplyInteractionTracker, String name) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Supply registration requires a player."));
            return 0;
        }

        supplyInteractionTracker.beginRegistration(player, name);
        source.sendFeedback(Text.literal("Right-click a container to register a supply point"
                + (name == null ? "." : " named '" + name + "'.")));
        return 1;
    }

    private static int listSupplies(FabricClientCommandSource source, SupplyStore supplyStore) {
        var supplies = supplyStore.list();
        if (supplies.isEmpty()) {
            source.sendFeedback(Text.literal("No supplies registered."));
            return 1;
        }

        source.sendFeedback(Text.literal("Supply points (" + supplies.size() + ")"));
        for (SupplyPoint point : supplies) {
            source.sendFeedback(Text.literal("#" + point.id() + " " + point.pos().toShortString() + " " + point.dimensionKey()
                    + (point.name() == null ? "" : " - " + point.name())));
        }
        return 1;
    }

    private static int removeSupply(FabricClientCommandSource source, SupplyStore supplyStore, int id) {
        if (!supplyStore.removeById(id)) {
            source.sendError(Text.literal("Supply id not found: " + id));
            return 0;
        }

        source.sendFeedback(Text.literal("Removed supply #" + id));
        return 1;
    }

    private static int clearSupplies(FabricClientCommandSource source, SupplyStore supplyStore) {
        int removed = supplyStore.clear();
        source.sendFeedback(Text.literal("Cleared " + removed + " supply point(s)."));
        return 1;
    }

    private static int showSettings(FabricClientCommandSource source, MapartSettingsStore settingsStore) {
        MapartSettings settings = settingsStore.current();
        source.sendFeedback(Text.literal("showHud=" + settings.showHud()));
        source.sendFeedback(Text.literal("showSchematicOverlay=" + settings.showSchematicOverlay()));
        source.sendFeedback(Text.literal("overlayCurrentRegionOnly=" + settings.overlayCurrentRegionOnly()));
        source.sendFeedback(Text.literal("overlayShowOnlyIncorrect=" + settings.overlayShowOnlyIncorrect()));
        source.sendFeedback(Text.literal("hudCompact=" + settings.hudCompact()));
        source.sendFeedback(Text.literal("clientTimerSpeed=" + settings.clientTimerSpeed()));
        source.sendFeedback(Text.literal("hudX=" + settings.hudX()));
        source.sendFeedback(Text.literal("hudY=" + settings.hudY()));
        source.sendFeedback(Text.literal("clientTimerSpeed=" + ClientTimerController.getMultiplier()));
        return 1;
    }

    private static int setSetting(FabricClientCommandSource source, MapartSettingsStore settingsStore, String key, String value) {
        Optional<String> error = settingsStore.set(key, value);
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(Text.literal("Updated " + key + " = " + value));
        return 1;
    }

    private static int setClientTimerSpeed(FabricClientCommandSource source, double multiplier) {
        ClientTimerController.setMultiplier(multiplier);
        source.sendFeedback(Text.literal("Client timer speed set to " + ClientTimerController.getMultiplier() + "x."));
        return 1;
    }
}
