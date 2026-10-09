# Player persistence acceptance

Date: 2026-10-09. Runtime remains `0.4.0-alpha.53+1.21.1`; this pass adds evidence without a runtime change.

Ten isolated processes passed 189 fixture checks across Fabric and NeoForge. A server-created synthetic player exercised all 41 persistent inventory slots alongside a real barrel through Guardian's protected runtime coordinator. Successful saves and explicit reconciliation confirmed complete images. Failed saves and process termination kept the durable protection marker, both owner claims, the source claim and unacknowledged images. SQLite integrity passed in every case. Original dedicated servers and their player data were untouched. [Machine-readable evidence](validation/player-persistence-alpha53.json) records each case.

## Real file work under a controlled fixture

Minecraft's player-data save runs synchronously on the server thread. The private fixture replaced the storage directory's `File.toPath()` result with paths backed by a delegating file provider. Creation, attributes, reads, moves and ordinary writes still used the real filesystem. An armed channel wrote real compressed player bytes before either reporting an I/O failure or pausing before the temporary file replaced the current UUID file. The pause occurred past the ten-byte gzip header. No Minecraft bytecode was transformed and no fixture hook was added to the shipped mod.

MixinMCP checks confirmed each loader's save path. NeoForge's `PlayerList.save` intentionally skips players with no connection listener. Its fixture therefore supplied a real listener object backed by an unconnected `Connection`. The synthetic player was registered only for UUID lookup, outside the world-player tick list; no socket or client was connected. These fixtures exercise persistence, not login or packet synchronization.

## Outcomes on both loaders

| Case | Expected and observed result |
| --- | --- |
| Normal save | The complete live and saved player/barrel images match. The durable receipt is acknowledged before protection and owner claims are released. The source claim remains. |
| Failure after compressed bytes are written | Vanilla reports the save failure. Guardian refuses the unchanged current player file as evidence for the expected inventory and retains protection. A read-only recovery attempt also refuses the old image. Orderly shutdown closes SQLite cleanly without releasing the claims. |
| Explicit retry after all affected owners are saved | The first read-only attempt remains unresolved. The fixture then saves the current player and barrel through their vanilla persistence paths, waits for actual work to finish, and explicitly requests read-only reconciliation. Only the complete matching images permit acknowledgment and owner release. The reconciliation port itself does not write items or force saves. |
| Terminate during the player temporary-file write | Only the private fixture's own process is terminated, before replacement of the current UUID file. SQLite remains marked unclean but passes integrity checks; the durable applying intent and protected images/claims survive. |
| Fresh process after termination | Startup classifies the intent for recovery without replay. The fixture recreates its known empty synthetic inventory, matching the independently decoded unchanged current file. Explicit read-only admission remains unresolved and keeps protection. Subsequent orderly shutdown closes SQLite cleanly. This does not exercise vanilla client login or player loading. |

An independent bounded NBT reader checked the real player files in all ten cases. Successful current files contained the expected coal; failed and interrupted current files remained empty. The uncommitted temporary files in the failure, retry and termination cases contained complete compressed images with coal. Those valid temporary images were never substituted for the current UUID file. Successful replacement consumed its temporary file.

During fixture development, an initial transaction used a system actor for a player-owned slot and was correctly rejected. An initial retry saved only the player, leaving its affected barrel unsaved; Guardian correctly stayed unresolved until both owners were saved. Initial interception was also strengthened from the gzip header to compressed payload bytes. These setup attempts are excluded from the passing case count. The NeoForge fixture was adjusted for its listener precondition without changing production code.

The clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA. Successful cached results cover the existing 522 tests; this pass adds no unit tests or runtime version bump. Standard runtime artifacts remain SQLite-only.

## Remaining acceptance

Connected-client menus, cursor ownership, inventory updates, disconnect, death and dimension transfer still need acceptance. Synthetic players cannot certify those paths. Supported-environment exclusion and safe refusal of unsupported inventory implementations remain required, as do operator permissions, preflight, confirmation, cancellation and recovery messages. Item rollback apply remains disabled.
