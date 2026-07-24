/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.bukkit;

import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class BukkitWorldInterfaceDummyModeTest
{
    @Test
    public void returnsDummyBlockStateWithoutAccessingChunkSnapshots() throws ReflectiveOperationException
    {
        BukkitWorldInterface world = allocateWithoutBukkitRuntime();
        world.dummyMode = true;
        world.dummyColumn = Map.of(-64, "minecraft:bedrock", 70, "minecraft:stone");

        assertEquals("minecraft:bedrock", world.getBlockStateAsStringAt(-8128, -64, 8064));
        assertEquals("minecraft:stone", world.getBlockStateAsStringAt(8000, 70, -8128));
        assertEquals("minecraft:air", world.getBlockStateAsStringAt(8064, 71, 8064));
    }

    private static BukkitWorldInterface allocateWithoutBukkitRuntime() throws ReflectiveOperationException
    {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (BukkitWorldInterface) unsafe.allocateInstance(BukkitWorldInterface.class);
    }
}
