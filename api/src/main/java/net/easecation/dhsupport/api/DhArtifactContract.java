package net.easecation.dhsupport.api;

/** Shared constants for the disk-backed SQLite LOD artifact contract. */
public final class DhArtifactContract
{
    public static final int SCHEMA_VERSION = 1;
    public static final int LOD_VERSION = 2;

    public static final String META_TABLE = "artifact_meta";
    public static final String LODS_TABLE = "lods";

    private DhArtifactContract()
    {
    }
}
