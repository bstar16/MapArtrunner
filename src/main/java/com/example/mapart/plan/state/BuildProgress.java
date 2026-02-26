package com.example.mapart.plan.state;

public class BuildProgress {
    private int currentRegionIndex;
    private int currentPlacementIndex;
    private int totalCompletedPlacements;

    public int getCurrentRegionIndex() {
        return currentRegionIndex;
    }

    public void setCurrentRegionIndex(int currentRegionIndex) {
        this.currentRegionIndex = currentRegionIndex;
    }

    public int getCurrentPlacementIndex() {
        return currentPlacementIndex;
    }

    public void setCurrentPlacementIndex(int currentPlacementIndex) {
        this.currentPlacementIndex = currentPlacementIndex;
    }

    public int getTotalCompletedPlacements() {
        return totalCompletedPlacements;
    }

    public void incrementCompletedPlacements() {
        this.totalCompletedPlacements++;
    }
}
