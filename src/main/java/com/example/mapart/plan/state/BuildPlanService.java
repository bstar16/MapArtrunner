package com.example.mapart.plan.state;

import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.PlanLoader;
import com.example.mapart.plan.PlanLoaderRegistry;
import net.minecraft.server.command.ServerCommandSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class BuildPlanService {
    private final PlanLoaderRegistry loaderRegistry;
    private final BuildPlanState state;
    private final ConfigStore configStore;
    private final ProgressStore progressStore;

    public BuildPlanService(PlanLoaderRegistry loaderRegistry, BuildPlanState state, ConfigStore configStore, ProgressStore progressStore) {
        this.loaderRegistry = loaderRegistry;
        this.state = state;
        this.configStore = configStore;
        this.progressStore = progressStore;
    }

    public BuildPlan load(Path path, ServerCommandSource source) throws Exception {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }

        PlanLoader loader = loaderRegistry.findLoader(path)
                .orElseThrow(() -> new IllegalArgumentException("No loader registered for path: " + path));

        BuildPlan plan = loader.load(path, source);
        state.setCurrentPlan(plan);
        configStore.rememberLoadedPlan(plan);
        progressStore.initializePlanProgress(plan);
        return plan;
    }

    public Optional<BuildPlan> currentPlan() {
        return state.getCurrentPlan();
    }
}
