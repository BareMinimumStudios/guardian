package com.bareminimumstudios.guardian.storage.codec

import com.bareminimumstudios.guardian.domain.*
import kotlin.test.*

class ItemFrameSnapshotCodecTest {
    private val full=ItemFrameSnapshot(ItemStackSnapshot(ResourceId.parse("example:relic"),1,BinaryPayload.of(byteArrayOf(1,2,3))),7)
    @Test fun codecPreservesOpaqueComponentBytesAndEmptyState() {
        assertEquals(full,ItemFrameSnapshotCodec.decode(ItemFrameSnapshotCodec.encode(full)))
        val empty=ItemFrameSnapshot(ItemStackSnapshot.EMPTY,0);assertEquals(empty,ItemFrameSnapshotCodec.decode(ItemFrameSnapshotCodec.encode(empty)))
    }
    @Test fun codecRejectsTruncationTrailingUnknownFormatAndInvalidCounts() {
        val bytes=ItemFrameSnapshotCodec.encode(full)
        assertFailsWith<IllegalArgumentException>{ItemFrameSnapshotCodec.decode(bytes.copyOf(5))}
        assertFailsWith<IllegalArgumentException>{ItemFrameSnapshotCodec.decode(bytes+byteArrayOf(0))}
        assertFailsWith<IllegalArgumentException>{ItemFrameSnapshotCodec.decode(bytes.clone().also { it[0]=0 })}
        assertFailsWith<IllegalArgumentException>{ItemFrameSnapshotCodec.decode(bytes.clone().also { it[5]=2 })}
    }
    @Test fun codecEnforcesComponentPayloadBound() {
        assertFailsWith<IllegalArgumentException>{ItemFrameSnapshotCodec.encode(full.copy(item=full.item.copy(itemData=BinaryPayload.of(ByteArray(1024*1024+1)))))}
    }
}
