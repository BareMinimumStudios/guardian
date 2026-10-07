package com.bareminimumstudios.guardian.platform.minecraft;

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** Converts an immutable Guardian block snapshot back into a registered Minecraft BlockState. */
public final class BlockStateSnapshotDecoder {
    private BlockStateSnapshotDecoder() {
    }

    public static BlockState decode(BlockStateSnapshot snapshot) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(snapshot.getBlockId().getNamespace(), snapshot.getBlockId().getPath());
        Block block = BuiltInRegistries.BLOCK.getOptional(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown block identifier in Guardian history: " + id));

        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : snapshot.getProperties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("Block " + id + " no longer has property '" + entry.getKey() + "'");
            }
            state = withParsedValue(state, property, entry.getValue(), id);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withParsedValue(
        BlockState state,
        Property<T> property,
        String rawValue,
        ResourceLocation blockId
    ) {
        T parsed = property.getValue(rawValue)
            .orElseThrow(() -> new IllegalArgumentException(
                "Block " + blockId + " property '" + property.getName() + "' no longer accepts value '" + rawValue + "'"
            ));
        return state.setValue(property, parsed);
    }
}
