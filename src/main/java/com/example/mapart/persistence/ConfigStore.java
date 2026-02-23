package com.example.mapart.persistence;

import com.example.mapart.plan.BuildPlan;

import java.nio.file.Path;
import java.util.Optional;

public class ConfigStore {
    public Optional<Path> getLastLoadedPlanPath() {
        return Optional.empty();
    }

    public void rememberLoadedPlan(BuildPlan plan) {
        // Milestone A stub: persistence implementation is added later.
    }
}
