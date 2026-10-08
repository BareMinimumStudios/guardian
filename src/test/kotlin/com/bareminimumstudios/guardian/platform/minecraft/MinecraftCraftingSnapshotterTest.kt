package com.bareminimumstudios.guardian.platform.minecraft
import com.bareminimumstudios.guardian.domain.*
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.*
import net.minecraft.world.item.*
import java.util.UUID
import kotlin.test.*
class MinecraftCraftingSnapshotterTest {
    companion object { init { SharedConstants.tryDetectVersion();Bootstrap.bootStrap() } }
    private val inventory=CraftingTestInventory.create()
    private val id=UUID.randomUUID()
    private val registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    @Test fun inventoryGridCapturedWhileRecipePreviewIsExcluded() {
        val menu=CraftingTestInventory.menu(inventory)
        CraftingTestInventory.seed(menu.slots[1].container as CraftingContainer,0,ItemStack(Items.OAK_LOG,2))
        menu.slots[0].container.setItem(0,ItemStack(Items.OAK_PLANKS,4))
        val snapshot=MinecraftCraftingSnapshotter.capture(menu,inventory,id,registries)!!
        assertEquals(46,snapshot.slots.size)
        assertEquals(2,snapshot.slots.getValue(ItemSlotAddress(ItemSlotOwner.CraftingGrid(id,0),0)).count)
        assertTrue(snapshot.slots.values.none{it.itemId==ResourceId.parse("minecraft:oak_planks")})
    }
    @Test fun craftingTableUsesNineGridSlotsAndCorrectMenuIdentity() {
        val menu=CraftingMenu(7,inventory)
        val snapshot=MinecraftCraftingSnapshotter.capture(menu,inventory,id,registries)!!
        assertEquals(51,snapshot.slots.size)
        assertEquals(9,snapshot.slots.keys.count{it.owner==ItemSlotOwner.CraftingGrid(id,7)})
    }
    @Test fun extendedAndAliasedLayoutsAreSkipped() {
        val menu=CraftingTestInventory.menu(inventory)
        menu.slots[2]=menu.slots[1]
        assertNull(MinecraftCraftingSnapshotter.capture(menu,inventory,id,registries))
        val extended=CraftingTestInventory.extended(inventory)
        assertNull(MinecraftCraftingSnapshotter.capture(extended,inventory,id,registries))
    }
    @Test fun ingredientComponentsAreImmutableAcrossLiveStackMutation() {
        val menu=CraftingTestInventory.menu(inventory)
        val stack=ItemStack(Items.OAK_LOG,2)
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Before"))
        CraftingTestInventory.seed(menu.slots[1].container as CraftingContainer,0,stack)
        val first=MinecraftCraftingSnapshotter.capture(menu,inventory,id,registries)!!
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("After"));stack.count=1
        val second=MinecraftCraftingSnapshotter.capture(menu,inventory,id,registries)!!
        val address=ItemSlotAddress(ItemSlotOwner.CraftingGrid(id,0),0)
        assertEquals(2,first.slots.getValue(address).count)
        assertNotEquals(first.slots.getValue(address).itemData,second.slots.getValue(address).itemData)
    }
}
