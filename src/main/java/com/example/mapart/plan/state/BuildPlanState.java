package com.example.mapart.plan.state;

import com.example.mapart.plan.BuildPlan;

import java.util.Optional;

public class BuildPlanState {
    private BuildPlan currentPlan;

    public void setCurrentPlan(BuildPlan plan) {
        this.currentPlan = plan;
    }

    public Optional<BuildPlan> getCurrentPlan() {
        return Optional.ofNullable(currentPlan);
    }

    public void clear() {
        this.currentPlan = null;
    }
}
