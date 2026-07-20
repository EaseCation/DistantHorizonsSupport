package net.easecation.dhsupport.api;

import org.bukkit.World;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stable Bukkit service for runtime Distant Horizons LOD operations.
 *
 * <p>Consumers obtain this service through Bukkit's {@code ServicesManager} and must check
 * {@link #apiVersion()} before invoking mutating methods.</p>
 */
public interface DhSupportRuntimeApi
{
    int API_VERSION = 1;

    int apiVersion();

    DhWorldRegistration ensureWorldRegistered(World world);

    CompletableFuture<DhPregenResult> preGenerate(World world, DhPregenRequest request);

    CompletableFuture<DhExportResult> exportWorldLods(UUID worldId, Path artifact);

    /**
     * Exports an artifact with immutable pipeline metadata.
     *
     * <p>This is an additive API v1 method. The path-only method remains suitable for
     * administrative exports that do not belong to a published world revision.</p>
     */
    CompletableFuture<DhExportResult> exportWorldLods(UUID worldId, DhExportRequest request);

    CompletableFuture<DhImportResult> importWorldLods(UUID worldId, Path artifact, DhImportMode mode);

    CompletableFuture<Long> countWorldLods(UUID worldId);

    CompletableFuture<Long> deleteWorldLods(UUID worldId);

    CompletableFuture<Void> awaitIdle();
}
