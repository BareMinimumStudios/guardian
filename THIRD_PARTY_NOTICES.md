# Third-party notices

Guardian is licensed separately under the Bare Minimum License (BML) v1.0. Third-party components retain their own licenses.

## CoreProtect

The public CoreProtect source is used as a behavioral and migration-format reference. CoreProtect is licensed under the Artistic License 2.0. Guardian does not claim the CoreProtect name, logo, or trademarks.

## Fzzy Config

Guardian depends on Fzzy Config as an external Fabric mod dependency and does not bundle or copy its source. Fzzy Config retains its own license and copyright notices.

## Fabric Permissions API / LuckPerms

Guardian compiles against Fabric Permissions API as an optional API and can use LuckPerms or another compatible permission provider when present. These projects retain their own licenses.

## SQLite JDBC

Guardian embeds `org.xerial:sqlite-jdbc` so SQLite storage works out of the box. The Xerial SQLite JDBC driver is distributed under the Apache License 2.0 and BSD-2-Clause terms described by that project.

## DuckDB JDBC

Guardian embeds `org.duckdb:duckdb_jdbc`. DuckDB is distributed under the MIT License.

## WorldEdit / Guardian WorldEdit Adapter

The BML Guardian core contains no WorldEdit imports and does not bundle WorldEdit. Step 3 adds a separate `worldedit-adapter` Gradle module/artifact that compiles against WorldEdit 7.3.8 and is not included in the core jar. That adapter carries its own GPLv3-compatible license and requires both Guardian and WorldEdit at runtime. WorldEdit retains its own copyright and GNU GPL terms.

Because the BML core and GPL adapter are separately licensed artifacts, redistribution of a combined package should be reviewed against the applicable licenses rather than assuming the BML terms apply to WorldEdit or the adapter.

## Gradle wrapper

The Gradle wrapper files retain Gradle's applicable license and notices.
