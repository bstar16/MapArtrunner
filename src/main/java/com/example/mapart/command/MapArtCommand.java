package com.example.mapart.command;

import com.example.mapart.gui.MapArtConfigScreen;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.sweep.grounded.GroundedRefillController;
import com.example.mapart.plan.sweep.grounded.GroundedRecoveryState;
import com.example.mapart.plan.sweep.grounded.GroundedSingleLaneDebugRunner;
import com.example.mapart.plan.sweep.grounded.GroundedSweepLeftoverTracker;
import com.example.mapart.plan.sweep.grounded.GroundedSweepSettings;
import com.example.mapart.plan.sweep.grounded.TorchGridSettings;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyInteractionTracker;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
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
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(PRIMARY_COMMAND, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> createAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(LEGACY_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> createRunnerAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(MOD_NAME_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createForName(
            String commandName,
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
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
                        .executes(context -> showGroundedStatus(context.getSource())))
                .then(ClientCommandManager.literal("start")
                        .executes(context -> {
                            GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
                            if (runner == null) {
                                context.getSource().sendError(Text.literal("Grounded sweep runner is unavailable."));
                                return 0;
                            }
                            Optional<BuildSession> session = planService.currentSession();
                            if (session.isEmpty()) {
                                context.getSource().sendError(Text.literal("No build plan loaded."));
                                return 0;
                            }
                            GroundedSweepSettings groundedSettings = groundedSettings(settingsStore.current());
                            runner.resetExhaustedStateForNewRun();
                            Optional<String> error = runner.startFullSweep(session.get(), groundedSettings);
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(Text.literal("Build started."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("pause")
                        .executes(context -> {
                            GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
                            if (runner == null) {
                                context.getSource().sendError(Text.literal("Grounded sweep runner is unavailable."));
                                return 0;
                            }
                            if (!runner.status().active()) {
                                context.getSource().sendError(Text.literal("No build is currently active."));
                                return 0;
                            }
                            Optional<String> error = runner.pauseForResume();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(Text.literal("Build paused. Use /mapart resume to continue from this point."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("resume")
                        .executes(context -> {
                            GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
                            if (runner == null) {
                                context.getSource().sendError(Text.literal("Grounded sweep runner is unavailable."));
                                return 0;
                            }
                            Optional<BuildSession> session = planService.currentSession();
                            if (session.isEmpty()) {
                                context.getSource().sendError(Text.literal("No build plan loaded."));
                                return 0;
                            }
                            GroundedSweepSettings groundedSettings = groundedSettings(settingsStore.current());
                            GroundedSingleLaneDebugRunner.ResumeStartResult result = runner.resumeFromPauseOrSmart(session.get(), groundedSettings);
                            if (result.error().isPresent()) {
                                context.getSource().sendError(Text.literal(result.error().get()));
                                return 0;
                            }
                            if (result.usedPausedSnapshot()) {
                                context.getSource().sendFeedback(Text.literal("Resuming paused build."));
                            } else if (result.fellBackToSmartScan()) {
                                context.getSource().sendFeedback(Text.literal("Paused resume point was unavailable; falling back to smart resume."));
                            } else {
                                context.getSource().sendFeedback(Text.literal("Build resumed."));
                            }
                            return 1;
                        }))
                .then(ClientCommandManager.literal("stop")
                        .executes(context -> {
                            GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
                            if (runner == null) {
                                context.getSource().sendError(Text.literal("Grounded sweep runner is unavailable."));
                                return 0;
                            }
                            runner.stop();
                            context.getSource().sendFeedback(Text.literal("Build stopped."));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("panic")
                        .executes(context -> panic(planService, context.getSource())))
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
                                .executes(context -> clearSupplies(context.getSource(), supplyStore)))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> groundedRefillSupplyStatus(context.getSource(), supplyStore))))
                .then(ClientCommandManager.literal("settings")
                        .executes(context -> openSettingsScreen(context.getSource(), settingsStore))
                        .then(ClientCommandManager.literal("show")
                                .executes(context -> showSettings(context.getSource(), settingsStore)))
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("key", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> setSetting(
                                                        context.getSource(),
                                                        settingsStore,
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value")
                                                ))))))
                .then(ClientCommandManager.literal("debug")
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> debugGroundedStatus(context.getSource())))
                        .then(ClientCommandManager.literal("grounded-trace")
                                .then(ClientCommandManager.literal("on")
                                        .executes(context -> groundedTraceSet(context.getSource(), true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(context -> groundedTraceSet(context.getSource(), false)))
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> groundedTraceStatus(context.getSource()))))
                        .then(ClientCommandManager.literal("diagnostics")
                                .then(ClientCommandManager.literal("on").executes(context -> groundedDiagnosticsSet(context.getSource(), true)))
                                .then(ClientCommandManager.literal("off").executes(context -> groundedDiagnosticsSet(context.getSource(), false)))
                                .then(ClientCommandManager.literal("status").executes(context -> groundedDiagnosticsStatus(context.getSource())))
                                .then(ClientCommandManager.literal("path").executes(context -> groundedDiagnosticsPath(context.getSource()))))
                        .then(ClientCommandManager.literal("grounded-recovery")
                                .then(ClientCommandManager.literal("status")
                                        .executes(context -> groundedRecoveryStatus(context.getSource()))))
                        .then(ClientCommandManager.literal("mismatches")
                                .executes(context -> debugMismatches(context.getSource(), 100))
                                .then(ClientCommandManager.argument("limit", IntegerArgumentType.integer(1, 10000))
                                        .executes(context -> debugMismatches(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                );
    }


    public static int panic(BuildPlanService planService, FabricClientCommandSource source) {
        BuildCoordinator.PanicResult result = triggerPanic(planService);
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        boolean runnerWasActive = runner != null && runner.status().active();
        if (runner != null) {
            runner.cancelRefillAndStop();
        }

        if (!result.didAnything() && !runnerWasActive) {
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

    private static int showGroundedStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null || !runner.status().active()) {
            source.sendFeedback(Text.literal("No build active."));
            return 0;
        }
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        source.sendFeedback(Text.literal("Lane: " + status.laneIndex()
                + ", phase: " + status.phase()
                + ", pending: " + status.pendingVerification()
                + ", refill: " + (runner.getRefillController().isActive() ? "yes" : "no")
                + ", recovery: " + (runner.getRecoveryState().isActive() ? "yes" : "no")));
        return 1;
    }

    private static int debugGroundedStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        if (!status.active()) {
            source.sendFeedback(Text.literal("No grounded sweep run is active. "
                    + "Last state=" + status.walkState()
                    + ", smartResumeUsed=" + status.smartResumeUsed()
                    + ", ticks=" + status.ticksElapsed()
                    + ", success=" + status.successfulPlacements()
                    + ", missed=" + status.missedPlacements()
                    + ", failed=" + status.failedPlacements()
                    + ", pendingVerification=" + status.pendingVerification()
                    + ", leftovers=" + status.leftovers().size()));
            status.resumePoint().ifPresent(point -> source.sendFeedback(Text.literal(
                    "Last resume lane=" + point.laneIndex()
                            + ", reason=" + point.reason()
                            + ", progress=" + point.progressCoordinate()
            )));
            status.failureReason().ifPresent(reason -> source.sendFeedback(Text.literal("Failure reason: " + reason)));
            return 0;
        }

        source.sendFeedback(Text.literal("Grounded lane=" + status.laneIndex()
                + ", phase=" + status.phase()
                + ", state=" + status.walkState()
                + ", smartResumeUsed=" + status.smartResumeUsed()
                + ", skippedCompletedLanes=" + status.skippedCompletedForwardLanes()
                + ", awaitingStartApproach=" + status.awaitingStartApproach()
                + ", awaitingLaneShift=" + status.awaitingLaneShift()
                + ", ticks=" + status.ticksElapsed()
                + ", success=" + status.successfulPlacements()
                + ", missed=" + status.missedPlacements()
                + ", failed=" + status.failedPlacements()
                + ", pendingVerification=" + status.pendingVerification()
                + ", leftovers=" + status.leftovers().size()));
        status.resumePoint().ifPresent(point -> source.sendFeedback(Text.literal(
                "Resume lane=" + point.laneIndex()
                        + ", reason=" + point.reason()
                        + ", progress=" + point.progressCoordinate()
                        + ", standing=" + point.standingPosition().toShortString()
        )));
        status.failureReason().ifPresent(reason -> source.sendFeedback(Text.literal("Failure reason: " + reason)));
        if (!status.leftovers().isEmpty()) {
            GroundedSweepLeftoverTracker.GroundedLeftoverRecord record = status.leftovers().getFirst();
            source.sendFeedback(Text.literal("Example leftover #" + record.placementIndex() + " reasons=" + record.reasons()));
        }
        return 1;
    }

    private static int groundedTraceSet(FabricClientCommandSource source, boolean enabled) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }
        runner.setGroundedTraceEnabled(enabled);
        source.sendFeedback(Text.literal("Grounded trace " + (enabled ? "enabled" : "disabled") + "."));
        return 1;
    }

    private static int groundedTraceStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }
        source.sendFeedback(Text.literal("Grounded trace is " + (runner.groundedTraceEnabled() ? "on" : "off") + "."));
        return 1;
    }

    private static int groundedDiagnosticsSet(FabricClientCommandSource source, boolean enabled) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) { source.sendError(Text.literal("Grounded sweep debug runner is unavailable.")); return 0; }
        runner.setGroundedDiagnosticsEnabled(enabled);
        source.sendFeedback(Text.literal("Grounded diagnostics " + (enabled ? "enabled" : "disabled") + "."));
        return 1;
    }

    private static int groundedDiagnosticsStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) { source.sendError(Text.literal("Grounded sweep debug runner is unavailable.")); return 0; }
        source.sendFeedback(Text.literal("Grounded diagnostics is " + (runner.groundedDiagnosticsEnabled() ? "on" : "off") + "."));
        return 1;
    }

    private static int groundedDiagnosticsPath(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) { source.sendError(Text.literal("Grounded sweep debug runner is unavailable.")); return 0; }
        source.sendFeedback(Text.literal("Grounded diagnostics path: " + runner.groundedDiagnosticsPath()));
        return 1;
    }

    private static int groundedRecoveryStatus(FabricClientCommandSource source) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }

        GroundedRecoveryState recoveryState = runner.getRecoveryState();
        source.sendFeedback(Text.literal("Recovery auto-resume: " + (recoveryState.isAutoResumeEnabled() ? "enabled" : "disabled")));

        if (!recoveryState.isActive()) {
            source.sendFeedback(Text.literal("No recovery is currently active."));
            return 1;
        }

        var snapshot = recoveryState.snapshot().orElseThrow();
        source.sendFeedback(Text.literal("Recovery active:"));
        source.sendFeedback(Text.literal("  Reason: " + snapshot.reason()));
        source.sendFeedback(Text.literal("  Lane: " + snapshot.activeLane().laneIndex() + " " + snapshot.laneDirection()));
        source.sendFeedback(Text.literal("  Pass phase: " + snapshot.passPhase()));
        source.sendFeedback(Text.literal("  Last safe progress: " + String.format("%.2f", snapshot.lastKnownSafeProgressCoordinate())));
        source.sendFeedback(Text.literal("  Player position: " + String.format("%.2f, %.2f, %.2f",
                snapshot.playerPosition().x, snapshot.playerPosition().y, snapshot.playerPosition().z)));

        if (recoveryState.isStabilizing()) {
            source.sendFeedback(Text.literal("  Status: Stabilizing..."));
        } else if (recoveryState.isRetrying()) {
            source.sendFeedback(Text.literal("  Status: Retrying auto-resume (waiting for player to stabilize)"));
        } else if (recoveryState.isReadyForAutoResume()) {
            source.sendFeedback(Text.literal("  Status: Ready for auto-resume"));
        }

        return 1;
    }

    private static int debugMismatches(FabricClientCommandSource source, int limit) {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner == null) {
            source.sendError(Text.literal("Grounded sweep debug runner is unavailable."));
            return 0;
        }
        String result = runner.debugMismatchScan(source.getWorld(), limit);
        source.sendFeedback(Text.literal(result));
        return 1;
    }

    private static int groundedRefillSupplyStatus(FabricClientCommandSource source, SupplyStore supplyStore) {
        java.util.List<SupplyPoint> supplies = supplyStore.list();
        if (supplies.isEmpty()) {
            source.sendFeedback(Text.literal("No supply containers registered."));
        } else {
            source.sendFeedback(Text.literal("Registered supply containers (" + supplies.size() + "):"));
            for (SupplyPoint s : supplies) {
                source.sendFeedback(Text.literal("  #" + s.id() + " '"
                        + (s.name() != null ? s.name() : "unnamed") + "' at "
                        + s.pos().toShortString() + " [" + s.dimensionKey() + "]"));
            }
        }
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        if (runner != null && runner.getRefillController().isActive()) {
            GroundedRefillController ctrl = runner.getRefillController();
            source.sendFeedback(Text.literal("Refill active: state=" + ctrl.state()
                    + ", target=" + ctrl.targetSupply()
                    .map(s -> "#" + s.id() + " at " + s.pos().toShortString())
                    .orElse("none")));
        } else {
            source.sendFeedback(Text.literal("No refill currently active."));
        }
        return 1;
    }

    private static GroundedSweepSettings groundedSettings(MapartSettings settings) {
        return new GroundedSweepSettings(
                settings.sweepHalfWidth(),
                settings.sweepTotalWidth(),
                settings.laneStride(),
                settings.forwardLookaheadSteps(),
                settings.trivialBehindCleanupSteps(),
                settings.groundedSweepConstantSprint(),
                settings.reservedHotbarSlots(),
                new TorchGridSettings(
                        settings.torchGridEnabled(),
                        settings.torchGridSpacing(),
                        settings.torchGridWarnMissingTorches(),
                        settings.torchGridMaxPlacementsPerTick()
                ),
                1.0
        );
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

    private static int openSettingsScreen(FabricClientCommandSource source, MapartSettingsStore settingsStore) {
        net.minecraft.client.MinecraftClient mc = source.getClient();
        mc.execute(() -> mc.setScreen(new MapArtConfigScreen(settingsStore, mc.currentScreen)));
        return 1;
    }

    private static int showSettings(FabricClientCommandSource source, MapartSettingsStore settingsStore) {
        MapartSettings settings = settingsStore.current();
        source.sendFeedback(Text.literal("showHud=" + settings.showHud()));
        source.sendFeedback(Text.literal("hudCompact=" + settings.hudCompact()));
        source.sendFeedback(Text.literal("showSchematicOverlay=" + settings.showSchematicOverlay()));
        source.sendFeedback(Text.literal("overlayCurrentRegionOnly=" + settings.overlayCurrentRegionOnly()));
        source.sendFeedback(Text.literal("overlayShowOnlyIncorrect=" + settings.overlayShowOnlyIncorrect()));
        source.sendFeedback(Text.literal("groundedSweepConstantSprint=" + settings.groundedSweepConstantSprint()));
        source.sendFeedback(Text.literal("placementDelayTicks=" + settings.placementDelayTicks()));
        source.sendFeedback(Text.literal("inventoryClickDelayTicks=" + settings.inventoryClickDelayTicks()));
        source.sendFeedback(Text.literal("reservedHotbarSlots=" + settings.reservedHotbarSlots()
                + (settings.reservedHotbarSlots() == 0
                ? " (no automated hotbar protection)"
                : " (protects user hotbar slots 1-" + settings.reservedHotbarSlots()
                + " for tools, food, pearls, rockets, pickaxe, ender chest, or future shulker slots)")));
        source.sendFeedback(Text.literal("clientTimerSpeed=" + settings.clientTimerSpeed()));
        source.sendFeedback(Text.literal("clientTimerEnabled=" + settings.clientTimerEnabled()));
        source.sendFeedback(Text.literal("torchGridEnabled=" + settings.torchGridEnabled()));
        source.sendFeedback(Text.literal("torchGridSpacing=" + settings.torchGridSpacing()));
        source.sendFeedback(Text.literal("torchGridWarnMissingTorches=" + settings.torchGridWarnMissingTorches()));
        source.sendFeedback(Text.literal("torchGridMaxPlacementsPerTick=" + settings.torchGridMaxPlacementsPerTick()));
        source.sendFeedback(Text.literal("manualAirPlaceEnabled=" + settings.manualAirPlaceEnabled()));
        source.sendFeedback(Text.literal("manualAirPlaceRender=" + settings.manualAirPlaceRender()));
        source.sendFeedback(Text.literal("manualAirPlaceUseCustomRange=" + settings.manualAirPlaceUseCustomRange()));
        source.sendFeedback(Text.literal("manualAirPlaceCustomRange=" + settings.manualAirPlaceCustomRange()));
        source.sendFeedback(Text.literal("manualAirPlaceRequireSneak=" + settings.manualAirPlaceRequireSneak()));
        source.sendFeedback(Text.literal("manualAirPlaceDisableWhileRunnerActive=" + settings.manualAirPlaceDisableWhileRunnerActive()));
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
}
