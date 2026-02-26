package com.example.mapart.persistence;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.Optional;

public class ConfigStore {
    private Path lastLoadedPlanPath;
    private BlockPos lastOrigin;

    public Optional<Path> getLastLoadedPlanPath() {
        return Optional.ofNullable(lastLoadedPlanPath);
    }

    public void rememberLoadedPlan(BuildPlan plan) {
        this.lastLoadedPlanPath = plan.sourcePath();
    }

    public void rememberOrigin(BlockPos origin) {
        this.lastOrigin = origin.toImmutable();
    }

    public Optional<BlockPos> getLastOrigin() {
        return Optional.ofNullable(lastOrigin);
    }

    public void clearRememberedState() {
        lastLoadedPlanPath = null;
        lastOrigin = null;
    }
}
