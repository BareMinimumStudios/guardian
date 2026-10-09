package com.bareminimumstudios.guardian.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapSaveInvoker {
    @Invoker("save") boolean guardian$saveChunk(ChunkAccess chunk);
}
