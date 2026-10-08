# Controlled hopper load test

Date: 2026-10-07. Guardian `0.4.0-alpha.7+1.21.1`, Minecraft 1.21.1, Java 21, SQLite schema 5.

Both dedicated servers were tested sequentially on the same local machine, with no connected players. Fabric used Loader 0.19.5 and Fabric API 0.116.17; NeoForge used 21.1.256. The servers retained their normal small fixture mod sets; the full production pack and Lithium were not installed. Each process used `-Xms512M -Xmx2G`.

Each server received 100 independent barrel → hopper → barrel rigs spread over four loaded chunks. Source barrels held 27 full stacks of ordinary coal. Stages enabled 10, 50, then 100 hoppers, with the others disabled. Each stage reset inventory contents while ticks were frozen, warmed for five seconds, then sampled for 15 seconds. Three `tick query` readings report rolling 100-tick windows; the table gives the range of those readings, not a pooled percentile. Process working set was sampled alongside them. Logging-off and logging-on runs used separate fresh JVMs.

| Loader | Logging | Active hoppers | Average tick ms | P95 tick ms | Working set MiB |
|---|---|---:|---:|---:|---:|
| Fabric | Off | 10 | 0.4–0.7 | 0.9–1.0 | 834.9–857.7 |
| Fabric | Off | 50 | 0.3–0.4 | 0.6–0.9 | 861.0–867.5 |
| Fabric | Off | 100 | 0.3–0.3 | 0.6–0.8 | 875.5–881.9 |
| Fabric | On | 10 | 0.6–1.0 | 2.6–3.9 | 835.4–836.3 |
| Fabric | On | 50 | 1.1–1.4 | 6.8–10.3 | 836.9–857.9 |
| Fabric | On | 100 | 2.0–2.2 | 14.8–15.3 | 837.3–864.6 |
| Neoforge | Off | 10 | 0.7–1.3 | 1.6–1.9 | 1160.8–1164.8 |
| Neoforge | Off | 50 | 0.6–0.7 | 0.8–1.9 | 1166.8–1179.2 |
| Neoforge | Off | 100 | 0.6–0.6 | 1.1–1.3 | 1166.3–1186.3 |
| Neoforge | On | 10 | 0.8–1.2 | 2.8–4.4 | 1157.7–1170.0 |
| Neoforge | On | 50 | 1.3–1.5 | 7.8–9.6 | 1160.7–1188.3 |
| Neoforge | On | 100 | 2.3–3.3 | 17.6–21.8 | 1166.1–1202.8 |

With logging disabled, hopper audit submissions stayed at zero. With logging enabled, capture/write failures and backpressure stayed at zero. After shutdown, an independent SQLite payload reader checked every fixture record for unique IDs, system attribution, exact component-payload conservation and one-item source/destination deltas. Persisted counts matched the final submitted counters:

- Fabric: 16,160 balanced transactions.
- Neoforge: 16,160 balanced transactions.

The first Fabric run, before default-payload reuse, averaged 10.5–14.2 ms at 100 hoppers with P95 readings of 71.7–121.9 ms. The repeat after the change averaged 2.0–2.2 ms, with P95 readings of 14.8–15.3 ms. This comparison uses the same synchronized default-coal fixture; it does not establish performance for component-heavy items. Modified component patches retain the original full codec path.

## Sided slots and chunk boundary

A separate five-rig test ran on each loader. Each produced exactly 27 balanced transactions:

- Three iron ore entered a furnace from above into its input slot.
- Three coal entered a furnace from the side into its fuel slot.
- Three iron ingots left the furnace output slot through a hopper below it.
- Three iron ore were pulled into a hopper beside another furnace, but rejected insertion into the fuel slot produced no furnace record.
- Three coal crossed a loaded chunk boundary through a sideways hopper, preserving the physical source/destination coordinates.

An initial fixture also confirmed that vanilla accepts coal into the input slot from above, despite it not being smeltable. That is a valid transfer, not a failed insertion. Independent payload decoding checked the destination slots, item identities, component bytes and counts. The fixtures, inventories and only the force-loads added for these tests were removed; configurations were restored and both servers stopped normally. Backups remain outside Git.

## Scope

This is a short controlled acceptance and load comparison, with one pass per loader and logging setting. It is not a production capacity estimate or a long-running memory test. Synchronized hopper ticks deliberately expose bursts. Named/component-heavy streams, optimization-mod combinations, unloaded-neighbor behavior and sealed-loot generation still require their own runtime checks. The earlier named-item and small Lithium fixtures remain separate acceptance evidence. DuckDB correctness remains covered by its storage tests and earlier live fixtures; this load comparison used SQLite.

Numeric samples and decoded route summaries are stored in [the validation data](validation/hopper-pressure-alpha7.json).
