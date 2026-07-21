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

import org.junit.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SectionFailureLogLimiterTest
{
    @Test
    public void retainsFirstCauseAndReportsSuppressedCountPerSection()
    {
        SectionFailureLogLimiter limiter = new SectionFailureLogLimiter(Duration.ofNanos(10));
        SectionGenerationKey key = new SectionGenerationKey(UUID.randomUUID(), 4, 5);

        SectionFailureLogLimiter.Decision first = limiter.recordFailure(key, 0);
        assertTrue(first.shouldLog());
        assertEquals(0, first.suppressedSinceLastLog());

        assertFalse(limiter.recordFailure(key, 1).shouldLog());
        assertFalse(limiter.recordFailure(key, 2).shouldLog());

        SectionFailureLogLimiter.Decision nextWindow = limiter.recordFailure(key, 10);
        assertTrue(nextWindow.shouldLog());
        assertEquals(2, nextWindow.suppressedSinceLastLog());

        limiter.clear(key);
        assertTrue(limiter.recordFailure(key, 11).shouldLog());
    }
}
