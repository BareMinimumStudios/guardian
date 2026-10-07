package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.logging.container.ContainerTransactionCorrelation
import net.minecraft.SharedConstants
import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.TransientCraftingContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID
import kotlin.test.*

class MinecraftInventorySnapshotterTest {
    companion object {
        init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
        private val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }
    // Snapshot and eligibility paths do not call player behavior; no running server is needed.
    private val inventory = InventoryMenuFixtures.inventory()
    private val menu = InventoryMenuFixtures.create(inventory)
    private val playerId = UUID.randomUUID()
    private fun address(index: Int) = ItemSlotAddress(ItemSlotOwner.PlayerInventory(playerId), index)
    private fun capture() = MinecraftInventorySnapshotter.capturePlayer(menu, inventory, playerId, registries)

    @Test fun capturesLogicalInventoryArmorOffhandAndCursorWithoutRetainingStacks() {
        val stack = ItemStack(Items.DIAMOND, 8)
        inventory.setItem(0, stack)
        inventory.setItem(39, ItemStack(Items.DIAMOND_HELMET))
        inventory.setItem(40, ItemStack(Items.SHIELD))
        menu.carried = ItemStack(Items.STONE, 3)
        val value = capture()
        stack.count = 1; inventory.setItem(40, ItemStack.EMPTY); menu.carried = ItemStack.EMPTY
        assertEquals(42, value.slots.size)
        assertEquals(8, value.slots.getValue(address(0)).count)
        assertEquals(ResourceId.parse("minecraft:diamond_helmet"), value.slots.getValue(address(39)).itemId)
        assertEquals(ResourceId.parse("minecraft:shield"), value.slots.getValue(address(40)).itemId)
        assertEquals(3, value.slots.getValue(ItemSlotAddress(ItemSlotOwner.Cursor(playerId), 0)).count)
    }

    @Test fun acceptsInventoryArmorOffhandAndOutsideClicksButNotCraftOrInvalidIndices() {
        assertTrue(MinecraftInventorySnapshotter.supportsInventoryMenu(menu, inventory))
        for (index in listOf(5, 8, 9, 35, 36, 44, 45, -999)) {
            assertTrue(MinecraftInventorySnapshotter.acceptsInventoryClick(menu, inventory, index), "slot $index")
        }
        for (index in listOf(0, 1, 2, 3, 4, -1, -1000, 46)) {
            assertFalse(MinecraftInventorySnapshotter.acceptsInventoryClick(menu, inventory, index), "slot $index")
        }
    }

    @Test fun refusesOccupiedCraftingInputsAndResults() {
        // Supply a backing list for the fixture without invoking recipe callbacks.
        val items = NonNullList.withSize(4, ItemStack.EMPTY)
        val crafting = TransientCraftingContainer(menu, 2, 2, items)
        for (index in 1..4) menu.slots[index] = Slot(crafting, index - 1, 0, 0)
        items[0] = ItemStack(Items.STONE)
        assertFalse(MinecraftInventorySnapshotter.supportsInventoryMenu(menu, inventory))
        items[0] = ItemStack.EMPTY
        menu.slots[0].container.setItem(0, ItemStack(Items.STONE))
        assertFalse(MinecraftInventorySnapshotter.supportsInventoryMenu(menu, inventory))
    }

    @Test fun rejectsExtraOrReplacedMenuSlotsAndOtherInventoryOwners() {
        assertFalse(MinecraftInventorySnapshotter.isPlayerSlot(Slot(InventoryMenuFixtures.inventory(), 0, 0, 0), inventory))
        assertFalse(MinecraftInventorySnapshotter.isPlayerSlot(Slot(inventory, 41, 0, 0), inventory))
        assertFalse(MinecraftInventorySnapshotter.isPlayerSlot(menu.slots[1], inventory))
        assertTrue(MinecraftInventorySnapshotter.isPlayerSlot(menu.slots[45], inventory))
        menu.slots[9] = Slot(SimpleContainer(1), 0, 0, 0)
        assertFalse(MinecraftInventorySnapshotter.supportsInventoryMenu(menu, inventory))
        val extended = InventoryMenuFixtures.create(inventory)
        extended.slots.add(Slot(SimpleContainer(1), 0, 0, 0))
        assertFalse(MinecraftInventorySnapshotter.supportsInventoryMenu(extended, inventory))
    }

    @Test fun inventoryToCursorTransferIsOnePlayerTransactionWithNoBlockLocation() {
        inventory.setItem(0, ItemStack(Items.DIAMOND, 8))
        val before = capture()
        inventory.setItem(0, ItemStack.EMPTY); menu.carried = ItemStack(Items.DIAMOND, 8)
        val value = assertNotNull(ContainerTransactionCorrelation(ActorIdentity.Player(playerId, "Tester"), 0, ContainerAction.PICKUP, before).finish(capture(), true))
        assertEquals(2, value.changes.size)
        assertTrue(value.containers.isEmpty())
        assertEquals(ActionType.ITEM_CHANGE, ContainerAuditEntry(value).action)
    }

    @Test fun creativeComponentOnlyReplacementIsRecordedAndNoOpIsSkipped() {
        val stack = ItemStack(Items.DIAMOND_SWORD)
        inventory.setItem(0, stack)
        val actor = ActorIdentity.Player(playerId, "Tester")
        val before = capture()
        assertNull(ContainerTransactionCorrelation(actor, 0, ContainerAction.CREATIVE_SET, before).finish(capture(), true))
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Creative test"))
        val value = assertNotNull(ContainerTransactionCorrelation(actor, 0, ContainerAction.CREATIVE_SET, before).finish(capture(), true))
        assertEquals(1, value.changes.size); assertEquals(address(0), value.changes.single().address)
        assertEquals("Creative test", MinecraftItemSnapshotter.restore(value.changes.single().after, registries).get(DataComponents.CUSTOM_NAME)?.string)
    }
}
