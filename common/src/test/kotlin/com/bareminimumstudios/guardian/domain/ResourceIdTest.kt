package com.bareminimumstudios.guardian.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceIdTest {
    @Test
    fun parsesExplicitNamespace() {
        assertEquals(ResourceId("create", "brass_block"), ResourceId.parse("create:brass_block"))
    }

    @Test
    fun defaultsToMinecraftNamespace() {
        assertEquals(ResourceId("minecraft", "stone"), ResourceId.parse("stone"))
    }

    @Test
    fun rejectsInvalidUppercaseIdentifiers() {
        assertFailsWith<IllegalArgumentException> { ResourceId.parse("Minecraft:Stone") }
    }
}
