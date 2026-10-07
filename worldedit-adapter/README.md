# Guardian WorldEdit Adapter

This optional server-side Fabric 1.21.1 module logs WorldEdit changes through Guardian's integration API. Install it alongside Guardian and WorldEdit 7.3.8.

The adapter captures immutable block snapshots around WorldEdit mutations and submits bounded bulk operations to Guardian's writer. It also supplies WorldEdit selection filters for lookup and rollback.

```bash
./gradlew :worldedit-adapter:build --no-daemon --warning-mode all
```

Use the runtime jar from `worldedit-adapter/build/libs`. See [the testing guide](../docs/STEP_3_WORLD_EDIT_TESTING.md) for edit, undo, selection, and pressure checks.

This module is licensed under [GPL-3.0-or-later](LICENSE). It is distributed separately from the BML core and does not bundle WorldEdit.
