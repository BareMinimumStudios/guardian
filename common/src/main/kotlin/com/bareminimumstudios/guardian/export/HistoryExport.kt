package com.bareminimumstudios.guardian.export

import com.bareminimumstudios.guardian.domain.*
import com.bareminimumstudios.guardian.storage.QueryableStorageBackend
import com.bareminimumstudios.guardian.storage.query.*

enum class ExportKind(val permission:String,val fallback:Int) {
    BLOCKS("guardian.lookup",2), ITEMS("guardian.lookup",2), CHAT("guardian.chat",3), SESSIONS("guardian.sessions",3), COMMANDS("guardian.commands",3), ENTITIES("guardian.entities",2), FLUIDS("guardian.fluids",2)
}
sealed interface ExportQuery {
    val kind:ExportKind
    val limit:Int
    fun read(storage:QueryableStorageBackend):List<Any>
    data class Blocks(val query:BlockLookupQuery):ExportQuery { override val kind=ExportKind.BLOCKS;override val limit get()=query.limit;override fun read(storage:QueryableStorageBackend)=storage.lookupBlocks(query) }
    data class Items(val query:ContainerLookupQuery):ExportQuery { override val kind=ExportKind.ITEMS;override val limit get()=query.limit;override fun read(storage:QueryableStorageBackend)=storage.lookupContainers(query) }
    data class Activity(override val kind:ExportKind,val query:PlayerActivityQuery):ExportQuery {
        init { require(query.kinds==when(kind){ExportKind.CHAT->setOf(PlayerActivityKind.CHAT);ExportKind.SESSIONS->setOf(PlayerActivityKind.LOGIN,PlayerActivityKind.LOGOUT);ExportKind.COMMANDS->setOf(PlayerActivityKind.COMMAND);else->error("Not activity history")}) }
        override val limit get()=query.limit;override fun read(storage:QueryableStorageBackend)=storage.lookupActivity(query)
    }
    data class Entities(val query:EntityLookupQuery):ExportQuery { override val kind=ExportKind.ENTITIES;override val limit get()=query.limit;override fun read(storage:QueryableStorageBackend)=storage.lookupEntities(query) }
    data class Fluids(val query:FluidLookupQuery):ExportQuery { override val kind=ExportKind.FLUIDS;override val limit get()=query.limit;override fun read(storage:QueryableStorageBackend)=storage.lookupFluids(query) }
}

/** Domain-only projection; opaque versioned snapshots are preserved, never executed or decoded here. */
internal object ExportRows {
    private fun actor(a:ActorIdentity):Map<String,Any?> = when(a) {
        is ActorIdentity.Player->mapOf("type" to "player","uuid" to a.uuid.toString(),"name" to a.lastKnownName)
        is ActorIdentity.Entity->mapOf("type" to "entity","uuid" to a.uuid?.toString(),"entityType" to a.entityType.toString())
        is ActorIdentity.System->mapOf("type" to "system","source" to a.source)
        ActorIdentity.Unknown->mapOf("type" to "unknown")
    }
    private fun pos(p:BlockPosition)=mapOf("x" to p.x,"y" to p.y,"z" to p.z)
    private fun bytes(b:BinaryPayload?)=b?.let { java.util.Base64.getEncoder().encodeToString(it.copyBytes()) }
    private fun block(s:BlockStateSnapshot)=mapOf("id" to s.blockId.toString(),"properties" to s.properties,"blockEntityBase64" to bytes(s.blockEntityData))
    private fun item(s:ItemStackSnapshot)=mapOf("id" to s.itemId?.toString(),"count" to s.count,"componentsBase64" to bytes(s.itemData))
    private fun owner(o:ItemSlotOwner):Map<String,Any?> = when(o) {
        is ItemSlotOwner.EntityContainer->mapOf("type" to "entity_inventory","dimension" to o.dimension.toString(),"uuid" to o.entityUuid.toString(),"entityType" to o.entityType.toString(),"position" to pos(o.position))
        is ItemSlotOwner.BlockContainer->mapOf("type" to "container","dimension" to o.dimension.toString(),"position" to pos(o.position))
        is ItemSlotOwner.PlayerInventory->mapOf("type" to "player_inventory","uuid" to o.playerId.toString())
        is ItemSlotOwner.Cursor->mapOf("type" to "cursor","uuid" to o.playerId.toString())
        is ItemSlotOwner.CraftingGrid->mapOf("type" to "crafting_grid","uuid" to o.playerId.toString(),"menuId" to o.menuId)
    }
    private fun frame(s:ItemFrameSnapshot?)=s?.let { mapOf("item" to item(it.item),"rotation" to it.rotation) }
    private fun fluid(s:FluidSnapshot)=mapOf("fluid" to s.fluidId.toString(),"block" to s.blockId.toString(),"source" to s.source,"amount" to s.amount)
    fun payloadBytes(row:Any):Long = when(row) {
        is StoredBlockChange->(row.snapshot.before.blockEntityData?.size ?: 0).toLong()+(row.snapshot.after.blockEntityData?.size ?: 0)
        is ContainerTransactionSnapshot->{require(row.changes.size<=4096);row.changes.sumOf { (it.before.itemData?.size ?: 0).toLong()+(it.after.itemData?.size ?: 0) }}
        is EntityAuditEvent->(row.frameBefore?.item?.itemData?.size ?: 0).toLong()+(row.frameAfter?.item?.itemData?.size ?: 0)
        is PlayerActivity,is FluidAuditEvent->0L
        else->error("Unsupported export record")
    }
    fun value(row:Any):Map<String,Any?> = when(row) {
        is StoredBlockChange->row.snapshot.let { s->mapOf("recordType" to "block","rowId" to row.rowId,"eventUuid" to s.eventId.toString(),"time" to s.timestampEpochMillis,"actor" to actor(s.actor),"dimension" to s.dimension.toString(),"position" to pos(s.position),"action" to s.action.name,"cause" to s.cause.name,"journalState" to row.rollbackState.name,"movementUuid" to s.movementId?.toString(),"movementPhase" to s.movementPhase?.name,"before" to block(s.before),"after" to block(s.after)) }
        is ContainerTransactionSnapshot->mapOf("recordType" to "item_transaction","uuid" to row.transactionId.toString(),"time" to row.timestampEpochMillis,"actor" to actor(row.actor),"action" to row.action.name,"menuId" to row.menuId,"containers" to row.containers.map(::owner),"changes" to row.changes.map { mapOf("owner" to owner(it.address.owner),"slot" to it.address.index,"before" to item(it.before),"after" to item(it.after)) })
        is PlayerActivity->mapOf("recordType" to "activity","uuid" to row.eventId.toString(),"time" to row.timestampEpochMillis,"actor" to actor(row.actor),"kind" to row.kind.name,"text" to row.text)
        is EntityAuditEvent->mapOf("recordType" to "entity","uuid" to row.eventId.toString(),"time" to row.timestampEpochMillis,"actor" to actor(row.actor),"dimension" to row.dimension.toString(),"position" to pos(row.position),"targetUuid" to row.targetUuid.toString(),"targetType" to row.targetType.toString(),"kind" to row.kind.name,"beforeName" to row.beforeName,"afterName" to row.afterName,"damageType" to row.damageType?.toString(),"parentUuid" to row.parentUuid?.toString(),"partnerUuid" to row.partnerUuid?.toString(),"frameBefore" to frame(row.frameBefore),"frameAfter" to frame(row.frameAfter))
        is FluidAuditEvent->mapOf("recordType" to "fluid","uuid" to row.eventId.toString(),"time" to row.timestampEpochMillis,"actor" to actor(row.actor),"dimension" to row.dimension.toString(),"position" to pos(row.position),"kind" to row.kind.name,"before" to fluid(row.before),"after" to fluid(row.after))
        else->error("Unsupported export record")
    }
}
