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

public class WorldGenerationInvalidatedException extends IllegalStateException
{
    public WorldGenerationInvalidatedException(SectionGenerationKey key)
    {
        super("World lifecycle changed while generating " + key);
    }
}
