package com.example.mapart.plan.state;

public class BuildProgress {
    private int currentRegionIndex;
    private int currentPlacementIndex;
    private int totalCompletedPlacements;

    public int getCurrentRegionIndex() {
        return currentRegionIndex;
    }

    public void setCurrentRegionIndex(int currentRegionIndex) {
        this.currentRegionIndex = Math.max(0, currentRegionIndex);
    }

    public int getCurrentPlacementIndex() {
        return currentPlacementIndex;
    }

    public void setCurrentPlacementIndex(int currentPlacementIndex) {
        this.currentPlacementIndex = Math.max(0, currentPlacementIndex);
    }

    public int getTotalCompletedPlacements() {
        return totalCompletedPlacements;
    }

    public void incrementCompletedPlacements() {
        this.totalCompletedPlacements++;
    }

    public void setTotalCompletedPlacements(int totalCompletedPlacements) {
        this.totalCompletedPlacements = Math.max(0, totalCompletedPlacements);
    }

    public void reset() {
        this.currentRegionIndex = 0;
        this.currentPlacementIndex = 0;
        this.totalCompletedPlacements = 0;
    }
}
