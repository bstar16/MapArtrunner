package com.example.mapart.plan;

import java.nio.file.Path;

public interface PlanLoader {
    boolean supports(Path path);

    String formatId();

    BuildPlan load(Path path) throws Exception;
}
