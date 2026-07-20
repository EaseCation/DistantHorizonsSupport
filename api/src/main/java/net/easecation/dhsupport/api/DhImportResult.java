package net.easecation.dhsupport.api;

import java.util.UUID;

/** Result of an atomic, idempotent LOD artifact import. */
public record DhImportResult(
    UUID worldId,
    DhImportStatus status,
    long lodCount,
    String artifactSha256
)
{
}
