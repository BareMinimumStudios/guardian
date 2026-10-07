package com.bareminimumstudios.guardian.platform.minecraft;

import com.bareminimumstudios.guardian.domain.BlockStateSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.Map;

/** Converts an immutable Guardian block snapshot back into a registered Minecraft BlockState. */
public final class BlockStateSnapshotDecoder {
    private BlockStateSnapshotDecoder() {
    }

    public static BlockState decode(BlockStateSnapshot snapshot) {
        Identifier id = Identifier.of(snapshot.getBlockId().getNamespace(), snapshot.getBlockId().getPath());
        Block block = Registries.BLOCK.getOrEmpty(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown block identifier in Guardian history: " + id));

        BlockState state = block.getDefaultState();
        for (Map.Entry<String, String> entry : snapshot.getProperties().entrySet()) {
            Property<?> property = block.getStateManager().getProperty(entry.getKey());
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
        Identifier blockId
    ) {
        T parsed = property.parse(rawValue)
            .orElseThrow(() -> new IllegalArgumentException(
                "Block " + blockId + " property '" + property.getName() + "' no longer accepts value '" + rawValue + "'"
            ));
        return state.with(property, parsed);
    }
}
