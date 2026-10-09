package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.mixin.CompoundContainerAccessor
import com.bareminimumstudios.guardian.rollback.ItemMenuCoordination
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import com.bareminimumstudios.guardian.rollback.ItemTransferCoordination
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.CompoundContainer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.*
import net.minecraft.world.level.block.entity.*
import java.util.Collections
import java.util.IdentityHashMap
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.entity.Hopper
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

object MinecraftInventoryCoordination {
    private class Binding(val server: MinecraftServer, val owners: ItemOwnerCoordination) {
        val transfers = ItemTransferCoordination(owners)
        val menus = ItemMenuCoordination(owners)
        val slotWriter = MinecraftReservedSlotWriter(server, owners)
        val lifecycleCopy = MinecraftLifecycleInventoryCopy(server)
        val lifecycleRemoval = MinecraftLifecycleItemRemoval(server)
        val actualIo = com.bareminimumstudios.guardian.rollback.ActualIoDrain()
        var operations: MinecraftItemOperations? = null
        var stopping = false
        val sessions = java.util.IdentityHashMap<ItemOwnerCoordination.Lease,MinecraftBoundInventories>()
        private var savedPlayers: MinecraftSavedPlayerReader? = null
        fun playerReader(): MinecraftSavedPlayerReader = savedPlayers ?: MinecraftSavedPlayerReader(actualIo, false).also { savedPlayers = it }
        fun closeSessions() { sessions.values.toList().forEach { it.close() };savedPlayers?.close();savedPlayers = null }
    }
    @Volatile private var binding: Binding? = null

    fun install(server: MinecraftServer): ItemOwnerCoordination {
        check(server.isSameThread)
        check(binding == null) { "Inventory coordination is already installed" }
        return ItemOwnerCoordination().also { binding = Binding(server, it) }
    }

    /** Attach only this server's runtime-owned coordinator; it grants no command admission. */
    internal fun attachOperations(server: MinecraftServer, operations: MinecraftItemOperations) {
        check(server.isSameThread)
        val current=checkNotNull(binding)
        check(current.server === server && !current.stopping && current.operations == null)
        current.operations=operations
    }

    fun uninstall(owners: ItemOwnerCoordination?) {
        check(tryUninstall(owners)) { "Journal protection must drain before inventory coordination is uninstalled" }
    }

    /** Nonblocking: the host closes its journal worker, polls drain, then retries shutdown. */
    fun tryUninstall(owners: ItemOwnerCoordination?): Boolean {
        val current = binding ?: return true
        if (owners !== current.owners) return true
        check(current.server.isSameThread)
        if (current.owners.hasJournalRetention()) return false
        if (!current.stopping) beginShutdown(owners)
        if (!current.actualIo.isDrained) return false
        current.owners.stop()
        current.closeSessions()
        binding = null
        return true
    }

    /** Stop bound I/O admission before disposing sessions. Result completion is not disk drain. */
    fun beginShutdown(owners: ItemOwnerCoordination?): java.util.concurrent.CompletionStage<Void> {
        val current = binding
        if (current == null || current.owners !== owners) return java.util.concurrent.CompletableFuture.completedFuture<Void>(null).minimalCompletionStage()
        check(current.server.isSameThread)
        current.stopping = true
        current.actualIo.close()
        current.owners.stop()
        current.closeSessions()
        return current.actualIo.drained
    }

    @JvmStatic fun allowsPush(level: Level, hopper: HopperBlockEntity): Boolean = allows(level) {
        endpoints(it, hopper.blockPos, hopper.blockPos.relative(hopper.blockState.getValue(HopperBlock.FACING)))
    }

    @JvmStatic fun allowsPull(level: Level, hopper: Hopper): Boolean = allows(level) {
        if (hopper !is HopperBlockEntity) null else endpoints(it, hopper.blockPos, hopper.blockPos.above())
    }

    @JvmStatic fun allowsDispense(level: Level): Boolean = allowsAutomation(level)

    @JvmStatic fun allowsAutomation(level: Level): Boolean {
        val current = binding ?: return true
        if (level !is ServerLevel || level.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.transfers.allowsUnboundedAutomation()
    }

    @JvmStatic fun allowsFurnaceTick(level: Level, position: BlockPos): Boolean {
        val current = binding ?: return true
        if (level !is ServerLevel || level.server !== current.server) return true
        if (!current.server.isSameThread) return false
        if (current.owners.hasJournalRetention()) return false
        if (!current.owners.hasReservations()) return current.owners.isRunning()
        return current.owners.allowsMutation(blockOwner(level, position))
    }

    @JvmStatic fun needsStructuralCheck(level: Level?): Boolean =
        structuralBinding(level)?.owners?.hasReservations() == true

    @JvmStatic fun beforeBlockChange(level: Level?, position: BlockPos, previous: BlockState) {
        val current = structuralBinding(level) ?: return
        if (!current.owners.hasReservations()) return
        val serverLevel = level as ServerLevel
        current.owners.invalidate(blockOwner(serverLevel, position))
        if (previous.block is ChestBlock && previous.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            current.owners.invalidate(blockOwner(serverLevel, position.relative(ChestBlock.getConnectedDirection(previous))))
        }
    }

    fun bindInventories(server: MinecraftServer, lease: ItemOwnerCoordination.Lease): MinecraftBoundInventories {
        check(server.isSameThread)
        val current = checkNotNull(binding)
        check(!current.stopping && current.server === server && currentLease(current,lease)) { "A current lease from this server is required" }
        current.sessions.values.filter { !it.isCurrent() }.forEach { it.close() }
        check(!current.sessions.containsKey(lease)) { "This lease already has a bound session" }
        check(current.sessions.size < 32) { "Bound inventory session limit reached" }
        return MinecraftBoundInventories(server,lease,{ binding === current && currentLease(current,lease) },
            { current.playerReader() },{ current.owners.invalidate(it) },{ if (current.sessions[lease] === it) current.sessions.remove(lease) }, current.actualIo).also { current.sessions[lease] = it }
    }

    /** Close the old mutating session first. A prefix fence leaves new read-only I/O admission open. */
    internal fun retainedDiskFence(server: MinecraftServer, lease: ItemOwnerCoordination.Lease): java.util.concurrent.CompletionStage<Void> {
        check(server.isSameThread)
        val current = checkNotNull(binding)
        check(current.server === server && !current.stopping && lease.isRetained(lease.owners) && !lease.isCurrent(lease.owners))
        check(current.sessions[lease] == null) { "The prior mutating session must be closed before its disk fence" }
        return current.actualIo.fence()
    }

    /** Retained revoked owners may be read without restoring any mutation permit. */
    internal fun bindReadOnlyInventories(server: MinecraftServer, lease: ItemOwnerCoordination.Lease): MinecraftBoundInventories {
        check(server.isSameThread)
        val current = checkNotNull(binding)
        fun held() = binding === current && !current.stopping && lease.isRetained(lease.owners) && !lease.isCurrent(lease.owners)
        check(current.server === server && held()) { "Retained ownership from this server is required" }
        current.sessions.values.filter { !it.isCurrent() }.forEach { it.close() }
        check(!current.sessions.containsKey(lease) && current.sessions.size < 32)
        return MinecraftBoundInventories(server, lease, { held() }, { current.playerReader() },
            { current.owners.invalidate(it) }, { if(current.sessions[lease] === it) current.sessions.remove(lease) },
            current.actualIo, readOnly = true).also { current.sessions[lease] = it }
    }

    private fun currentLease(current: Binding,lease: ItemOwnerCoordination.Lease): Boolean =
        lease.isCurrent(lease.owners) && lease.owners.all { current.owners.allowsMutation(it,lease) }

    /** Internal apply primitive only; commands never call this or acquire a lease. */
    fun writeReservedSlot(lease: ItemOwnerCoordination.Lease, container: Container, slot: Int,
                          expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        val current = checkNotNull(binding) { "Inventory coordination is not installed" }
        current.slotWriter.write(lease, container, slot, expected, replacement)
    }

    /** Main-thread block API guard. Unknown callbacks can affect owners beyond this block. */
    @JvmStatic fun allowsBlockInventoryMutation(block: BlockEntity): Boolean {
        val current = structuralBinding(block.level) ?: return true
        return !current.owners.hasJournalRetention()
    }

    /** Keep the exact audited setter chain usable; refuse ordinary block writes during retention. */
    @JvmStatic fun allowsReservedBlockSlotWrite(container: Container, slot: Int,
                                               stack: net.minecraft.world.item.ItemStack, entry: ReservedSlotEntry): Boolean {
        val current = binding
        if (current != null && current.server.isSameThread && current.slotWriter.consume(container, slot, stack, entry)) return true
        val block = container as BlockEntity
        if (!allowsBlockInventoryMutation(block)) return false
        beforeBlockInventoryMutation(block)
        return true
    }

    /** Direct player slot APIs only; composite operations need their own caller contracts. */
    @JvmStatic fun allowsInventoryMutation(player: net.minecraft.world.entity.player.Player): Boolean {
        val current = binding ?: return true
        if (player !is ServerPlayer || player.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return !current.owners.hasJournalRetention()
    }

    /** Consume the exact player setter ticket before checking ordinary mutation denial. */
    @JvmStatic fun allowsReservedPlayerSlotWrite(inventory: Inventory, slot: Int,
                                                stack: net.minecraft.world.item.ItemStack): Boolean {
        val current = binding
        if (current != null && current.server.isSameThread &&
            (current.lifecycleCopy.consume(inventory, slot, stack) ||
                current.slotWriter.consume(inventory, slot, stack, ReservedSlotEntry.PLAYER))) return true
        if (!allowsInventoryMutation(inventory.player)) return false
        beforeInventoryMutation(inventory.player)
        return true
    }

    /** Called only around restoreFrom's vanilla replaceWith invocation. No lease is revived. */
    @JvmStatic fun restoreLifecycleInventory(target: Inventory, source: Inventory, operation: Runnable) {
        val current = binding
        if (current == null || target.player !is ServerPlayer || target.player.server !== current.server) {
            operation.run(); return
        }
        check(current.server.isSameThread)
        if (!current.owners.hasJournalRetention()) { operation.run(); return }
        current.owners.invalidateAll()
        current.lifecycleCopy.restore(target, source, operation)
    }

    @JvmStatic fun setLifecycleCopiedSlot(target: Inventory, slot: Int,
                                         stack: net.minecraft.world.item.ItemStack, operation: Runnable) {
        val current = binding
        if (current == null || target.player !is ServerPlayer || target.player.server !== current.server) {
            operation.run(); return
        }
        current.lifecycleCopy.setCopiedSlot(target, slot, stack, operation)
    }

    @JvmStatic fun removeLifecycleVanishingItem(inventory: Inventory, slot: Int,
                                              operation: java.util.function.Supplier<net.minecraft.world.item.ItemStack>): net.minecraft.world.item.ItemStack {
        val current = binding
        if (current == null || inventory.player !is ServerPlayer || inventory.player.server !== current.server) return operation.get()
        check(current.server.isSameThread)
        if (!current.owners.hasJournalRetention()) return operation.get()
        current.owners.invalidateAll()
        return current.lifecycleRemoval.removeVanishing(inventory, slot, operation)
    }

    @JvmStatic fun allowsLifecycleSlotRemoval(inventory: Inventory, slot: Int): Boolean {
        val current = binding
        if (current != null && current.server.isSameThread && current.lifecycleRemoval.consume(inventory, slot)) return true
        if (!allowsInventoryMutation(inventory.player)) return false
        beforeInventoryMutation(inventory.player)
        return true
    }

    @JvmStatic fun beforeReservedSlotWrite(container: Container, slot: Int,
                                           stack: net.minecraft.world.item.ItemStack, entry: ReservedSlotEntry) {
        val current = binding
        if (current != null && current.server.isSameThread && current.slotWriter.consume(container, slot, stack, entry)) return
        when (container) {
            is Inventory -> beforeInventoryMutation(container.player)
            is BlockEntity -> beforeBlockInventoryMutation(container)
        }
    }

    @JvmStatic fun beforeBlockInventoryMutation(block: BlockEntity) {
        if (block !is BaseContainerBlockEntity) return
        val current = structuralBinding(block.level) ?: return
        // Inventory writes can run item, loot or component callbacks with unbounded owners.
        // Cancel coordination, then let the caller's normal mutation proceed.
        current.owners.invalidateAll()
    }

    @JvmStatic fun beforeBlockEntityRemoval(block: BlockEntity) {
        val level = block.level ?: return
        if (needsStructuralCheck(level)) beforeBlockChange(level, block.blockPos, block.blockState)
    }

    @JvmStatic fun beforeChunkUnload(chunk: LevelChunk) {
        val level = chunk.level ?: return
        val current = structuralBinding(level) ?: return
        if (!current.owners.hasReservations()) return
        current.owners.invalidateBlockChunk(ResourceId.parse((level as ServerLevel).dimension().location().toString()), chunk.pos.x, chunk.pos.z)
    }

    private fun structuralBinding(level: Level?): Binding? {
        val current = binding ?: return null
        // World-generation workers cannot own a live coordinated inventory. Unsupported
        // off-thread mod mutation still needs a separate exclusion contract before apply.
        return current.takeIf { level is ServerLevel && level.server === it.server && it.server.isSameThread }
    }

    private fun blockOwner(level: ServerLevel, pos: BlockPos) = ItemSlotOwner.BlockContainer(
        ResourceId.parse(level.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z)
    )

    @JvmStatic fun allowsPlayerMutation(player: ServerPlayer): Boolean {
        val current = binding ?: return true
        if (player.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.menus.allowsMutation(player.uuid) { emptyList() }
    }

    @JvmStatic fun allowsMenuMutation(player: ServerPlayer, menu: AbstractContainerMenu): Boolean {
        val current = binding ?: return true
        if (player.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.menus.allowsMutation(player.uuid) { menuOwners(player, menu) }
    }

    @JvmStatic fun allowsOpen(player: ServerPlayer, provider: MenuProvider?): Boolean {
        if (provider == null) return true
        val current = binding ?: return true
        if (player.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.menus.allowsMutation(player.uuid) {
            // Do not create a menu or unpack loot to discover its participating inventory.
            if (provider is BlockEntity && provider is Container && provider.javaClass in supportedBlocks) physicalOwner(player, provider)?.let(::listOf)
            else null
        }
    }

    @JvmStatic fun beforeInventoryMutation(player: net.minecraft.world.entity.player.Player) {
        val current = binding ?: return
        if (player !is ServerPlayer || player.server !== current.server) return
        check(current.server.isSameThread)
        current.owners.invalidate(ItemSlotOwner.PlayerInventory(player.uuid))
    }

    @JvmStatic fun beforeEquipmentMutation(player: ServerPlayer) = beforeBulkInventoryMutation(player)

    @JvmStatic fun beforeItemUseCleanup(player: ServerPlayer) = beforeBulkInventoryMutation(player)

    @JvmStatic fun beforeBulkInventoryMutation(player: net.minecraft.world.entity.player.Player) {
        val current = binding ?: return
        if (player !is ServerPlayer || player.server !== current.server) return
        check(current.server.isSameThread)
        // Bulk clearing can also mutate an extra container and invoke predicates.
        current.owners.invalidateAll()
    }

    // Lifecycle work must proceed, but no operation may retain the old player's
    // inventory identity or its menu owners across save, copy, death or travel.
    @JvmStatic fun beforePlayerTransition(player: ServerPlayer) = beforeClose(player)

    @JvmStatic fun beforeClose(player: ServerPlayer) {
        val current = binding ?: return
        if (player.server !== current.server) return
        check(current.server.isSameThread)
        current.menus.beforeCleanup(player.uuid) { menuOwners(player, player.containerMenu) }
    }

    @JvmStatic fun resynchronize(player: ServerPlayer) {
        check(player.server.isSameThread)
        player.inventoryMenu.sendAllDataToRemote()
        if (player.containerMenu !== player.inventoryMenu) player.containerMenu.sendAllDataToRemote()
    }

    private fun menuOwners(player: ServerPlayer, menu: AbstractContainerMenu): Set<ItemSlotOwner>? {
        // Extended menus can mutate inventories not represented by their visible slots.
        if (menu.javaClass !in supportedMenus || menu.slots.size > 256) return null
        val result = linkedSetOf<ItemSlotOwner>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Container, Boolean>())
        fun visit(container: Container, depth: Int): Boolean {
            if (depth > 4) return false
            if (!seen.add(container)) return true
            if (seen.size > 32) return false
            when (container) {
                is Inventory -> {
                    if (container.javaClass != Inventory::class.java || container.player !is ServerPlayer || container.player.server !== player.server) return false
                    result.add(ItemSlotOwner.PlayerInventory(container.player.uuid))
                }
                is BlockEntity -> result.add(physicalOwner(player, container) ?: return false)
                is CompoundContainer -> {
                    val parts = container as CompoundContainerAccessor
                    if (!visit(parts.`guardian$first`(), depth + 1) || !visit(parts.`guardian$second`(), depth + 1)) return false
                }
                is TransientCraftingContainer, is ResultContainer -> {
                    if (container.javaClass != TransientCraftingContainer::class.java && container.javaClass != ResultContainer::class.java) return false
                    result.add(ItemSlotOwner.PlayerInventory(player.uuid))
                }
                else -> return false
            }
            return true
        }
        for (slot in menu.slots) if (!visit(slot.container, 0)) return null
        return result
    }

    private fun physicalOwner(player: ServerPlayer, block: BlockEntity): ItemSlotOwner.BlockContainer? {
        if (block.javaClass !in supportedBlocks) return null
        val level = block.level as? ServerLevel ?: return null
        if (level.server !== player.server || block.isRemoved) return null
        val pos = block.blockPos
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null || level.getBlockEntity(pos) !== block) return null
        return ItemSlotOwner.BlockContainer(ResourceId.parse(level.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z))
    }

    private val supportedBlocks: Set<Class<*>> = setOf(
        BarrelBlockEntity::class.java, ChestBlockEntity::class.java, HopperBlockEntity::class.java,
        DispenserBlockEntity::class.java, DropperBlockEntity::class.java, FurnaceBlockEntity::class.java,
        BlastFurnaceBlockEntity::class.java, SmokerBlockEntity::class.java
    )

    private val supportedMenus: Set<Class<*>> = setOf(
        InventoryMenu::class.java, ChestMenu::class.java, HopperMenu::class.java,
        DispenserMenu::class.java, FurnaceMenu::class.java, BlastFurnaceMenu::class.java,
        SmokerMenu::class.java, CraftingMenu::class.java
    )

    private fun allows(level: Level, resolve: (ServerLevel) -> Collection<ItemSlotOwner>?): Boolean {
        val current = binding ?: return true
        if (level !is ServerLevel || level.server !== current.server) return true
        if (!current.server.isSameThread) return false
        return current.transfers.allowsExternalTransfer { resolve(level) }
    }

    private fun endpoints(level: ServerLevel, a: BlockPos, b: BlockPos): Set<ItemSlotOwner> {
        val dimension = ResourceId.parse(level.dimension().location().toString())
        val positions = linkedSetOf(a.immutable(), b.immutable())
        for (position in listOf(a, b)) {
            if (level.chunkSource.getChunkNow(position.x shr 4, position.z shr 4) == null) continue
            val state = level.getBlockState(position)
            if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                positions.add(position.relative(ChestBlock.getConnectedDirection(state)))
            }
        }
        return positions.map { ItemSlotOwner.BlockContainer(dimension, BlockPosition(it.x, it.y, it.z)) }.toSet()
    }
}
