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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Limits repeated failures without losing the first complete cause for a section. */
public final class SectionFailureLogLimiter
{
    private final long intervalNanos;
    private final ConcurrentHashMap<SectionGenerationKey, State> states = new ConcurrentHashMap<>();

    public SectionFailureLogLimiter(Duration interval)
    {
        this.intervalNanos = interval.toNanos();
    }

    public Decision recordFailure(SectionGenerationKey key, long nowNanos)
    {
        AtomicReference<Decision> decision = new AtomicReference<>();
        this.states.compute(key, (ignored, state) -> {
            if (state == null || nowNanos >= state.nextLogAtNanos()) {
                int suppressed = state == null ? 0 : state.suppressed();
                decision.set(new Decision(true, suppressed));
                return new State(nowNanos + this.intervalNanos, 0);
            }

            decision.set(new Decision(false, state.suppressed() + 1));
            return new State(state.nextLogAtNanos(), state.suppressed() + 1);
        });
        return decision.get();
    }

    public void clear(SectionGenerationKey key)
    {
        this.states.remove(key);
    }

    public void clearWorld(java.util.UUID worldId)
    {
        this.states.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    private record State(long nextLogAtNanos, int suppressed) {}

    public record Decision(boolean shouldLog, int suppressedSinceLastLog) {}
}
