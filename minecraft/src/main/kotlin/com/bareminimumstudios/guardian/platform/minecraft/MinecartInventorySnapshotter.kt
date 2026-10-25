package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer

/** Immutable audit views only. This does not grant an entity inventory any rollback capability. */
object MinecartInventorySnapshotter {
    fun owner(cart:AbstractMinecartContainer,level:ServerLevel):ItemSlotOwner.EntityContainer? {
        if(cart.level()!==level || cart.isRemoved || level.getEntity(cart.uuid)!==cart)return null
        val p=cart.blockPosition()
        return ItemSlotOwner.EntityContainer(ResourceId.parse(level.dimension().location().toString()),cart.uuid,ResourceId.parse(BuiltInRegistries.ENTITY_TYPE.getKey(cart.type).toString()),BlockPosition(p.x,p.y,p.z))
    }
    fun capture(container:Container,level:ServerLevel,anchor:ItemSlotOwner.EntityContainer?=null):InventorySnapshot? {
        if(container !is AbstractMinecartContainer)return MinecraftBlockContainerSnapshotter.capture(container,level)
        val live=owner(container,level) ?: return null
        if(container.lootTable!=null)return null
        if(anchor!=null && (anchor.dimension!=live.dimension || anchor.entityUuid!=live.entityUuid || anchor.entityType!=live.entityType))return null
        require(container.containerSize in 1..256)
        val owner=anchor ?: live;val items=MinecraftItemSnapshotter.CaptureBatch(level.registryAccess())
        var bytes=0L
        val slots=(0 until container.containerSize).associate { index->
            val item=items.capture(container.getItem(index));bytes+=(item.itemData?.size ?: 0)
            require(bytes<=16L*1024*1024) { "Minecart audit exceeds its payload budget" }
            ItemSlotAddress(owner,index) to item
        }
        return InventorySnapshot(slots)
    }
}
