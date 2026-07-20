package no.jckf.dhsupport.core.database.repositories;

import net.easecation.dhsupport.api.DhExportRequest;
import net.easecation.dhsupport.api.DhExportResult;
import net.easecation.dhsupport.api.DhImportMode;
import net.easecation.dhsupport.api.DhImportResult;
import net.easecation.dhsupport.api.DhImportStatus;
import no.jckf.dhsupport.core.database.Database;
import no.jckf.dhsupport.core.database.migrations.AddLodVersionColumn;
import no.jckf.dhsupport.core.database.migrations.CreateImportedLodArtifactsTable;
import no.jckf.dhsupport.core.database.migrations.CreateLodsTable;
import no.jckf.dhsupport.core.database.migrations.DeleteLodsWithoutVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LodArtifactRepositoryTest
{
    private Path temporaryDirectory;
    private Database database;
    private AsyncLodRepository repository;

    @Before
    public void setUp() throws Exception
    {
        this.temporaryDirectory = Files.createTempDirectory("dhs-artifact-test-");
        this.database = new Database();
        this.database.open(this.temporaryDirectory.resolve("main.sqlite").toString());
        this.database.addMigration(CreateLodsTable.class);
        this.database.addMigration(AddLodVersionColumn.class);
        this.database.addMigration(DeleteLodsWithoutVersion.class);
        this.database.addMigration(CreateImportedLodArtifactsTable.class);
        this.database.migrate();

        this.repository = new AsyncLodRepository(this.database, "0.14.0-ec.1-test");
        this.repository.setLogger(Logger.getLogger(getClass().getName()));
    }

    @After
    public void tearDown() throws Exception
    {
        if (this.repository != null) {
            this.repository.awaitIdle().join();
            this.repository.shutdown();
        }
        if (this.database != null) {
            this.database.close();
        }
        if (this.temporaryDirectory != null) {
            try (var paths = Files.walk(this.temporaryDirectory)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void exportImportRoundTripIsIdempotent() throws Exception
    {
        UUID worldId = UUID.randomUUID();
        save(worldId, 1, 2, new byte[] { 1, 2, 3 });
        save(worldId, 3, 4, new byte[] { 4, 5, 6 });

        Path artifact = this.temporaryDirectory.resolve("round-trip.sqlite");
        DhExportResult exportResult = this.repository
            .exportWorldLodsAsync(worldId, request(artifact))
            .join();
        assertEquals(2L, exportResult.lodCount());
        assertTrue(exportResult.artifactBytes() > 0);
        assertEquals(64, exportResult.artifactSha256().length());

        assertEquals(2L, this.repository.deleteWorldLodsAsync(worldId).join().longValue());
        DhImportResult firstImport = this.repository
            .importWorldLodsAsync(worldId, artifact, DhImportMode.REPLACE_WORLD)
            .join();
        assertEquals(DhImportStatus.IMPORTED, firstImport.status());
        assertEquals(2L, this.repository.countWorldLodsAsync(worldId).join().longValue());

        DhImportResult secondImport = this.repository
            .importWorldLodsAsync(worldId, artifact, DhImportMode.REPLACE_WORLD)
            .join();
        assertEquals(DhImportStatus.ALREADY_IMPORTED, secondImport.status());
        assertEquals(firstImport.artifactSha256(), secondImport.artifactSha256());
    }

    @Test
    public void importRollbackRestoresRowsAndDoesNotWriteMarker() throws Exception
    {
        UUID worldId = UUID.randomUUID();
        save(worldId, 1, 0, new byte[] { 1 });
        save(worldId, 2, 0, new byte[] { 2 });
        Path artifact = this.temporaryDirectory.resolve("rollback.sqlite");
        this.repository.exportWorldLodsAsync(worldId, request(artifact)).join();

        this.repository.deleteWorldLodsAsync(worldId).join();
        save(worldId, 99, 0, new byte[] { 99 });
        try (Statement statement = this.database.getConnection().createStatement()) {
            statement.execute("""
                CREATE TRIGGER reject_bulk_import
                BEFORE INSERT ON lods
                WHEN NEW.x = 2
                BEGIN
                    SELECT RAISE(ABORT, 'synthetic import failure');
                END
            """);
        }

        try {
            this.repository.importWorldLodsAsync(worldId, artifact, DhImportMode.REPLACE_WORLD).join();
            fail("Import should fail when the target trigger aborts a row");
        } catch (CompletionException expected) {
            assertTrue(expected.getCause().getMessage().contains("synthetic import failure"));
        }

        assertEquals(1L, this.repository.countWorldLodsAsync(worldId).join().longValue());
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM lods WHERE worldId = ? AND x = 99", worldId));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM ec_imported_lod_artifacts WHERE world_uuid = ?", worldId));
    }

    @Test
    public void bulkRoundTripUsesLessHeapThanArtifactPayload() throws Exception
    {
        UUID worldId = UUID.randomUUID();
        byte[] oneMiB = new byte[1024 * 1024];
        for (int index = 0; index < oneMiB.length; index++) {
            oneMiB[index] = (byte) (index * 31);
        }

        int rows = 256;
        for (int index = 0; index < rows; index++) {
            oneMiB[0] = (byte) index;
            save(worldId, index, -index, oneMiB);
        }

        Path artifact = this.temporaryDirectory.resolve("bounded-heap.sqlite");
        DhExportResult exported = this.repository.exportWorldLodsAsync(worldId, request(artifact)).join();
        assertEquals(rows, exported.lodCount());
        assertTrue("artifact must exceed the 128 MiB test heap", exported.artifactBytes() > 192L * 1024 * 1024);

        this.repository.deleteWorldLodsAsync(worldId).join();
        this.repository.importWorldLodsAsync(worldId, artifact, DhImportMode.REPLACE_WORLD).join();
        assertEquals(rows, this.repository.countWorldLodsAsync(worldId).join().longValue());
        assertEquals(
            (long) rows * oneMiB.length,
            queryLong("SELECT SUM(length(data)) FROM lods WHERE worldId = ?", worldId)
        );
    }

    private void save(UUID worldId, int x, int z, byte[] data)
    {
        this.repository.saveLod(worldId, x, z, data, new byte[] { 0 }, 2);
    }

    private DhExportRequest request(Path artifact)
    {
        return new DhExportRequest(
            artifact,
            "main",
            "20260720T143000Z-test00",
            "main_20260720T143000Z_test00",
            "test-builder-config-sha256",
            0,
            0,
            3000,
            "circle"
        );
    }

    private long queryLong(String sql, UUID worldId) throws Exception
    {
        try (PreparedStatement statement = this.database.getConnection().prepareStatement(sql)) {
            statement.setString(1, worldId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }
}
