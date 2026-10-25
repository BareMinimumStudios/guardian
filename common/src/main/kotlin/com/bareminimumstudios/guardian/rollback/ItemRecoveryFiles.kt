package com.bareminimumstudios.guardian.rollback

import java.nio.file.*
import java.nio.channels.FileChannel
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Targeted immutable before/after files. Only an externally owned stopped world may use this store. */
class ItemRecoveryFiles(private val world: Path, private val recoveryRoot: Path) {
    data class Edit(val relative: String, val stage: (Path) -> Unit)
    data class Entry(val relative: String, val before: String, val after: String, val beforeSize: Long, val afterSize: Long)
    data class Plan(val operation: UUID, val fingerprint: String, val token: UUID, val job: UUID, val world: String, val entries: List<Entry>)
    private val root=world.toRealPath()
    private fun area(id: UUID)=recoveryRoot.resolve(id.toString())
    private fun job(plan: Plan)=area(plan.operation).resolve(plan.job.toString())
    private fun manifest(id: UUID)=area(id).resolve("plan.bin")

    fun load(id: UUID, fingerprint: String): Plan? {
        val file=manifest(id)
        if(!Files.exists(file))return null
        require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && file.toRealPath().startsWith(recoveryRoot.toRealPath()))
        require(Files.size(file) in 1..65536)
        val data=Files.readAllBytes(file)
        require(data.size>32 && sha(data.copyOfRange(0,data.size-32))==data.copyOfRange(data.size-32,data.size).joinToString("") { "%02x".format(it) }) { "Recovery plan checksum differs" }
        val plan=DataInputStream(ByteArrayInputStream(data,0,data.size-32)).use { input ->
            require(input.readInt()==0x47525031)
            val operation=UUID.fromString(input.readUTF());val bound=input.readUTF();val token=UUID.fromString(input.readUTF());val job=UUID.fromString(input.readUTF());val savedWorld=input.readUTF()
            val count=input.readInt();require(count in 1..8)
            val entries=(0 until count).map { Entry(input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong()) }
            require(input.available()==0)
            Plan(operation,bound,token,job,savedWorld,entries)
        }
        require(plan.operation==id && plan.fingerprint==fingerprint && plan.world==root.toString()) { "Recovery plan belongs to another journal or world" }
        require(plan.entries.map { it.relative }.distinct().size==plan.entries.size)
        var total=0L
        plan.entries.forEach {
            target(it.relative)
            require(it.before.matches(Regex("[a-f0-9]{64}")) && it.after.matches(Regex("[a-f0-9]{64}")))
            require(it.beforeSize in 1..MAX_FILE && it.afterSize in 1..MAX_FILE)
            total+=it.beforeSize+it.afterSize;require(total<=MAX_TOTAL)
        }
        verifyCopies(plan)
        return plan
    }

    /** Creates backups and staged after files, never opens a live target for writing. */
    fun prepare(id: UUID, fingerprint: String, edits: List<Edit>): Plan {
        load(id,fingerprint)?.let { plan -> checkCurrent(plan);return plan }
        require(edits.size in 1..8 && edits.map { it.relative }.distinct().size==edits.size)
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        Files.createDirectories(area(id))
        require(Files.isDirectory(area(id),LinkOption.NOFOLLOW_LINKS) && area(id).toRealPath().startsWith(recoveryRoot.toRealPath()))
        require(Files.list(area(id)).use { it.count() }<16) { "Too many interrupted preparation directories; preserve them for review" }
        val token=UUID.randomUUID();val job=UUID.randomUUID();val folder=area(id).resolve(job.toString())
        Files.createDirectory(folder)
        var total=0L
        val entries=edits.mapIndexed { i, edit ->
            val source=target(edit.relative)
            val size=Files.size(source);require(size in 1..MAX_FILE)
            val beforeHash=hash(source)
            val before=folder.resolve("$i.before");val after=folder.resolve("$i.after")
            Files.copy(source,before);force(before)
            require(hash(before)==beforeHash && hash(source)==beforeHash) { "World file changed while preparing recovery" }
            Files.copy(before,after)
            edit.stage(after);force(after)
            val afterSize=Files.size(after);require(afterSize in 1..MAX_FILE)
            total+=size+afterSize;require(total<=MAX_TOTAL)
            Entry(edit.relative,beforeHash,hash(after),size,afterSize)
        }
        val plan=Plan(id,fingerprint,token,job,root.toString(),entries)
        checkCurrent(plan)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x47525031);output.writeUTF(id.toString());output.writeUTF(fingerprint);output.writeUTF(token.toString());output.writeUTF(job.toString());output.writeUTF(root.toString());output.writeInt(entries.size)
            entries.forEach { output.writeUTF(it.relative);output.writeUTF(it.before);output.writeUTF(it.after);output.writeLong(it.beforeSize);output.writeLong(it.afterSize) }
        }
        val body=bytes.toByteArray();val packed=body+MessageDigest.getInstance("SHA-256").digest(body)
        val temporary=folder.resolve("plan.tmp")
        Files.write(temporary,packed,StandardOpenOption.CREATE_NEW);force(temporary)
        // The journal/world locks exclude another cooperating preparer; never replace a prior plan.
        check(!Files.exists(manifest(id)))
        Files.move(temporary,manifest(id),StandardCopyOption.ATOMIC_MOVE)
        force(manifest(id));syncDirectory(area(id))
        return load(id,fingerprint)!!
    }

    /** A retry accepts only exact original/staged file hashes, including all unrelated file contents. */
    fun checkCurrent(plan: Plan) {
        require(plan.world==root.toString())
        plan.entries.forEach { entry ->
            val value=hash(target(entry.relative))
            require(value==entry.before || value==entry.after) { "Conflicting file: ${entry.relative}; recovery will not overwrite it" }
        }
    }

    fun install(plan: Plan, token: UUID, installed: (Int) -> Unit = {}) {
        require(load(plan.operation,plan.fingerprint)==plan) { "Recovery plan differs from its committed manifest" }
        require(token==plan.token) { "Recovery confirmation does not match this plan" }
        verifyCopies(plan);checkCurrent(plan)
        plan.entries.forEachIndexed { index, entry ->
            val target=target(entry.relative)
            if(hash(target)==entry.after)return@forEachIndexed
            check(hash(target)==entry.before) { "Recovery target changed" }
            val temporary=Files.createTempFile(target.parent,".guardian-${plan.operation}-",".tmp")
            try {
                Files.copy(job(plan).resolve("$index.after"),temporary,StandardCopyOption.REPLACE_EXISTING);force(temporary)
                require(hash(temporary)==entry.after && hash(target)==entry.before)
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                force(target);syncDirectory(target.parent)
                check(hash(target)==entry.after) { "Installed recovery file differs" }
                installed(index+1)
            } finally { Files.deleteIfExists(temporary) }
        }
        require(plan.entries.all { hash(target(it.relative))==it.after }) { "Not all recovery files were saved" }
    }

    private fun verifyCopies(plan: Plan) {
        require(Files.isDirectory(job(plan),LinkOption.NOFOLLOW_LINKS) && job(plan).toRealPath().startsWith(recoveryRoot.toRealPath()))
        plan.entries.forEachIndexed { i, entry ->
            val before=job(plan).resolve("$i.before");val after=job(plan).resolve("$i.after")
            require(Files.isRegularFile(before,LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(after,LinkOption.NOFOLLOW_LINKS))
            require(Files.size(before)==entry.beforeSize && Files.size(after)==entry.afterSize && hash(before)==entry.before && hash(after)==entry.after) { "Targeted recovery copy is missing or altered" }
        }
    }
    private fun target(relative: String): Path {
        require(relative.isNotBlank() && relative.length<=1024 && !relative.contains('\\') && !relative.contains(':'))
        require(relative.split('/').none { it.isEmpty() || it=="." || it==".." })
        val path=root.resolve(relative).normalize()
        require(path.startsWith(root) && path!=root && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { "Recovery file is missing or outside the world" }
        require(path.toRealPath().startsWith(root)) { "Recovery file resolves outside the world" }
        return path
    }
    companion object {
        const val MAX_FILE=128L*1024*1024
        const val MAX_TOTAL=512L*1024*1024
        fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(path: Path): String {
            require(Files.size(path)<=MAX_FILE)
            val digest=MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input -> val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)} }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun force(path: Path) { FileChannel.open(path,StandardOpenOption.WRITE).use { it.force(true) } }
        private fun syncDirectory(path: Path) {
            try { FileChannel.open(path,StandardOpenOption.READ).use { it.force(true) } }
            catch(error: AccessDeniedException) { if(!System.getProperty("os.name").startsWith("Windows"))throw error }
        }
    }
}
