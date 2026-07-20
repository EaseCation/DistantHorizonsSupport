/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.core.database.repositories;

import net.easecation.dhsupport.api.DhArtifactContract;
import net.easecation.dhsupport.api.DhExportRequest;
import net.easecation.dhsupport.api.DhExportResult;
import net.easecation.dhsupport.api.DhImportMode;
import net.easecation.dhsupport.api.DhImportResult;
import net.easecation.dhsupport.api.DhImportStatus;
import net.easecation.dhsupport.api.DhSupportRuntimeApi;
import no.jckf.dhsupport.core.database.Database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Performs constant-heap SQLite artifact operations on the DH repository connection.
 *
 * <p>The caller must serialize every method with ordinary repository work. LOD BLOBs are copied
 * by SQLite through {@code ATTACH DATABASE} and {@code INSERT ... SELECT}; Java never materializes
 * an artifact row or a collection of rows.</p>
 */
final class LodArtifactRepository
{
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;
    private static final String ARTIFACT_ALIAS = "ec_artifact";
    private static final Set<String> REQUIRED_LOD_COLUMNS = Set.of(
        "worldId", "x", "z", "data", "beacons", "timestamp", "version"
    );
    private static final Set<String> REQUIRED_META_KEYS = Set.of(
        "artifact_schema_version",
        "template_id",
        "revision_id",
        "storage_world_name",
        "world_uuid",
        "dh_support_version",
        "runtime_api_version",
        "lod_schema_version",
        "center_x",
        "center_z",
        "radius",
        "shape",
        "builder_config_sha256",
        "lod_count",
        "created_at"
    );

    private final Database database;
    private final String pluginVersion;

    LodArtifactRepository(Database database, String pluginVersion)
    {
        this.database = database;
        this.pluginVersion = pluginVersion;
    }

    DhExportResult exportWorld(UUID worldId, DhExportRequest request) throws Exception
    {
        Path artifact = request.artifact().toAbsolutePath().normalize();
        Path parent = artifact.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(artifact);

        Connection connection = this.database.getConnection();
        this.database.clearQueryCache();
        attach(connection, artifact.toString(), false);

        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            configureAttachedDatabase(connection);
            connection.setAutoCommit(false);
            createArtifactTables(connection);

            long lodCount = copyWorldToArtifact(connection, worldId);
            writeArtifactMetadata(connection, worldId, request, lodCount);
            connection.commit();

            connection.setAutoCommit(previousAutoCommit);
            detach(connection);

            String sha256 = sha256(artifact);
            return new DhExportResult(
                worldId,
                artifact,
                lodCount,
                Files.size(artifact),
                sha256,
                DhArtifactContract.SCHEMA_VERSION
            );
        } catch (Exception exception) {
            rollbackQuietly(connection);
            restoreAutoCommit(connection, previousAutoCommit);
            detachQuietly(connection);
            Files.deleteIfExists(artifact);
            throw exception;
        }
    }

    DhImportResult importWorld(UUID worldId, Path input, DhImportMode mode) throws Exception
    {
        Path artifact = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("LOD artifact is not a regular file: " + artifact);
        }

        String artifactSha256 = sha256(artifact);
        ArtifactMetadata metadata = validateArtifact(artifact, worldId);
        Connection connection = this.database.getConnection();

        if (wasImported(connection, worldId, artifactSha256)) {
            return new DhImportResult(worldId, DhImportStatus.ALREADY_IMPORTED, metadata.lodCount(), artifactSha256);
        }

        this.database.clearQueryCache();
        attach(connection, readOnlyUri(artifact), true);
        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            configureImportDatabases(connection);
            connection.setAutoCommit(false);
            if (mode == DhImportMode.REPLACE_WORLD) {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM main.lods WHERE worldId = ?")) {
                    statement.setString(1, worldId.toString());
                    statement.executeUpdate();
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR REPLACE INTO main.lods (worldId, x, z, data, beacons, timestamp, version)
                SELECT worldId, x, z, data, beacons, timestamp, version
                FROM ec_artifact.lods
                WHERE worldId = ?
            """)) {
                statement.setString(1, worldId.toString());
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO main.ec_imported_lod_artifacts
                    (revision, artifact_sha256, world_uuid, lod_count, imported_at, api_version)
                VALUES (?, ?, ?, ?, ?, ?)
            """)) {
                statement.setString(1, metadata.revisionId());
                statement.setString(2, artifactSha256);
                statement.setString(3, worldId.toString());
                statement.setLong(4, metadata.lodCount());
                statement.setLong(5, Instant.now().getEpochSecond());
                statement.setInt(6, DhSupportRuntimeApi.API_VERSION);
                statement.executeUpdate();
            }

            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
            detach(connection);
            return new DhImportResult(worldId, DhImportStatus.IMPORTED, metadata.lodCount(), artifactSha256);
        } catch (Exception exception) {
            rollbackQuietly(connection);
            restoreAutoCommit(connection, previousAutoCommit);
            detachQuietly(connection);
            throw exception;
        }
    }

    private ArtifactMetadata validateArtifact(Path artifact, UUID expectedWorldId) throws Exception
    {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + readOnlyUri(artifact))) {
            requireTable(connection, DhArtifactContract.META_TABLE);
            requireTable(connection, DhArtifactContract.LODS_TABLE);
            requireLodColumns(connection);

            Map<String, String> metadata = readMetadata(connection);
            Set<String> missingKeys = new HashSet<>(REQUIRED_META_KEYS);
            missingKeys.removeAll(metadata.keySet());
            if (!missingKeys.isEmpty()) {
                throw new IllegalArgumentException("Artifact metadata is missing keys: " + missingKeys);
            }

            requireInt(metadata, "artifact_schema_version", DhArtifactContract.SCHEMA_VERSION);
            requireInt(metadata, "runtime_api_version", DhSupportRuntimeApi.API_VERSION);
            requireInt(metadata, "lod_schema_version", DhArtifactContract.LOD_VERSION);

            UUID artifactWorldId = UUID.fromString(metadata.get("world_uuid"));
            if (!expectedWorldId.equals(artifactWorldId)) {
                throw new IllegalArgumentException(
                    "Artifact world UUID " + artifactWorldId + " does not match " + expectedWorldId
                );
            }

            long actualCount = queryLong(connection, "SELECT COUNT(*) FROM lods");
            long declaredCount = Long.parseLong(metadata.get("lod_count"));
            if (actualCount != declaredCount) {
                throw new IllegalArgumentException(
                    "Artifact lod_count " + declaredCount + " does not match actual count " + actualCount
                );
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM lods WHERE worldId <> ? OR worldId IS NULL"
            )) {
                statement.setString(1, expectedWorldId.toString());
                if (singleLong(statement) != 0) {
                    throw new IllegalArgumentException("Artifact contains rows for another world UUID");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM lods WHERE version IS NULL OR version <> ?"
            )) {
                statement.setInt(1, DhArtifactContract.LOD_VERSION);
                if (singleLong(statement) != 0) {
                    throw new IllegalArgumentException("Artifact contains an unsupported LOD version");
                }
            }

            return new ArtifactMetadata(metadata.get("revision_id"), declaredCount);
        }
    }

    private long copyWorldToArtifact(Connection connection, UUID worldId) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM main.lods WHERE worldId = ? AND (version IS NULL OR version <> ?)"
        )) {
            statement.setString(1, worldId.toString());
            statement.setInt(2, DhArtifactContract.LOD_VERSION);
            if (singleLong(statement) != 0) {
                throw new IllegalArgumentException("Source database contains an unsupported LOD version");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO ec_artifact.lods (worldId, x, z, data, beacons, timestamp, version)
            SELECT worldId, x, z, data, beacons, timestamp, version
            FROM main.lods
            WHERE worldId = ?
        """)) {
            statement.setString(1, worldId.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM ec_artifact.lods WHERE worldId = ?"
        )) {
            statement.setString(1, worldId.toString());
            return singleLong(statement);
        }
    }

    private void writeArtifactMetadata(
        Connection connection,
        UUID worldId,
        DhExportRequest request,
        long lodCount
    ) throws SQLException
    {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("artifact_schema_version", Integer.toString(DhArtifactContract.SCHEMA_VERSION));
        metadata.put("template_id", request.templateId());
        metadata.put("revision_id", request.revisionId());
        metadata.put("storage_world_name", request.storageWorldName());
        metadata.put("world_uuid", worldId.toString());
        metadata.put("dh_support_version", this.pluginVersion);
        metadata.put("runtime_api_version", Integer.toString(DhSupportRuntimeApi.API_VERSION));
        metadata.put("lod_schema_version", Integer.toString(DhArtifactContract.LOD_VERSION));
        metadata.put("center_x", Integer.toString(request.centerX()));
        metadata.put("center_z", Integer.toString(request.centerZ()));
        metadata.put("radius", Integer.toString(request.radius()));
        metadata.put("shape", request.shape());
        metadata.put("builder_config_sha256", request.builderConfigSha256());
        metadata.put("lod_count", Long.toString(lodCount));
        metadata.put("created_at", Instant.now().toString());

        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO ec_artifact.artifact_meta (key, value) VALUES (?, ?)"
        )) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void createArtifactTables(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE ec_artifact.artifact_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);
            statement.execute("""
                CREATE TABLE ec_artifact.lods (
                    worldId STRING NOT NULL,
                    x INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    data BLOB NOT NULL,
                    beacons BLOB NOT NULL,
                    timestamp INTEGER NOT NULL,
                    version INTEGER NOT NULL,
                    PRIMARY KEY (worldId, x, z)
                )
            """);
        }
    }

    private static void configureAttachedDatabase(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ec_artifact.journal_mode=DELETE");
            statement.execute("PRAGMA ec_artifact.synchronous=FULL");
            statement.execute("PRAGMA ec_artifact.cache_size=-32768");
            statement.execute("PRAGMA main.cache_size=-32768");
            statement.execute("PRAGMA temp_store=FILE");
        }
    }

    private static void configureImportDatabases(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ec_artifact.cache_size=-32768");
            statement.execute("PRAGMA main.cache_size=-32768");
            statement.execute("PRAGMA temp_store=FILE");
        }
    }

    private static boolean wasImported(Connection connection, UUID worldId, String artifactSha256) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM ec_imported_lod_artifacts
            WHERE artifact_sha256 = ? AND world_uuid = ?
            LIMIT 1
        """)) {
            statement.setString(1, artifactSha256);
            statement.setString(2, worldId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void requireTable(Connection connection, String table) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Artifact is missing table " + table);
                }
            }
        }
    }

    private static void requireLodColumns(Connection connection) throws SQLException
    {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(lods)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        if (!columns.containsAll(REQUIRED_LOD_COLUMNS)) {
            Set<String> missing = new HashSet<>(REQUIRED_LOD_COLUMNS);
            missing.removeAll(columns);
            throw new IllegalArgumentException("Artifact lods table is missing columns: " + missing);
        }
    }

    private static Map<String, String> readMetadata(Connection connection) throws SQLException
    {
        Map<String, String> metadata = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT key, value FROM artifact_meta")) {
            while (result.next()) {
                metadata.put(result.getString("key"), result.getString("value"));
            }
        }
        return metadata;
    }

    private static void requireInt(Map<String, String> metadata, String key, int expected)
    {
        int actual = Integer.parseInt(metadata.get(key));
        if (actual != expected) {
            throw new IllegalArgumentException(
                "Artifact " + key + " " + actual + " is incompatible with " + expected
            );
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("Aggregate query returned no row: " + sql);
            }
            return result.getLong(1);
        }
    }

    private static long singleLong(PreparedStatement statement) throws SQLException
    {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Aggregate query returned no row");
            }
            return result.getLong(1);
        }
    }

    private static String sha256(Path artifact) throws IOException
    {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }

        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream input = new DigestInputStream(Files.newInputStream(artifact), digest)) {
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the digest without retaining artifact bytes.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String readOnlyUri(Path artifact)
    {
        return artifact.toUri() + "?mode=ro&immutable=1";
    }

    private static void attach(Connection connection, String location, boolean readOnly) throws SQLException
    {
        String value = readOnly ? location : Path.of(location).toAbsolutePath().normalize().toString();
        try (PreparedStatement statement = connection.prepareStatement(
            "ATTACH DATABASE ? AS " + ARTIFACT_ALIAS
        )) {
            statement.setString(1, value);
            statement.execute();
        }
    }

    private static void detach(Connection connection) throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DETACH DATABASE " + ARTIFACT_ALIAS);
        }
    }

    private static void detachQuietly(Connection connection)
    {
        try {
            detach(connection);
        } catch (SQLException ignored) {
        }
    }

    private static void rollbackQuietly(Connection connection)
    {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit)
    {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    private record ArtifactMetadata(String revisionId, long lodCount)
    {
    }
}
