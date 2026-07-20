package net.easecation.dhsupport.api;

/** Parameters for a bounded DH LOD pregeneration pass. */
public record DhPregenRequest(
    int centerX,
    int centerZ,
    int radius,
    boolean force,
    boolean generateNewChunks
)
{
    public DhPregenRequest
    {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
    }

    public static DhPregenRequest existingChunksOnly(int centerX, int centerZ, int radius, boolean force)
    {
        return new DhPregenRequest(centerX, centerZ, radius, force, false);
    }
}
