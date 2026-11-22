package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.rollback.*
import net.minecraft.nbt.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Plans targeted files only. The maintenance engine must hold the world lock and verify journal authority. */
internal object EquipmentPlayerFileRecovery {
    private const val BUDGET = 16 * 1024 * 1024

    fun edits(world: Path, owner: ItemSlotOwner.PlayerInventory, images: ItemRollbackImages,
              platform: EquipmentSavePlatform): List<ItemRecoveryFiles.Edit> {
        val layout = requireNotNull(owner.layout)
        require(images.record.purpose == ItemRecoveryPurpose.CHECKPOINT_RESTORE)
        require(images.original.slots.keys.map { it.owner }.toSet() == setOf(owner))
        val root = world.toRealPath()
        val player = read(root, "playerdata/${owner.playerId}.dat", true)
        val cosmetic = if ("cosmeticarmorreworked" in layout.providers)
            read(root, "playerdata/${owner.playerId}.cosarmor", false) else null
        fun extra(snapshot: InventorySnapshot) = layout.extraSlots.withIndex().associate { (index, address) ->
            address to snapshot.slots.getValue(ItemSlotAddress(owner, layout.normalSlots + index))
        }
        val before = extra(images.original)
        val target = extra(images.expected)
        val repaired = MinecraftEquipmentSavedNbt.repair(player.tag, cosmetic?.tag, platform, layout, before, target)
        MaintenanceInventoryNbt.repairEquipmentNormalPart(repaired.player, owner, images)
        // Verify the complete normal/equipment after image before staging either file.
        val repeated = repaired.player.copy()
        MaintenanceInventoryNbt.repairEquipmentNormalPart(repeated, owner, images)
        require(repeated == repaired.player)
        MinecraftEquipmentSavedNbt.verify(repaired.player, repaired.cosmeticArmor, platform, layout, target)
        return buildList {
            add(edit(player, repaired.player))
            cosmetic?.let { add(edit(it, requireNotNull(repaired.cosmeticArmor))) }
        }
    }

    private class Saved(val relative: String, val tag: CompoundTag, val sha: String, val compressed: Boolean)

    private fun read(root: Path, relative: String, compressed: Boolean): Saved {
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(root)) {
            "Missing or unsafe equipment recovery file: $relative"
        }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.size() in 1..BUDGET.toLong())
        val bytes = Files.newInputStream(path).use { it.readNBytes(BUDGET + 1) }
        require(bytes.size in 1..BUDGET)
        val tag = ByteArrayInputStream(bytes).use { input ->
            if (compressed) NbtIo.readCompressed(input, NbtAccounter.create(BUDGET.toLong()))
            else DataInputStream(input).use { NbtIo.read(it, NbtAccounter.create(BUDGET.toLong())) }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
            "Equipment file changed while planning recovery"
        }
        return Saved(relative, tag, ItemRecoveryFiles.sha(bytes), compressed)
    }

    private fun edit(source: Saved, target: CompoundTag) = ItemRecoveryFiles.Edit(source.relative) { staged ->
        val bytes = Files.newInputStream(staged).use { it.readNBytes(BUDGET + 1) }
        require(bytes.size <= BUDGET && ItemRecoveryFiles.sha(bytes) == source.sha) {
            "Equipment file changed between recovery inspection and staging"
        }
        if (source.compressed) NbtIo.writeCompressed(target, staged)
        else Files.newOutputStream(staged).use { output -> DataOutputStream(output).use { NbtIo.write(target, it) } }
        val actual = Files.newInputStream(staged).use { input ->
            if (source.compressed) NbtIo.readCompressed(input, NbtAccounter.create(BUDGET.toLong()))
            else DataInputStream(input).use { NbtIo.read(it, NbtAccounter.create(BUDGET.toLong())) }
        }
        require(actual == target) { "Staged equipment file differs from the verified recovery image" }
    }
}
