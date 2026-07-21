/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package no.jckf.dhsupport.core;

import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SectionGenerationContextTest
{
    @Test
    public void ownsImmutableSectionCoordinatesAndRequiresAllSnapshots()
    {
        SectionGenerationKey key = new SectionGenerationKey(UUID.randomUUID(), -12, 19);
        SectionGenerationContext context = new SectionGenerationContext(key, 3, world(true), false);

        SectionPosition first = context.newPosition();
        first.setX(999);
        SectionPosition second = context.newPosition();
        assertEquals(-12, second.getX());
        assertEquals(19, second.getZ());
        assertEquals(6, second.getDetailLevel());

        try {
            new SectionGenerationContext(key, 3, world(false), false);
            fail("Expected incomplete snapshots to be rejected");
        } catch (IllegalStateException expected) {
            assertEquals("Incomplete chunk snapshot set for " + key, expected.getMessage());
        }

        new SectionGenerationContext(key, 3, world(false), true);
    }

    private static WorldInterface world(boolean complete)
    {
        return (WorldInterface) Proxy.newProxyInstance(
            WorldInterface.class.getClassLoader(),
            new Class<?>[] { WorldInterface.class },
            (proxy, method, arguments) -> {
                if (method.getName().equals("hasCompleteChunkSnapshot")) {
                    return complete;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == double.class) {
                    return 0d;
                }
                if (returnType == byte.class) {
                    return (byte) 0;
                }
                return null;
            }
        );
    }
}
