package net.easecation.dhsupport.api;

/** Behavior for rows already present in the target DH database. */
public enum DhImportMode
{
    /** Upsert artifact rows and preserve target rows outside the artifact. */
    MERGE,

    /** Delete all target rows for the world before importing the artifact. */
    REPLACE_WORLD
}
