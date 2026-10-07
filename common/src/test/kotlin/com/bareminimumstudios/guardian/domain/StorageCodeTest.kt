package com.bareminimumstudios.guardian.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageCodeTest {
    @Test
    fun stableCodesRoundTrip() {
        ActionType.entries.forEach { assertEquals(it, ActionType.fromStorageCode(it.storageCode)) }
        ChangeCause.entries.forEach { assertEquals(it, ChangeCause.fromStorageCode(it.storageCode)) }
    }

    @Test
    fun unknownCodesFailLoudly() {
        assertFailsWith<IllegalArgumentException> { ActionType.fromStorageCode(9999) }
        assertFailsWith<IllegalArgumentException> { ChangeCause.fromStorageCode(9999) }
    }
}
