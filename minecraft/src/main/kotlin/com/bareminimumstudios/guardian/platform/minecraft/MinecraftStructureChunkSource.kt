package com.bareminimumstudios.guardian.platform.minecraft

import com.bareminimumstudios.guardian.claims.*
import com.bareminimumstudios.guardian.domain.ResourceId
import net.minecraft.nbt.*
import net.minecraft.nbt.visitors.CollectFields
import net.minecraft.nbt.visitors.FieldSelector
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.ChunkScanAccess
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.*

/** Read-only native metadata scans. It never obtains a generation ticket or loads a chunk. */
internal class MinecraftStructureChunkSource(private val server: MinecraftServer,structures: Set<ResourceId>) : StructureChunkSource,AutoCloseable {
    private class Level(val level: ServerLevel,val id: ResourceId,val folder: Path,
                        val structures: Map<Structure,ResourceId>,val scanner: ChunkScanAccess) {
        val byId=structures.entries.associate { it.value.toString() to it.key }
    }
    private class Candidate(val id: ResourceId,val structure: Structure,val box: BoundingBox)
    private class Parsed(val candidates: List<Candidate>,val references: Set<StructureChunkKey>,val complete: Boolean)
    private val root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
    private val canonicalRoot=root.toRealPath()
    private val version=MinecraftSavedPlayerDecoder.currentDataVersion()
    private val levels=server.allLevels.associate { level ->
        val id=MinecraftDimensionId.of(level)
        val folder=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),root).normalize()
        require(folder.startsWith(root)) { "Unsafe structure metadata dimension path" }
        id to Level(level,id,folder,MinecraftStructureRegistry.selected(level,structures),level.chunkSource.chunkMap.chunkScanner())
    }
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4),
        { task -> Thread(task,"Guardian-Structure-Metadata").also { it.isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    @Volatile private var closed=false

    override fun loaded(key: StructureChunkKey): StructureChunkMetadata? {
        check(server.isSameThread)
        if(closed || !inside(key))return UNKNOWN
        val context=levels[key.dimension] ?: return UNKNOWN
        val chunk=context.level.chunkSource.getChunkNow(key.x,key.z) ?: return null
        return try {
            val footprints=mutableListOf<StructureClaimFootprint>()
            val references=linkedSetOf<StructureChunkKey>()
            for((structure,id) in context.structures) {
                val start=chunk.allStarts[structure]
                if(start!=null && start.isValid)footprints.add(footprint(id,start.boundingBox))
                chunk.allReferences[structure]?.let { values ->
                    require(values.size<=256)
                    val iterator=values.iterator()
                    while(iterator.hasNext())references.add(reference(context.id,iterator.nextLong()))
                }
                require(references.size<=256)
            }
            StructureChunkMetadata(footprints,references,true)
        } catch(_:Exception) { UNKNOWN }
    }

    override fun stored(key: StructureChunkKey): CompletionStage<StructureChunkMetadata> {
        check(server.isSameThread)
        val context=levels[key.dimension]
        if(closed || context==null || !inside(key))return CompletableFuture.completedFuture(UNKNOWN)
        val result=CompletableFuture<StructureChunkMetadata>()
        dispatch(result) checkFile@{
            // Native region scanners can open region files. Do not ask them to open a
            // nonexistent region merely because someone requested a distant claim.
            val file=context.folder.resolve("region/r.${key.x shr 5}.${key.z shr 5}.mca")
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(canonicalRoot)) {
                result.complete(UNKNOWN);return@checkFile
            }
            val collector=CollectFields(
                FieldSelector(StringTag.TYPE,"Status"),FieldSelector(IntTag.TYPE,"DataVersion"),
                FieldSelector("structures",CompoundTag.TYPE,"starts"),
                FieldSelector("structures",CompoundTag.TYPE,"References"))
            context.scanner.scanChunk(ChunkPos(key.x,key.z),collector).whenComplete { _,error ->
                if(error!=null)result.completeExceptionally(error)
                else dispatch(result) parseScan@{
                    val parsed=parse(context,collector.result as? CompoundTag)
                    if(closed) { result.completeExceptionally(IllegalStateException("Structure lookup stopped"));return@parseScan }
                    // A mod may override adjustBoundingBox. Invoke that native method
                    // on the server thread after the background scan and parsing finish.
                    try { server.execute {
                        try {
                            check(!closed)
                            val boxes=parsed.candidates.map { footprint(it.id,it.structure.adjustBoundingBox(it.box)) }
                            result.complete(StructureChunkMetadata(boxes,parsed.references,parsed.complete))
                        } catch(failure:Exception) { result.completeExceptionally(failure) }
                    } } catch(failure:Exception) { result.completeExceptionally(failure) }
                }
            }
        }
        return result
    }

    private fun parse(context: Level,tag: CompoundTag?): Parsed {
        if(tag==null)return Parsed(emptyList(),emptySet(),false)
        val structures=tag.get("structures") as? CompoundTag ?: return Parsed(emptyList(),emptySet(),false)
        val starts=structures.get("starts") as? CompoundTag ?: return Parsed(emptyList(),emptySet(),false)
        val refs=structures.get("References") as? CompoundTag ?: return Parsed(emptyList(),emptySet(),false)
        val candidates=mutableListOf<Candidate>()
        val references=linkedSetOf<StructureChunkKey>()
        var pieces=0
        for((name,structure) in context.byId) {
            starts.get(name)?.let { value ->
                val start=value as? CompoundTag ?: error("Invalid structure start metadata")
                require(start.get("id") is StringTag)
                val id=EquipmentSavedNbtAccess.string(start,"id")
                if(id!="INVALID") {
                    require(id==name)
                    val children=start.get("Children") as? ListTag ?: error("Missing structure piece boxes")
                    require(children.isNotEmpty());pieces+=children.size;require(pieces<=4096)
                    var combined: BoundingBox?=null
                    for(child in children) {
                        val raw=(child as? CompoundTag)?.get("BB") as? IntArrayTag ?: error("Missing structure piece box")
                        val coordinates=raw.asIntArray
                        require(coordinates.size==6 && coordinates.all { it.toLong() in -30004096L..30004096L })
                        require(coordinates[0]<=coordinates[3] && coordinates[1]<=coordinates[4] && coordinates[2]<=coordinates[5])
                        val box=BoundingBox(coordinates[0],coordinates[1],coordinates[2],coordinates[3],coordinates[4],coordinates[5])
                        combined=combined?.let { previous -> BoundingBox(
                            minOf(previous.minX(),box.minX()),minOf(previous.minY(),box.minY()),minOf(previous.minZ(),box.minZ()),
                            maxOf(previous.maxX(),box.maxX()),maxOf(previous.maxY(),box.maxY()),maxOf(previous.maxZ(),box.maxZ())) } ?: box
                    }
                    candidates.add(Candidate(ResourceId.parse(name),structure,checkNotNull(combined)))
                }
            }
            refs.get(name)?.let { value ->
                val raw=value as? LongArrayTag ?: error("Invalid structure references")
                require(raw.asLongArray.size<=256)
                raw.asLongArray.forEach { references.add(reference(context.id,it)) }
                require(references.size<=256)
            }
        }
        val full=tag.get("Status") is StringTag && EquipmentSavedNbtAccess.string(tag,"Status") in setOf("full","minecraft:full")
        val current=tag.get("DataVersion") is IntTag && EquipmentSavedNbtAccess.integer(tag,"DataVersion")==version
        return Parsed(candidates,references,full && current)
    }

    private fun reference(dimension: ResourceId,packed: Long)=StructureChunkKey(dimension,ChunkPos.getX(packed),ChunkPos.getZ(packed)).also {
        require(inside(it)) { "Structure reference is outside the supported world boundary" }
    }
    private fun footprint(id: ResourceId,box: BoundingBox)=StructureClaimFootprint(id,box.minX(),box.minZ(),box.maxX(),box.maxZ())
    private fun inside(key: StructureChunkKey)=key.x in -1875000..1875000 && key.z in -1875000..1875000
    private fun dispatch(result: CompletableFuture<StructureChunkMetadata>,action: ()->Unit) {
        try { worker.execute {
            try { check(!closed);action() } catch(error:Exception) { result.completeExceptionally(error) }
        } } catch(error:Exception) { result.completeExceptionally(error) }
    }
    override fun close() { closed=true;worker.shutdown() }
    companion object { private val UNKNOWN=StructureChunkMetadata(emptyList(),emptySet(),false) }
}
