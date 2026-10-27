package com.mapartrunner.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable normalized expectations; the importer supplies the content fingerprint and stable IDs. */
public record BuildPlan(String fingerprint, GridSize dimensions, List<Target> targets) {
    public BuildPlan {
        requireText(fingerprint, "fingerprint");
        Objects.requireNonNull(dimensions, "dimensions");
        targets = List.copyOf(targets);
        var ids = new HashSet<Integer>();
        var positions = new HashSet<GridPos>();
        for (Target target : targets) {
            if (!ids.add(target.id())) {
                throw new IllegalArgumentException("Duplicate target ID: " + target.id());
            }
            GridPos pos = target.relativePos();
            if (!positions.add(pos)) {
                throw new IllegalArgumentException("Duplicate target position for target " + target.id() + ": " + pos);
            }
            if (pos.x() < 0 || pos.x() >= dimensions.width()
                    || pos.y() < 0 || pos.y() >= dimensions.height()
                    || pos.z() < 0 || pos.z() >= dimensions.depth()) {
                throw new IllegalArgumentException("Target " + target.id() + " is outside source dimensions: " + pos);
            }
        }
    }

    public record GridSize(int width, int height, int depth) {
        public GridSize {
            if (width <= 0 || height <= 0 || depth <= 0) {
                throw new IllegalArgumentException("Source dimensions must be positive");
            }
        }
    }

    /** Also used for world origins, so coordinates may be negative. */
    public record GridPos(int x, int y, int z) { }

    public record BlockSpec(String id, Map<String, String> properties) {
        private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

        public BlockSpec {
            Objects.requireNonNull(id, "id");
            if (!IDENTIFIER.matcher(id).matches()) {
                throw new IllegalArgumentException("Block ID must be a namespaced identifier: " + id);
            }
            properties = Map.copyOf(properties);
            properties.forEach((name, value) -> {
                requireText(name, "property name");
                requireText(value, "property value");
            });
        }
    }

    public record Target(int id, GridPos relativePos, BlockSpec block) {
        public Target {
            if (id < 0) {
                throw new IllegalArgumentException("Target ID must be nonnegative");
            }
            Objects.requireNonNull(relativePos, "relativePos");
            Objects.requireNonNull(block, "block");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
