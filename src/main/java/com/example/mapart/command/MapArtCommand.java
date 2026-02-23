package com.example.mapart.command;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.state.BuildPlanService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public final class MapArtCommand {
    private MapArtCommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> create(BuildPlanService planService) {
        return CommandManager.literal("mapart")
                .then(CommandManager.literal("load")
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
                .then(CommandManager.literal("info")
                        .executes(context -> {
                            BuildPlan plan = planService.currentPlan().orElse(null);
                            if (plan == null) {
                                context.getSource().sendError(Text.literal("No build plan loaded. Use /mapart load <path> first."));
                                return 0;
                            }

                            context.getSource().sendFeedback(
                                    () -> Text.literal("Plan format: " + plan.sourceFormat() + ", source: " + plan.sourcePath()),
                                    false
                            );
                            context.getSource().sendFeedback(
                                    () -> Text.literal("Dimensions: " + plan.dimensions().getX() + "x"
                                            + plan.dimensions().getY() + "x" + plan.dimensions().getZ()
                                            + ", placements: " + plan.placements().size()
                                            + ", chunk regions: " + plan.regions().size()),
                                    false
                            );

                            context.getSource().sendFeedback(() -> Text.literal("Required materials:"), false);
                            plan.materialCounts().entrySet().stream()
                                    .sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
                                    .limit(10)
                                    .forEach(entry -> context.getSource().sendFeedback(() -> Text.literal("- "
                                            + Registries.BLOCK.getId(entry.getKey()) + ": " + entry.getValue()), false));

                            if (plan.materialCounts().size() > 10) {
                                int remainder = plan.materialCounts().size() - 10;
                                context.getSource().sendFeedback(
                                        () -> Text.literal("... and " + remainder + " more materials."),
                                        false
                                );
                            }

                            return 1;
                        }));
    }
}
