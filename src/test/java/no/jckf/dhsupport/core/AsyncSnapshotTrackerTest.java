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

import org.junit.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AsyncSnapshotTrackerTest
{
    @Test
    public void reportsOldestPendingChunkPerWorld()
    {
        AsyncSnapshotTracker tracker = new AsyncSnapshotTracker();
        UUID world = UUID.randomUUID();
        UUID otherWorld = UUID.randomUUID();
        long now = System.nanoTime();

        tracker.begin(world, 4, 5, now - Duration.ofSeconds(8).toNanos());
        tracker.begin(world, 8, 9, now - Duration.ofSeconds(2).toNanos());
        tracker.begin(otherWorld, 1, 2, now - Duration.ofSeconds(30).toNanos());

        AsyncSnapshotTracker.Snapshot snapshot = tracker.snapshot(world);
        assertEquals(2, snapshot.activeLoads());
        assertEquals(Integer.valueOf(4), snapshot.oldestChunkX());
        assertEquals(Integer.valueOf(5), snapshot.oldestChunkZ());
        assertTrue(snapshot.oldestWait().compareTo(Duration.ofSeconds(7)) >= 0);
    }

    @Test
    public void completionRemovesPendingChunk()
    {
        AsyncSnapshotTracker tracker = new AsyncSnapshotTracker();
        UUID world = UUID.randomUUID();
        tracker.begin(world, -3, 7, System.nanoTime());

        tracker.complete(world, -3, 7);

        AsyncSnapshotTracker.Snapshot snapshot = tracker.snapshot(world);
        assertEquals(0, snapshot.activeLoads());
        assertNull(snapshot.oldestChunkX());
        assertNull(snapshot.oldestChunkZ());
        assertEquals(Duration.ZERO, snapshot.oldestWait());
    }
}
