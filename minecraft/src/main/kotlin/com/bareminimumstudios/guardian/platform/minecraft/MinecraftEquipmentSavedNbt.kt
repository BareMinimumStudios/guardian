package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.domain.*
import net.minecraft.nbt.*

internal enum class EquipmentSavePlatform { FABRIC, NEOFORGE }

/** Equipment portion only. The caller must separately verify player UUID, data version and journal authority. */
internal object MinecraftEquipmentSavedNbt {
    class Repaired(val player: CompoundTag, val cosmeticArmor: CompoundTag?)
    private data class Key(val provider: String, val name: String)
    private class Group(val size: Int, val items: List<CompoundTag?>, val replace: (List<CompoundTag?>) -> Unit)
    private val supported = setOf("minecraft", "trinkets", "curios", "accessories", "cosmeticarmorreworked", "toolbelt", "backpacked", "travelersbackpack")
    private val itemFields = setOf("id", "count", "components")

    fun verify(player: CompoundTag, cosmeticArmor: CompoundTag?, platform: EquipmentSavePlatform,
               layout: PlayerStorageLayout, expected: Map<EquipmentSlotAddress, ItemStackSnapshot>) {
        val wanted = topology(layout, expected)
        val groups = groups(player.copy(), cosmeticArmor?.copy(), platform, layout.providers, wanted)
        require(groups.mapValues { it.value.size } == wanted) { "Saved equipment topology differs from its recorded image" }
        for ((address, snapshot) in expected) {
            val (key, index) = split(address)
            require(groups.getValue(key).items[index] == item(snapshot)) { "Saved equipment differs at $address" }
        }
    }

    /** Validate every before/after slot first, then edit private document copies. Never writes a live file. */
    fun repair(player: CompoundTag, cosmeticArmor: CompoundTag?, platform: EquipmentSavePlatform,
               layout: PlayerStorageLayout, original: Map<EquipmentSlotAddress, ItemStackSnapshot>,
               expected: Map<EquipmentSlotAddress, ItemStackSnapshot>): Repaired {
        val wanted = topology(layout, original)
        require(topology(layout, expected) == wanted)
        val result = Repaired(player.copy(), cosmeticArmor?.copy())
        val groups = groups(result.player, result.cosmeticArmor, platform, layout.providers, wanted)
        require(groups.mapValues { it.value.size } == wanted) { "Saved equipment topology differs from the recovery image" }
        val targets = wanted.mapValues { (_, size) -> MutableList<CompoundTag?>(size) { null } }
        for ((address, before) in original) {
            val (key, index) = split(address)
            val after = item(expected.getValue(address))
            val actual = groups.getValue(key).items[index]
            require(actual == item(before) || actual == after) { "Conflicting saved equipment at $address; no file will be overwritten" }
            targets.getValue(key)[index] = after
        }
        targets.forEach { (key, items) -> groups.getValue(key).replace(items) }
        verify(result.player, result.cosmeticArmor, platform, layout, expected)
        return result
    }

    private fun topology(layout: PlayerStorageLayout, image: Map<EquipmentSlotAddress, ItemStackSnapshot>): Map<Key, Int> {
        require(layout.providers.all { it in supported }) { "Unsupported equipment provider in recovery image" }
        require(image.keys == layout.extraSlots.toSet()) { "Incomplete equipment image" }
        return image.keys.groupBy { split(it).first }.mapValues { (_, addresses) ->
            val indices = addresses.map { split(it).second }.sorted()
            require(indices == indices.indices.toList()) { "Equipment group has missing or duplicate indices" }
            indices.size
        }
    }

    private fun split(address: EquipmentSlotAddress): Pair<Key, Int> {
        val separator = address.slot.lastIndexOf('/')
        require(separator > 0)
        val text = address.slot.substring(separator + 1)
        val index = text.toIntOrNull() ?: error("Invalid named equipment index")
        require(index in 0..255 && text == index.toString())
        return Key(address.provider, address.slot.substring(0, separator)) to index
    }

    private fun groups(player: CompoundTag, cosmetic: CompoundTag?, platform: EquipmentSavePlatform,
                       providers: Set<String>, wanted: Map<Key, Int>): Map<Key, Group> {
        // A migrated save can retain both loaders' attachment roots. Never choose one
        // silently when both can contain an independently restored copy of the same item.
        val foreignRoot = if (platform == EquipmentSavePlatform.FABRIC) "neoforge:attachments" else "fabric:attachments"
        fun hasPath(vararg path: String): Boolean {
            var tag: Tag? = player
            for (key in path) tag = (tag as? CompoundTag)?.get(key) ?: return false
            return true
        }
        if ("accessories" in providers) require(!hasPath(foreignRoot, "accessories:inventory_holder")) {
            "Ambiguous Accessories storage remains under the other loader's attachment root"
        }
        if ("travelersbackpack" in providers) {
            val foreign = if (platform == EquipmentSavePlatform.FABRIC)
                hasPath("neoforge:attachments", "travelersbackpack:travelers_backpack")
            else hasPath("cardinal_components", "travelersbackpack:travelersbackpack")
            require(!foreign) { "Ambiguous Traveler's Backpack storage remains under the other loader's root" }
        }
        if ("backpacked" in providers) {
            val foreign = if (platform == EquipmentSavePlatform.FABRIC)
                (player.get("neoforge:attachments") as? CompoundTag)?.get("framework:data_holder")
            else (player.get("FrameworkDataHolder") as? CompoundTag)?.get("Keys")
            if (foreign != null) {
                require(foreign is ListTag) { "Invalid foreign Framework storage" }
                require(foreign.none { entry ->
                    val value = compound(entry)
                    string(value, "DataKey") in setOf("backpacked:backpacks", "backpacked:backpack")
                }) { "Ambiguous Backpacked storage remains under the other loader's root" }
            }
        }
        val result = linkedMapOf<Key, Group>()
        fun add(provider: String, name: String, group: Group) {
            require(group.size in 0..256)
            if (group.size > 0) require(result.put(Key(provider, name), group) == null) { "Duplicate saved equipment group" }
        }
        fun indexed(provider: String, name: String, holder: CompoundTag, key: String, size: Int, byteSlot: Boolean = false, skinFlags: Boolean = false) {
            add(provider, name, sparse(holder, key, size, byteSlot, skinFlags))
        }
        if ("minecraft" in providers) indexed("minecraft", "ender_chest", player, "EnderItems", 27, true)
        if ("trinkets" in providers) {
            val root = branch(player, "cardinal_components", "trinkets:trinkets")
            for (group in EquipmentSavedNbtAccess.keys(root)) {
                val types = compound(root.get(group))
                for (slot in EquipmentSavedNbtAccess.keys(types)) {
                    val inventory = compound(types.get(slot))
                    val values = list(inventory, "Items")
                    add("trinkets", "$group/$slot", positional(inventory, "Items", values.size))
                }
            }
        }
        if ("accessories" in providers) {
            val root = branch(player, attachmentRoot(platform), "accessories:inventory_holder", "accessories_containers")
            for (name in EquipmentSavedNbtAccess.keys(root)) {
                val holder = compound(root.get(name))
                val size = integer(holder, "current_size")
                indexed("accessories", name, holder, "items", size)
                indexed("accessories", "cosmetic/$name", holder, "cosmetics", size)
            }
        }
        if ("curios" in providers) {
            val root = branch(player, "neoforge:attachments", "curios:inventory")
            val names = hashSetOf<String>()
            for (entry in list(root, "Curios")) {
                val container = compound(entry)
                val name = string(container, "Identifier")
                require(names.add(name)) { "Duplicate saved Curios identifier" }
                val handler = compound(container.get("StacksHandler"))
                val stacks = compound(handler.get("Stacks"))
                indexed("curios", name, stacks, "Items", integer(stacks, "Size"))
                val cosmetics = compound(handler.get("Cosmetics"))
                val image = sparse(cosmetics, "Items", integer(cosmetics, "Size"), false, false)
                if (boolean(handler, "HasCosmetic")) add("curios", "cosmetic/$name", image)
                else require(image.items.all { it == null }) { "Disabled Curios cosmetics contain unrecorded items" }
            }
        }
        if ("cosmeticarmorreworked" in providers) {
            val file = requireNotNull(cosmetic) { "Cosmetic Armor's separate saved file is missing" }
            indexed("cosmeticarmorreworked", "armor", file, "Items", integer(file, "Size"), skinFlags = true)
        }
        if ("toolbelt" in providers) {
            val holder = branch(player, "neoforge:attachments", "toolbelt:belt")
            val size = if (holder.get("Size") == null) 1 else integer(holder, "Size")
            require(size == 1) { "Unsupported saved Tool Belt layout" }
            indexed("toolbelt", "belt", holder, "Items", size)
        }
        if ("backpacked" in providers) {
            val holder = if (platform == EquipmentSavePlatform.FABRIC) branch(player, "FrameworkDataHolder")
                else branch(player, "neoforge:attachments")
            val key = if (platform == EquipmentSavePlatform.FABRIC) "Keys" else "framework:data_holder"
            val entries = list(holder, key)
            var selected: CompoundTag? = null
            for (entry in entries) {
                val value = compound(entry)
                val dataKey = string(value, "DataKey")
                if (dataKey == "backpacked:backpacks") {
                    require(string(value, "ClassKey") == "minecraft:player" && selected == null) { "Ambiguous saved Backpacked key" }
                    selected = value
                } else if (dataKey == "backpacked:backpack") {
                    require(normalized(compound(value.get("Value"))) == null) { "Legacy Backpacked storage still contains an item" }
                }
            }
            if (selected == null) {
                require(wanted[Key("backpacked", "back")] == 1) { "Missing nondefault Backpacked slot layout" }
                selected = CompoundTag().apply {
                    putString("ClassKey", "minecraft:player"); putString("DataKey", "backpacked:backpacks")
                    put("Value", ListTag().apply { add(CompoundTag()) })
                }
                entries.add(selected); holder.put(key, entries)
            }
            val values = list(selected, "Value")
            add("backpacked", "back", positional(selected, "Value", values.size))
        }
        if ("travelersbackpack" in providers) {
            val holder: CompoundTag
            val key: String
            if (platform == EquipmentSavePlatform.FABRIC) {
                holder = branch(player, "cardinal_components", "travelersbackpack:travelersbackpack"); key = "Wearable"
            } else { holder = branch(player, "neoforge:attachments"); key = "travelersbackpack:travelers_backpack" }
            val raw = holder.get(key)?.let(::compound) ?: CompoundTag()
            val image = normalized(raw)
            val size = wanted[Key("travelersbackpack", "back")]
            if (size == null) require(image == null) { "Unowned standalone Traveler's Backpack remains in saved data" }
            else {
                require(size == 1)
                add("travelersbackpack", "back", Group(1, listOf(image)) { items -> holder.put(key, items.single()?.copy() ?: CompoundTag()) })
            }
        }
        return result
    }

    private fun sparse(holder: CompoundTag, key: String, size: Int, byteSlot: Boolean, skinFlags: Boolean): Group {
        require(size in 0..256)
        val values = list(holder, key)
        require(values.size <= size)
        val originals = arrayOfNulls<CompoundTag>(size)
        val items = MutableList<CompoundTag?>(size) { null }
        for (entry in values) {
            val raw = compound(entry)
            val index = if (byteSlot) EquipmentSavedNbtAccess.byte(raw, "Slot").toInt() and 255 else integer(raw, "Slot")
            require(index in 0 until size && originals[index] == null) { "Duplicate or invalid equipment slot" }
            val metadata = if (skinFlags) setOf("Slot", "isSkinArmor") else setOf("Slot")
            items[index] = normalized(raw, metadata)
            originals[index] = raw
        }
        return Group(size, items) { replacements ->
            require(replacements.size == size)
            val output = ListTag()
            for (index in 0 until size) {
                val target = replacements[index]
                val entry = originals[index]?.copy() ?: CompoundTag()
                itemFields.forEach(entry::remove)
                target?.let { value -> EquipmentSavedNbtAccess.keys(value).forEach { entry.put(it, requireNotNull(value.get(it)).copy()) } }
                val hasMetadata = EquipmentSavedNbtAccess.keys(entry).any { it != "Slot" }
                if (target != null || hasMetadata) {
                    if (byteSlot) entry.putByte("Slot", index.toByte()) else entry.putInt("Slot", index)
                    output.add(entry)
                }
            }
            holder.put(key, output)
        }
    }

    private fun positional(holder: CompoundTag, key: String, size: Int): Group {
        val values = list(holder, key)
        require(size in 0..256 && values.size == size)
        val items = values.map { normalized(compound(it)) }
        return Group(size, items) { replacements ->
            require(replacements.size == size)
            holder.put(key, ListTag().apply { replacements.forEach { add(it?.copy() ?: CompoundTag()) } })
        }
    }

    private fun normalized(raw: CompoundTag, metadata: Set<String> = emptySet()): CompoundTag? {
        require(EquipmentSavedNbtAccess.keys(raw).all { it in itemFields || it in metadata }) { "Unknown saved equipment item fields" }
        if (raw.get("id") == null) {
            require(EquipmentSavedNbtAccess.keys(raw).all { it in metadata }) { "Invalid empty equipment item" }
            return null
        }
        val id = string(raw, "id")
        ResourceId.parse(id)
        val count = if (raw.get("count") == null) 1 else integer(raw, "count")
        require(count in 1..99)
        val result = raw.copy()
        metadata.forEach(result::remove)
        result.putInt("count", count)
        result.get("components")?.let {
            require(it is CompoundTag)
            if (it.isEmpty) result.remove("components")
        }
        return result
    }

    private fun item(snapshot: ItemStackSnapshot): CompoundTag? {
        if (snapshot.isEmpty) return null
        require(snapshot.count in 1..99)
        val tag = MinecraftItemSnapshotter.decodeTag(requireNotNull(snapshot.itemData).copyBytes())
        require(EquipmentSavedNbtAccess.keys(tag).all { it in setOf("id", "components") })
        require(string(tag, "id") == snapshot.itemId.toString())
        tag.putInt("count", snapshot.count)
        return normalized(tag)
    }

    private fun compound(tag: Tag?): CompoundTag = tag as? CompoundTag ?: error("Missing or invalid equipment compound")
    private fun list(holder: CompoundTag, key: String): ListTag = holder.get(key)?.let { it as? ListTag ?: error("Invalid equipment item list") } ?: ListTag()
    private fun string(holder: CompoundTag, key: String): String { require(holder.get(key) is StringTag); return EquipmentSavedNbtAccess.string(holder, key) }
    private fun integer(holder: CompoundTag, key: String): Int { require(holder.get(key) is IntTag); return EquipmentSavedNbtAccess.integer(holder, key) }
    private fun boolean(holder: CompoundTag, key: String): Boolean { require(holder.get(key) is ByteTag); return EquipmentSavedNbtAccess.byte(holder, key).toInt() != 0 }
    private fun attachmentRoot(platform: EquipmentSavePlatform) = if (platform == EquipmentSavePlatform.FABRIC) "fabric:attachments" else "neoforge:attachments"
    private fun branch(root: CompoundTag, vararg path: String): CompoundTag {
        var current = root
        for (key in path) {
            val existing = current.get(key)
            val child = if (existing == null) CompoundTag().also { current.put(key, it) } else compound(existing)
            current = child
        }
        return current
    }
}
