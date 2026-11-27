package com.bareminimumstudios.guardian.platform.minecraft

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import java.util.IdentityHashMap

/** Native provider roots remain unavailable until every part of a journal-owned handback succeeds. */
internal object EquipmentMutationGate {
    private val lock=Any()
    private val roots=IdentityHashMap<Any,Registration>()
    private var reloads=0
    @JvmStatic fun beginResourceReload(): Boolean = synchronized(lock) {
        if(roots.isNotEmpty())false else { reloads++;true }
    }
    @JvmStatic fun endResourceReload()=synchronized(lock) { check(reloads>0);reloads--;Unit }
    /** Callers receive an observer; cancelling it cannot release a still-running native reload. */
    @JvmStatic fun reloadResources(start: Supplier<CompletableFuture<Void>>): CompletableFuture<Void> {
        if(!beginResourceReload())return CompletableFuture.failedFuture(
            IllegalStateException("Finish inventory restoration or recovery before reloading data packs"))
        val observed=object:CompletableFuture<Void>() {
            override fun cancel(mayInterruptIfRunning: Boolean)=false
        }
        try {
            start.get().whenComplete { value,error ->
                endResourceReload()
                if(error==null)observed.complete(value) else observed.completeExceptionally(error)
            }
        } catch(error: Throwable) {
            endResourceReload();observed.completeExceptionally(error)
        }
        return observed
    }

    private class Ticket(val root: Any,val value: Any) { var consumed=false }

    class Registration internal constructor(private val owned: List<Any>) : AutoCloseable {
        private val thread=Thread.currentThread()
        private var closed=false
        private var ticket: Ticket?=null

        internal fun accepts(root: Any,value: Any): Boolean = synchronized(lock) {
            val permit=ticket
            if(closed || Thread.currentThread()!==thread || permit==null || permit.consumed ||
                permit.root!==root || permit.value!==value)false
            else { permit.consumed=true;true }
        }

        internal fun <T> replace(root: Any,value: Any,action: ()->T): T {
            val permit=Ticket(root,value)
            synchronized(lock) {
                check(!closed && Thread.currentThread()===thread && ticket==null && roots[root]===this)
                ticket=permit
            }
            try {
                val result=action()
                check(permit.consumed) { "Native equipment replacement hook did not consume its exact permit" }
                return result
            } finally { synchronized(lock) { ticket=null } }
        }

        override fun close()=synchronized(lock) {
            check(Thread.currentThread()===thread && !closed && ticket==null)
            check(owned.all { roots[it]===this }) { "Native provider ownership changed before handback" }
            owned.forEach(roots::remove);closed=true
        }
    }

    fun acquire(objects: Collection<Any>): Registration = synchronized(lock) {
        check(reloads==0) { "Equipment restoration is unavailable during a data pack reload" }
        val seen=IdentityHashMap<Any,Boolean>()
        val copy=objects.filter { seen.put(it,true)==null }
        require(copy.isNotEmpty() && copy.size<=1024)
        check(copy.none { roots.containsKey(it) }) { "Native equipment root is already owned" }
        Registration(copy).also { registration -> copy.forEach { roots[it]=registration } }
    }

    @JvmStatic fun blocked(root: Any): Boolean = synchronized(lock) { roots.containsKey(root) }

    /** Called by one verified native method entry; its callbacks cannot reuse this authorization. */
    @JvmStatic fun allowsReplacement(root: Any,value: Any): Boolean {
        val registration=synchronized(lock) { roots[root] } ?: return true
        return registration.accepts(root,value)
    }

    fun <T> replace(root: Any,value: Any,action: ()->T): T {
        val registration=synchronized(lock) { roots[root] } ?: return action()
        return registration.replace(root,value,action)
    }
}
