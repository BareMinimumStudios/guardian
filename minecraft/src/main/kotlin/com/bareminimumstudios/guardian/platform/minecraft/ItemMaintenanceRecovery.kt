package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import com.bareminimumstudios.guardian.storage.codec.ItemRollbackImagesCodec
import com.bareminimumstudios.guardian.storage.StorageBackendFactory
import com.bareminimumstudios.guardian.storage.jdbc.SqliteStorageBackend
import com.bareminimumstudios.guardian.config.GuardianConfigManager
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.nbt.*
import net.minecraft.server.MinecraftServer
import net.minecraft.util.DirectoryLock
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.util.UUID

/** Explicit maintenance boot, before vanilla setup creates chunk workers, players or network listeners. */
object ItemMaintenanceRecovery {
    const val PROPERTY="guardian.itemRecovery"
    private val logger=LoggerFactory.getLogger("Guardian/ItemRecovery")
    @JvmStatic fun requested()=System.getProperty(PROPERTY)!=null

    @JvmStatic fun run(server: MinecraftServer, access: LevelStorageSource.LevelStorageAccess, lock: DirectoryLock): Int {
        return try {
            check(server.isDedicatedServer && lock.isValid) { "Maintenance recovery requires the dedicated server's exclusive world lock" }
            val parts=System.getProperty(PROPERTY).split(':')
            require(parts.size in 2..3 && parts[0] in setOf("preview","apply")) { "Use -Dguardian.itemRecovery=preview:<operation UUID> or apply:<operation UUID>:<token>" }
            val id=UUID.fromString(parts[1]);require(id.toString()==parts[1])
            val apply=parts[0]=="apply"
            require(parts.size==if(apply)3 else 2)
            val root=server.serverDirectory.toAbsolutePath().normalize().resolve("guardian")
            val backend=StorageBackendFactory.create(GuardianConfigManager.config.storage,root)
            require(backend is SqliteStorageBackend) { "Maintenance recovery currently supports SQLite only" }
            backend.use { db ->
                db.open()
                val engine=Engine(access.getLevelPath(LevelResource.ROOT),root.resolve("item-recovery"),db,lock)
                if(apply && (db.itemRollback(id)?.phase==ItemRollbackPhase.CANCELLED || db.itemRollbackImages(id)?.acknowledged==true)) {
                    logger.info("GUARDIAN_ITEM_RECOVERY_ALREADY_RESOLVED {}. No inventory files changed.",id)
                } else if(apply) {
                    engine.apply(id,UUID.fromString(parts[2])) { index -> logger.info("GUARDIAN_ITEM_RECOVERY_INSTALLED {} file {}",id,index) }
                    if(db.itemRollback(id)?.phase==ItemRollbackPhase.CANCELLED) logger.info("GUARDIAN_ITEM_RECOVERY_CANCELLED_UNSTARTED {}. No inventory files changed.",id)
                    else logger.info("GUARDIAN_ITEM_RECOVERY_COMPLETE {}. Saved target files and durable receipt verified. Remove the maintenance flag before normal startup.",id)
                } else {
                    val unstarted=engine.unstartedToken(id)
                    val plan=if(unstarted==null)engine.preview(id) else null
                    if(unstarted!=null) logger.info("GUARDIAN_ITEM_RECOVERY_UNSTARTED {}: no item writes began. Confirm cancellation: -Dguardian.itemRecovery=apply:{}:{}",id,id,unstarted)
                    else if(plan==null) logger.info("GUARDIAN_ITEM_RECOVERY_ALREADY_RESOLVED {}. No inventory files changed.",id)
                    else {
                        logger.info("GUARDIAN_ITEM_RECOVERY_WORLD {}",plan.world)
                        plan.entries.forEach { logger.info("GUARDIAN_ITEM_RECOVERY_FILE {}",it.relative) }
                        logger.info("GUARDIAN_ITEM_RECOVERY_PREVIEW {}: {} targeted files; no world/player files changed. Confirmation: -Dguardian.itemRecovery=apply:{}:{}",id,plan.entries.size,id,plan.token)
                    }
                }
            }
            logger.info("Maintenance run finished. Gameplay was not started.")
            0
        } catch(error: Exception) {
            logger.error("GUARDIAN_ITEM_RECOVERY_REFUSED: {}. No gameplay will start; journal protection remains.",error.message,error)
            2
        }
    }

    internal class Engine(private val world: Path, recoveryRoot: Path, private val journal: ItemRollbackProtectionJournal, private val lock: DirectoryLock) {
        private val files=ItemRecoveryFiles(world,recoveryRoot)
        private fun receipt(id: UUID): ItemRollbackImageRecord {
            check(lock.isValid)
            val receipt=checkNotNull(journal.itemRollbackImages(id)) { "Complete protected inventory images are unavailable" }
            val images=receipt.images
            require(images.record.phase in setOf(ItemRollbackPhase.PREPARED,ItemRollbackPhase.APPLYING,ItemRollbackPhase.RECOVERY_REQUIRED,ItemRollbackPhase.COMPLETED))
            require(images.record.entries.all { ContainerRollbackPlanner.conserving(it.changes) }) { "Nonconserving journal cannot be repaired" }
            val owners=images.original.slots.keys.map { it.owner }.toSet()
            require(owners.size in 1..8 && images.original.slots.count { (address,item) -> images.expected.slots[address]!=item }<=64)
            require((images.original.slots.values+images.expected.slots.values).sumOf { (it.itemData?.size ?: 0).toLong() }<=2L*1024*1024)
            if(!receipt.acknowledged)require(journal.itemRollbackProtected(id)) { "Journal lacks persistent protection" }
            else require(images.record.phase==ItemRollbackPhase.COMPLETED)
            return receipt
        }
        fun unstartedToken(id: UUID): UUID? {
            if(journal.itemRollback(id)?.phase==ItemRollbackPhase.CANCELLED)return null
            val receipt=receipt(id)
            return if(receipt.images.record.phase==ItemRollbackPhase.PREPARED) UUID.nameUUIDFromBytes(("cancel:"+ItemRecoveryFiles.sha(ItemRollbackImagesCodec.encode(receipt.images))).toByteArray(Charsets.UTF_8)) else null
        }
        fun preview(id: UUID): ItemRecoveryFiles.Plan? {
            if(journal.itemRollback(id)?.phase==ItemRollbackPhase.CANCELLED)return null
            val receipt=receipt(id)
            if(receipt.acknowledged)return null
            require(receipt.images.record.phase!=ItemRollbackPhase.PREPARED) { "Unstarted operation requires cancellation confirmation" }
            val fingerprint=ItemRecoveryFiles.sha(ItemRollbackImagesCodec.encode(receipt.images))
            files.load(id,fingerprint)?.let { files.checkCurrent(it);return it }
            return files.prepare(id,fingerprint,edits(receipt.images))
        }
        fun apply(id: UUID, token: UUID, installed: (Int) -> Unit = {}) {
            if(journal.itemRollback(id)?.phase==ItemRollbackPhase.CANCELLED)return
            val receipt=receipt(id)
            if(receipt.images.record.phase==ItemRollbackPhase.PREPARED) {
                require(token==unstartedToken(id)) { "Unstarted cancellation confirmation differs" }
                check(journal.cancelUnstartedItemRollback(receipt.images.record))
                return
            }
            if(receipt.acknowledged)return
            val fingerprint=ItemRecoveryFiles.sha(ItemRollbackImagesCodec.encode(receipt.images))
            val plan=checkNotNull(files.load(id,fingerprint)) { "Run a maintenance preview first" }
            require(plan.token==token) { "Recovery confirmation does not match" }
            files.checkCurrent(plan)
            var record=receipt.images.record
            if(record.phase==ItemRollbackPhase.APPLYING) {
                check(journal.transitionItemRollback(id,ItemRollbackPhase.APPLYING,ItemRollbackPhase.RECOVERY_REQUIRED))
                record=checkNotNull(journal.itemRollback(id))
            }
            check(lock.isValid)
            files.install(plan,token) { index -> check(lock.isValid);installed(index) }
            // Every actual file is byte-identical to the staged file whose complete target inventories
            // were decoded and checked before this plan was committed. No live chunk save is running.
            check(lock.isValid && journal.acknowledgeItemRollback(record,receipt.images,receipt.images.expected))
            val confirmed=this.receipt(id)
            check(confirmed.acknowledged && confirmed.images.record.phase==ItemRollbackPhase.COMPLETED)
        }

        private fun edits(images: ItemRollbackImages): List<ItemRecoveryFiles.Edit> {
            val root=world.toRealPath()
            val owners=images.original.slots.keys.map { it.owner }.distinct()
            val grouped=owners.groupBy { owner -> when(owner) {
                is ItemSlotOwner.PlayerInventory -> root.resolve("playerdata").resolve("${owner.playerId}.dat")
                is ItemSlotOwner.BlockContainer -> {
                    val dimension=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension.toString()))
                    val folder=DimensionType.getStorageFolder(dimension,root).resolve("region").normalize()
                    val pos=owner.position
                    require(!Files.exists(folder.resolve("c.${pos.x shr 4}.${pos.z shr 4}.mcc"))) { "External chunk streams are not supported" }
                    folder.resolve("r.${(pos.x shr 4) shr 5}.${(pos.z shr 4) shr 5}.mca")
                }
                else -> error("Unsupported inventory owner")
            } }
            return grouped.entries.sortedBy { it.key.toString() }.map { (path, affected) ->
                require(path.normalize().startsWith(root) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(root)) { "Missing or unsafe saved inventory file" }
                val relative=root.relativize(path.normalize()).toString().replace('\\','/')
                ItemRecoveryFiles.Edit(relative) { staged ->
                    if(affected.first() is ItemSlotOwner.PlayerInventory) {
                        require(affected.size==1)
                        val tag=NbtIo.readCompressed(staged,NbtAccounter.create(16L*1024*1024))
                        MaintenanceInventoryNbt.repair(tag,affected.single(),images)
                        NbtIo.writeCompressed(tag,staged)
                        val verify=NbtIo.readCompressed(staged,NbtAccounter.create(16L*1024*1024))
                        val copy=verify.copy();MaintenanceInventoryNbt.repair(copy,affected.single(),images);check(copy==verify)
                    } else {
                        val first=affected.first() as ItemSlotOwner.BlockContainer
                        val dimension=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(first.dimension.toString()))
                        RegionFile(RegionStorageInfo(root.fileName.toString(),dimension,"guardian-item-recovery"),staged,staged.parent,true).use { region ->
                            affected.groupBy { owner -> (owner as ItemSlotOwner.BlockContainer).position.let { ChunkPos(it.x shr 4,it.z shr 4) } }.forEach { (pos, group) ->
                                val tag=region.getChunkDataInputStream(pos)?.use { NbtIo.read(it,NbtAccounter.create(32L*1024*1024)) } ?: error("Missing saved chunk")
                                group.forEach { MaintenanceInventoryNbt.repair(tag,it,images) }
                                region.getChunkDataOutputStream(pos).use { NbtIo.write(tag,it) }
                                require(!Files.exists(staged.parent.resolve("c.${pos.x}.${pos.z}.mcc"))) { "Repaired chunk needs an external stream; no live files changed" }
                                val verify=region.getChunkDataInputStream(pos)?.use { NbtIo.read(it,NbtAccounter.create(32L*1024*1024)) } ?: error("Staged chunk could not be read")
                                val copy=verify.copy();group.forEach { MaintenanceInventoryNbt.repair(copy,it,images) };check(copy==verify)
                            }
                            region.flush()
                        }
                    }
                }
            }
        }
    }
}
