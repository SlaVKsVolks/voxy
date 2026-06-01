package me.cortex.voxy.client.sodium.provider;

import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.voxelization.VoxelizedSection;

public final class VoxyTerrainTileValidator {
    private VoxyTerrainTileValidator() {
    }

    public static VoxyTerrainFailureReason validateBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return VoxyTerrainFailureReason.SECTION_OUT_OF_BOUNDS;
        }
        return VoxyTerrainFailureReason.NONE;
    }

    public static VoxyTerrainFailureReason validateMaterial(VoxyResolvedTerrainMaterial material) {
        if (material == null) {
            return VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE;
        }
        return material.canMesh() ? VoxyTerrainFailureReason.NONE : material.failureReason();
    }

    public static VoxyTerrainFailureReason validateSource(WorldSection section) {
        if (section == null || !hasProviderTrustedRealData(section)) {
            return VoxyTerrainFailureReason.UNTRUSTED_SOURCE;
        }
        return VoxyTerrainFailureReason.NONE;
    }

    private static boolean hasProviderTrustedRealData(WorldSection section) {
        return section.getSourceKind() == VoxelizedSection.SourceKind.REAL_CHUNK
                && section.getConfidence().atLeast(VoxelizedSection.Confidence.MEDIUM)
                && VoxelizedSection.isTrustedLight(section.getLightSourceKind());
    }
}
