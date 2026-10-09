# Started chunk-write acceptance

Date: 2026-10-09. Runtime remains `0.4.0-alpha.53+1.21.1`; this pass adds verification without a runtime change.

Ten isolated server processes passed 150 fixture checks across Fabric and NeoForge. Each loader ran cancellation/recovery, orderly shutdown, fresh-process successful recovery, process termination during a write and fresh-process refusal after that termination. SQLite integrity and the expected durable protection state passed in every case. Original dedicated servers and their databases were untouched. [Machine-readable evidence](validation/started-chunk-write-alpha53.json) records each case.

## What actually paused

The private fixture used Guardian's existing protected runtime admission for a two-barrel operation with complete persisted inventory images. Minecraft's real region-file channel was wrapped in a delegating `FileChannel`, installed on the owning I/O worker. The wrapper wrote actual chunk payload bytes to the original channel, then paused once before `RegionFile.write` could update and commit its region header. Both loaders' methods were checked through MixinMCP. No Minecraft bytecode was transformed and no fixture hook was added to the shipped mod.

The fixture recorded the number of bytes written, the sector position and the I/O thread. It also verified that the host was saving and the real physical prefix fence was still pending. This exercises started file persistence rather than a synthetic ticket or a queued request that has not entered a write.

## Outcomes

| Case | Expected and observed result |
| --- | --- |
| Cancel while the write is paused | Result cancellation preserves protected owners; later server ticks cannot complete the actual I/O fence. After releasing the write, explicit read-only recovery confirms full saved images and the durable receipt before releasing owners. |
| Stop while the write is paused | Runtime admission closes while the physical fence is pending. The process remains running until the fixture releases the write. SQLite then closes cleanly; the unacknowledged receipt, protection marker, two owner claims and source claim remain. |
| Restart after orderly interruption | Startup classifies the intent as recovery required without replay. Explicit fresh-process read-only handoff reconciles the complete saved images, acknowledges the receipt and releases the marker and owner claims; the source claim remains. |
| Terminate before region-header commit | Only the private fixture's own server process is terminated. SQLite remains marked unclean but passes integrity checks. The durable intent, complete unacknowledged images and claims survive. |
| Restart after termination | The previous region header exposes the older inventory image. Read-only recovery refuses that image rather than treating it as successful saved rollback contents. Protection and both owner claims remain, and item contents are not replayed. Subsequent orderly shutdown closes SQLite cleanly. |

The restart refusal fixture also checked the live inventories after inspection: the top barrel remained empty and the lower barrel retained its original coal. The successful recovery cases kept their source claims as audit evidence.

An initial fixture assertion used the wrong return type for the recovery admission method; it was corrected in the private harness. Another initial readiness assertion ran after NeoForge's `Done` message but before Guardian's `ServerStarted` initialization. The completed NeoForge restart case waits for a command barrier before inspecting startup classification. These were fixture errors, not runtime fixes, and are excluded from the passing case count.

The clean build, repeated build with configuration-cache reuse and separate WorldEdit build passed through IDEA. Existing successful results cover 522 tests; this documentation pass adds no unit tests or runtime version bump. Runtime artifacts remain SQLite-only.

## Remaining limits

These controlled fixtures cover small vanilla barrels in one loaded chunk. They do not certify connected-client synchronization, player-data saves, arbitrary mod inventories, off-thread writes, machine power loss or general filesystem durability. Started player saves and client lifecycle acceptance remain separate gates, alongside supported-environment exclusion and operator command integration. Item rollback apply remains disabled.
