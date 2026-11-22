# Protect structures from claims

Guardian can stop players claiming configured world structures through **Open Parties and Claims 0.33.0**. The integration is optional and off by default. Install OPAC and its normal dependencies on the server before enabling it.

## Set it up

Stop the server. In `config/guardian/main.toml`, set:

```toml
[structureClaims]
enabled = true
structures = "minecraft:ancient_city,minecraft:desert_pyramid"
bufferBlocks = 50
```

Use structure registry IDs, including modded IDs such as `example:castle`. These are structure IDs, not block IDs or schematic names. You can list up to 64 IDs, separated by commas or spaces. The buffer accepts 0–256 blocks. Restart after changing these settings.

Run `/guardian status` and look for `OPAC structures`. Startup also reports the number of protected IDs and the buffer. An enabled integration with missing OPAC, an unsupported OPAC version, unknown structure IDs or missing required hooks stops startup rather than silently leaving structures unprotected.

## What gets protected

Protection covers the **entire recorded structure bounding box**, plus the horizontal buffer. With `50`, a claim is refused if any part of its chunk lies within 50 blocks of that box. Since OPAC claims whole chunks, protection can extend to the edge of the last intersecting chunk. Height does not affect the decision.

Guardian checks loaded structure metadata and reads saved metadata in the background. It does not generate or load chunks to answer a claim request. A distant, ungenerated, outdated or still-checking area is refused with a retry message. Visit the area to let Minecraft generate or update it, then try again. A positive structure match is refused immediately.

This uses Minecraft's recorded structure starts and references. A building pasted with WorldEdit, or a modded building that never registers a native structure start, is outside this protection.

Existing claims stay in place. Enabling protection does not remove them or change who owns them. New claims, forced claim API writes, anchors and queued ownership replacements are checked; a refused replacement preserves the old claim. Unclaiming remains available.

## Staff reservations

Staff can reserve a protected structure only when **all three** conditions hold:

- They have `guardian.claims.structure.bypass` (operator level 4 by default).
- OPAC claims admin mode is enabled for them.
- They are using OPAC server claiming mode, and the new claim belongs to the server.

Use OPAC's own controls to enable its modes. Operator status alone does not allow a personal claim. Guardian rechecks permission, mode and the same connected player when queued work executes. Disconnecting or losing permission removes the bypass. OPAC's other limits and denials still apply.

## Version support

Use the OPAC 0.33.0 file matching your Minecraft version and loader. This integration is not a reason to install an OPAC jar for another Minecraft version. Where OPAC has no matching 0.33.0 build, leave Guardian's structure protection disabled; Guardian's logging and recovery work independently.

Keep world and Guardian database backups when upgrading. Structure protection does not replace a claims backup or recover claims removed by another tool.
