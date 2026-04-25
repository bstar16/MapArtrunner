package com.example.mapart.plan.sweep.grounded;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class GroundedSweepLeftoverTracker {
    private final Map<Integer, EnumSet<GroundedLeftoverReason>> reasonsByPlacement = new TreeMap<>();

    public void mark(int placementIndex, GroundedLeftoverReason reason) {
        reasonsByPlacement.computeIfAbsent(placementIndex, ignored -> EnumSet.noneOf(GroundedLeftoverReason.class)).add(reason);
    }

    public void clear(int placementIndex) {
        reasonsByPlacement.remove(placementIndex);
    }

    public void clearReason(int placementIndex, GroundedLeftoverReason reason) {
        EnumSet<GroundedLeftoverReason> reasons = reasonsByPlacement.get(placementIndex);
        if (reasons == null) {
            return;
        }
        reasons.remove(reason);
        if (reasons.isEmpty()) {
            reasonsByPlacement.remove(placementIndex);
        }
    }

    public List<GroundedLeftoverRecord> snapshot() {
        List<GroundedLeftoverRecord> records = new ArrayList<>();
        for (Map.Entry<Integer, EnumSet<GroundedLeftoverReason>> entry : reasonsByPlacement.entrySet()) {
            List<GroundedLeftoverReason> reasons = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
            records.add(new GroundedLeftoverRecord(entry.getKey(), reasons));
        }
        return List.copyOf(records);
    }

    public enum GroundedLeftoverReason {
        DEFERRED,
        MISSED,
        FAILED,
        LAG_GRACE_RETRY_DELAYED
    }

    public record GroundedLeftoverRecord(int placementIndex, List<GroundedLeftoverReason> reasons) {
        public GroundedLeftoverRecord {
            reasons = List.copyOf(reasons);
        }
    }
}
