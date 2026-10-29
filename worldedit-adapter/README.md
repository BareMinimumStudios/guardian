# Guardian WorldEdit Adapter

**Fabric only, Minecraft 1.21.1. Do not install this adapter on NeoForge.**

Install this optional server mod alongside Guardian and WorldEdit 7.3.8 to record WorldEdit changes.

The adapter captures immutable block snapshots around WorldEdit mutations and submits bounded bulk operations to Guardian's writer. It also supplies WorldEdit selection filters for lookup, rollback and block restore/redo.

```bash
./gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```

Use the runtime jar from `worldedit-adapter/build/libs`. Use `r:#worldedit` or `r:#we` with Guardian lookup/block rollback/restore to select your current cuboid. Confirm history after a small edit and WorldEdit undo in an isolated world. See the [release acceptance checklist](../docs/RELEASE_SCOPE.md).

This module is licensed under [GPL-3.0-or-later](LICENSE). It is distributed separately from the BML core and does not bundle WorldEdit.

For a recorded edit that Guardian has already rolled back, preview the selected region with `/guardian restore preview t:1h r:#worldedit`, then apply separately with `/guardian restore t:1h r:#worldedit`. Restore needs `guardian.restore` (operator level 3 by default). Its time filter refers to the original edit, not the later rollback. Use a backed-up isolated world and check the preview before applying.
