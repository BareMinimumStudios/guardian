# Database packaging

Standard Guardian builds bundle SQLite only. This gives both Fabric and NeoForge a self-contained database with no extra installation step. DuckDB is omitted from the runtime dependency graph and nested jars, reducing each core jar from about 88 MiB to about 12 MiB.

## Optional DuckDB support

The existing backend implementation remains available and covered by storage tests. A maintainer can build a self-contained variant with both drivers:

```powershell
.\gradlew clean build -PbundleDuckDb=true --no-daemon --warning-mode all
```

These artifacts include `with-duckdb` in their names. They have the same Guardian mod ID, so install only one variant for a loader. No separate database add-on or automatic download is introduced. The standard publishing workflow builds the SQLite variant and rejects artifacts that include DuckDB or exceed 16 MiB.

Selecting `DUCKDB` in a standard installation requires the driver to be supplied to Guardian's runtime separately or a DuckDB-enabled build. A plain JDBC jar in `mods` is not a documented installation method. Missing drivers produce a clear error before creating database/lock files. Guardian does not silently switch backends or convert an existing DuckDB database into SQLite. A separate SQLite database starts separate history.

## Why the previous jar was large

The alpha.7 nested drivers accounted for almost the entire file:

| Contents | Compressed size inside the Fabric jar |
|---|---:|
| DuckDB JDBC 1.4.5.0 | 76.17 MiB |
| SQLite JDBC 3.53.4.0 | 11.41 MiB |
| Guardian code, resources, metadata and archive overhead | 0.44 MiB |

The native libraries support several operating systems. Kotlin, Fabric API, Fzzy Config, Minecraft and WorldEdit are not embedded in the core. No native architecture stripping is used.

CoreProtect's [Maven definition](https://github.com/PlayPro/CoreProtect/blob/master/pom.xml) marks DuckDB as provided and its [plugin definition](https://github.com/PlayPro/CoreProtect/blob/master/src/main/resources/plugin.yml) declares a server-loaded library. That keeps it outside the plugin download rather than eliminating the dependency.

Guardian's [startup comparison](STARTUP_VALIDATION.md) found a material NeoForge retained-heap difference with the large nested driver. Those observations and the user's SQLite preference motivated the default change. The database writer still batches work on a background thread; this was not a fix for DuckDB calls during server ticks.
