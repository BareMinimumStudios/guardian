package com.bareminimumstudios.guardian.storage.purge

import com.bareminimumstudios.guardian.domain.*
import java.util.Collections

/** Exact registry IDs only. Collections are copied before a confirmation can retain this scope. */
class PurgeScope(val world: ResourceId? = null, include: Set<ResourceId> = emptySet(), exclude: Set<ResourceId> = emptySet()) {
    val include: Set<ResourceId> = Collections.unmodifiableSet(LinkedHashSet(include))
    val exclude: Set<ResourceId> = Collections.unmodifiableSet(LinkedHashSet(exclude))
    init { require(include.size <= 64 && exclude.size <= 64) }
    val typed: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()
    val global: Boolean get() = world == null && !typed
    fun resources(ids: Collection<ResourceId>): Boolean = (include.isEmpty() || ids.any { it in include }) && ids.none { it in exclude }
    fun matches(block: BlockChangeSnapshot): Boolean = (world == null || world == block.dimension) && resources(listOf(block.before.blockId, block.after.blockId))
    fun matches(items: ContainerTransactionSnapshot): Boolean = (world == null || items.containers.any { it.dimension == world }) && resources(items.changes.flatMap { listOfNotNull(it.before.itemId, it.after.itemId) })
    fun description(): String = if(global) "global across all users/worlds" else buildString {
        append("all users; world: ").append(world ?: "all")
        if(include.isNotEmpty())append("; include: ").append(include.sortedBy { it.toString() }.joinToString(","))
        if(exclude.isNotEmpty())append("; exclude: ").append(exclude.sortedBy { it.toString() }.joinToString(","))
        append(if(typed) "; block/item history only" else "; block/item/entity/fluid history only")
    }
}
