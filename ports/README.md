# Guardian experimental ports

These are testing builds for Fabric and NeoForge. The retained baseline targets Minecraft 1.21.1.
The current testing checkpoint is alpha.101 for every target. Newer builds still need connected-client,
real permission-provider, modpack and broader interruption-boundary acceptance.

## Targets and checks

| Minecraft | Java | Shared + loader tests | Checkpoint capture / restore / undo |
| --- | --- | --- | --- |
| 1.21.4 | 21 | 986 | PASS on both loaders |
| 1.21.5 | 21 | 988 | PASS on both loaders |
| 1.21.6 to 1.21.11 | 21 | 984 per version | PASS on both loaders |
| 26.1.2, 26.2 | 25 | 984 per version | PASS on both loaders |

NeoForge profiles for 1.21.6, 1.21.7 and 1.21.9 use beta loader builds.
Native probes load the configured Minecraft hook targets and check inventory ownership,
retained aliases, item components, saved-image readback and release. Passing these probes
does not certify all gameplay hooks or recovery scenarios.

## Build a target

Run from this directory with Java 21 available for 1.21.x, or Java 25 for 26.x:

```powershell
.\gradlew clean build -PguardianTarget=1.21.10 --no-daemon --warning-mode all
.\gradlew build -PguardianTarget=1.21.10 --no-daemon --warning-mode all
```

The wrapper uses Gradle 9.9.1. The default target is 26.2. Explicit dependency versions
are in `targets/<version>.properties`; install the chosen loader's dependencies on the
server. Guardian is not required on clients. Minecraft 1.21.x uses official Mojang
mappings; 26.x uses the unobfuscated game pipeline.

Each nondefault target writes jars under `build/<version>/fabric/libs` and
`build/<version>/neoforge/libs`. Default 26.2 loader outputs are `fabric/build/libs`
and `neoforge/build/libs`. Use the binary jar, not the `-sources.jar`.
The optional GPL WorldEdit adapter is separate and remains Fabric 1.21.1 only.

## Compatibility boundaries

Platform-neutral storage and recovery code stays shared. Version-specific source paths
replace matching shared adapters through the profile's source family. Target build
outputs are isolated. Test support libraries and native probe fixtures are not shipped.

Minecraft 1.21.4 and 1.21.5 have 41 player inventory slots; newer targets have 43.
The 1.21.5 adapter refuses nonempty player body equipment without a persistent inventory
address before ownership changes. The 1.21.4 adapter owns its main, armor and offhand
backing lists together with their compartment aliases.

Item and block-entity payloads include the Minecraft data version. Incompatible payloads
are refused; do not assume recovery journals can be applied after upgrading Minecraft.
Keep backups and resolve unfinished recovery before upgrading the game.

See [the support matrix](../docs/VERSION_SUPPORT.md) for target status.

Selected native block/item rollback and targeted recovery checks now pass on every listed
loader/version pair. See [rollback validation](../docs/PORT_VALIDATION.md) for the exact scope
and remaining limits.

[Item redo validation](../docs/validation/item-redo-alpha100.json) covers all target/loader
pairs, with selected interruption and unchanged-slot conflict checks on the endpoint versions.

[Full inventory checkpoint validation](../docs/validation/inventory-checkpoints-alpha101.json)
covers all targets. Shared storage suites run once for each image layout; every
Minecraft/loader pair still has its own build, unit tests and native recovery check.
