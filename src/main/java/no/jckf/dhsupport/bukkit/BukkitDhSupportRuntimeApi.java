/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.bukkit;

import net.easecation.dhsupport.api.DhExportRequest;
import net.easecation.dhsupport.api.DhExportResult;
import net.easecation.dhsupport.api.DhImportMode;
import net.easecation.dhsupport.api.DhImportResult;
import net.easecation.dhsupport.api.DhPregenRequest;
import net.easecation.dhsupport.api.DhPregenProgress;
import net.easecation.dhsupport.api.DhPregenResult;
import net.easecation.dhsupport.api.DhSupportRuntimeApi;
import net.easecation.dhsupport.api.DhWorldRegistration;
import no.jckf.dhsupport.core.DhSupport;
import no.jckf.dhsupport.core.AsyncSnapshotTracker;
import no.jckf.dhsupport.core.PreGenerator;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bukkit service implementation backed by DH Support's serialized repository queue. */
public final class BukkitDhSupportRuntimeApi implements DhSupportRuntimeApi
{
    private final DhSupportBukkitPlugin plugin;

    public BukkitDhSupportRuntimeApi(DhSupportBukkitPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public int apiVersion()
    {
        return API_VERSION;
    }

    @Override
    public DhWorldRegistration ensureWorldRegistered(World world)
    {
        return this.plugin.ensureWorldRegistered(Objects.requireNonNull(world, "world"));
    }

    @Override
    public CompletableFuture<DhPregenResult> preGenerate(World world, DhPregenRequest request)
    {
        Objects.requireNonNull(request, "request");
        DhWorldRegistration registration = this.ensureWorldRegistered(world);
        DhSupport support = requireSupport();
        WorldInterface worldInterface = support.getWorldInterface(registration.worldId());
        if (worldInterface == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("World registration disappeared for " + registration.worldId())
            );
        }

        return support.preGenerateAsync(
                worldInterface,
                request.centerX(),
                request.centerZ(),
                request.radius(),
                request.force(),
                request.generateNewChunks()
            )
            .thenCompose((preGenerator) -> support.getLodRepository().awaitIdle()
                .thenApply((ignored) -> toResult(registration.worldId(), preGenerator)));
    }

    @Override
    public Optional<DhPregenProgress> pregenerationProgress(UUID worldId)
    {
        DhSupport support = requireSupport();
        WorldInterface world = support.getWorldInterface(worldId);
        if (world == null) {
            return Optional.empty();
        }
        PreGenerator generator = support.getPreGenerator(world);
        if (generator == null) {
            return Optional.empty();
        }

        AsyncSnapshotTracker.Snapshot chunks = support.getAsyncSnapshotTracker().snapshot(worldId);
        String phase;
        if (chunks.activeLoads() == 0) {
            phase = "DH_LOD";
        } else if (chunks.oldestWait().toSeconds() >= 5) {
            phase = "DH_ASYNC_FULL_WAIT";
        } else {
            phase = "DH_ASYNC_SNAPSHOT";
        }
        return Optional.of(new DhPregenProgress(
            worldId,
            phase,
            generator.getCompletedRequests(),
            generator.getTargetRequests(),
            generator.getInFlightRequests(),
            chunks.activeLoads(),
            chunks.oldestChunkX(),
            chunks.oldestChunkZ(),
            chunks.oldestWait(),
            generator.getElapsedTime()
        ));
    }

    @Override
    public CompletableFuture<DhExportResult> exportWorldLods(UUID worldId, Path artifact)
    {
        WorldInterface world = requireWorld(worldId);
        return this.exportWorldLods(
            worldId,
            DhExportRequest.administrative(artifact, world.getName())
        );
    }

    @Override
    public CompletableFuture<DhExportResult> exportWorldLods(UUID worldId, DhExportRequest request)
    {
        requireWorld(worldId);
        return requireSupport().getLodRepository().exportWorldLodsAsync(worldId, request);
    }

    @Override
    public CompletableFuture<DhImportResult> importWorldLods(UUID worldId, Path artifact, DhImportMode mode)
    {
        requireWorld(worldId);
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(mode, "mode");
        return requireSupport().getLodRepository().importWorldLodsAsync(worldId, artifact, mode);
    }

    @Override
    public CompletableFuture<Boolean> isWorldArtifactImported(UUID worldId, String artifactSha256)
    {
        requireWorld(worldId);
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        return requireSupport().getLodRepository().isWorldArtifactImportedAsync(worldId, artifactSha256);
    }

    @Override
    public CompletableFuture<Long> countWorldLods(UUID worldId)
    {
        return requireSupport().getLodRepository().countWorldLodsAsync(worldId);
    }

    @Override
    public CompletableFuture<Long> deleteWorldLods(UUID worldId)
    {
        return requireSupport().getLodRepository().deleteWorldLodsAsync(worldId);
    }

    @Override
    public CompletableFuture<Void> awaitIdle()
    {
        return requireSupport().awaitIdle();
    }

    private DhSupport requireSupport()
    {
        DhSupport support = this.plugin.getDhSupport();
        if (support == null) {
            throw new IllegalStateException("DH Support is not enabled");
        }
        return support;
    }

    private WorldInterface requireWorld(UUID worldId)
    {
        Objects.requireNonNull(worldId, "worldId");
        WorldInterface world = requireSupport().getWorldInterface(worldId);
        if (world == null) {
            throw new IllegalStateException("No DH world interface is registered for " + worldId);
        }
        return world;
    }

    private static DhPregenResult toResult(UUID worldId, PreGenerator preGenerator)
    {
        return new DhPregenResult(
            worldId,
            preGenerator.getCompletedRequests(),
            preGenerator.getTargetRequests(),
            preGenerator.getElapsedTime(),
            preGenerator.wasCancelled()
        );
    }
}
