# Step 2B manual server test

Use Java 21 and a clean Fabric 1.21.1 server.

## Build

```bash
./gradlew clean test
./gradlew clean build
```

Install the resulting Guardian jar together with Fabric API, Fabric Language Kotlin, and Fzzy Config.

## Functional cases

With the default SQLite backend, join the server and test one operation at a time:

1. place and break stone;
2. place and break stairs in multiple orientations;
3. place a waterloggable block and verify the `waterlogged` property;
4. place a chest, add items, then break it (the break BEFORE payload should contain block-entity data);
5. place/edit/break a sign (block-entity payload should exist);
6. place scaffolding outward from an existing scaffold to verify resolved placement-position capture;
7. if a content mod is installed, place/break one modded block and verify its namespaced ID is stored unchanged.

## Database inspection

Stop the server cleanly before inspecting SQLite directly. The following query gives the newest block events with normalized IDs resolved:

```sql
SELECT
    b.rowid,
    b.time,
    a.uuid,
    a.name,
    w.world,
    b.x, b.y, b.z,
    before_r.resource AS before_block,
    before_d.data AS before_properties,
    after_r.resource AS after_block,
    after_d.data AS after_properties,
    b.action,
    length(b.before_meta) AS before_meta_bytes,
    length(b.after_meta) AS after_meta_bytes
FROM ex_block b
LEFT JOIN ex_actor_map a ON a.id = b.actor
JOIN ex_world_map w ON w.id = b.wid
JOIN ex_resource_map before_r ON before_r.id = b.before_type
JOIN ex_blockdata_map before_d ON before_d.id = b.before_data
JOIN ex_resource_map after_r ON after_r.id = b.after_type
JOIN ex_blockdata_map after_d ON after_d.id = b.after_data
ORDER BY b.rowid DESC
LIMIT 50;
```

Expected action codes from the current stable storage contract:

- `1` = block place
- `2` = block break

## Current scope caveats

Step 2B is intentionally not a complete world mutation logger. A door/bed/tall plant may create or change an additional block outside the primary placement position; those side effects are not guaranteed to be captured until the broader mutation-attribution step.
