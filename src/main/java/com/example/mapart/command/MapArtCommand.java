package com.example.mapart.command;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildPlanState;
import com.example.mapart.plan.state.BuildSession;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
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

    public static LiteralArgumentBuilder<ServerCommandSource> create(BuildPlanService planService) {
        return createForName(PRIMARY_COMMAND, planService);
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createAlias(BuildPlanService planService) {
        return createForName(LEGACY_ALIAS, planService);
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createRunnerAlias(BuildPlanService planService) {
        return createForName(MOD_NAME_ALIAS, planService);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createForName(
            String commandName,
            BuildPlanService planService
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
                                context.getSource().sendFeedback(() -> Text.literal("Build completed."), false);
                                return 1;
                            }

                            Placement placement = result.placement();
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Next placement: " + Registries.BLOCK.getId(placement.block())
                                            + " at " + result.targetPos().toShortString()
                            ), false);
                            return 1;
                        }));
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
        Optional<BuildSession> sessionOptional = planService.currentSession();
        if (sessionOptional.isEmpty()) {
            source.sendFeedback(() -> Text.literal("State: IDLE (no plan loaded)."), false);
            return 1;
        }

        BuildSession session = sessionOptional.get();
        BuildPlan plan = session.getPlan();
        source.sendFeedback(() -> Text.literal("Plan: " + plan.sourcePath().getFileName()), false);
        source.sendFeedback(() -> Text.literal("State: " + session.getState()), false);
        source.sendFeedback(() -> Text.literal("Origin: " + (session.getOrigin() == null ? "not set" : session.getOrigin().toShortString())), false);
        source.sendFeedback(() -> Text.literal("Region: " + session.getProgress().getCurrentRegionIndex() + "/" + plan.regions().size()), false);
        source.sendFeedback(() -> Text.literal("Placement: " + session.getProgress().getCurrentPlacementIndex() + "/" + plan.placements().size()), false);
        source.sendFeedback(() -> Text.literal("Completed placements: " + session.getTotalCompletedPlacements()), false);

        int nextIndex = session.getProgress().getCurrentPlacementIndex();
        if (nextIndex >= 0 && nextIndex < plan.placements().size() && session.getOrigin() != null
                && session.getState() != BuildPlanState.COMPLETED) {
            Placement nextPlacement = plan.placements().get(nextIndex);
            BlockPos nextPos = session.getOrigin().add(nextPlacement.relativePos());
            source.sendFeedback(() -> Text.literal("Next block: " + Registries.BLOCK.getId(nextPlacement.block())), false);
            source.sendFeedback(() -> Text.literal("Next target: " + nextPos.toShortString()), false);
        }

        return 1;
    }
}
