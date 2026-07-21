# DH Support Runtime API

EaseCation DH Support `0.14.0-ec.1` publishes `net.easecation.dhsupport:api` and registers
`DhSupportRuntimeApi` through Bukkit `ServicesManager`. Consumers must soft-depend on `DHSupport`, obtain the service
at runtime, and require `apiVersion() == 1` before mutating data.

## Operations

- `ensureWorldRegistered(World)` idempotently registers ASP/Iris worlds created after DH startup.
- `preGenerate(World, request)` completes only after every generation future and repository write is terminal.
- `exportWorldLods` writes a single-world SQLite artifact in one read snapshot.
- `importWorldLods` validates and imports an artifact in one transaction.
- `countWorldLods`, `deleteWorldLods`, and `awaitIdle` use the same repository FIFO executor.

All bulk work is serialized with ordinary LOD reads/writes. `pause()` is not a database lock and is not part of this
contract.

## Streaming invariant

The API never creates `List<LodRecord>` and never materializes all BLOBs in Java. Export and import attach a
disk-backed SQLite database and use `INSERT ... SELECT`; SHA-256 uses a fixed 1 MiB buffer. SQLite uses a bounded page
cache and `temp_store=FILE` for artifact work.

The surrounding Pipeline must stream XZ and GridFS data to disk. It must enforce compressed/uncompressed size,
decoder-memory, and free-disk limits before passing the verified SQLite path to this API.

## Artifact schema

An uncompressed artifact has two tables:

```text
artifact_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
lods(worldId, x, z, data, beacons, timestamp, version)
```

Required metadata includes template/revision/storage names, world UUID, plugin/API/schema versions, generation bounds,
builder config SHA-256, LOD count, and creation time. Import rejects a mismatched UUID, schema/API version, row count,
second world UUID, or unsupported LOD version before modifying the main database.

`ec_imported_lod_artifacts` records `(artifact_sha256, world_uuid)` after the LOD rows commit. Re-import returns
`ALREADY_IMPORTED`. `REPLACE_WORLD` deletes and inserts the selected world inside the same transaction.
`FORCE_REPLACE_WORLD` additionally bypasses the ledger fast path and assigns every imported row a timestamp greater
than both the replaced world and artifact maxima. This reset-specific timestamp rebase makes clients accept a
baseline for the same world UUID even when their local runtime-edit cache was newer. Any SQL error rolls back the row
replacement, timestamp rebase, and ledger marker together.

`artifact_sha256` is the SHA-256 of the uncompressed SQLite bytes. `transport_sha256` belongs to the compressed
GridFS object and is verified by the consumer before decompression.

## Compatibility and lifecycle

- API v1 additive methods remain binary-compatible; incompatible contracts increment the integer API major version.
- Plugin releases use `<upstream-version>-ec.<patch>`.
- The service is registered after DH database/world initialization and unregistered before shutdown.
- Unknown-world touch events warn and return instead of throwing; callers should still call
  `ensureWorldRegistered` before generation or import.
- On disable, DH stops pregenerators, waits for the repository barrier with a timeout, shuts down its executor, and
  closes SQLite.
