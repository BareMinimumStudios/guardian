# Guardian shared core

This BML module contains immutable domain values, storage backends, the bounded writer and bulk audit queues, command filters, and pure rollback decisions. It has no Minecraft or loader imports.

Fabric packages these classes directly into the Guardian runtime jar. The NeoForge port will use the same module; loader-specific events and Minecraft objects belong outside it.

```bash
./gradlew :common:test
```

The core license is in the [repository LICENSE](../LICENSE).
