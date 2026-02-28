package com.example.mapart.command;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildSession;
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
                        .executes(context -> {
                            Optional<BuildSession> session = planService.currentSession();
                            if (session.isEmpty()) {
                                context.getSource().sendError(Text.literal("No build plan loaded. Use /" + commandName + " load <path> first."));
                                return 0;
                            }

                            BlockPos playerPos = BlockPos.ofFloored(context.getSource().getPosition());
                            Optional<String> error = planService.coordinator().setOrigin(playerPos);
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }

                            context.getSource().sendFeedback(Text.literal("Origin set to " + playerPos.toShortString()));
                            return 1;
                        }))
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
                        .executes(context -> pauseBuild(context.getSource(), planService, baritoneFacade)))
                .then(ClientCommandManager.literal("resume")
                        .executes(context -> resumeBuild(context.getSource(), planService, baritoneFacade)))
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
                );
    }


    private static int pauseBuild(FabricClientCommandSource source, BuildPlanService planService, BaritoneFacade baritoneFacade) {
        Optional<String> error = planService.coordinator().pause();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        BaritoneFacade.CommandResult result = baritoneFacade.pause();
        if (!result.success()) {
            source.sendError(Text.literal("Build session paused, but failed to pause Baritone: " + result.message()));
            return 0;
        }

        source.sendFeedback(Text.literal("Build session paused."));
        return 1;
    }

    private static int resumeBuild(FabricClientCommandSource source, BuildPlanService planService, BaritoneFacade baritoneFacade) {
        Optional<String> error = planService.coordinator().resume();
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        BaritoneFacade.CommandResult result = baritoneFacade.resume();
        if (!result.success()) {
            source.sendFeedback(Text.literal("Build session resumed."));
            source.sendError(Text.literal("Baritone was not resumed: " + result.message()));
            return 1;
        }

        source.sendFeedback(Text.literal("Build session resumed."));
        return 1;
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
                        + Registries.BLOCK.getId(entry.getKey()) + ": " + entry.getValue())));

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
        source.sendFeedback(Text.literal("hudX=" + settings.hudX()));
        source.sendFeedback(Text.literal("hudY=" + settings.hudY()));
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
