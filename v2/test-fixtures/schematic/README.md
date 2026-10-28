# Schematic import fixtures

These are deterministic gzip, big-endian NBT files authored offline on 28 October 2026 with Python 3.14 standard-library `struct`/`gzip` (`mtime=0`), not game/exporter output. No generator dependency is required. Retained authoring/independent inspection: [lane script](../../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-1-fixture.py), [lane decode](../../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-1-fixture-inspection.txt), [structure decode](../../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-2-evidence/fixture-inspection.json), [air authoring and literal semantic calculations](../../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-author-fixture.py). Those local evidence files are ignored by Git; the binaries and executable assertions are tracked.

## Literal expectations

Dimensions are width × height × depth; coordinates are source-relative `(x,y,z)`. Regions below are relative 16×16 X/Z chunks, not a new plan field.

| File | Gzip bytes / uncompressed NBT bytes | File SHA-256 |
| --- | --- | --- |
| [grounded-single-lane.schem](grounded-single-lane.schem) | 134 / 194 | `7c194b89faf8339bf03b20b122b7b3b17f22064f1fb4e4139e1781bb4664ce0a` |
| [flat-asymmetric.nbt](flat-asymmetric.nbt) | 174 / 329 | `5cf68ed66fa3f0062bb7996961a86423668ecc11896d762dcd43fc8e9f5bdec6` |
| [all-air.schem](all-air.schem) | 132 / 156 | `afe1224408ad2757eae8e285a9a3c013f1fdf7021dca19bca99adf172e97ec54` |

- **Lane:** empty-named root compound, INT Version=2/DataVersion=4671, SHORT dimensions 12×1×5, palette `minecraft:stone`→INT 0, PaletteMax=1, BYTE_ARRAY BlockData containing 60 zero varints. All 60 targets are stone with no properties; material stone=60; region `(0,0)`=60. For each occupied row `z=0..4`, normalized ID `12*z+j` has `(x,y,z)=(j,0,z)` on even rows and `(11-j,0,z)` on odd rows, `j=0..11`. Thus IDs 0/11/12/23/24/48/59 are `(0,0,0)`/`(11,0,0)`/`(11,0,1)`/`(0,0,1)`/`(0,0,2)`/`(0,0,4)`/`(11,0,4)`.
- **Asymmetric structure:** INT-list size `[18,3,5]`; palette entries oak_log with `axis=x`, then stone; empty entities. Source record order is stone `(3,1,4)`, log `(17,1,0)`, stone `(1,1,2)`, stone `(0,1,0)`, log `(16,1,2)`. Normalized IDs 0..4 are respectively stone `(0,1,0)`, log `(17,1,0)`, log `(16,1,2)`, stone `(1,1,2)`, stone `(3,1,4)`. Complete log properties are `{axis=x}` and stone properties `{}`. Materials: stone=3, oak_log=2; regions `(0,0)`=3 and `(1,0)`=2. Source dimensions and padded Y=1 survive; empty Z rows do not toggle traversal parity. Metadata assertions do not prove physical log orientation placement.
- **Air:** empty-named root compound, INT Version=2/DataVersion=4671, SHORT dimensions 3×2×4, palette `minecraft:air`→INT 0, PaletteMax=1, 24 zero byte-varints. All 24 cells validate before air removal; targets, materials and regions are empty. Dimensions remain 3×2×4.

| Plan | Normalized semantic SHA-256 |
| --- | --- |
| Lane | `3163e482e8af48950898a172829747f62090c43697b6a69c76a3229ede63047a` |
| Asymmetric | `3a05428d7956c4b10d02f9d1bb7dd9560e60a4898d2459dac8c3e355e9151414` |
| Air | `5bd2e7c59bea4ec529380a518c4ae2f5f88281028f3979d65484eadeab932d0e` |

File SHA hashes the checked-in gzip bytes. Semantic SHA hashes the `mapartrunner-plan-v1` schema: length-delimited UTF-8 strings and big-endian INT dimensions/count, ordered target IDs/coordinates/block IDs, and sorted complete property names/values. Filename, gzip header, metadata, palette numbers and source record order are excluded; different file bytes may normalize identically. The historical programmatic lane fingerprint `3f86565ff52a70f932eed385fa8c67aff9c614794c8ff6b5941db889763f3205` and fixture-definition hash `1966849d838982d6dbdecdd9af0aadbd1e81752de845009c5aa8bfda8c050b11` are separate identities.

## Import policy and limits

[SchematicPlanImporter.importPlan(Path)](../../fabric/src/main/java/com/mapartrunner/fabric/schematic/SchematicPlanImporter.java) fully validates before returning an immutable core `BuildPlan`; Fabric owns file/gzip/NBT/registry conversion and does not mutate a run. Case-insensitive `.schem`, `.schem.nbt` and `.nbt` suffixes accept either recognized shape: flat Sponge palette/BlockData byte-varints or INT arrays, or structure size/palette/blocks, optionally under one compound `Schematic` wrapper. Recognized shapes have no version whitelist.

Native defaults and explicit full block properties survive. Every palette entry, even unused entries, must be valid; non-air itemless states fail. Air/cave_air/void_air are removed only after validation; absent sparse structure cells mean air. Positive source dimensions and coordinates survive, source offsets are ignored and run origin is applied later. Air Y padding is allowed around one occupied plane; multiple occupied Y planes fail. Targets sort by Y and occupied Z rows with alternating X direction, resetting parity per Y; IDs follow normalization. Materials use native block items.

Reject malformed exact tag types, references/counts/varints/overflow, duplicate structure coordinates (including air), invalid states/properties, entity or block-entity payloads (including on air), alternate palettes/ambiguous schemas, raw NBT, legacy `.schematic`, `.litematic`, numeric Blocks/Data and nested-v3 construction. Absent/correctly empty entity data and benign metadata are allowed; a chest without payload is not rejected merely for block-entity capability. Errors retain normalized source path and field/index context plus expected causes.

Native NBT limits are 104857600 tracked bytes and depth 512. Positive volume must fit checked INT indexing; dense counts are checked before target allocation, while sparse structure work follows records/palette rather than declared volume. This is not a bound on all normalized-model heap use; no 100 MiB allocation test, large-file benchmark or universal placement-state parity is claimed.

## Accepted import evidence

The accepted source stack is commit `766ef0dc3bf2781263805c1e8e37b51a3ba1d54d`, tree `53320b6f974faac8bf3cecd712c6e83de819c6b8`. [Commit receipts](../../../.superpowers/sdd/2026-10-28-v2-schematic-import/task-4-bc-commit-report.md) link its semantics to the previously tested working cohort, distinguish Git line-ending normalization and retain 179+9 input equality checks; this is not a separate game launch of the committed tree.

Tracked [unit assertions](../../fabric/src/test/java/com/mapartrunner/fabric/schematic/SchematicPlanImporterTest.java), [factory gate](../../fabric/src/gametest/java/com/mapartrunner/fabric/scenario/SingleLaneFixture.java) and [native import/empty provider](../../fabric/src/gametest/java/com/mapartrunner/fabric/scenario/SchematicImportGameTest.java) assert literal content. Imported anchors are state ID0, seed ID24, far outer ID48 and omission ID11, replacing historical programmatic 0/2/4/55 at the same physical cells. See [current runtime, historical unit and release evidence](../../docs/testing.md#current-schematic-import-evidence) for exact launch/artifact paths and limits. Import evidence alone does not complete Checkpoint B, command/UI/material reporting, broad state-placement parity or v1 replacement.
