package net.easecation.dhsupport.api;

import java.util.UUID;

/** Result of registering a Bukkit world with DH Support. */
public record DhWorldRegistration(UUID worldId, String worldName, boolean created)
{
}
