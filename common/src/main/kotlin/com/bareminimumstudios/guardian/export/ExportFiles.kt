package com.bareminimumstudios.guardian.export

import java.io.*
import java.nio.file.*
import java.time.Instant
import java.util.UUID

/** Completed files are retained deliberately. No custom paths, replacement, import or automatic deletion. */
object ExportFiles {
    const val MAX_BYTES=16L*1024*1024
    const val MAX_FILES=64
    data class Result(val filename:String,val count:Int,val mayHaveMore:Boolean)
    fun write(root:Path,kind:ExportKind,limit:Int,filters:String,requester:String,requestedAt:Long,scope:Map<String,Any?>,rows:List<Any>,permitted:()->Boolean):Result {
        require(limit in 1..500 && rows.size<=limit)
        check(permitted()) { "Export cancelled" }
        var payload=0L
        for(row in rows){val bytes=ExportRows.payloadBytes(row);require(bytes<=4L*1024*1024) { "One record exceeds the 4 MiB snapshot budget" };payload+=bytes;require(payload<=8L*1024*1024) { "Export exceeds the 8 MiB snapshot budget; narrow filters or lower l:" }}
        val base=root.toAbsolutePath().normalize();require(!Files.isSymbolicLink(base))
        Files.createDirectories(base)
        val directory=base.resolve("exports");require(!Files.isSymbolicLink(directory));Files.createDirectories(directory)
        Files.list(directory).use { require(it.limit((MAX_FILES+1).toLong()).count()<MAX_FILES) { "Export directory is full; a server administrator must move or remove old exports" } }
        val id=UUID.randomUUID();val stem="guardian-${kind.name.lowercase()}-$requestedAt-$id"
        val partial=directory.resolve("$stem.part");val target=directory.resolve("$stem.jsonl")
        var published=false;var created=false
        try {
            Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { file->
                created=true
                val bounded=object:FilterOutputStream(file) {
                    var written=0L
                    override fun write(b:Int){check(permitted()) { "Export cancelled" };check(written+1<=MAX_BYTES){"Export exceeds 16 MiB; narrow filters or lower l:"};out.write(b);written++}
                    override fun write(b:ByteArray,off:Int,len:Int){check(permitted()) { "Export cancelled" };check(written+len<=MAX_BYTES){"Export exceeds 16 MiB; narrow filters or lower l:"};out.write(b,off,len);written+=len}
                }
                BufferedWriter(OutputStreamWriter(bounded,Charsets.UTF_8)).use { writer->
                    json(writer,mapOf("recordType" to "export","formatVersion" to 1,"category" to kind.name.lowercase(),"requestedAt" to requestedAt,"exportedAt" to Instant.now().toString(),"requester" to requester,"filters" to filters,"scope" to scope,"recordCount" to rows.size,"limit" to limit,"mayHaveMore" to (rows.size==limit),"snapshotEncoding" to "Guardian-native base64; not a backup/import format"));writer.newLine()
                    for(row in rows){check(permitted()){ "Export cancelled" };json(writer,ExportRows.value(row));writer.newLine()}
                }
            }
            check(permitted()) { "Export cancelled" }
            Files.move(partial,target)
            published=true
            return Result(target.fileName.toString(),rows.size,rows.size==limit)
        }finally{if(created && !published)Files.deleteIfExists(partial)}
    }
    private fun json(w:Writer,value:Any?) {
        when(value) {
            null->w.write("null")
            is String->{w.write("\"");for(c in value)when(c){'"'->w.write("\\\"");'\\'->w.write("\\\\");else->if(c.code<32 || c.isSurrogate())w.write("\\u"+c.code.toString(16).padStart(4,'0'))else w.write(c.code)};w.write("\"")}
            is Boolean,is Int,is Long->w.write(value.toString())
            is Map<*,*>->{w.write("{");value.entries.forEachIndexed { i,e->if(i>0)w.write(",");json(w,e.key as String);w.write(":");json(w,e.value) };w.write("}")}
            is Iterable<*>->{w.write("[");value.forEachIndexed { i,v->if(i>0)w.write(",");json(w,v) };w.write("]")}
            else->error("Unsupported export value")
        }
    }
}
