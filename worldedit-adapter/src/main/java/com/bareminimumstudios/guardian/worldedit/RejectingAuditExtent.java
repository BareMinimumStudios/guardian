package com.bareminimumstudios.guardian.worldedit;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/** Fail-closed extent used when Guardian cannot reserve a bulk audit operation. */
final class RejectingAuditExtent extends AbstractDelegateExtent {
    private final String reason;

    RejectingAuditExtent(Extent extent, String reason) {
        super(extent);
        this.reason = reason;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
        throw new GuardianWorldEditException(reason);
    }
}
