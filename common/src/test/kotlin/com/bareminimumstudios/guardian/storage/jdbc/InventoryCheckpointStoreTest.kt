package com.bareminimumstudios.guardian.storage.jdbc

import com.bareminimumstudios.guardian.rollback.ItemRollbackPhase
import java.sql.DriverManager
import java.sql.SQLException
import com.bareminimumstudios.guardian.checkpoint.*
import com.bareminimumstudios.guardian.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.*

class InventoryCheckpointStoreTest {
    private fun backends(test:((Path)->JdbcStorageBackend,Path)->Unit) {
        val folder=Files.createTempDirectory("guardian-checkpoints")
        test(::SqliteStorageBackend,folder.resolve("test.sqlite"));test(::DuckDbStorageBackend,folder.resolve("test.duckdb"))
    }
    private fun checkpoint(player:UUID=UUID.randomUUID(),time:Long=100,size:Int=41,data:ByteArray=byteArrayOf(1,2,3)):InventoryCheckpoint {
        val owner=ItemSlotOwner.PlayerInventory(player)
        val stack=ItemStackSnapshot(ResourceId.parse("minecraft:coal"),9,BinaryPayload.of(data))
        return InventoryCheckpoint(UUID.randomUUID(),player,"Alex",time,"1.21.1",InventoryCheckpointReason.MANUAL,
            ResourceId.parse("minecraft:overworld"),BlockPosition(1,64,2),InventorySnapshot((0 until size).associate {
                ItemSlotAddress(owner,it) to if(it==0)stack else ItemStackSnapshot.EMPTY
            }))
    }
    private fun connection(db:JdbcStorageBackend):Connection = JdbcStorageBackend::class.java.getDeclaredField("connection").let {
        it.isAccessible=true;it.get(db) as Connection
    }

    @Test fun equipmentImagesSurviveRestartAndDedupIncludesEquipment() = backends { factory,path ->
        val normal=checkpoint()
        val address=EquipmentSlotAddress("trinkets","hand/ring/0")
        val equipment=PlayerEquipmentSnapshot(mapOf("trinkets" to "3.10.0"),mapOf(address to ItemStackSnapshot.EMPTY))
        fun value(image:PlayerEquipmentSnapshot)=InventoryCheckpoint(UUID.randomUUID(),normal.playerId,normal.playerName,100,
            normal.minecraftVersion,InventoryCheckpointReason.PERIODIC,normal.dimension,normal.position,normal.inventory,image)
        val first=value(equipment)
        factory(path).use { db ->
            db.open();assertEquals(42,db.saveInventoryCheckpoint(first,InventoryCheckpointLimits()).slotCount)
            assertEquals(first.checkpointId,db.saveInventoryCheckpoint(value(equipment),InventoryCheckpointLimits()).checkpointId)
            val changed=value(PlayerEquipmentSnapshot(equipment.providers,mapOf(address to normal.inventory.slots.values.first { !it.isEmpty })))
            assertEquals(changed.checkpointId,db.saveInventoryCheckpoint(changed,InventoryCheckpointLimits()).checkpointId)
        }
        factory(path).use { db ->
            db.open();assertEquals(equipment,db.inventoryCheckpoint(first.checkpointId)?.equipment)
            assertEquals(2,db.inventoryCheckpoints(normal.playerId).size)
        }
    }

    @Test fun fullCheckpointAndMetadataSurviveRestart() = backends { factory,path ->
        val value=checkpoint(size=43)
        factory(path).use { db -> db.open();val header=db.saveInventoryCheckpoint(value,InventoryCheckpointLimits());assertEquals(value.checkpointId,header.checkpointId) }
        factory(path).use { db ->
            db.open();val read=assertNotNull(db.inventoryCheckpoint(value.checkpointId))
            assertEquals(value.inventory.slots,read.inventory.slots);assertEquals(value.minecraftVersion,read.minecraftVersion)
            assertEquals(listOf(value.playerId),db.inventoryCheckpointPlayers("aLeX"))
            assertEquals(listOf(value.checkpointId),db.inventoryCheckpoints(value.playerId).map { it.checkpointId })
        }
    }

    @Test fun optionalPlayerAndTimeFiltersKeepSequencePagination() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint(time=200);val second=checkpoint(time=300);val third=checkpoint(first.playerId,time=250)
            listOf(first,second,third).forEach { db.saveInventoryCheckpoint(it,InventoryCheckpointLimits()) }
            assertEquals(listOf(third.checkpointId,second.checkpointId,first.checkpointId),db.inventoryCheckpoints().map { it.checkpointId })
            assertEquals(listOf(second.checkpointId),db.inventoryCheckpoints(null,1,1,250).map { it.checkpointId })
            assertEquals(listOf(third.checkpointId),db.inventoryCheckpoints(first.playerId,10,0,201).map { it.checkpointId })
            assertTrue(db.inventoryCheckpoints(null,10,0,301).isEmpty())
            assertFails { db.inventoryCheckpoints(null,51,0) }
            assertFails { db.inventoryCheckpoints(null,10,0,-1) }
            assertFails { db.inventoryCheckpoints(null,10,100001) }
        }
    }

    @Test fun schema29UpgradeAddsQueryIndexesWithoutChangingCheckpointData() = backends { factory,path ->
        val value=checkpoint()
        val current=checkpoint(value.playerId,time=200,data=byteArrayOf(4,5,6))
        val before=InventoryCheckpoint(current.checkpointId,current.playerId,current.playerName,current.capturedAt,current.minecraftVersion,
            InventoryCheckpointReason.BEFORE_RESTORE,current.dimension,current.position,current.inventory)
        val operation=UUID.randomUUID()
        factory(path).use { db ->
            db.open();db.saveInventoryCheckpoint(value,InventoryCheckpointLimits())
            val record=db.prepareCheckpointRestore(operation,200,value.checkpointId,before,CheckpointRequester(null,"Console"),InventoryCheckpointLimits())
            assertTrue(db.protectItemRollback(record,before.inventory))
            assertTrue(db.transitionItemRollback(operation,ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING))
        }
        DriverManager.getConnection((if(path.toString().endsWith("duckdb"))"jdbc:duckdb:" else "jdbc:sqlite:")+path).use { c ->
            c.createStatement().use {
                it.executeUpdate("DROP INDEX ex_checkpoint_budget_idx")
                it.executeUpdate("DROP INDEX ex_checkpoint_name_idx")
                it.executeUpdate("DELETE FROM ex_schema_migrations WHERE version>=30")
                it.executeUpdate("UPDATE ex_meta SET meta_value='29' WHERE meta_key='schema_version'")
            }
        }
        factory(path).use { db ->
            db.open();assertEquals(GuardianSchema.CURRENT_VERSION,db.health().schemaVersion)
            assertEquals(value.inventory.slots,db.inventoryCheckpoint(value.checkpointId)?.inventory?.slots)
            assertEquals(listOf(value.playerId),db.inventoryCheckpointPlayers("aLeX"))
            assertEquals(listOf(before.checkpointId,value.checkpointId),db.inventoryCheckpoints().map { it.checkpointId })
            val receipt=assertNotNull(db.itemRollbackImages(operation))
            assertFalse(receipt.acknowledged)
            assertEquals(ItemRollbackPhase.RECOVERY_REQUIRED,receipt.images.record.phase)
            assertEquals(value.checkpointId,receipt.images.record.checkpointOf)
            assertEquals(before.inventory.slots,receipt.images.original.slots)
            assertEquals(value.inventory.slots,receipt.images.expected.slots)
            assertTrue(db.itemRollbackProtected(operation))
            assertFails { db.checkpointRestoreSource(value.checkpointId) }
            for(sql in GuardianSchema.migrations.single { it.version==30 }.statements) {
                assertFailsWith<SQLException> { connection(db).createStatement().use { it.executeUpdate(sql) } }
            }
            if(path.toString().endsWith("sqlite")) {
                connection(db).createStatement().use { s ->
                    s.executeQuery("EXPLAIN QUERY PLAN SELECT COUNT(*),COALESCE(SUM(payload_bytes),0) FROM ex_inventory_checkpoint").use { rows ->
                        val details=buildList { while(rows.next())add(rows.getString(4)) }.joinToString(" ")
                        assertTrue("COVERING INDEX ex_checkpoint_budget_idx" in details,details)
                    }
                    s.executeQuery("EXPLAIN QUERY PLAN SELECT DISTINCT player_uuid FROM ex_inventory_checkpoint WHERE lower(player_name)=lower('aLeX') ORDER BY player_uuid LIMIT 2").use { rows ->
                        val details=buildList { while(rows.next())add(rows.getString(4)) }.joinToString(" ")
                        assertTrue("ex_checkpoint_name_idx" in details,details)
                    }
                }
            }
        }
    }

    @Test fun retentionRemovesOnlyOlderUnclaimedCheckpoints() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint();val other=checkpoint();val limits=InventoryCheckpointLimits(perPlayer=1,total=3)
            db.saveInventoryCheckpoint(first,limits);db.saveInventoryCheckpoint(other,limits)
            val next=checkpoint(first.playerId,time=90);db.saveInventoryCheckpoint(next,limits)
            assertNull(db.inventoryCheckpoint(first.checkpointId));assertNotNull(db.inventoryCheckpoint(other.checkpointId))
            assertEquals(next.checkpointId,db.inventoryCheckpoints(first.playerId).single().checkpointId)
        }
    }

    @Test fun recoveryClaimsAreNeverPrunedToMakeRoom() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint();val limits=InventoryCheckpointLimits(perPlayer=1,total=1)
            db.saveInventoryCheckpoint(first,limits)
            connection(db).prepareStatement("INSERT INTO ex_checkpoint_restore_claim(checkpoint_uuid,operation_uuid) VALUES (?,?)").use {
                it.setString(1,first.checkpointId.toString());it.setString(2,UUID.randomUUID().toString());it.executeUpdate()
            }
            val next=checkpoint(first.playerId,time=101)
            assertFailsWith<InventoryCheckpointCapacityException> { db.saveInventoryCheckpoint(next,limits) }
            assertNotNull(db.inventoryCheckpoint(first.checkpointId));assertNull(db.inventoryCheckpoint(next.checkpointId))
        }
    }

    @Test fun byteBudgetPrunesOldUnclaimedImagesWithoutTruncatingItems() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint(data=ByteArray(1024*1024) { 7 })
            val second=checkpoint(first.playerId,time=101,data=ByteArray(1024*1024) { 8 })
            val limits=InventoryCheckpointLimits(bytes=2L*1024*1024)
            db.saveInventoryCheckpoint(first,limits);db.saveInventoryCheckpoint(second,limits)
            assertNull(db.inventoryCheckpoint(first.checkpointId))
            assertEquals(second.inventory.slots,db.inventoryCheckpoint(second.checkpointId)?.inventory?.slots)
        }
    }

    @Test fun periodicCaptureSkipsIdenticalImagesWhileManualCheckpointsRemainDistinct() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint();val limits=InventoryCheckpointLimits();db.saveInventoryCheckpoint(first,limits)
            val periodic=InventoryCheckpoint(UUID.randomUUID(),first.playerId,first.playerName,101,first.minecraftVersion,
                InventoryCheckpointReason.PERIODIC,first.dimension,first.position,first.inventory)
            assertEquals(first.checkpointId,db.saveInventoryCheckpoint(periodic,limits).checkpointId)
            assertNull(db.inventoryCheckpoint(periodic.checkpointId))
            val manual=checkpoint(first.playerId,time=102);db.saveInventoryCheckpoint(manual,limits)
            assertEquals(2,db.inventoryCheckpoints(first.playerId).size)
        }
    }

    @Test fun periodicCaptureRetainsAChangedPlayerNameForOfflineLookup() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint();val limits=InventoryCheckpointLimits();db.saveInventoryCheckpoint(first,limits)
            val renamed=InventoryCheckpoint(UUID.randomUUID(),first.playerId,"AlexNew",101,first.minecraftVersion,
                InventoryCheckpointReason.PERIODIC,first.dimension,first.position,first.inventory)
            assertEquals(renamed.checkpointId,db.saveInventoryCheckpoint(renamed,limits).checkpointId)
            assertEquals(listOf(first.playerId),db.inventoryCheckpointPlayers("AlexNew"))
            assertEquals(2,db.inventoryCheckpoints(first.playerId).size)
            val unchanged=InventoryCheckpoint(UUID.randomUUID(),first.playerId,"AlexNew",102,first.minecraftVersion,
                InventoryCheckpointReason.PERIODIC,first.dimension,first.position,first.inventory)
            assertEquals(renamed.checkpointId,db.saveInventoryCheckpoint(unchanged,limits).checkpointId)
        }
    }

    @Test fun aCheckpointUuidCannotBeReusedWithAnotherImage() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val first=checkpoint();val limits=InventoryCheckpointLimits()
            db.saveInventoryCheckpoint(first,limits);db.saveInventoryCheckpoint(first,limits)
            val different=checkpoint(first.playerId)
            val reused=InventoryCheckpoint(first.checkpointId,first.playerId,first.playerName,first.capturedAt,first.minecraftVersion,
                first.reason,first.dimension,BlockPosition(9,64,9),different.inventory)
            assertFails { db.saveInventoryCheckpoint(reused,limits) }
            assertEquals(first.position,db.inventoryCheckpoint(first.checkpointId)?.position)
        }
    }

    @Test fun alteredPayloadOrIndexedMetadataCannotAuthorizeRecovery() = backends { factory,path ->
        factory(path).use { db ->
            db.open();val value=checkpoint();db.saveInventoryCheckpoint(value,InventoryCheckpointLimits())
            val valid=InventoryCheckpointCodec.encode(value);val corrupt=valid.copyOf();corrupt[40]=(corrupt[40].toInt() xor 1).toByte()
            connection(db).prepareStatement("UPDATE ex_inventory_checkpoint SET payload=? WHERE checkpoint_uuid=?").use {
                it.setBytes(1,corrupt);it.setString(2,value.checkpointId.toString());it.executeUpdate()
            }
            assertFails { db.inventoryCheckpoint(value.checkpointId) }
            connection(db).prepareStatement("UPDATE ex_inventory_checkpoint SET payload=?,player_name='Different' WHERE checkpoint_uuid=?").use {
                it.setBytes(1,valid);it.setString(2,value.checkpointId.toString());it.executeUpdate()
            }
            assertFails { db.inventoryCheckpoint(value.checkpointId) }
        }
    }
}
