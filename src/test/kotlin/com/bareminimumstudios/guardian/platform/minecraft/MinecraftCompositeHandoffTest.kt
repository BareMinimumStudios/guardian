package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.world.item.ItemStack
import kotlin.test.*

class MinecraftCompositeHandoffTest {
    private class Part : MinecraftInventoryHandoff {
        var current=true
        var releases=0
        var failRelease=false
        var duringWrite: () -> Unit = {}
        val writes=mutableListOf<Int>()
        override fun isCurrent()=current
        override fun write(index: Int,expected: ItemStackSnapshot,replacement: ItemStackSnapshot,setter: (ItemStack) -> Unit) {
            check(current);writes.add(index);duringWrite()
        }
        override fun release() { check(current);releases++;if(failRelease)error("native handback failed");current=false }
        override fun equals(other: Any?)=other is Part
        override fun hashCode()=1
    }
    private val empty=ItemStackSnapshot.EMPTY
    private fun write(handoff: MinecraftInventoryHandoff,index: Int)=handoff.write(index,empty,empty) {}

    @Test fun routesNamedSlotsByNativeIndexAndReleasesEachPhysicalStorageOnce() {
        val normal=Part();val equipment=Part()
        val slots=mutableListOf(MinecraftCompositeHandoff.Slot(normal,0),MinecraftCompositeHandoff.Slot(equipment,10),
            MinecraftCompositeHandoff.Slot(equipment,2),MinecraftCompositeHandoff.Slot(normal,1))
        val composite=MinecraftCompositeHandoff(slots);slots.clear()
        write(composite,1);write(composite,2);write(composite,3)
        assertEquals(listOf(10,2),equipment.writes);assertEquals(listOf(1),normal.writes)
        composite.release();assertFalse(composite.isCurrent())
        assertEquals(1,normal.releases);assertEquals(1,equipment.releases)
        assertFailsWith<IllegalStateException> { write(composite,0) }
        assertFailsWith<IllegalStateException> { composite.release() }
    }

    @Test fun duplicatePhysicalSlotsExpiredStorageAndInvalidBoundsRefuse() {
        val part=Part()
        assertFailsWith<IllegalArgumentException> { MinecraftCompositeHandoff(emptyList()) }
        assertFailsWith<IllegalArgumentException> { MinecraftCompositeHandoff(List(257) { MinecraftCompositeHandoff.Slot(part,it%256) }) }
        assertFailsWith<IllegalArgumentException> { MinecraftCompositeHandoff(listOf(MinecraftCompositeHandoff.Slot(part,0),MinecraftCompositeHandoff.Slot(part,0))) }
        assertFailsWith<IllegalArgumentException> { MinecraftCompositeHandoff(listOf(MinecraftCompositeHandoff.Slot(part,-1))) }
        part.current=false
        assertFailsWith<IllegalStateException> { MinecraftCompositeHandoff(listOf(MinecraftCompositeHandoff.Slot(part,0))) }
    }

    @Test fun aChangedNeighbourRevokesTheWholeImageBeforeAndAfterSlotWrites() {
        val normal=Part();val equipment=Part()
        val composite=MinecraftCompositeHandoff(listOf(MinecraftCompositeHandoff.Slot(normal,0),MinecraftCompositeHandoff.Slot(equipment,0)))
        normal.duringWrite={ equipment.current=false }
        assertFailsWith<IllegalStateException> { write(composite,0) }
        assertFalse(composite.isCurrent());assertEquals(listOf(0),normal.writes)
        assertFailsWith<IllegalStateException> { write(composite,1) };assertTrue(equipment.writes.isEmpty())
    }

    @Test fun partialHandbackCannotResumeWritesOrReleaseAlreadyReturnedStorageAgain() {
        val normal=Part();val equipment=Part().apply { failRelease=true }
        val composite=MinecraftCompositeHandoff(listOf(MinecraftCompositeHandoff.Slot(normal,0),MinecraftCompositeHandoff.Slot(equipment,0)))
        assertFailsWith<IllegalStateException> { composite.release() }
        assertFalse(composite.isCurrent());assertFalse(normal.current);assertTrue(equipment.current)
        assertFailsWith<IllegalStateException> { write(composite,1) }
        assertFailsWith<IllegalStateException> { composite.release() }
        assertEquals(1,normal.releases);assertEquals(1,equipment.releases)
    }
}
