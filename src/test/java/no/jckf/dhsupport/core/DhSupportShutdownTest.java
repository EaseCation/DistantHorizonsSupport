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
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DhSupportShutdownTest
{
    @Test
    public void rejectsNewGenerationAfterShutdownBegins()
    {
        DhSupport support = new DhSupport("test", "test");
        support.beginShutdown();

        assertTrue(support.isShuttingDown());

        try {
            support.generateLod(UUID.randomUUID(), new SectionPosition(), true).join();
            fail("Expected generation to be rejected during shutdown");
        } catch (CompletionException exception) {
            assertTrue(exception.getCause() instanceof DhSupportShutdownException);
        }
    }
}
