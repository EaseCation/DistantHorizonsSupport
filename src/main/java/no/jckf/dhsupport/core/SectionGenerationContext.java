/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.core;

import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.world.WorldInterface;

/** Immutable ownership boundary for snapshots, tickets, builder work, and persistence. */
public record SectionGenerationContext(
    SectionGenerationKey key,
    long worldEpoch,
    WorldInterface snapshotWorld,
    boolean dummyMode
)
{
    public SectionGenerationContext
    {
        if (!dummyMode && !snapshotWorld.hasCompleteChunkSnapshot(key.sectionX(), key.sectionZ())) {
            throw new IllegalStateException("Incomplete chunk snapshot set for " + key);
        }
    }

    public SectionPosition newPosition()
    {
        SectionPosition position = new SectionPosition();
        position.setDetailLevel(6);
        position.setX(this.key.sectionX());
        position.setZ(this.key.sectionZ());
        return position;
    }
}
