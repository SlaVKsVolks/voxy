package me.cortex.voxy.client.sodium.provider;

import me.cortex.voxy.client.core.model.ModelQueries;

public final class VoxyRuntimeMeshValidator {
    private static final int MAX_CLIENT_MODEL_ID = 1 << 16;

    private VoxyRuntimeMeshValidator() {
    }

    public static VoxyTerrainFailureReason validateBlockId(int blockStateId, int rawModelIdCount) {
        if (blockStateId < 0 || blockStateId >= rawModelIdCount) {
            return VoxyTerrainFailureReason.INVALID_PALETTE_ID;
        }
        return VoxyTerrainFailureReason.NONE;
    }

    public static VoxyTerrainFailureReason validateModelId(int modelId) {
        if (modelId < 0) {
            return VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK;
        }
        if (modelId >= MAX_CLIENT_MODEL_ID) {
            return VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK;
        }
        return VoxyTerrainFailureReason.NONE;
    }

    public static VoxyTerrainFailureReason validateSupportedRuntimePass(long modelMetadata) {
        if (ModelQueries.isFluid(modelMetadata)
                || ModelQueries.containsFluid(modelMetadata)
                || ModelQueries.isTranslucent(modelMetadata)) {
            return VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED;
        }
        return VoxyTerrainFailureReason.NONE;
    }
}
