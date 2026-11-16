# Minecraft version support

Guardian's available build is currently **Minecraft 1.21.1**, on Fabric and NeoForge.
Newer ports are planned below. They are not yet installable Guardian builds.

The owner requested the Minecraft versions published by [GriefLogger](https://modrinth.com/mod/grieflogger/versions),
strictly above 1.21.1. Both loader variants were checked against its release feed on 2026-11-15.
Versions it skips are outside this scope. The first port target is **26.2**, followed by the
remaining entries. The existing 1.21.1 build is retained.

| Minecraft | Fabric | NeoForge | Java |
| --- | --- | --- | --- |
| 1.21.1 | Available alpha.96 | Available alpha.96 | 21 |
| 1.21.4 | Planned | Planned | 21 |
| 1.21.5 | Planned | Planned | 21 |
| 1.21.6 | Planned | Planned | 21 |
| 1.21.7 | Planned | Planned | 21 |
| 1.21.8 | Planned | Planned | 21 |
| 1.21.9 | Planned | Planned | 21 |
| 1.21.10 | Planned | Planned | 21 |
| 1.21.11 | Planned | Planned | 21 |
| 26.1.2 | Planned | Planned | 25 |
| 26.2 | Planned | Planned | 25 |

The [machine-readable matrix](../gradle/minecraft-targets.json) links each target to its published
GriefLogger reference and Mojang release manifest. GriefLogger shares files across some patch
releases; Guardian must verify each version before making that compatibility claim.

## Porting gates

Each Minecraft/loader pair needs dependency resolution, compilation, packaged metadata checks
and isolated server checks before being marked available. Native checks cover capture hooks,
inspection, transactions, command/whisper permissions, saved rollback and interruption recovery.
An executable that starts successfully has not necessarily verified its capture hooks.

Minecraft 26.x requires Java 25 and an unobfuscated build pipeline. Earlier 1.21.x targets use
Java 21 and official Mojang mappings. Gradle 9.8.0 remains the requested wrapper version.
Existing Fzzy Config and Kotlin dependencies have releases for 26.2, but their integration still
needs testing. Cloche's multi-target model is a candidate for build management; selection awaits
a working port prototype rather than changing the validated 1.21.1 build first.

The platform-neutral domain/storage/queue/recovery code should stay shared. Minecraft hooks,
serialization and inventory/save adapters need version-specific compatibility checks. Old item
components and block-entity payloads must not be assumed safe to restore across game upgrades.
Preserve history and fail closed on incompatible recovery data until migration is verified.

The optional GPL WorldEdit adapter remains separate and Fabric-only. Its newer-version support
requires matching WorldEdit builds and separate checks; core port status does not enable it.

No world, database, loader compatibility range or publish workflow is expanded by this plan.
Use the existing 1.21.1 jars until a target is explicitly marked available with build evidence.

Sources: [GriefLogger release feed](https://api.modrinth.com/v2/project/grieflogger/version),
[Fabric 26.1 tooling changes](https://fabricmc.net/2026/03/14/261.html),
[Fabric 26.2 tooling notes](https://fabricmc.net/2026/06/15/262.html),
[Cloche targets](https://github.com/terrarium-earth/cloche).
