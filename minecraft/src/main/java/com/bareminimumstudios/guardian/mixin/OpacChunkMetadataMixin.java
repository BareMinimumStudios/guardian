package com.bareminimumstudios.guardian.mixin;

import com.bareminimumstudios.guardian.platform.minecraft.OpacChunkHooks;
import com.bareminimumstudios.guardian.platform.minecraft.OpacStructureProtection;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class OpacChunkMetadataMixin implements OpacChunkHooks {
    @Inject(method={"setStartForStructure","setAllStarts","addReferenceForStructure","setAllReferences"},at=@At("RETURN"))
    private void guardian$metadataChanged(CallbackInfo result) {
        ChunkAccess chunk=(ChunkAccess)(Object)this;
        OpacStructureProtection.invalidateChunk(chunk.getPos());
    }
}
