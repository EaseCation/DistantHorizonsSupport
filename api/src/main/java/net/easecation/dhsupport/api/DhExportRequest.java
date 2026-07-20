package net.easecation.dhsupport.api;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable business metadata and destination for a single-world LOD artifact. */
public record DhExportRequest(
    Path artifact,
    String templateId,
    String revisionId,
    String storageWorldName,
    String builderConfigSha256,
    int centerX,
    int centerZ,
    int radius,
    String shape
)
{
    public DhExportRequest
    {
        Objects.requireNonNull(artifact, "artifact");
        templateId = requireText(templateId, "templateId");
        revisionId = requireText(revisionId, "revisionId");
        storageWorldName = requireText(storageWorldName, "storageWorldName");
        builderConfigSha256 = requireText(builderConfigSha256, "builderConfigSha256");
        shape = requireText(shape, "shape");
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
    }

    private static String requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static DhExportRequest administrative(Path artifact, String storageWorldName)
    {
        return new DhExportRequest(
            artifact,
            "administrative",
            "unpublished",
            storageWorldName,
            "unavailable",
            0,
            0,
            1,
            "unknown"
        );
    }
}
