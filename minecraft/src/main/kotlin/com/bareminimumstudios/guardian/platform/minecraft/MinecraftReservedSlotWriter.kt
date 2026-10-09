package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.ItemOwnerCoordination
import com.bareminimumstudios.guardian.rollback.ItemWriteScope
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.*

/** Entries consumed only by the exact synchronous setter invocation initiated below. */
enum class ReservedSlotEntry { BASE, RANDOMIZED, HOPPER, FURNACE, PLAYER }

internal class MinecraftReservedSlotWriter(
    private val server: MinecraftServer,
    owners: ItemOwnerCoordination
) {
    private val scopes = ItemWriteScope(owners)
    private class Ticket(
        val permit: ItemWriteScope.Permit,
        val container: Container,
        val slot: Int,
        val stack: ItemStack,
        val entries: List<ReservedSlotEntry>
    ) { var next = 0 }
    private var ticket: Ticket? = null

    fun consume(container: Container, slot: Int, stack: ItemStack, entry: ReservedSlotEntry): Boolean {
        check(server.isSameThread)
        val active = ticket ?: return false
        if (container !== active.container || slot != active.slot || stack !== active.stack ||
            entry != active.entries[active.next]) return false
        active.permit.requireCurrent(active.permit.owner)
        active.next++
        // Remove authorization before setter side effects can invoke other inventory writes.
        if (active.next == active.entries.size) ticket = null
        return true
    }

    fun write(lease: ItemOwnerCoordination.Lease, container: Container, slot: Int,
              expected: ItemStackSnapshot, replacement: ItemStackSnapshot) {
        check(server.isSameThread)
        check(ticket == null) { "A reserved setter is already in progress" }
        val owner = resolve(container)
        scopes.write(lease, owner) { permit ->
            require(slot in 0 until container.containerSize) { "Invalid inventory slot" }
            check(container !is RandomizableContainerBlockEntity || container.lootTable == null) {
                "Deferred loot inventories cannot be written"
            }
            val registries = server.registryAccess()
            val stack = MinecraftItemSnapshotter.restore(replacement, registries)
            require(stack.isEmpty || stack.count <= container.getMaxStackSize(stack)) { "Replacement exceeds the slot stack limit" }
            check(MinecraftItemSnapshotter.capture(container.getItem(slot), registries) == expected) {
                "Inventory contents changed before the reserved write"
            }
            check(resolve(container) == owner)
            permit.requireCurrent(owner)
            val active = Ticket(permit, container, slot, stack, entries(container))
            ticket = active
            try {
                container.setItem(slot, stack)
                check(active.next == active.entries.size && ticket == null) { "Reserved setter hooks were not consumed" }
                permit.requireCurrent(owner)
                check(resolve(container) == owner)
                check(MinecraftItemSnapshotter.capture(container.getItem(slot), registries) == replacement) {
                    "Reserved setter did not produce the planned contents"
                }
                permit.requireCurrent(owner)
            } finally {
                ticket = null
            }
        }
    }

    private fun resolve(container: Container): ItemSlotOwner {
        if (container.javaClass == Inventory::class.java) {
            val inventory = container as Inventory
            val player = inventory.player as? ServerPlayer ?: error("A live server player is required")
            check(player.server === server && player.inventory === inventory &&
                server.playerList.getPlayer(player.uuid) === player) { "Player inventory identity changed" }
            return ItemSlotOwner.PlayerInventory(player.uuid)
        }
        check(container.javaClass in supportedBlocks) { "Unsupported reserved inventory" }
        val block = container as BlockEntity
        val level = block.level as? ServerLevel ?: error("A live server container is required")
        val pos = block.blockPos
        check(level.server === server && !block.isRemoved &&
            level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) != null &&
            level.getBlockEntity(pos) === block) { "Block inventory identity changed" }
        return ItemSlotOwner.BlockContainer(ResourceId.parse(level.dimension().location().toString()), BlockPosition(pos.x, pos.y, pos.z))
    }

    private fun entries(container: Container): List<ReservedSlotEntry> = when (container) {
        is Inventory -> listOf(ReservedSlotEntry.PLAYER)
        is HopperBlockEntity -> listOf(ReservedSlotEntry.HOPPER)
        is AbstractFurnaceBlockEntity -> listOf(ReservedSlotEntry.FURNACE)
        else -> listOf(ReservedSlotEntry.RANDOMIZED, ReservedSlotEntry.BASE)
    }

    private val supportedBlocks: Set<Class<*>> = setOf(
        BarrelBlockEntity::class.java, ChestBlockEntity::class.java, HopperBlockEntity::class.java,
        DispenserBlockEntity::class.java, DropperBlockEntity::class.java, FurnaceBlockEntity::class.java,
        BlastFurnaceBlockEntity::class.java, SmokerBlockEntity::class.java
    )
}
