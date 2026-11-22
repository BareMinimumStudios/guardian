package com.bareminimumstudios.guardian.checkpoint

import com.bareminimumstudios.guardian.platform.minecraft.MinecraftItemSnapshotter
import com.bareminimumstudios.guardian.domain.EquipmentSlotAddress
import net.minecraft.server.level.ServerPlayer
import com.bareminimumstudios.guardian.platform.minecraft.EquipmentListBinding
import net.minecraft.world.SimpleContainer
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Method
import java.util.Optional
import java.util.UUID

/** Optional versioned adapters. An installed provider failing to read is never silently omitted. */
internal class MinecraftEquipmentProviders(installed: Map<String,String>) {
    private val installed=installed.toMap()
    internal class Group(val provider:String,val name:String,val identity:Any,val size:Int,val backing:EquipmentListBinding?=null,val read:(Int)->ItemStack) {
        var write: ((Int,ItemStack)->Unit)? = null
        var accepts: (Int,ItemStack)->Boolean = { _,_ -> true }
        var beforeDetach: ()->Unit = {}
        var single: com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingleSlotBinding? = null
        var attached: ()->Boolean = { true }
        var roots: List<Any> = listOf(identity)
        init { require(size in 0..256);EquipmentSlotAddress(provider,name) }
    }
    class Observation(val image:PlayerEquipmentSnapshot,internal val groups:List<Group>)
    private val factories=linkedMapOf<String,()->((ServerPlayer)->List<Group>)>(
        "trinkets" to ::trinkets,"curios" to ::curios,"accessories" to ::accessories,
        "cosmeticarmorreworked" to ::cosmetic,"toolbelt" to ::toolbelt,
        "backpacked" to ::backpacked,"travelersbackpack" to ::travelers)
    private val readers=mutableMapOf<String,(ServerPlayer)->List<Group>>()

    private fun resolve(player:ServerPlayer):Pair<Map<String,String>,List<Group>> {
        check(requireNotNull(player.level().server).isSameThread)
        val providers=linkedMapOf("minecraft" to com.bareminimumstudios.guardian.platform.minecraft.GuardianMinecraftVersion.current)
        val groups=mutableListOf(container("minecraft","ender_chest",player.enderChestInventory))
        for((id,factory) in factories) {
            val version=installed[id] ?: continue
            if(id=="trinkets" && "accessories_compat_layer" in installed) {
                check("accessories" in installed) { "Trinkets compatibility layer has no Accessories provider" };continue
            }
            if(id=="curios" && "cclayer" in installed) {
                check("accessories" in installed) { "Curios compatibility layer has no Accessories provider" };continue
            }
            try {
                val reader=readers.getOrPut(id,factory)
                providers[id]=version
                groups.addAll(reader(player))
            } catch(error:Exception) {
                val cause=generateSequence<Throwable>(error) {
                    (it as? java.lang.reflect.InvocationTargetException)?.targetException
                }.last()
                val detail=(cause.message ?: cause.javaClass.simpleName)
                    .replace(Regex("\\p{Cntrl}")," ").take(180)
                throw IllegalStateException("Equipment provider $id ($version) could not be read: $detail",error)
            } catch(error:LinkageError) {
                throw IllegalStateException("Equipment provider $id ($version) has an incompatible API",error)
            }
        }
        groups.firstOrNull { !it.attached() }?.let { error("Equipment provider ${it.provider}/${it.name} root changed during capture") }
        return providers to groups
    }

    /** Check native roots and topology without serializing every item for each protected slot write. */
    fun matches(player: ServerPlayer,previous: Observation): Boolean {
        val (versions,groups)=resolve(player)
        if(versions!=previous.image.providers || groups.size!=previous.groups.size)return false
        val current=groups.associateBy { it.provider to it.name }
        if(current.size!=groups.size)return false
        return previous.groups.all { old ->
            val group=current[old.provider to old.name]
            group!=null && group.identity===old.identity && group.size==old.size &&
                group.roots.size==old.roots.size && group.roots.indices.all { group.roots[it]===old.roots[it] }
        }
    }

    fun observe(player:ServerPlayer):Observation {
        val (providers,groups)=resolve(player)
        // Compatibility layers can expose one native inventory under several names.
        // Duplicating that image would also duplicate its authority during recovery.
        val identities=java.util.IdentityHashMap<Any,Boolean>()
        val backings=java.util.IdentityHashMap<Any,Boolean>()
        val currentBackings=java.util.IdentityHashMap<Group,Any>()
        for(group in groups)if(group.size>0) {
            check(identities.put(group.identity,true)==null) {
                "Equipment provider ${group.provider} exposes the same physical inventory twice"
            }
            group.backing?.let { binding ->
                val native=binding.read()
                check(native.size==group.size) { "Equipment backing size changed during capture" }
                check(backings.put(native,true)==null) { "Equipment providers share the same physical backing" }
                currentBackings[group]=native
            }
        }
        groups.firstOrNull { !it.attached() }?.let { error("Equipment provider ${it.provider}/${it.name} root changed during capture") }
        val slots=linkedMapOf<EquipmentSlotAddress,com.bareminimumstudios.guardian.domain.ItemStackSnapshot>()
        val capture=MinecraftItemSnapshotter.CaptureBatch(player.registryAccess())
        var bytes=0L
        for(group in groups)for(index in 0 until group.size) {
            val address=EquipmentSlotAddress(group.provider,"${group.name}/$index")
            check(address !in slots) { "Equipment storage was exposed more than once" }
            check(slots.size<256) { "Equipment slot budget exceeded" }
            val image=capture.capture(group.read(index))
            bytes+=image.itemData?.size ?: 0
            check(bytes<=InventoryCheckpoint.MAX_COMPONENT_BYTES) { "Equipment item data budget exceeded" }
            slots[address]=image
        }
        for((group,backing) in currentBackings)check(group.backing!!.read()===backing) {
            "Equipment backing changed during capture"
        }
        groups.firstOrNull { !it.attached() }?.let { error("Equipment provider ${it.provider}/${it.name} root changed during capture") }
        return Observation(PlayerEquipmentSnapshot(providers,slots),groups.toList())
    }

    private fun type(name:String)=Class.forName(name,false,javaClass.classLoader)
    private fun method(owner:String,name:String,vararg args:Class<*>)=type(owner).getMethod(name,*args)
    private fun Method.call(receiver:Any?,vararg args:Any?):Any? = invoke(receiver,*args)
    private fun optional(value:Any?)=(value as Optional<*>).orElse(null)
    private fun map(value:Any?)=value as Map<*,*>
    private fun container(id:String,name:String,value:Any):Group {
        val c=value as Container
        return Group(id,name,c,c.containerSize,if(c is SimpleContainer)EquipmentListBinding.simple(c) else null,c::getItem).also {
            it.write=c::setItem
        }
    }

    private fun trinkets():(ServerPlayer)->List<Group> {
        val get=method("dev.emi.trinkets.api.TrinketsApi","getTrinketComponent",LivingEntity::class.java)
        val inventory=method("dev.emi.trinkets.api.TrinketComponent","getInventory")
        // Vanilla-style getters call update(), which can resize and spill a changing slot.
        // Read the existing persisted backing image instead; refuse an incompatible layout.
        val stacks=type("dev.emi.trinkets.api.TrinketInventory").getDeclaredField("stacks").also { it.isAccessible=true }
        return { player ->
            val component=optional(get.call(null,player)) ?: error("Trinkets component is missing")
            buildList {
                for((group,types) in map(inventory.call(component)))for((slot,value) in map(types)) {
                    val owner=checkNotNull(value);val items=stacks.get(owner) as net.minecraft.core.NonNullList<*>
                    add(Group("trinkets","$group/$slot",owner,items.size,EquipmentListBinding.field(owner,"stacks")) { (stacks.get(owner) as net.minecraft.core.NonNullList<*>)[it] as ItemStack }.also { entry ->
                        entry.write=(owner as Container)::setItem
                        entry.roots=listOf(component,owner)
                        entry.attached={ optional(get.call(null,player))===component &&
                            map(map(inventory.call(component))[group])[slot]===owner }
                    })
                }
            }
        }
    }
    private fun accessories():(ServerPlayer)->List<Group> {
        val get=method("io.wispforest.accessories.AccessoriesInternals","getHolder",LivingEntity::class.java)
        fun field(owner:String,name:String)=type(owner).getDeclaredField(name).also { it.isAccessible=true }
        val holderType="io.wispforest.accessories.impl.AccessoriesHolderImpl"
        val slots=field(holderType,"slotContainers");val loading=field(holderType,"loadedFromTag")
        val invalid=field(holderType,"invalidStacks")
        val normal=field("io.wispforest.accessories.impl.AccessoriesContainerImpl","accessories")
        val cosmetic=field("io.wispforest.accessories.impl.AccessoriesContainerImpl","cosmeticAccessories")
        val resizing=field("io.wispforest.accessories.impl.AccessoriesContainerImpl","resizingUpdate")
        val updating=field("io.wispforest.accessories.impl.AccessoriesContainerImpl","update")
        val capability=field("io.wispforest.accessories.impl.AccessoriesContainerImpl","capability")
        return { player ->
            val holder=checkNotNull(get.call(null,player)) { "Accessories holder is missing" }
            check(!loading.getBoolean(holder)) { "Accessories is awaiting native load initialization; retry after its next tick" }
            check((invalid.get(holder) as List<*>).all { it is ItemStack && it.isEmpty }) { "Accessories has pending displaced items" }
            buildList {
                // Public capability/container getters can reset, resize or spill invalid
                // items. A checkpoint only reads the current already-initialized roots.
                for((name,owner) in map(slots.get(holder))) {
                    val current=checkNotNull(owner)
                    check(com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate.blocked(current) || !(updating.getBoolean(current) && resizing.getBoolean(current))) {
                        "Accessories slot layout has a pending native resize; retry after its next tick"
                    }
                    for((prefix,reader) in listOf("" to normal,"cosmetic/" to cosmetic)) {
                        val leaf=checkNotNull(reader.get(current))
                        add(container("accessories","$prefix$name",leaf).also { entry ->
                            entry.roots=listOf(holder,current,leaf,checkNotNull(capability.get(current)))
                            entry.attached={ get.call(null,player)===holder && map(slots.get(holder))[name]===current && reader.get(current)===leaf }
                        })
                    }
                }
            }
        }
    }
    private fun curios():(ServerPlayer)->List<Group> {
        val get=method("top.theillusivec4.curios.api.CuriosApi","getCuriosInventory",LivingEntity::class.java)
        val containers=method("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler","getCurios")
        // NeoForge capability queries create a new facade. Pin the real serialized
        // attachment, not that facade, and do not recreate it while checking ownership.
        val attachment=(type("top.theillusivec4.curios.common.CuriosRegistry").getField("INVENTORY").get(null) as java.util.function.Supplier<*>).get()
        val data=Entity::class.java.getMethod("getData",type("net.neoforged.neoforge.attachment.AttachmentType"))
        val nativeRoot=type("top.theillusivec4.curios.common.capability.CurioInventoryCapability").getDeclaredField("curioInventory").also { it.isAccessible=true }
        val loading=type("top.theillusivec4.curios.common.capability.CurioInventory").getDeclaredField("markDeserialized").also { it.isAccessible=true }
        val invalid=type("top.theillusivec4.curios.common.capability.CurioInventory").getDeclaredField("invalidStacks").also { it.isAccessible=true }
        val handler="top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler"
        val normal=method(handler,"getStacks");val cosmetic=method(handler,"getCosmeticStacks");val hasCosmetic=method(handler,"hasCosmetic")
        val stacks="top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler"
        val size=method(stacks,"getSlots");val read=method(stacks,"getStackInSlot",Int::class.javaPrimitiveType!!)
        val write=method(stacks,"setStackInSlot",Int::class.javaPrimitiveType!!,ItemStack::class.java)
        val valid=method(stacks,"isItemValid",Int::class.javaPrimitiveType!!,ItemStack::class.java)
        fun group(name:String,value:Any)=Group("curios",name,value,size.call(value) as Int,EquipmentListBinding.itemHandler(value)) { read.call(value,it) as ItemStack }.also { entry ->
            entry.write={ index,stack -> write.call(value,index,stack) }
            entry.accepts={ index,stack -> stack.isEmpty || valid.call(value,index,stack)==true }
        }
        return { player ->
            val attached=checkNotNull(data.invoke(player,attachment))
            check(!loading.getBoolean(attached)) { "Curios is awaiting native load initialization; retry after its next tick" }
            check((invalid.get(attached) as List<*>).all { it is ItemStack && it.isEmpty }) { "Curios has pending displaced items" }
            val capability=optional(get.call(null,player)) ?: error("Curios inventory is missing")
            val root=checkNotNull(nativeRoot.get(capability))
            check(data.invoke(player,attachment)===root) { "Curios capability differs from its serialized attachment" }
            buildList { for((name,owner) in map(containers.call(capability))) {
                for((prefix,reader) in listOf("" to normal,"cosmetic/" to cosmetic)) {
                    if(prefix.isNotEmpty() && hasCosmetic.call(owner)!=true)continue
                    val leaf=checkNotNull(reader.call(owner))
                    add(group("$prefix$name",leaf).also { entry ->
                        entry.roots=listOf(root,checkNotNull(owner),leaf)
                        entry.attached={
                            check(data.invoke(player,attachment)===root) { "Curios attachment identity changed" }
                            check(map(containers.call(capability))[name]===owner) { "Curios $name slot holder changed" }
                            check(reader.call(owner)===leaf) { "Curios $name native stacks changed" }
                            true
                        }
                    })
                }
            } }
        }
    }
    private fun cosmetic():(ServerPlayer)->List<Group> {
        val get=method("lain.mods.cos.api.CosArmorAPI","getCAStacks",UUID::class.java)
        val base="lain.mods.cos.api.inventory.CAStacksBase"
        val size=method(base,"getSlots");val read=method(base,"getStackInSlot",Int::class.javaPrimitiveType!!)
        val write=method(base,"setStackInSlot",Int::class.javaPrimitiveType!!,ItemStack::class.java)
        return { player ->
            val owner=checkNotNull(get.call(null,player.uuid))
            listOf(Group("cosmeticarmorreworked","armor",owner,size.call(owner) as Int,EquipmentListBinding.itemHandler(owner)) { read.call(owner,it) as ItemStack }.also { entry ->
                entry.write={ index,stack -> write.call(owner,index,stack) }
                entry.attached={ get.call(null,player.uuid)===owner }
            })
        }
    }
    private fun toolbelt():(ServerPlayer)->List<Group> {
        val base="dev.gigaherz.toolbelt.slot.BeltAttachment"
        val get=method(base,"get",LivingEntity::class.java)
        val write=method(base,"setContents",ItemStack::class.java)
        val inventory=type(base).getDeclaredField("inventory").also { it.isAccessible=true }
        return { player ->
            val attachment=get.call(null,player)
            if(attachment==null)emptyList() else {
                val handler=checkNotNull(inventory.get(attachment))
                val backing=EquipmentListBinding.itemHandler(handler)
                check(backing.read().size==1) { "Unsupported Tool Belt attachment layout" }
                listOf(Group("toolbelt","belt",handler,1,backing) { backing.read()[it] }.also { entry ->
                    entry.write={ index,stack ->
                        require(index==0)
                        com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate.replace(checkNotNull(attachment),stack) { write.call(attachment,stack) }
                    }
                    entry.roots=listOf(checkNotNull(attachment),handler)
                    entry.attached={ get.call(null,player)===attachment && inventory.get(attachment)===handler }
                })
            }
        }
    }
    private fun backpacked():(ServerPlayer)->List<Group> {
        // BackpackHelper.getBackpacks can resize storage and spawn overflow. A backup read
        // must not trigger that behavior; read Framework's current persisted value directly.
        val key=type("com.mrcrayfish.backpacked.core.ModSyncedDataKeys").getField("BACKPACKS").get(null)
        val get=method("com.mrcrayfish.framework.api.sync.SyncedDataKey","getValue",Entity::class.java)
        val dataType=type("com.mrcrayfish.framework.entity.sync.SyncedEntityData")
        val manager=dataType.getMethod("instance").invoke(null)
        val holder=dataType.getDeclaredMethod("getDataHolder",Entity::class.java).also { it.isAccessible=true }
        val dataMap=type("com.mrcrayfish.framework.entity.sync.DataHolder").getDeclaredField("dataMap").also { it.isAccessible=true }
        val entryType=type("com.mrcrayfish.framework.entity.sync.DataEntry")
        val entryValue=entryType.getDeclaredField("value").also { it.isAccessible=true }
        val nativeSet=entryType.getDeclaredMethod("setValue",Any::class.java).also { it.isAccessible=true }
        val sync=entryType.getMethod("markForSync")
        val caches=Player::class.java.getField("backpacked\$Inventory")
        val cachedType=type("com.mrcrayfish.backpacked.inventory.BackpackInventory")
        val pending=cachedType.getDeclaredField("save").also { it.isAccessible=true }
        val cachedStack=cachedType.getMethod("getBackpackStack")
        fun verifyCaches(player:ServerPlayer,backpacks:net.minecraft.core.NonNullList<*>) {
            // The public cache getters can create inventories and spill locked/overflow
            // items. Inspect existing caches only; do not flush or mutate a backup read.
            val current=caches.get(player) as Array<*>? ?: return
            current.forEachIndexed { index,cache -> if(cache!=null) {
                check(!pending.getBoolean(cache)) { "Backpacked has pending item contents; retry after its next tick" }
                check(index<backpacks.size && cachedStack.invoke(cache)===backpacks[index]) {
                    "Backpacked cached storage no longer matches its equipped item"
                }
            } }
        }
        return { player ->
            check(player is com.bareminimumstudios.guardian.platform.minecraft.OwnedBackpackedPlayer) {
                "Backpacked native ownership hooks are unavailable for this API"
            }
            val backpacks=get.call(key,player) as net.minecraft.core.NonNullList<*>
            verifyCaches(player,backpacks)
            val root=checkNotNull(holder.invoke(manager,player)) { "Framework data holder is missing" }
            val entry=checkNotNull(map(dataMap.get(root))[key]) { "Framework backpack entry is missing" }
            @Suppress("UNCHECKED_CAST")
            fun current()=entryValue.get(entry) as net.minecraft.core.NonNullList<ItemStack>
            check(current()===backpacks) { "Framework public and native backpack storage differ" }
            val backing=EquipmentListBinding.native(entry,::current) { next ->
                verifyCaches(player,current())
                // Rebuild native inventory caches from the handed-back item. Old wrappers
                // must never overwrite restored components or retain mutable item aliases.
                caches.set(player,null)
                com.bareminimumstudios.guardian.platform.minecraft.EquipmentMutationGate.replace(entry,next) {
                    nativeSet.invoke(entry,next)
                }
            }
            listOf(Group("backpacked","back",entry,backpacks.size,backing) {
                val current=current()
                check(current.size==backpacks.size) { "Backpacked slot layout changed during capture" }
                verifyCaches(player,current)
                current[it]
            }.also { group ->
                group.roots=listOf(root,entry)
                group.beforeDetach={ verifyCaches(player,current());caches.set(player,null) }
                group.attached={ holder.invoke(manager,player)===root && map(dataMap.get(root))[key]===entry && get.call(key,player)===current() }
                group.write={ index,stack -> current()[index]=stack;sync.invoke(entry) }
            })
        }
    }
    private fun travelers():(ServerPlayer)->List<Group> {
        val mod=method("com.tiviacz.travelersbackpack.TravelersBackpack","enableIntegration")
        val fabric=runCatching { type("com.tiviacz.travelersbackpack.component.ComponentUtils") }.getOrNull()
        val get=if(fabric!=null)fabric.getMethod("getComponent",Player::class.java)
            else method("com.tiviacz.travelersbackpack.capability.AttachmentUtils","getAttachment",Player::class.java)
        val api=if(fabric!=null)"com.tiviacz.travelersbackpack.component.ITravelersBackpack" else "com.tiviacz.travelersbackpack.capability.ITravelersBackpack"
        val read=method(api,"getBackpack")
        val equip=method(api,"equipBackpack",ItemStack::class.java);val remove=method(api,"remove")
        val itemType=type(if(fabric!=null)"com.tiviacz.travelersbackpack.item.TravelersBackpackItem" else "com.tiviacz.travelersbackpack.items.TravelersBackpackItem")
        return { player ->
            check(com.bareminimumstudios.guardian.platform.minecraft.OwnedEquipmentProvider::class.java.isAssignableFrom(get.declaringClass)) {
                "Traveler's Backpack wrapper ownership hooks are unavailable for this API"
            }
            if(mod.call(null)==true) {
                check(listOf("trinkets","curios","accessories").any { it in installed }) { "Backpack integration has no equipment provider" }
                emptyList()
            } else {
                val owner=optional(get.call(null,player)) ?: error("Traveler's Backpack storage is missing")
                val stack=owner.javaClass.getField("backpack")
                val wrapper=owner.javaClass.getField("backpackWrapper")
                val binding=com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingleSlotBinding(owner,
                    { com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingleSlotBinding.State(stack.get(owner) as ItemStack,wrapper.get(owner)) },
                    { state -> stack.set(owner,state.item);wrapper.set(owner,state.wrapper) },
                    { value -> if(value.isEmpty)remove.call(owner) else equip.call(owner,value) },
                    { value -> value.isEmpty || value.count==1 && itemType.isInstance(value.item) })
                listOf(Group("travelersbackpack","back",owner,1) { read.call(owner) as ItemStack }.also { group ->
                    group.single=binding
                    group.write={ index,value -> require(index==0);com.bareminimumstudios.guardian.platform.minecraft.EquipmentSingletonStorage.write(owner,value) }
                    group.attached={ optional(get.call(null,player))===owner }
                })
            }
        }
    }
}
