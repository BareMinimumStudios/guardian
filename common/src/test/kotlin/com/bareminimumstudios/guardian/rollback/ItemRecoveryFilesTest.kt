package com.bareminimumstudios.guardian.rollback

import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class ItemRecoveryFilesTest {
    private val base=Files.createTempDirectory("guardian-item-files")
    private val world=Files.createDirectory(base.resolve("world"))
    private val recovery=base.resolve("recovery")
    private val store=ItemRecoveryFiles(world,recovery)
    private val id=UUID.randomUUID()
    private val fingerprint=ItemRecoveryFiles.sha(byteArrayOf(1,2,3))
    private fun seed() { Files.write(world.resolve("a.dat"),byteArrayOf(1));Files.write(world.resolve("b.dat"),byteArrayOf(2)) }
    private fun edits()=listOf(ItemRecoveryFiles.Edit("a.dat") { Files.write(it,byteArrayOf(11)) },ItemRecoveryFiles.Edit("b.dat") { Files.write(it,byteArrayOf(22)) })
    private fun original() { assertContentEquals(byteArrayOf(1),Files.readAllBytes(world.resolve("a.dat")));assertContentEquals(byteArrayOf(2),Files.readAllBytes(world.resolve("b.dat"))) }
    @Test fun previewOnlyCreatesImmutableTargetedCopies() {
        seed();val plan=store.prepare(id,fingerprint,edits());original()
        assertEquals(plan,store.load(id,fingerprint));assertEquals(2,plan.entries.size)
        assertContentEquals(byteArrayOf(1),Files.readAllBytes(recovery.resolve("$id/${plan.job}/0.before")))
    }
    @Test fun interruptedInstallResumesExactFileMixtureAndAcknowledgesNothingItself() {
        seed();val plan=store.prepare(id,fingerprint,edits())
        assertFailsWith<IllegalStateException> { store.install(plan,plan.token) { if(it==1)error("simulated process interruption") } }
        assertContentEquals(byteArrayOf(11),Files.readAllBytes(world.resolve("a.dat")))
        assertContentEquals(byteArrayOf(2),Files.readAllBytes(world.resolve("b.dat")))
        val restart=ItemRecoveryFiles(world,recovery);val loaded=assertNotNull(restart.load(id,fingerprint))
        restart.install(loaded,loaded.token)
        assertContentEquals(byteArrayOf(11),Files.readAllBytes(world.resolve("a.dat")))
        assertContentEquals(byteArrayOf(22),Files.readAllBytes(world.resolve("b.dat")))
        restart.install(loaded,loaded.token) { error("Already installed file must not be rewritten") }
    }
    @Test fun changedUnrelatedBytesRefuseBeforeAnyReplacement() {
        seed();val plan=store.prepare(id,fingerprint,edits());Files.write(world.resolve("b.dat"),byteArrayOf(99))
        assertFailsWith<IllegalArgumentException> { store.install(plan,plan.token) }
        assertContentEquals(byteArrayOf(1),Files.readAllBytes(world.resolve("a.dat")))
        assertContentEquals(byteArrayOf(99),Files.readAllBytes(world.resolve("b.dat")))
    }
    @Test fun wrongTokenAndForgedPlanNeverWriteTargets() {
        seed();val plan=store.prepare(id,fingerprint,edits())
        assertFailsWith<IllegalArgumentException> { store.install(plan,UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { store.install(plan.copy(token=UUID.randomUUID()),plan.token) }
        original()
    }
    @Test fun corruptedBeforeOrAfterCopiesCannotBeUsed() {
        seed();val plan=store.prepare(id,fingerprint,edits());Files.write(recovery.resolve("$id/${plan.job}/1.after"),byteArrayOf(99))
        assertFailsWith<IllegalArgumentException> { store.load(id,fingerprint) }
        assertFailsWith<IllegalArgumentException> { store.install(plan,plan.token) };original()
    }
    @Test fun incompletePreparationNeverChangesTargetsAndCanBePreparedAgain() {
        seed()
        assertFailsWith<IllegalStateException> { store.prepare(id,fingerprint,listOf(edits()[0],ItemRecoveryFiles.Edit("b.dat") { error("interrupted staging") })) }
        original();assertNull(store.load(id,fingerprint))
        val plan=store.prepare(id,fingerprint,edits());store.install(plan,plan.token)
        assertContentEquals(byteArrayOf(22),Files.readAllBytes(world.resolve("b.dat")))
    }
    @Test fun traversalMissingTargetsAndDifferentWorldOrJournalRefuse() {
        seed()
        assertFailsWith<IllegalArgumentException> { store.prepare(id,fingerprint,listOf(ItemRecoveryFiles.Edit("../outside.dat") {})) }
        assertFailsWith<IllegalArgumentException> { store.prepare(id,fingerprint,listOf(ItemRecoveryFiles.Edit("missing.dat") {})) }
        store.prepare(id,fingerprint,edits())
        assertFailsWith<IllegalArgumentException> { store.load(id,ItemRecoveryFiles.sha(byteArrayOf(4))) }
        val other=Files.createDirectory(base.resolve("other"))
        assertFailsWith<IllegalArgumentException> { ItemRecoveryFiles(other,recovery).load(id,fingerprint) }
        original()
    }
    @Test fun truncatedManifestCannotAuthorizeWrites() {
        seed();val plan=store.prepare(id,fingerprint,edits());Files.write(recovery.resolve("$id/plan.bin"),byteArrayOf(1,2,3))
        assertFailsWith<IllegalArgumentException> { store.install(plan,plan.token) };original()
    }
}
