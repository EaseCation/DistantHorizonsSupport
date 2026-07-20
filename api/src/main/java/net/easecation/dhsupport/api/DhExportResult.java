package net.easecation.dhsupport.api;

import java.nio.file.Path;
import java.util.UUID;

/** Result of a disk-backed single-world export. */
public record DhExportResult(
    UUID worldId,
    Path artifact,
    long lodCount,
    long artifactBytes,
    String artifactSha256,
    int artifactSchemaVersion
)
{
}
