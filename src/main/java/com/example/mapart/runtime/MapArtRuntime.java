package com.example.mapart.runtime;

import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.sweep.SingleLaneSweepDebugRunner;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyStore;

public final class MapArtRuntime {
    private static BuildPlanService buildPlanService;
    private static ConfigStore configStore;
    private static ProgressStore progressStore;
    private static MapartSettingsStore settingsStore;
    private static SupplyStore supplyStore;
    private static BaritoneFacade baritoneFacade;
    private static DebugReporter debugReporter;
    private static SingleLaneSweepDebugRunner singleLaneSweepDebugRunner;

    private MapArtRuntime() {
    }

    public static void initialize(
            BuildPlanService planService,
            ConfigStore config,
            ProgressStore progress,
            MapartSettingsStore settings,
            SupplyStore supplies,
            BaritoneFacade facade,
            DebugReporter reporter,
            SingleLaneSweepDebugRunner singleLaneRunner
    ) {
        buildPlanService = planService;
        configStore = config;
        progressStore = progress;
        settingsStore = settings;
        supplyStore = supplies;
        baritoneFacade = facade;
        debugReporter = reporter;
        singleLaneSweepDebugRunner = singleLaneRunner;
    }

    public static BuildPlanService buildPlanService() {
        return buildPlanService;
    }

    public static MapartSettingsStore settingsStore() {
        return settingsStore;
    }

    public static SupplyStore supplyStore() {
        return supplyStore;
    }

    public static BaritoneFacade baritoneFacade() {
        return baritoneFacade;
    }

    public static DebugReporter debugReporter() {
        return debugReporter;
    }

    public static SingleLaneSweepDebugRunner singleLaneSweepDebugRunner() {
        return singleLaneSweepDebugRunner;
    }
}
