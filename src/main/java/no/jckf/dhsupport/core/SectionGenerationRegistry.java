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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Atomically coalesces complete section generations and invalidates late world work. */
public final class SectionGenerationRegistry<T>
{
    private final ConcurrentMap<SectionGenerationKey, Entry<T>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<CompletableFuture<T>, Boolean> activeOperations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> worldEpochs = new ConcurrentHashMap<>();

    public long advanceWorldEpoch(UUID worldId)
    {
        long epoch = this.worldEpochs.merge(worldId, 1L, Long::sum);
        this.inFlight.forEach((key, entry) -> {
            if (key.worldId().equals(worldId)
                && entry.worldEpoch() != epoch
                && this.inFlight.remove(key, entry)) {
                entry.result().completeExceptionally(new WorldGenerationInvalidatedException(key));
            }
        });
        return epoch;
    }

    public long currentWorldEpoch(UUID worldId)
    {
        return this.worldEpochs.getOrDefault(worldId, 0L);
    }

    public boolean isCurrent(UUID worldId, long epoch)
    {
        return this.currentWorldEpoch(worldId) == epoch;
    }

    public CompletableFuture<T> coalesce(
        SectionGenerationKey key,
        long worldEpoch,
        Supplier<CompletableFuture<T>> starter
    )
    {
        while (true) {
            if (!this.isCurrent(key.worldId(), worldEpoch)) {
                return CompletableFuture.failedFuture(new WorldGenerationInvalidatedException(key));
            }

            Entry<T> existing = this.inFlight.get(key);
            if (existing != null) {
                if (existing.worldEpoch() == worldEpoch) {
                    return existing.result();
                }
                if (this.inFlight.remove(key, existing)) {
                    existing.result().completeExceptionally(new WorldGenerationInvalidatedException(key));
                }
                continue;
            }

            Entry<T> created = new Entry<>(worldEpoch, new CompletableFuture<>());
            if (this.inFlight.putIfAbsent(key, created) != null) {
                continue;
            }

            CompletableFuture<T> operation;
            try {
                operation = starter.get();
            } catch (Throwable throwable) {
                operation = CompletableFuture.failedFuture(throwable);
            }

            CompletableFuture<T> startedOperation = operation;
            this.activeOperations.put(startedOperation, Boolean.TRUE);
            startedOperation.whenComplete((value, error) -> {
                if (error == null) {
                    created.result().complete(value);
                } else {
                    created.result().completeExceptionally(error);
                }
                this.inFlight.remove(key, created);
                this.activeOperations.remove(startedOperation);
            });
            return created.result();
        }
    }

    public CompletableFuture<Void> awaitIdle()
    {
        CompletableFuture<?>[] operations = this.activeOperations.keySet().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(operations)
            .handle((ignored, error) -> null)
            .thenCompose(ignored -> this.activeOperations.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : this.awaitIdle()
            );
    }

    public int inFlightCount()
    {
        return this.inFlight.size();
    }

    private record Entry<T>(long worldEpoch, CompletableFuture<T> result) {}
}
