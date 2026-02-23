package com.example.mapart.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlanLoaderRegistry {
    private final List<PlanLoader> loaders = new ArrayList<>();

    public void register(PlanLoader loader) {
        loaders.add(loader);
    }

    public Optional<PlanLoader> findLoader(Path path) {
        return loaders.stream().filter(loader -> loader.supports(path)).findFirst();
    }
}
