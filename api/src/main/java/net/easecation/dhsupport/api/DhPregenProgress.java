/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.easecation.dhsupport.api;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/** Live, phase-specific DH pregeneration progress. */
public record DhPregenProgress(
    UUID worldId,
    String phase,
    long completedLods,
    long targetLods,
    long inFlightLods,
    int activeChunkLoads,
    @Nullable Integer oldestChunkX,
    @Nullable Integer oldestChunkZ,
    Duration oldestChunkWait,
    Duration elapsed
)
{
    public double percent()
    {
        return this.targetLods == 0 ? 1.0 : (double) this.completedLods / this.targetLods;
    }
}
