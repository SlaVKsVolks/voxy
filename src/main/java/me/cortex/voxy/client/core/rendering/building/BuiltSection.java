package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.sodium.provider.VoxyProviderRenderCell;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.util.Arrays;

//TODO: also have an AABB size stored
public final class BuiltSection {
    public static final boolean VERIFY_BUILT_SECTION_OFFSETS = VoxyCommon.isVerificationFlagOn("verifyBuiltSectionOffsets");
    public static final long NO_REQUEST_EPOCH = 0L;
    public final long position;
    public final long requestEpoch;
    public final byte childExistence;
    public final int aabb;
    public final MemoryBuffer geometryBuffer;
    public final int[] offsets;
    public final MemoryBuffer occupancy;

    private BuiltSection(long position, byte children) {
        this(position, NO_REQUEST_EPOCH, children, -1, null, null, null);
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position, (byte) 0);
    }
    public static BuiltSection empty(long position, long requestEpoch) {
        return new BuiltSection(position, requestEpoch, (byte) 0, -1, null, null, null);
    }
    public static BuiltSection emptyWithChildren(long position, byte children) {
        return new BuiltSection(position, children);
    }
    public static BuiltSection emptyWithChildren(long position, long requestEpoch, byte children) {
        return new BuiltSection(position, requestEpoch, children, -1, null, null, null);
    }

    public BuiltSection(long position, byte childExistence, int aabb, MemoryBuffer geometryBuffer, int[] offsets, MemoryBuffer occupancy) {
        this(position, NO_REQUEST_EPOCH, childExistence, aabb, geometryBuffer, offsets, occupancy);
    }

    public BuiltSection(long position, long requestEpoch, byte childExistence, int aabb, MemoryBuffer geometryBuffer, int[] offsets, MemoryBuffer occupancy) {
        this.position = position;
        this.requestEpoch = requestEpoch;
        this.childExistence = childExistence;
        this.aabb = aabb;
        this.geometryBuffer = geometryBuffer;
        this.offsets = offsets;
        if (offsets != null && VERIFY_BUILT_SECTION_OFFSETS) {
            for (int i = 0; i < offsets.length-1; i++) {
                int delta = offsets[i+1] - offsets[i];
                if (delta<0||delta>=(1<<16)) {
                    throw new IllegalArgumentException("Offsets out of range");
                }
            }
        }
        this.occupancy = occupancy;
    }

    public BuiltSection withRequestEpoch(long requestEpoch) {
        return new BuiltSection(this.position, requestEpoch, this.childExistence, this.aabb, this.geometryBuffer, this.offsets, this.occupancy);
    }

    public BuiltSection clone() {
        return new BuiltSection(this.position, this.requestEpoch, this.childExistence, this.aabb, this.geometryBuffer!=null?this.geometryBuffer.copy():null, this.offsets!=null?Arrays.copyOf(this.offsets, this.offsets.length):null, this.occupancy!=null?this.occupancy.copy():null);
    }

    public void free() {
        if (this.geometryBuffer != null) {
            this.geometryBuffer.free();
        }
        if (this.occupancy != null) {
            this.occupancy.free();
        }
    }

    public boolean isEmpty() {
        return this.geometryBuffer == null;
    }

    public int providerTerrainPassMask() {
        if (this.isEmpty() || this.offsets == null || this.offsets.length < 8) {
            return 0;
        }
        if (this.providerQuadCount(0) > 0) {
            return 0;
        }
        boolean hasCutout = this.providerQuadCount(1) > 0;
        boolean hasSolid = this.providerSolidQuadCount() > 0;
        int passMask = 0;
        if (hasCutout) {
            passMask |= VoxyProviderRenderCell.PASS_CUTOUT;
        }
        if (hasSolid) {
            passMask |= VoxyProviderRenderCell.PASS_SOLID;
        }
        return passMask;
    }

    private int providerSolidQuadCount() {
        int count = 0;
        for (int i = 2; i < 8; i++) {
            count += this.providerQuadCount(i);
        }
        return count;
    }

    private int providerQuadCount(int bufferIndex) {
        if (this.offsets == null || bufferIndex < 0 || bufferIndex >= this.offsets.length) {
            return 0;
        }
        int start = this.offsets[bufferIndex];
        int end;
        if (bufferIndex + 1 < this.offsets.length) {
            end = this.offsets[bufferIndex + 1];
        } else {
            end = this.geometryBuffer == null ? start : (int) (this.geometryBuffer.size / 8L);
        }
        return Math.max(0, end - start);
    }
}
