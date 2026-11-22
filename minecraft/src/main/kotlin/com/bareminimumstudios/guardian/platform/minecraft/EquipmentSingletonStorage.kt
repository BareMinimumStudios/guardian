package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap

/** A verified single equipped item whose native wrapper must be rebuilt before final save acknowledgement. */
internal class EquipmentSingleSlotBinding(
    val root: Any,val read: ()->State,val install: (State)->Unit,
    val prepare: (ItemStack)->Unit,val accepts: (ItemStack)->Boolean
) {
    class State(val item: ItemStack,val wrapper: Any?)
}

internal object EquipmentSingletonStorage {
    private val lock=Any()
    private val owned=IdentityHashMap<Any,MinecraftDetachedEquipmentSlot>()
    internal fun register(root: Any,frame: MinecraftDetachedEquipmentSlot)=synchronized(lock) {
        check(!owned.containsKey(root));owned[root]=frame
    }
    internal fun remove(root: Any,frame: MinecraftDetachedEquipmentSlot)=synchronized(lock) {
        check(owned[root]===frame);owned.remove(root);Unit
    }
    private fun frame(root: Any)=synchronized(lock) { owned[root] }
    @JvmStatic fun preparing(root: Any): Boolean = frame(root)?.isPreparing()==true
    @JvmStatic fun read(root: Any): ItemStack? = frame(root)?.let { if(it.isPreparing())null else it.read() }
    @JvmStatic fun saved(root: Any): CompoundTag? = frame(root)?.let { frame ->
        if(frame.isPreparing())null else frame.snapshot().let { item ->
            if(item.isEmpty)CompoundTag() else MinecraftItemSnapshotter.decodeTag(checkNotNull(item.itemData).copyBytes()).apply { putInt("count",item.count) }
        }
    }
    fun write(root: Any,stack: ItemStack)=checkNotNull(frame(root)) { "Native singleton equipment is not owned" }.accept(stack)
}

/** Item authority remains encoded; live getters cannot expose its stack or its old wrapper. */
internal class MinecraftDetachedEquipmentSlot private constructor(
    private val binding: EquipmentSingleSlotBinding,
    private val registries: HolderLookup.Provider,
    private val storage: MinecraftOwnedItemStorage
) : MinecraftInventoryHandoff {
    private val thread=Thread.currentThread()
    private var preparing=false
    private var prepared: EquipmentSingleSlotBinding.State?=null
    private var returned=false
    private var finished=false
    fun isPreparing()=Thread.currentThread()===thread && preparing
    fun read(): ItemStack { check(Thread.currentThread()===thread && !finished);return storage.view[0] }
    fun snapshot(): ItemStackSnapshot { check(Thread.currentThread()===thread && !finished);return storage.snapshot().single() }
    fun accept(stack: ItemStack) {
        check(Thread.currentThread()===thread && !finished && prepared==null && !returned)
        require(binding.accepts(stack)) { "Replacement is not a supported wearable backpack" }
        storage.view[0]=stack
    }
    override fun isCurrent(): Boolean {
        check(Thread.currentThread()===thread)
        if(finished || returned || !EquipmentMutationGate.blocked(binding.root))return false
        val actual=binding.read();val ready=prepared
        return if(ready==null)actual.item===ItemStack.EMPTY && actual.wrapper==null
        else actual.item===ready.item && actual.wrapper===ready.wrapper &&
            MinecraftItemSnapshotter.capture(actual.item,registries)==snapshot()
    }
    override fun beginWrite() {
        check(isCurrent())
        if(prepared!=null) {
            binding.install(EquipmentSingleSlotBinding.State(ItemStack.EMPTY,null))
            prepared=null
            check(isCurrent())
        }
    }
    override fun write(index: Int,expected: ItemStackSnapshot,replacement: ItemStackSnapshot,setter: (ItemStack)->Unit) {
        require(index==0);beginWrite()
        require(binding.accepts(MinecraftItemSnapshotter.restore(replacement,registries)))
        storage.throughSetter(0,expected,replacement,setter);check(isCurrent())
    }
    override fun prepareRelease() {
        check(isCurrent())
        if(prepared!=null)return
        val expected=snapshot();val stack=MinecraftItemSnapshotter.restore(expected,registries)
        preparing=true
        try {
            binding.prepare(stack)
            val actual=binding.read()
            check(MinecraftItemSnapshotter.capture(actual.item,registries)==expected) {
                "Native backpack wrapper changed item components before final save"
            }
            check(actual.item.isEmpty==(actual.wrapper==null)) { "Native backpack wrapper does not match its equipped item" }
            prepared=actual
        } finally { preparing=false }
        check(isCurrent())
    }
    override fun release() { check(isCurrent() && prepared!=null);returned=true }
    fun finishRelease() {
        check(Thread.currentThread()===thread && returned && !finished)
        EquipmentSingletonStorage.remove(binding.root,this);storage.retire();finished=true
    }
    companion object {
        fun capture(binding: EquipmentSingleSlotBinding,registries: HolderLookup.Provider): MinecraftDetachedEquipmentSlot {
            check(EquipmentMutationGate.blocked(binding.root)) { "Native singleton handoff requires provider root ownership" }
            val original=binding.read();require(binding.accepts(original.item))
            require(original.item.isEmpty==(original.wrapper==null)) { "Backpack is awaiting native wrapper initialization" }
            original.wrapper?.let { wrapper ->
                check(wrapper.javaClass.getMethod("getBackpackStack").invoke(wrapper)===original.item) {
                    "Backpack wrapper no longer matches its equipped item"
                }
            }
            val storage=MinecraftOwnedItemStorage.capture(listOf(original.item),registries)
            val frame=MinecraftDetachedEquipmentSlot(binding,registries,storage)
            EquipmentSingletonStorage.register(binding.root,frame)
            try {
                binding.install(EquipmentSingleSlotBinding.State(ItemStack.EMPTY,null));check(frame.isCurrent())
                return frame
            } catch(error: Exception) {
                binding.install(original)
                EquipmentSingletonStorage.remove(binding.root,frame);storage.retire()
                throw error
            }
        }
    }
}
