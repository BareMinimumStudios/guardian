package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.mixin.OwnedSimpleInventoryAccessor
import net.minecraft.core.NonNullList
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Field

/** Native backing identity only. An operation must also exclude enclosing gameplay mutations. */
internal class EquipmentListBinding private constructor(
    val identity:Any,private val readList:()->NonNullList<ItemStack>,private val installList:(NonNullList<ItemStack>)->Unit
) {
    fun read():NonNullList<ItemStack> = readList().also { require(it.size in 1..256) }
    fun install(value:NonNullList<ItemStack>) {
        check(value.size==read().size) { "Equipment backing size changed" }
        installList(value);check(read()===value) { "Equipment backing was not installed" }
    }
    companion object {
        /** Explicitly verified external storage, preserving its native signal/update setter. */
        fun native(identity: Any,read: ()->NonNullList<ItemStack>,install: (NonNullList<ItemStack>)->Unit)=
            EquipmentListBinding(identity,read,install)
        fun simple(container:SimpleContainer)=EquipmentListBinding(container,
            { (container as OwnedSimpleInventoryAccessor).`guardian$getItems`() },
            { (container as OwnedSimpleInventoryAccessor).`guardian$setItems`(it) })
        /** Verified native handler family; never interpret an arbitrary mod field as storage. */
        fun itemHandler(owner:Any):EquipmentListBinding {
            var type:Class<*>?=owner.javaClass
            while(type!=null && type.name!="net.neoforged.neoforge.items.ItemStackHandler")type=type.superclass
            check(type!=null) { "Unsupported equipment item handler" }
            return field(owner,"stacks")
        }
        fun field(owner:Any,name:String):EquipmentListBinding {
            var type:Class<*>?=owner.javaClass;var field:Field?=null
            while(type!=null && field==null) {
                try { field=type.getDeclaredField(name) } catch(_:NoSuchFieldException) { type=type.superclass }
            }
            val target=checkNotNull(field) { "Equipment backing field is not available" }
            check(NonNullList::class.java.isAssignableFrom(target.type)) { "Unsupported equipment backing type" }
            target.isAccessible=true
            @Suppress("UNCHECKED_CAST")
            fun read()=target.get(owner) as NonNullList<ItemStack>
            return EquipmentListBinding(owner,::read,{ target.set(owner,it) })
        }
    }
}
