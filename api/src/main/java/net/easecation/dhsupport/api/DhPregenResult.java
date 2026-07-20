package net.easecation.dhsupport.api;

import java.time.Duration;
import java.util.UUID;

/** Terminal pregeneration result after all generated LOD writes reached the repository barrier. */
public record DhPregenResult(
    UUID worldId,
    long completedLods,
    long targetLods,
    Duration elapsed,
    boolean cancelled
)
{
}
