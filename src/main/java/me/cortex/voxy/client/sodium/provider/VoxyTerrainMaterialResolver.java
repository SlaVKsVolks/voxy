package me.cortex.voxy.client.sodium.provider;

import java.util.Map;
import java.util.Set;

public final class VoxyTerrainMaterialResolver {
    private final Set<Integer> validBlockStateIds;
    private final Set<Integer> validBiomeIds;
    private final Map<Integer, String> modelKeys;
    private final Map<Integer, Integer> colors;
    private final Set<Integer> configuredBlackBlocks;

    public VoxyTerrainMaterialResolver(
            Set<Integer> validBlockStateIds,
            Set<Integer> validBiomeIds,
            Map<Integer, String> modelKeys,
            Map<Integer, Integer> colors,
            Set<Integer> configuredBlackBlocks
    ) {
        this.validBlockStateIds = Set.copyOf(validBlockStateIds);
        this.validBiomeIds = Set.copyOf(validBiomeIds);
        this.modelKeys = Map.copyOf(modelKeys);
        this.colors = Map.copyOf(colors);
        this.configuredBlackBlocks = Set.copyOf(configuredBlackBlocks);
    }

    public VoxyResolvedTerrainMaterial resolve(
            int blockStateId,
            int biomeId,
            int packedLight,
            VoxyTerrainPass pass
    ) {
        if (pass == VoxyTerrainPass.FLUID || pass == VoxyTerrainPass.TRANSLUCENT) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED);
        }
        if (!this.validBlockStateIds.contains(blockStateId)) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE);
        }
        String modelKey = this.modelKeys.get(blockStateId);
        if (modelKey == null || modelKey.isBlank()) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK);
        }
        if (!this.validBiomeIds.contains(biomeId)) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.MISSING_BIOME_TINT);
        }
        if (packedLight < 0 || packedLight > 0xFFFF) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.INVALID_LIGHT);
        }
        int color = this.colors.getOrDefault(blockStateId, 0);
        if (color == 0 && !this.configuredBlackBlocks.contains(blockStateId)) {
            return invalid(blockStateId, biomeId, packedLight, pass, VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK);
        }
        return new VoxyResolvedTerrainMaterial(blockStateId, biomeId, color, packedLight, pass, modelKey, true, VoxyTerrainFailureReason.NONE);
    }

    private static VoxyResolvedTerrainMaterial invalid(
            int blockStateId,
            int biomeId,
            int packedLight,
            VoxyTerrainPass pass,
            VoxyTerrainFailureReason reason
    ) {
        return new VoxyResolvedTerrainMaterial(blockStateId, biomeId, 0, packedLight, pass, "", false, reason);
    }
}
