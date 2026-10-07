package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.BinaryPayload
import com.bareminimumstudios.guardian.domain.ItemStackSnapshot
import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.*
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Registry-aware item codec. Call capture/restore on the logical server thread only. */
object MinecraftItemSnapshotter {
    private val HEADER = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'M'.code.toByte(), 1)
    private const val MAX_ENCODED_BYTES = 1024 * 1024
    private const val MAX_DECODED_BYTES = 8L * 1024 * 1024

    fun capture(stack: ItemStack, registries: HolderLookup.Provider): ItemStackSnapshot {
        if (stack.isEmpty) return ItemStackSnapshot.EMPTY
        // Disk codecs omit transient components. Reject such patches rather than claim a complete snapshot.
        require(stack.componentsPatch.entrySet().none { it.key.codec() == null }) {
            "Item has a nonpersistent component patch that cannot be audited losslessly"
        }
        val tag = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack).orThrow
        require(tag is CompoundTag) { "Item codec did not produce a compound" }
        val output = ByteArrayOutputStream()
        output.write(HEADER)
        NbtIo.writeCompressed(canonical(tag) as CompoundTag, output)
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_ENCODED_BYTES) { "Item snapshot exceeds the encoded size limit" }
        // Also enforce the read budget at capture time so every successful snapshot can be restored.
        decodeTag(bytes)
        return ItemStackSnapshot(ResourceId.parse(BuiltInRegistries.ITEM.getKey(stack.item).toString()), stack.count, BinaryPayload.of(bytes))
    }

    fun restore(snapshot: ItemStackSnapshot, registries: HolderLookup.Provider): ItemStack {
        if (snapshot.isEmpty) return ItemStack.EMPTY
        val bytes = checkNotNull(snapshot.itemData).copyBytes()
        val restored = ItemStack.SINGLE_ITEM_CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), decodeTag(bytes)).orThrow
        require(ResourceId.parse(BuiltInRegistries.ITEM.getKey(restored.item).toString()) == snapshot.itemId) {
            "Item payload identity does not match its snapshot"
        }
        restored.count = snapshot.count
        return restored
    }

    private fun decodeTag(bytes: ByteArray): CompoundTag {
        require(bytes.size in (HEADER.size + 1)..MAX_ENCODED_BYTES) { "Invalid item snapshot size" }
        require(bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) { "Unsupported Guardian item payload format/version" }
        return ByteArrayInputStream(bytes, HEADER.size, bytes.size - HEADER.size).use {
            NbtIo.readCompressed(it, NbtAccounter.create(MAX_DECODED_BYTES))
        }
    }

    // Compound iteration order must not create false changes; list order remains meaningful.
    private fun canonical(tag: Tag): Tag = when (tag) {
        is CompoundTag -> CompoundTag().also { output -> tag.allKeys.sorted().forEach { output.put(it, canonical(checkNotNull(tag.get(it)))) } }
        is ListTag -> ListTag().also { output -> tag.forEach { output.add(canonical(it)) } }
        else -> tag.copy()
    }
}
