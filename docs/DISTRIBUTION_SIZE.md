# Distribution size

Measured 2026-10-07 on the alpha.7 runtime artifacts. Both loader jars are about 88 MiB (roughly 92 MB in decimal units).

| Contents | Compressed size inside the Fabric jar |
|---|---:|
| DuckDB JDBC 1.4.5.0 | 76.17 MiB |
| SQLite JDBC 3.53.4.0 | 11.41 MiB |
| Guardian code, resources, metadata and archive overhead | 0.44 MiB |

NeoForge has nearly identical totals. Kotlin, Fabric API, Fzzy Config, Minecraft and WorldEdit are not embedded into the core jar. The large drivers contain native database libraries for multiple platforms. Numeric byte counts are in [the artifact measurements](validation/artifact-size-alpha7.json).

## CoreProtect comparison

CoreProtect's current [Maven definition](https://github.com/PlayPro/CoreProtect/blob/master/pom.xml) marks DuckDB JDBC as provided; its [plugin definition](https://github.com/PlayPro/CoreProtect/blob/master/src/main/resources/plugin.yml) declares DuckDB under server-loaded libraries. The server resolves that dependency outside the plugin jar. A small plugin download therefore does not measure its complete database dependency footprint.

The visible [commit history](https://github.com/PlayPro/CoreProtect/commits/master/) includes avoiding unnecessary block-state reads and regex compilation, item metadata improvements, lookup filters and WorldEdit logging changes. These are useful reference topics for future parity reviews. Guardian continues to target Minecraft 1.21.1 and its staged architecture; no CoreProtect implementation was copied in this milestone.

## Next packaging milestone

Keep SQLite self-contained in the default Guardian jar and distribute DuckDB as an optional loader-compatible add-on. The estimated default size is about 12 MiB. Preserve existing DuckDB databases and configuration: selecting DuckDB without its add-on must produce a clear startup error instead of switching storage silently. Test both backends on both loaders before changing the release workflow and installation instructions.

This plan removes an unused optional backend from most installations. Moving all drivers to external dependencies would make the core jar smaller again, but would add installation requirements rather than reduce their total size. Native architecture stripping is not planned for the universal builds.

Alpha.7 still bundles both drivers. Its size has not been reduced by the hopper snapshot optimization; runtime encoding cost and distribution size are separate measurements.
