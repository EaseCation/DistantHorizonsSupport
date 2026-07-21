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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SectionGenerationRegistryTest
{
    @Test
    public void coalescesCompleteGenerationByWorldAndSection()
    {
        SectionGenerationRegistry<String> registry = new SectionGenerationRegistry<>();
        UUID worldId = UUID.randomUUID();
        long epoch = registry.advanceWorldEpoch(worldId);
        SectionGenerationKey key = new SectionGenerationKey(worldId, 12, -9);
        CompletableFuture<String> source = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CompletableFuture<String>>> calls = new ArrayList<>();

        for (int i = 0; i < 32; i++) {
            calls.add(workers.submit(() -> {
                start.await();
                return registry.coalesce(key, epoch, () -> {
                    starts.incrementAndGet();
                    return source;
                });
            }));
        }
        start.countDown();

        List<CompletableFuture<String>> requests = calls.stream().map(call -> {
            try {
                return call.get();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).toList();
        workers.shutdownNow();

        assertEquals(1, starts.get());
        assertEquals(1, registry.inFlightCount());
        requests.forEach(request -> assertSame(requests.get(0), request));

        source.complete("lod");
        requests.forEach(request -> assertEquals("lod", request.join()));
        registry.awaitIdle().join();
        assertEquals(0, registry.inFlightCount());
    }

    @Test
    public void invalidatesLateWorldGenerationAndAllowsFreshEpoch()
    {
        SectionGenerationRegistry<String> registry = new SectionGenerationRegistry<>();
        UUID worldId = UUID.randomUUID();
        long oldEpoch = registry.advanceWorldEpoch(worldId);
        SectionGenerationKey key = new SectionGenerationKey(worldId, -3, 7);
        CompletableFuture<String> oldOperation = new CompletableFuture<>();
        CompletableFuture<String> oldResult = registry.coalesce(key, oldEpoch, () -> oldOperation);

        long newEpoch = registry.advanceWorldEpoch(worldId);
        try {
            oldResult.join();
            fail("Expected invalidated generation to fail");
        } catch (CompletionException exception) {
            assertTrue(exception.getCause() instanceof WorldGenerationInvalidatedException);
        }

        CompletableFuture<String> freshOperation = new CompletableFuture<>();
        CompletableFuture<String> freshResult = registry.coalesce(key, newEpoch, () -> freshOperation);
        freshOperation.complete("fresh");
        assertEquals("fresh", freshResult.join());

        oldOperation.complete("late");
        registry.awaitIdle().join();
        assertEquals(0, registry.inFlightCount());
    }
}
