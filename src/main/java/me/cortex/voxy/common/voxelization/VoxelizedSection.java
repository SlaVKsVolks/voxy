package me.cortex.voxy.common.voxelization;


import java.util.Arrays;

//16x16x16 block section
public class VoxelizedSection {
    public enum LightingState {
        UNKNOWN,
        REAL_LIGHT,
        VALID_EMPTY_SKY_LIGHT,
        VALID_DEFAULT_SKY_LIGHT,
        SYNTHETIC_PREVIEW_LIGHT,
        MISSING_SKY_LIGHT,
        NO_SKY_DIMENSION
    }

    public enum LightSourceKind {
        UNKNOWN,
        REAL_LIGHT,
        VALID_EMPTY_SKY_LIGHT,
        VALID_DEFAULT_SKY_LIGHT,
        MISSING_SKY_LIGHT,
        SYNTHETIC_SURFACE_PREVIEW,
        NO_SKY_DIMENSION
    }

    public enum SourceKind {
        UNKNOWN,
        REAL_CHUNK,
        SURFACE_PREVIEW,
        SYNTHETIC_PREVIEW,
        ZERO_CLEAR
    }

    public enum Confidence {
        UNKNOWN(0),
        LOW(1),
        MEDIUM(2),
        HIGH(3);

        public final int rank;

        Confidence(int rank) {
            this.rank = rank;
        }

        public boolean atLeast(Confidence other) {
            return this.rank >= other.rank;
        }
    }

    public int x;
    public int y;
    public int z;
    public int lvl0NonAirCount;
    public boolean syntheticPreview;
    public LightingState lightingState = LightingState.UNKNOWN;
    public LightSourceKind lightSourceKind = LightSourceKind.UNKNOWN;
    public SourceKind sourceKind = SourceKind.UNKNOWN;
    public Confidence confidence = Confidence.UNKNOWN;
    public long dataEpoch;
    public final long[] section;
    public VoxelizedSection(long[] section) {
        this.section = section;
    }

    public static int getBaseIndexForLevel(int lvl) {
        int offset = lvl==1?(1<<12):0;
        offset |= lvl==2?(1<<12)|(1<<9):0;
        offset |= lvl==3?(1<<12)|(1<<9)|(1<<6):0;
        offset |= lvl==4?(1<<12)|(1<<9)|(1<<6)|(1<<3):0;
        return offset;
    }

    public VoxelizedSection setPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public VoxelizedSection setSyntheticPreview(boolean syntheticPreview) {
        this.syntheticPreview = syntheticPreview;
        return this;
    }

    public VoxelizedSection setLightingState(LightingState lightingState) {
        this.lightingState = lightingState == null ? LightingState.UNKNOWN : lightingState;
        this.lightSourceKind = lightSourceKindFrom(this.lightingState);
        this.syntheticPreview |= this.lightingState == LightingState.SYNTHETIC_PREVIEW_LIGHT;
        return this;
    }

    public VoxelizedSection setLightSourceKind(LightSourceKind lightSourceKind) {
        this.lightSourceKind = lightSourceKind == null ? LightSourceKind.UNKNOWN : lightSourceKind;
        this.lightingState = lightingStateFrom(this.lightSourceKind);
        this.syntheticPreview |= this.lightSourceKind == LightSourceKind.SYNTHETIC_SURFACE_PREVIEW;
        return this;
    }

    public VoxelizedSection setSource(SourceKind sourceKind, Confidence confidence) {
        this.sourceKind = sourceKind == null ? SourceKind.UNKNOWN : sourceKind;
        this.confidence = confidence == null ? Confidence.UNKNOWN : confidence;
        this.syntheticPreview |= this.sourceKind == SourceKind.SURFACE_PREVIEW
                || this.sourceKind == SourceKind.SYNTHETIC_PREVIEW;
        return this;
    }

    public VoxelizedSection setDataEpoch(long dataEpoch) {
        this.dataEpoch = dataEpoch;
        return this;
    }

    public boolean isTrustedRealData() {
        return this.sourceKind == SourceKind.REAL_CHUNK
                && isTrustedLight(this.lightSourceKind)
                && this.confidence.atLeast(Confidence.HIGH);
    }

    public boolean isProvisional() {
        return this.sourceKind == SourceKind.SURFACE_PREVIEW
                || this.sourceKind == SourceKind.SYNTHETIC_PREVIEW
                || this.lightSourceKind == LightSourceKind.SYNTHETIC_SURFACE_PREVIEW
                || this.syntheticPreview;
    }

    public boolean hasFinalizableLight() {
        return isTrustedLight(this.lightSourceKind);
    }

    public static boolean isTrustedLight(LightSourceKind lightSourceKind) {
        return switch (lightSourceKind == null ? LightSourceKind.UNKNOWN : lightSourceKind) {
            case REAL_LIGHT, VALID_EMPTY_SKY_LIGHT, VALID_DEFAULT_SKY_LIGHT, NO_SKY_DIMENSION -> true;
            case UNKNOWN, MISSING_SKY_LIGHT, SYNTHETIC_SURFACE_PREVIEW -> false;
        };
    }

    public static int sourceRank(SourceKind sourceKind) {
        return switch (sourceKind == null ? SourceKind.UNKNOWN : sourceKind) {
            case UNKNOWN -> 0;
            case ZERO_CLEAR -> 1;
            case SYNTHETIC_PREVIEW -> 2;
            case SURFACE_PREVIEW -> 3;
            case REAL_CHUNK -> 4;
        };
    }

    public static LightSourceKind lightSourceKindFrom(LightingState lightingState) {
        return switch (lightingState == null ? LightingState.UNKNOWN : lightingState) {
            case REAL_LIGHT -> LightSourceKind.REAL_LIGHT;
            case VALID_EMPTY_SKY_LIGHT -> LightSourceKind.VALID_EMPTY_SKY_LIGHT;
            case VALID_DEFAULT_SKY_LIGHT -> LightSourceKind.VALID_DEFAULT_SKY_LIGHT;
            case SYNTHETIC_PREVIEW_LIGHT -> LightSourceKind.SYNTHETIC_SURFACE_PREVIEW;
            case MISSING_SKY_LIGHT -> LightSourceKind.MISSING_SKY_LIGHT;
            case NO_SKY_DIMENSION -> LightSourceKind.NO_SKY_DIMENSION;
            case UNKNOWN -> LightSourceKind.UNKNOWN;
        };
    }

    public static LightingState lightingStateFrom(LightSourceKind lightSourceKind) {
        return switch (lightSourceKind == null ? LightSourceKind.UNKNOWN : lightSourceKind) {
            case REAL_LIGHT -> LightingState.REAL_LIGHT;
            case VALID_EMPTY_SKY_LIGHT -> LightingState.VALID_EMPTY_SKY_LIGHT;
            case VALID_DEFAULT_SKY_LIGHT -> LightingState.VALID_DEFAULT_SKY_LIGHT;
            case SYNTHETIC_SURFACE_PREVIEW -> LightingState.SYNTHETIC_PREVIEW_LIGHT;
            case MISSING_SKY_LIGHT -> LightingState.MISSING_SKY_LIGHT;
            case NO_SKY_DIMENSION -> LightingState.NO_SKY_DIMENSION;
            case UNKNOWN -> LightingState.UNKNOWN;
        };
    }

    private static int getIdx(int x, int y, int z, int shiftBy, int size) {
        int M = (1<<size)-1;
        x = (x>>shiftBy)&M;
        y = (y>>shiftBy)&M;
        z = (z>>shiftBy)&M;
        return (y<<(size<<1))|(z<<size)|(x);
    }

    public long get(int lvl, int x, int y, int z) {
        int offset = lvl==1?(1<<12):0;
        offset |= lvl==2?(1<<12)|(1<<9):0;
        offset |= lvl==3?(1<<12)|(1<<9)|(1<<6):0;
        offset |= lvl==4?(1<<12)|(1<<9)|(1<<6)|(1<<3):0;
        return this.section[getIdx(x, y, z, 0, 4-lvl) + offset];
    }

    public static VoxelizedSection createEmpty() {
        return new VoxelizedSection(new long[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2 + 1]);
    }

    public VoxelizedSection zero() {
        this.lvl0NonAirCount = 0;
        Arrays.fill(this.section, 0);
        return this;
    }

    public VoxelizedSection copy() {
        var copy = new VoxelizedSection(Arrays.copyOf(this.section, this.section.length));
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        copy.lvl0NonAirCount = this.lvl0NonAirCount;
        copy.syntheticPreview = this.syntheticPreview;
        copy.lightingState = this.lightingState;
        copy.lightSourceKind = this.lightSourceKind;
        copy.sourceKind = this.sourceKind;
        copy.confidence = this.confidence;
        copy.dataEpoch = this.dataEpoch;
        return copy;
    }
}
