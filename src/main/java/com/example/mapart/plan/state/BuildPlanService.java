package com.example.mapart.plan.state;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.PlanLoader;
import com.example.mapart.plan.PlanLoaderRegistry;
import net.minecraft.server.command.ServerCommandSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class BuildPlanService {
    private final PlanLoaderRegistry loaderRegistry;
    private final BuildCoordinator buildCoordinator;

    public BuildPlanService(PlanLoaderRegistry loaderRegistry, BuildCoordinator buildCoordinator) {
        this.loaderRegistry = loaderRegistry;
        this.buildCoordinator = buildCoordinator;
    }

    public BuildPlan load(Path path, ServerCommandSource source) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }

        PlanLoader loader = loaderRegistry.findLoader(path)
                .orElseThrow(() -> new IllegalArgumentException("No loader registered for path: " + path));

        BuildPlan plan = loader.load(path, source);
        buildCoordinator.loadPlan(plan);
        return plan;
    }

    public Optional<BuildPlan> currentPlan() {
        return buildCoordinator.currentPlan();
    }

    public Optional<BuildSession> currentSession() {
        return buildCoordinator.getSession();
    }

    public boolean unload() {
        return buildCoordinator.unload();
    }

    public BuildCoordinator coordinator() {
        return buildCoordinator;
    }
}
