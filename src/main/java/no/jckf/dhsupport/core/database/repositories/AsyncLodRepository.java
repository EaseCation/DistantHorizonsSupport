/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package no.jckf.dhsupport.core.database.repositories;

import net.easecation.dhsupport.api.DhExportRequest;
import net.easecation.dhsupport.api.DhExportResult;
import net.easecation.dhsupport.api.DhImportMode;
import net.easecation.dhsupport.api.DhImportResult;
import no.jckf.dhsupport.core.database.Database;
import no.jckf.dhsupport.core.database.models.LodModel;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AsyncLodRepository extends LodRepository
{
    protected ExecutorService executor = Executors.newSingleThreadExecutor((task) -> {
        Thread thread = new Thread(task, "DHSupport-LodRepository");
        thread.setDaemon(true);
        return thread;
    });

    protected LodArtifactRepository artifactRepository;

    public AsyncLodRepository(Database database, String pluginVersion)
    {
        super(database);
        this.artifactRepository = new LodArtifactRepository(database, pluginVersion);
    }

    protected <T> CompletableFuture<T> queueTask(CheckedSupplier<T> task)
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        this.executor.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });

        return future;
    }

    public CompletableFuture<LodModel> saveLodAsync(UUID worldId, int sectionX, int sectionZ, byte[] data, byte[] beacons, int version)
    {
        return this.queueTask(() -> this.saveLod(worldId, sectionX, sectionZ, data, beacons, version));
    }

    public CompletableFuture<LodModel> loadLodAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.loadLod(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Boolean> lodExistsAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.lodExists(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Boolean> deleteLodAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.deleteLod(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Integer> trimLodsAsync(UUID worldId, int lowSectionX, int lowSectionZ, int highSectionX, int highSectionZ)
    {
        return this.queueTask(() -> this.trimLods(worldId, lowSectionX,lowSectionZ, highSectionX, highSectionZ));
    }

    public CompletableFuture<DhExportResult> exportWorldLodsAsync(UUID worldId, DhExportRequest request)
    {
        return this.queueTask(() -> this.artifactRepository.exportWorld(worldId, request));
    }

    public CompletableFuture<DhImportResult> importWorldLodsAsync(UUID worldId, Path artifact, DhImportMode mode)
    {
        return this.queueTask(() -> this.artifactRepository.importWorld(worldId, artifact, mode));
    }

    public CompletableFuture<Boolean> isWorldArtifactImportedAsync(UUID worldId, String artifactSha256)
    {
        return this.queueTask(() -> this.artifactRepository.isWorldArtifactImported(worldId, artifactSha256));
    }

    public CompletableFuture<Long> countWorldLodsAsync(UUID worldId)
    {
        return this.queueTask(() -> this.countWorldLods(worldId));
    }

    public CompletableFuture<Long> deleteWorldLodsAsync(UUID worldId)
    {
        return this.queueTask(() -> this.deleteWorldLods(worldId));
    }

    public CompletableFuture<Void> awaitIdle()
    {
        return this.queueTask(() -> null);
    }

    public void shutdown()
    {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    protected interface CheckedSupplier<T>
    {
        T get() throws Exception;
    }
}
