package com.bareminimumstudios.guardian.platform.minecraft;

import net.minecraft.world.level.block.state.properties.Property;

/**
 * Tiny Java generic bridge for Minecraft's Property<T extends Comparable<T>> API.
 * Keeping the unchecked cast here prevents raw/generic gymnastics from leaking through Kotlin.
 */
public final class BlockStatePropertySerializer {
    private BlockStatePropertySerializer() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static String valueName(Property property, Comparable value) {
        return property.getName(value);
    }
}