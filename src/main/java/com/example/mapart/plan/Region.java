package com.example.mapart.plan;

import net.minecraft.util.math.ChunkPos;

import java.util.List;

public record Region(ChunkPos chunkPos, List<Placement> placements) {
}
