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

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks asynchronous FULL chunk requests so operators can distinguish progress from a light wait. */
public final class AsyncSnapshotTracker
{
    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();

    public void begin(UUID worldId, int chunkX, int chunkZ, long startedAtNanos)
    {
        this.pending.put(new Key(worldId, chunkX, chunkZ), new Pending(chunkX, chunkZ, startedAtNanos));
    }

    public void complete(UUID worldId, int chunkX, int chunkZ)
    {
        this.pending.remove(new Key(worldId, chunkX, chunkZ));
    }

    public Snapshot snapshot(UUID worldId)
    {
        long now = System.nanoTime();
        @Nullable Pending oldest = this.pending.entrySet().stream()
            .filter(entry -> entry.getKey().worldId().equals(worldId))
            .map(java.util.Map.Entry::getValue)
            .min(Comparator.comparingLong(Pending::startedAtNanos))
            .orElse(null);
        int active = (int) this.pending.keySet().stream().filter(key -> key.worldId().equals(worldId)).count();

        if (oldest == null) {
            return new Snapshot(active, null, null, Duration.ZERO);
        }
        return new Snapshot(
            active,
            oldest.chunkX(),
            oldest.chunkZ(),
            Duration.ofNanos(Math.max(0, now - oldest.startedAtNanos()))
        );
    }

    private record Key(UUID worldId, int chunkX, int chunkZ) {}

    private record Pending(int chunkX, int chunkZ, long startedAtNanos) {}

    public record Snapshot(int activeLoads, @Nullable Integer oldestChunkX, @Nullable Integer oldestChunkZ, Duration oldestWait) {}
}
