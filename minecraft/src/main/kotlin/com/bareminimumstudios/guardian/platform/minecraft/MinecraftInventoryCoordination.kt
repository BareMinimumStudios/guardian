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
    }
    @Volatile private var binding: Binding? = null

    fun install(server: MinecraftServer): ItemOwnerCoordination {
        check(server.isSameThread)
        check(binding == null) { "Inventory coordination is already installed" }
        return ItemOwnerCoordination().also { binding = Binding(server, it) }
    }

    fun uninstall(owners: ItemOwnerCoordination?) {
        val current = binding ?: return
        if (owners !== current.owners) return
        check(current.server.isSameThread)
        current.owners.stop()
        binding = null
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

    /** Internal apply primitive only; commands never call this or acquire a lease. */
    fun writeReservedSlot(lease: ItemOwnerCoordination.Lease, container: Container, slot: Int,
                          expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        val current = checkNotNull(binding) { "Inventory coordination is not installed" }
        current.slotWriter.write(lease, container, slot, expected, replacement)
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
