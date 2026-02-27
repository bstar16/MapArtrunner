package com.example.mapart.command;

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
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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

    public static LiteralArgumentBuilder<ServerCommandSource> create(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(PRIMARY_COMMAND, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(LEGACY_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createRunnerAlias(
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return createForName(MOD_NAME_ALIAS, planService, settingsStore, supplyStore, supplyInteractionTracker);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createForName(
            String commandName,
            BuildPlanService planService,
            MapartSettingsStore settingsStore,
            SupplyStore supplyStore,
            SupplyInteractionTracker supplyInteractionTracker
    ) {
        return CommandManager.literal(commandName)
                .then(CommandManager.literal("load")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String rawPath = StringArgumentType.getString(context, "path");
                                    Path path = Path.of(rawPath).toAbsolutePath().normalize();
                                    try {
                                        BuildPlan plan = planService.load(path, context.getSource());
                                        context.getSource().sendFeedback(
                                                () -> Text.literal("Loaded plan " + path.getFileName() + " ("
                                                        + plan.placements().size() + " placements, "
                                                        + plan.regions().size() + " regions)."),
                                                false
                                        );
                                        return 1;
                                    } catch (Exception exception) {
                                        context.getSource().sendError(Text.literal("Failed to load plan: " + exception.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(CommandManager.literal("unload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            if (!planService.unload()) {
                                context.getSource().sendFeedback(() -> Text.literal("No build plan loaded."), false);
                                return 0;
                            }

                            context.getSource().sendFeedback(() -> Text.literal("Unloaded current build plan and cleared session progress."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("info")
                        .executes(context -> showPlanInfo(planService, commandName, context.getSource())))
                .then(CommandManager.literal("setorigin")
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

                            context.getSource().sendFeedback(() -> Text.literal("Build origin set to " + playerPos.toShortString() + "."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("status")
                        .executes(context -> showStatus(planService, context.getSource())))
                .then(CommandManager.literal("start")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().start();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(() -> Text.literal("Build session started."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("pause")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().pause();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(() -> Text.literal("Build session paused."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("resume")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().resume();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(() -> Text.literal("Build session resumed."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("stop")
                        .executes(context -> {
                            Optional<String> error = planService.coordinator().stop();
                            if (error.isPresent()) {
                                context.getSource().sendError(Text.literal(error.get()));
                                return 0;
                            }
                            context.getSource().sendFeedback(() -> Text.literal("Build stopped and progress reset."), false);
                            return 1;
                        }))
                .then(CommandManager.literal("next")
                        .executes(context -> {
                            BuildCoordinator.StepResult result = planService.coordinator().next(context.getSource());
                            if (!result.actionable() && !result.done()) {
                                context.getSource().sendError(Text.literal(result.message()));
                                return 0;
                            }

                            if (result.done()) {
                                planService.coordinator().unload();
                                context.getSource().sendFeedback(() -> Text.literal("Build completed and schematic unloaded."), false);
                                return 1;
                            }

                            Placement placement = result.placement();
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Next placement: " + Registries.BLOCK.getId(placement.block())
                                            + " at " + result.targetPos().toShortString()
                            ), false);
                            return 1;
                        }))
                .then(CommandManager.literal("supply")
                        .then(CommandManager.literal("add")
                                .executes(context -> addSupply(context.getSource(), supplyInteractionTracker, null))
                                .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> addSupply(context.getSource(), supplyInteractionTracker, StringArgumentType.getString(context, "name")))))
                        .then(CommandManager.literal("list")
                                .executes(context -> listSupplies(context.getSource(), supplyStore)))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                                        .executes(context -> removeSupply(context.getSource(), supplyStore, IntegerArgumentType.getInteger(context, "id")))))
                        .then(CommandManager.literal("clear")
                                .executes(context -> clearSupplies(context.getSource(), supplyStore))))
                .then(CommandManager.literal("settings")
                        .executes(context -> showSettings(context.getSource(), settingsStore))
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("key", StringArgumentType.word())
                                        .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                                .executes(context -> setSetting(
                                                        context.getSource(),
                                                        settingsStore,
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value")
                                                ))))))
                .then(CommandManager.literal("debug")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("secondlast")
                                .executes(context -> {
                                    Optional<String> error = planService.coordinator().debugSkipToSecondLastPlacement();
                                    if (error.isPresent()) {
                                        context.getSource().sendError(Text.literal(error.get()));
                                        return 0;
                                    }

                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Debug: moved progress to the second last placement. Use /"
                                                    + commandName + " next to continue."),
                                            false
                                    );
                                    return 1;
                                }))
                        );
    }

    private static int showPlanInfo(BuildPlanService planService, String commandName, ServerCommandSource source) {
        BuildPlan plan = planService.currentPlan().orElse(null);
        if (plan == null) {
            source.sendError(Text.literal(
                    "No build plan loaded. Use /" + commandName + " load <path> first."
            ));
            return 0;
        }

        source.sendFeedback(
                () -> Text.literal("Plan format: " + plan.sourceFormat() + ", source: " + plan.sourcePath()),
                false
        );
        source.sendFeedback(
                () -> Text.literal("Dimensions: " + plan.dimensions().getX() + "x"
                        + plan.dimensions().getY() + "x" + plan.dimensions().getZ()
                        + ", placements: " + plan.placements().size()
                        + ", chunk regions: " + plan.regions().size()),
                false
        );

        source.sendFeedback(() -> Text.literal("Required materials:"), false);
        plan.materialCounts().entrySet().stream()
                .sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(entry -> source.sendFeedback(() -> Text.literal("- "
                        + Registries.BLOCK.getId(entry.getKey()) + ": " + entry.getValue()), false));

        if (plan.materialCounts().size() > 10) {
            int remainder = plan.materialCounts().size() - 10;
            source.sendFeedback(
                    () -> Text.literal("... and " + remainder + " more materials."),
                    false
            );
        }

        return 1;
    }

    private static int showStatus(BuildPlanService planService, ServerCommandSource source) {
        Optional<BuildCoordinator.SessionStatus> statusOptional = planService.coordinator().sessionStatus();
        if (statusOptional.isEmpty()) {
            source.sendFeedback(() -> Text.literal("State: IDLE (no plan loaded)."), false);
            return 1;
        }

        BuildCoordinator.SessionStatus status = statusOptional.get();
        source.sendFeedback(() -> Text.literal("Plan: " + status.planId()), false);
        source.sendFeedback(() -> Text.literal("State: " + status.state()), false);
        source.sendFeedback(() -> Text.literal("Origin: " + (status.origin() == null ? "not set" : status.origin().toShortString())), false);
        source.sendFeedback(() -> Text.literal("Region: " + status.currentRegionIndex() + " / " + status.totalRegions()), false);
        source.sendFeedback(() -> Text.literal("Placement: " + status.currentPlacementIndex() + " / " + status.totalPlacements()), false);
        source.sendFeedback(() -> Text.literal("Completed placements: " + status.totalCompletedPlacements()), false);

        if (status.nextTarget().isPresent()) {
            BuildCoordinator.NextTarget nextTarget = status.nextTarget().get();
            source.sendFeedback(() -> Text.literal("Next block: " + Registries.BLOCK.getId(nextTarget.placement().block())), false);
            source.sendFeedback(() -> Text.literal("Next target: " + nextTarget.absolutePos().toShortString()), false);
        } else {
            source.sendFeedback(() -> Text.literal("Next block: none"), false);
            source.sendFeedback(() -> Text.literal("Next target: none"), false);
        }

        return 1;
    }

    private static int addSupply(ServerCommandSource source, SupplyInteractionTracker supplyInteractionTracker, String name) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayerOrThrow();
        } catch (Exception exception) {
            source.sendError(Text.literal("Supply registration requires a player."));
            return 0;
        }

        supplyInteractionTracker.beginRegistration(player, name);
        source.sendFeedback(() -> Text.literal("Right-click a container to register a supply point"
                + (name == null ? "." : " named '" + name + "'.")), false);
        return 1;
    }

    private static int listSupplies(ServerCommandSource source, SupplyStore supplyStore) {
        var supplies = supplyStore.list();
        if (supplies.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No supplies registered."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Supply points (" + supplies.size() + ")"), false);
        for (SupplyPoint point : supplies) {
            source.sendFeedback(() -> Text.literal("#" + point.id() + " " + point.pos().toShortString() + " " + point.dimensionKey()
                    + (point.name() == null ? "" : " - " + point.name())), false);
        }
        return 1;
    }

    private static int removeSupply(ServerCommandSource source, SupplyStore supplyStore, int id) {
        if (!supplyStore.removeById(id)) {
            source.sendError(Text.literal("Supply id not found: " + id));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Removed supply #" + id), false);
        return 1;
    }

    private static int clearSupplies(ServerCommandSource source, SupplyStore supplyStore) {
        int removed = supplyStore.clear();
        source.sendFeedback(() -> Text.literal("Cleared " + removed + " supply point(s)."), false);
        return 1;
    }

    private static int showSettings(ServerCommandSource source, MapartSettingsStore settingsStore) {
        MapartSettings settings = settingsStore.current();
        source.sendFeedback(() -> Text.literal("showHud=" + settings.showHud()), false);
        source.sendFeedback(() -> Text.literal("showSchematicOverlay=" + settings.showSchematicOverlay()), false);
        source.sendFeedback(() -> Text.literal("overlayCurrentRegionOnly=" + settings.overlayCurrentRegionOnly()), false);
        source.sendFeedback(() -> Text.literal("overlayShowOnlyIncorrect=" + settings.overlayShowOnlyIncorrect()), false);
        source.sendFeedback(() -> Text.literal("hudCompact=" + settings.hudCompact()), false);
        source.sendFeedback(() -> Text.literal("hudX=" + settings.hudX()), false);
        source.sendFeedback(() -> Text.literal("hudY=" + settings.hudY()), false);
        return 1;
    }

    private static int setSetting(ServerCommandSource source, MapartSettingsStore settingsStore, String key, String value) {
        Optional<String> error = settingsStore.set(key, value);
        if (error.isPresent()) {
            source.sendError(Text.literal(error.get()));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Updated " + key + " = " + value), false);
        return 1;
    }
}
