# Andromeda compatibility notes

Reviewed the supplied client and server logs on 2026-10-07. The server runs Fabric 0.19.5 / Minecraft 1.21.1 and Guardian 0.4.0-alpha.4, with 395 loaded mod entries (including bundled dependencies). The client lists 472 entries. Client and server inventories are not interchangeable.

Relevant server versions include Lithium 0.15.4, Open Parties and Claims 0.32.7, Polymer 0.9.19, Supplementaries 3.9.9, Amendments 2.1.10, Dusty Decorations 2.2.0, Chipped 4.0.2, Handcrafted 4.0.3, furniture 1.1.6 and Farmer's Delight Refabricated 3.3.6.

The supplied server log contains no Guardian snapshot/queue/write failures. This does not establish complete capture coverage; the server's audit database and exact failing block/action are still needed to reproduce missing history.

At 19:10:38 and 19:10:51, Dusty Decorations `cowhide_rug` attempts to assign texture 3 to a property limited to 0..2. At 20:37:58, `fishing_lures` assigns texture 4 to a property limited to 0..3. Both exceptions originate in that mod's block-added callbacks, during placement. Guardian alpha.5 makes placement frame cleanup exception-safe and handles early-return paths separately. It does not suppress these mod errors or invent successful placement records for throwing actions.

Next connected-player acceptance should cover representative decoration placements, block-backed modded menus, claim-denied placement/break/use/click packets, and hopper transfers with the pack's optimizations. Polymer display blocks must be checked against server-side registry IDs. Unknown/entity/crafting-backed menus remain outside the accepted container slice. No full-pack compatibility claim is made from the mod list alone.

## Focused Lithium acceptance

Guardian alpha.5 started with the pack's exact Lithium 0.15.4 jar on the dedicated Fabric server. A chest/hopper/barrel fixture moved three coal, produced six persisted balanced transfer records, and showed zero capture/write/backpressure errors. Older transfers were reachable through `p:2`. The fixture and temporary jar were removed and the original configuration restored. This covers that small vanilla-container path with Lithium; other pack interactions remain pending.
