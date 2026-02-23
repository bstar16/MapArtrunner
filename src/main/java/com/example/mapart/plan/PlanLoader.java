package com.example.mapart.plan;

import net.minecraft.server.command.ServerCommandSource;

import java.nio.file.Path;

public interface PlanLoader {
    boolean supports(Path path);

    String formatId();

    BuildPlan load(Path path, ServerCommandSource source) throws Exception;
}
