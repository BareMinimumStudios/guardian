# Minecraft version support

Guardian's available build is currently **Minecraft 1.21.1**, on Fabric and NeoForge.
The newer targets below have experimental testing builds. They are not public releases.

The owner requested the Minecraft versions published by [GriefLogger](https://modrinth.com/mod/grieflogger/versions),
strictly above 1.21.1. Both loader variants were checked against its release feed on 2026-11-15.
Versions it skips are outside this scope. The first port target is **26.2**, followed by the
remaining entries. The existing 1.21.1 build is retained.

| Minecraft | Fabric | NeoForge | Java |
| --- | --- | --- | --- |
| 1.21.1 | Testing alpha.98 | Testing alpha.98 | 21 |
| 1.21.4 | Experimental; acceptance pending | Experimental; acceptance pending | 21 |
| 1.21.5 | Experimental; acceptance pending | Experimental; acceptance pending | 21 |
| 1.21.6 | Experimental; acceptance pending | Experimental (NeoForge beta); acceptance pending | 21 |
| 1.21.7 | Experimental; acceptance pending | Experimental (NeoForge beta); acceptance pending | 21 |
| 1.21.8 | Experimental; acceptance pending | Experimental; acceptance pending | 21 |
| 1.21.9 | Experimental; acceptance pending | Experimental (NeoForge beta); acceptance pending | 21 |
| 1.21.10 | Experimental; acceptance pending | Experimental; acceptance pending | 21 |
| 1.21.11 | Experimental; acceptance pending | Experimental; acceptance pending | 21 |
| 26.1.2 | Experimental; acceptance pending | Experimental; acceptance pending | 25 |
| 26.2 | Experimental; acceptance pending | Experimental; acceptance pending | 25 |

The [machine-readable matrix](../gradle/minecraft-targets.json) links each target to its published
GriefLogger reference and Mojang release manifest. GriefLogger shares files across some patch
releases; Guardian must verify each version before making that compatibility claim.

## Porting gates

Each Minecraft/loader pair needs dependency resolution, compilation, packaged metadata checks
and isolated server checks before being marked available. Native checks cover capture hooks,
inspection, transactions, command/whisper permissions, saved rollback and interruption recovery.
An executable that starts successfully has not necessarily verified its capture hooks.

Minecraft 26.x requires Java 25 and an unobfuscated build pipeline. Earlier 1.21.x targets use
Java 21 and official Mojang mappings. The wrapper is Gradle 9.9.1, updated at the owner's request.
The isolated `ports/` build covers every newer target in the table on both loaders. Each has
passed automated tests and dedicated-server hook/inventory probes. Those probes force the
native mixin target classes to load and check guarded inventory ownership and saved-image
readback. They do not verify every capture path, permissions provider or interruption scenario.
Selected native block/item rollback and targeted recovery scenarios now pass; see
[the validation scope](PORT_VALIDATION.md). Connected-client, provider/modpack and broader
interruption-boundary acceptance remain pending.
The existing 1.21.1 build remains separate from these experiments.

The platform-neutral domain/storage/queue/recovery code should stay shared. Minecraft hooks,
serialization and inventory/save adapters need version-specific compatibility checks. Old item
components and block-entity payloads must not be assumed safe to restore across game upgrades.
Preserve history and fail closed on incompatible recovery data until migration is verified.

The optional GPL WorldEdit adapter remains separate and Fabric-only. Its newer-version support
requires matching WorldEdit builds and separate checks; core port status does not enable it.

No existing world, database or publish workflow is changed by these experimental builds.
Use the existing 1.21.1 jars until a target is explicitly marked available with build evidence.

Sources: [GriefLogger release feed](https://api.modrinth.com/v2/project/grieflogger/version),
[Fabric 26.1 tooling changes](https://fabricmc.net/2026/03/14/261.html),
[Fabric 26.2 tooling notes](https://fabricmc.net/2026/06/15/262.html),
[Cloche targets](https://github.com/terrarium-earth/cloche).

The 1.21.9 experiment uses the published NeoForge 21.9.16-beta build. Its loader
channel remains beta; passing Guardian checks does not change that status.

The 1.21.5 inventory adapter owns its native 41 slots. It refuses player body equipment
that has no persistent inventory address in that version, before detaching any backing state.
