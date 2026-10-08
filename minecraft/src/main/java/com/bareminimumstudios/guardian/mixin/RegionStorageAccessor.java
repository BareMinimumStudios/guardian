package com.bareminimumstudios.guardian.mixin;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(RegionFileStorage.class)
public interface RegionStorageAccessor {
    @Accessor("folder") Path guardian$folder();
    @Invoker("getRegionFile") RegionFile guardian$region(ChunkPos pos) throws java.io.IOException;
}
