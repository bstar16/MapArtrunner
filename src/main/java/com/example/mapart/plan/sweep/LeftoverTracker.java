package com.example.mapart.plan.sweep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class LeftoverTracker {
    private final Map<Integer, EnumSet<LeftoverReason>> reasonsByPlacement = new TreeMap<>();

    public void mark(int placementIndex, LeftoverReason reason) {
        reasonsByPlacement.computeIfAbsent(placementIndex, ignored -> EnumSet.noneOf(LeftoverReason.class)).add(reason);
    }

    public void clear(int placementIndex) {
        reasonsByPlacement.remove(placementIndex);
    }

    public int pendingCount() {
        return reasonsByPlacement.size();
    }

    public List<LeftoverRecord> snapshot() {
        List<LeftoverRecord> records = new ArrayList<>();
        for (Map.Entry<Integer, EnumSet<LeftoverReason>> entry : reasonsByPlacement.entrySet()) {
            List<LeftoverReason> reasons = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
            records.add(new LeftoverRecord(entry.getKey(), reasons));
        }
        return List.copyOf(records);
    }

    public void reset() {
        reasonsByPlacement.clear();
    }

    public enum LeftoverReason {
        DEFERRED,
        MISSED,
        FAILED,
        EXHAUSTED,
        NOT_REACHED
    }

    public record LeftoverRecord(int placementIndex, List<LeftoverReason> reasons) {
        public LeftoverRecord {
            reasons = List.copyOf(reasons);
        }
    }
}
