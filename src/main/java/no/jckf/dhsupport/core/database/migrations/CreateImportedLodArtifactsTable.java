/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.core.database.migrations;

import no.jckf.dhsupport.core.database.Database;

import java.sql.Statement;

public class CreateImportedLodArtifactsTable extends Migration
{
    public CreateImportedLodArtifactsTable(Database database)
    {
        super(database);
    }

    @Override
    public void up() throws Exception
    {
        try (Statement statement = this.database.getConnection().createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS ec_imported_lod_artifacts (
                    revision STRING NOT NULL,
                    artifact_sha256 STRING NOT NULL,
                    world_uuid STRING NOT NULL,
                    lod_count INTEGER NOT NULL,
                    imported_at INTEGER NOT NULL,
                    api_version INTEGER NOT NULL,
                    PRIMARY KEY (artifact_sha256, world_uuid)
                );
            """);
        }
    }
}
