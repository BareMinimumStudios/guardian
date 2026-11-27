package com.bareminimumstudios.guardian.claims

import com.bareminimumstudios.guardian.domain.ResourceId
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class StructureChunkKey(val dimension: ResourceId,val x: Int,val z: Int)

class StructureChunkMetadata(footprints: Collection<StructureClaimFootprint>,references: Set<StructureChunkKey>,val complete: Boolean) {
    val footprints: List<StructureClaimFootprint> = Collections.unmodifiableList(ArrayList(footprints))
    val references: Set<StructureChunkKey> = Collections.unmodifiableSet(HashSet(references))
    init { require(this.footprints.size<=64 && this.references.size<=256) }
}

/** Reads metadata only. Neither operation may generate chunks or block the claiming thread. */
interface StructureChunkSource {
    fun loaded(key: StructureChunkKey): StructureChunkMetadata?
    fun stored(key: StructureChunkKey): CompletionStage<StructureChunkMetadata>
}

/** Bounded metadata lookup. Missing, old or pending data never authorizes a claim. */
class StructureFootprintLookup(
    private val policy: StructureClaimPolicy,private val source: StructureChunkSource,
    private val clock: ()->Long = System::nanoTime,
    private val maxCached: Int = 4096,private val maxQueued: Int = 2048,
    private val maxInFlight: Int = 4,private val readsPerTick: Int = 4
) : AutoCloseable {
    private data class Coordinate(val x: Int,val z: Int)
    private class Entry(val value: StructureChunkMetadata,val time: Long)
    private class Request(val key: StructureChunkKey) { var invalidated=false }
    private class Completion(val request: Request,val value: StructureChunkMetadata?)
    private val thread=Thread.currentThread()
    private val cache=LinkedHashMap<StructureChunkKey,Entry>(16,.75f,true)
    private val queue=linkedSetOf<StructureChunkKey>()
    private val pending=mutableMapOf<StructureChunkKey,Request>()
    private val coordinates=mutableMapOf<Coordinate,MutableSet<StructureChunkKey>>()
    private val completed=ConcurrentLinkedQueue<Completion>()
    private val dirty=ConcurrentHashMap.newKeySet<Coordinate>()
    private val clearRequested=AtomicBoolean()
    private var closed=false
    val cachedChunks: Int get()=cache.size
    val queuedChunks: Int get()=queue.size
    val readingChunks: Int get()=pending.size
    init {
        require(policy.bufferBlocks<=256 && maxCached in 1..4096 && maxQueued in 1..2048 && maxInFlight in 1..4 && readsPerTick in 1..4)
    }

    fun check(requester: StructureClaimRequester,dimension: ResourceId,chunkX: Int,chunkZ: Int): StructureClaimDecision {
        checkThread();check(!closed);drainInvalidations()
        if(policy.structures.isEmpty() || requester.mayReserveStructure)return StructureClaimDecision.PASS
        if(chunkX !in -1875000..1875000 || chunkZ !in -1875000..1875000)return StructureClaimDecision.LOOKUP_PENDING
        val left=Math.floorDiv(chunkX.toLong()*16-policy.bufferBlocks,16).toInt()
        val right=Math.floorDiv(chunkX.toLong()*16+15+policy.bufferBlocks,16).toInt()
        val top=Math.floorDiv(chunkZ.toLong()*16-policy.bufferBlocks,16).toInt()
        val bottom=Math.floorDiv(chunkZ.toLong()*16+15+policy.bufferBlocks,16).toInt()
        var complete=true
        val references=linkedSetOf<StructureChunkKey>()
        val observed=hashSetOf<Coordinate>()
        fun accept(key: StructureChunkKey): Boolean {
            observed.add(Coordinate(key.x,key.z))
            val metadata=metadata(key)
            if(metadata==null) { complete=false;return false }
            if(!metadata.complete)complete=false
            if(policy.check(requester,chunkX,chunkZ,metadata.footprints,false)==StructureClaimDecision.PROTECTED_STRUCTURE)return true
            for(reference in metadata.references) {
                if(reference.dimension!=dimension) { complete=false;continue }
                if(references.size<256 || reference in references)references.add(reference) else complete=false
            }
            return false
        }
        for(x in left..right)for(z in top..bottom)
            if(accept(StructureChunkKey(dimension,x,z)))return StructureClaimDecision.PROTECTED_STRUCTURE
        // References point to complete starts, whose boxes may span many chunks.
        for(key in references) {
            observed.add(Coordinate(key.x,key.z))
            val value=metadata(key)
            if(value==null || !value.complete)complete=false
            if(value!=null && policy.check(requester,chunkX,chunkZ,value.footprints,false)==StructureClaimDecision.PROTECTED_STRUCTURE)
                return StructureClaimDecision.PROTECTED_STRUCTURE
        }
        if(clearRequested.get() || dirty.any { it in observed })complete=false
        return if(complete)StructureClaimDecision.PASS else StructureClaimDecision.LOOKUP_PENDING
    }

    private fun metadata(key: StructureChunkKey): StructureChunkMetadata? {
        source.loaded(key)?.let { store(key,it);return it }
        cache[key]?.let { entry ->
            val lifetime=if(entry.value.complete)30_000_000_000L else 1_000_000_000L
            if(clock()-entry.time<lifetime)return entry.value
            cache.remove(key);forget(key)
        }
        if(key !in pending && queue.size<maxQueued && queue.add(key))index(key)
        return null
    }

    fun tick() {
        checkThread();if(closed)return;drainInvalidations()
        while(true) {
            val completion=completed.poll() ?: break
            val request=completion.request
            if(pending[request.key]!==request)continue
            pending.remove(request.key)
            if(!request.invalidated)store(request.key,completion.value ?: StructureChunkMetadata(emptyList(),emptySet(),false))
            forget(request.key)
        }
        repeat(readsPerTick) {
            if(queue.isEmpty() || pending.size>=maxInFlight)return
            val key=queue.first();queue.remove(key)
            val loaded=source.loaded(key)
            if(loaded!=null) { store(key,loaded);forget(key);return@repeat }
            val request=Request(key);pending[key]=request;index(key)
            try { source.stored(key).whenComplete { value,error -> completed.add(Completion(request,if(error==null)value else null)) } }
            catch(_:Exception) { completed.add(Completion(request,null)) }
        }
    }

    /** May be called from world-generation workers; cache mutation stays on the owning thread. */
    fun invalidateChunk(chunkX: Int,chunkZ: Int) {
        if(dirty.size>=4096) { clearRequested.set(true);dirty.clear() }
        else dirty.add(Coordinate(chunkX,chunkZ))
    }
    fun invalidateAll() { clearRequested.set(true) }
    private fun drainInvalidations() {
        if(clearRequested.getAndSet(false)) {
            cache.clear();pending.values.forEach { it.invalidated=true }
            coordinates.entries.removeIf { (_,keys) -> keys.removeIf { it !in queue && it !in pending };keys.isEmpty() }
        }
        for(coordinate in dirty.toList())if(dirty.remove(coordinate)) {
            coordinates[coordinate]?.toList()?.forEach { key ->
                cache.remove(key);pending[key]?.invalidated=true;forget(key)
            }
        }
    }
    private fun store(key: StructureChunkKey,value: StructureChunkMetadata) {
        cache[key]=Entry(value,clock());index(key)
        while(cache.size>maxCached) { val old=cache.keys.first();cache.remove(old);forget(old) }
    }
    private fun index(key: StructureChunkKey) { coordinates.getOrPut(Coordinate(key.x,key.z)) { mutableSetOf() }.add(key) }
    private fun forget(key: StructureChunkKey) {
        if(key in cache || key in queue || key in pending)return
        val coordinate=Coordinate(key.x,key.z)
        coordinates[coordinate]?.let { it.remove(key);if(it.isEmpty())coordinates.remove(coordinate) }
    }
    override fun close() {
        checkThread();closed=true;queue.clear();cache.clear();coordinates.clear();dirty.clear()
        pending.values.forEach { it.invalidated=true }
    }
    private fun checkThread()=check(Thread.currentThread()===thread) { "Structure lookup must use its owning server thread" }
}
