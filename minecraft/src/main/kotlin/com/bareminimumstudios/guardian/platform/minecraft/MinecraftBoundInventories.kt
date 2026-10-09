package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.mixin.ChunkMapSaveInvoker
import com.bareminimumstudios.guardian.mixin.PlayerListSaveInvoker
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.level.block.entity.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.storage.LevelResource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Pinned live identities and saved inventory images. This is not proof of exclusive mutation ownership. */
class MinecraftBoundInventories internal constructor(
    private val server: MinecraftServer,
    private val lease: ItemOwnerCoordination.Lease,
    private val currentLease: () -> Boolean,
    private val players: () -> MinecraftSavedPlayerReader,
    private val onPlayerStateRejected: (ItemSlotOwner.PlayerInventory) -> Unit,
    private val onClose: (MinecraftBoundInventories) -> Unit
) : AutoCloseable {
    private data class Pin(val owner: ItemSlotOwner, val container: Container, val level: ServerLevel,
                           val chunk: LevelChunk?, val state: BlockState?, val size: Int)
    private val supportedBlocks = mapOf<Class<*>,Int>(
        BarrelBlockEntity::class.java to 27, ChestBlockEntity::class.java to 27, HopperBlockEntity::class.java to 5,
        DispenserBlockEntity::class.java to 9, DropperBlockEntity::class.java to 9, FurnaceBlockEntity::class.java to 3,
        BlastFurnaceBlockEntity::class.java to 3, SmokerBlockEntity::class.java to 3
    )
    private var playerStateRejected = false
    private val pins = lease.owners.associateWith(::resolve)
    private var closed = false
    private val pending = mutableSetOf<CompletableFuture<InventorySnapshot>>()

    init { check(isCurrent()) { "Inventory lease or identities changed during binding" }; read() }

    fun isCurrent(): Boolean {
        checkThread()
        if (closed || playerStateRejected || !currentLease()) return false
        return pins.all { (owner, pin) ->
            val matches = runCatching {
                val fresh = resolve(owner)
                fresh.container === pin.container && fresh.level === pin.level && fresh.chunk === pin.chunk &&
                    fresh.state == pin.state && fresh.size == pin.size
            }.getOrDefault(false)
            if (!matches && owner is ItemSlotOwner.PlayerInventory) {
                // Returning to an empty menu cannot revive an operation whose temporary
                // ownership changed. Revoke its lease; retained journal protection remains.
                playerStateRejected = true
                onPlayerStateRejected(owner)
            }
            matches
        }
    }

    /** Captures every logical persistent slot without unpacking loot or loading chunks. */
    fun read(): InventorySnapshot {
        checkThread()
        check(isCurrent()) { "Bound inventory identities are no longer current" }
        val slots = linkedMapOf<ItemSlotAddress, ItemStackSnapshot>()
        val capture = MinecraftItemSnapshotter.CaptureBatch(server.registryAccess())
        var bytes = 0L
        for ((owner, pin) in pins) for (index in 0 until pin.size) {
            val item = capture.capture(pin.container.getItem(index))
            bytes += item.itemData?.size ?: 0
            require(slots.size < 2048 && bytes <= 16L * 1024 * 1024) { "Bound inventory image exceeds budget" }
            slots[ItemSlotAddress(owner,index)] = item
        }
        check(isCurrent()) { "Bound inventory identity changed while reading" }
        return InventorySnapshot(slots)
    }

    fun write(address: ItemSlotAddress, expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        checkThread()
        check(isCurrent()) { "Bound inventory identities are no longer current" }
        val pin = checkNotNull(pins[address.owner]) { "Slot belongs to another inventory" }
        require(address.index in 0 until pin.size)
        MinecraftInventoryCoordination.writeReservedSlot(lease,pin.container,address.index,expected,replacement)
        check(isCurrent()) { "Bound inventory identities changed during writing" }
    }

    /** Save one pinned owner, flush/read real storage, then validate all live owners and the full saved image. */
    fun saveAndReadBack(owner: ItemSlotOwner): CompletionStage<InventorySnapshot> {
        checkThread()
        check(pending.isEmpty()) { "A bound inventory save is already pending" }
        val pin = checkNotNull(pins[owner]) { "Owner is not bound" }
        val baseline = read()
        val wanted = baseline.slots.keys.filter { it.owner == owner }.toSet()
        val expected = baseline.slots.filterKeys { it.owner == owner }
        val result = CompletableFuture<InventorySnapshot>()
        pending.add(result)
        try {
            val request = when (owner) {
                is ItemSlotOwner.BlockContainer -> {
                    val chunk = checkNotNull(pin.chunk)
                    chunk.setUnsaved(true)
                    if (!(pin.level.chunkSource.chunkMap as ChunkMapSaveInvoker).`guardian$saveChunk`(chunk)) {
                        chunk.setUnsaved(true)
                        error("Chunk save was not queued")
                    }
                    MinecraftSavedChunkReader.read(pin.level.chunkSource.chunkMap,chunk.pos)
                }
                is ItemSlotOwner.PlayerInventory -> {
                    val player = (pin.container as Inventory).player as ServerPlayer
                    (server.playerList as PlayerListSaveInvoker).`guardian$savePlayer`(player)
                    players().read(server.getWorldPath(LevelResource.PLAYER_DATA_DIR),owner.playerId)
                }
                else -> error("Unsupported bound owner")
            }
            check(read().slots == baseline.slots) { "Live contents changed during saving" }
            request.whenComplete { tag, failure ->
                if (!result.isDone) try {
                    server.execute {
                        if (result.isDone) return@execute
                        try {
                            check(failure == null && tag != null && tag.isPresent) { "Saved inventory is unavailable" }
                            check(read().slots == baseline.slots) { "Live identities or full contents changed during save/readback" }
                            val saved = when (owner) {
                                is ItemSlotOwner.BlockContainer -> {
                                    checkSavedType(tag.get(),pin)
                                    MinecraftSavedContainerDecoder.decode(tag.get(),owner,wanted,server.registryAccess())
                                }
                                is ItemSlotOwner.PlayerInventory -> MinecraftSavedPlayerDecoder.decode(tag.get(),owner,wanted,server.registryAccess())
                            }
                            check(saved.slots == expected) { "Actual saved inventory differs from the live image" }
                            check(read().slots == baseline.slots) { "Live contents changed while checking saved data" }
                            pending.remove(result)
                            result.complete(saved)
                        } catch (error: Exception) { pending.remove(result);result.completeExceptionally(error) }
                    }
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
        } catch (error: Exception) { pending.remove(result);result.completeExceptionally(error) }
        return result
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        val waiting = pending.toList()
        pending.clear()
        onClose(this)
        waiting.forEach { it.completeExceptionally(IllegalStateException("Bound inventory session closed")) }
    }

    private fun resolve(owner: ItemSlotOwner): Pin {
        checkThread()
        return when (owner) {
            is ItemSlotOwner.PlayerInventory -> {
                val player = checkNotNull(server.playerList.getPlayer(owner.playerId)) { "Player is not online" }
                val inventory = player.inventory
                check(player.server === server && !player.isRemoved && inventory.javaClass == Inventory::class.java && inventory.player === player && inventory.containerSize == 41)
                check(MinecraftPlayerInventoryEligibility.isIdle(player)) { "Player menu or temporary items are not idle" }
                Pin(owner,inventory,player.serverLevel(),null,null,41)
            }
            is ItemSlotOwner.BlockContainer -> {
                val key = ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(owner.dimension.toString()))
                val level = checkNotNull(server.getLevel(key)) { "Dimension is unavailable" }
                val pos = net.minecraft.core.BlockPos(owner.position.x,owner.position.y,owner.position.z)
                val chunk = checkNotNull(level.chunkSource.getChunkNow(pos.x shr 4,pos.z shr 4)) { "Chunk is not loaded" }
                val block = checkNotNull(level.getBlockEntity(pos)) { "Block inventory is unavailable" }
                val size = checkNotNull(supportedBlocks[block.javaClass]) { "Unsupported block inventory" }
                val container = block as Container
                check(!block.isRemoved && block.level === level && container.containerSize == size)
                check(block !is RandomizableContainerBlockEntity || block.lootTable == null) { "Deferred loot cannot be bound" }
                Pin(owner,container,level,chunk,level.getBlockState(pos),size)
            }
            else -> error("Only persistent vanilla inventories can be bound")
        }
    }

    private fun checkSavedType(tag: CompoundTag,pin: Pin) {
        val block = pin.container as BlockEntity
        val pos = block.blockPos
        val matches = tag.getList("block_entities",Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }.filter {
            it.getInt("x") == pos.x && it.getInt("y") == pos.y && it.getInt("z") == pos.z
        }
        check(matches.size == 1 && matches.single().getString("id") == BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(block.type).toString()) {
            "Saved block inventory type changed"
        }
    }

    private fun checkThread() = check(server.isSameThread) { "Bound inventories must use the server thread" }

}
