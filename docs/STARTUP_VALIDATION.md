# Bundled-driver startup and memory observations

Date: 2026-10-07. Guardian `0.4.0-alpha.7+1.21.1`; Java 21.0.10; SQLite selected on both dedicated fixtures.

The normal bundled jar was compared with a temporary test-only variant that omitted DuckDB and its loader metadata. Both retained SQLite, identical core code, world, settings, other mods and JVM memory limits (`-Xms512M -Xmx2G`). Each loader ran three times with each variant, alternating their order. Readiness measures process launch to the server’s `Done` message. Class-load tracing was enabled for every startup sample. These are warm-filesystem observations; the OS file cache was not flushed, and three samples do not establish a statistically precise overhead.

| Loader | DuckDB bundled | Startup seconds, min–max | Median seconds | Working set at ready, MiB |
|---|---|---:|---:|---:|
| Fabric | Yes | 11.587–12.122 | 11.674 | 863.4–893.2 |
| Fabric | Test-only omission | 9.164–11.090 | 10.868 | 863.1–888.4 |
| Neoforge | Yes | 10.759–12.905 | 12.865 | 1146.6–1154.1 |
| Neoforge | Test-only omission | 10.761–11.943 | 11.612 | 850.2–867.7 |

Bundled startup medians were about 0.8 seconds higher on Fabric and 1.3 seconds higher on NeoForge in this comparison. Some sample ranges overlap. This includes loader discovery of the larger jar, not just Guardian’s storage initialization. It does not measure the full production modpack.

## What SQLite loads

Bundled SQLite runs registered `org.duckdb.DuckDBDriver`, its anonymous helper and `org.duckdb.io.LimitedInputStream`. No `DuckDBNative` class appeared. JDBC discovery therefore loads a few Java driver classes, while the DuckDB native engine remains inactive. This is narrower than saying the bundled driver has no loading or memory cost.

## Memory follow-up

One further idle run per loader and variant sampled process working set and `jcmd GC.heap_info` after ten seconds. An explicitly requested diagnostic garbage collection was then followed by a second sample two seconds later. This diagnostic is a test procedure; Guardian does not force garbage collection. Used heap, committed heap and process working set measure different things.

| Loader | DuckDB bundled | Used heap after diagnostic GC, MiB | Committed heap, MiB | Working set, MiB |
|---|---|---:|---:|---:|
| Fabric | Yes | 262.77 | 545.0 | 859.0 |
| Fabric | Test-only omission | 262.68 | 531.0 | 841.4 |
| Neoforge | Yes | 471.45 | 802.0 | 1132.4 |
| Neoforge | Test-only omission | 240.72 | 512.0 | 839.3 |

Fabric’s post-GC used heap differed by less than 1 MiB. NeoForge retained about 231 MiB more used heap with the bundled jar. The latter is a material observation and is not explained by loading the native engine, which was absent from the class trace. Nested-archive retention is a candidate explanation, not a proven object-ownership diagnosis.

Three additional NeoForge runs stored the nested JDBC jar entries without outer ZIP compression. Retained heap remained around 472 MiB and startup around 12.5–12.9 seconds; that packaging adjustment did not address the difference and was not adopted.

## Decision and remaining work

These alpha.7 comparisons restored the original bundled jar bytes after every run. The subsequent alpha.8 decision bundles SQLite by default and retains DuckDB as an optional build-time integration. No temporary test variant is a release artifact, and no runtime loading implementation changed in this checkpoint. Both servers stopped normally; their original jars and configuration were restored. Prior database/configuration backups remain outside Git.

The standard alpha.8 artifact omits the large driver; startup and memory observations for the shipped artifact are documented separately. Retained-archive investigation remains relevant to the optional DuckDB-enabled variant. Do not claim that the large file is free of startup or memory effects. Continue the separate steady-runtime and component-heavy capture measurements.

Raw numeric samples and class lists are in [the validation data](validation/startup-loot-alpha7.json).
