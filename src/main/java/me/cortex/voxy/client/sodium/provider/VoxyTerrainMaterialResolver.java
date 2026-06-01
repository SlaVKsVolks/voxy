package me.cortex.voxy.client.sodium.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class VoxyTerrainMaterialResolver {
    private final Set<Integer> validBlockStateIds;
    private final Set<Integer> validBiomeIds;
    private final Map<Integer, String> modelKeys;
    private final Map<Integer, Integer> colors;
    private final Set<Integer> configuredBlackBlocks;
    private final ThreadLocal<Map<CacheKey, VoxyResolvedTerrainMaterial>> cache = ThreadLocal.withInitial(HashMap::new);

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
        CacheKey key = new CacheKey(blockStateId, biomeId, packedLight, pass);
        return this.cache.get().computeIfAbsent(key, this::resolveUncached);
    }

    private VoxyResolvedTerrainMaterial resolveUncached(CacheKey key) {
        if (key.pass == VoxyTerrainPass.FLUID || key.pass == VoxyTerrainPass.TRANSLUCENT) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED);
        }
        if (!this.validBlockStateIds.contains(key.blockStateId)) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE);
        }
        String modelKey = this.modelKeys.get(key.blockStateId);
        if (modelKey == null || modelKey.isBlank()) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK);
        }
        if (!this.validBiomeIds.contains(key.biomeId)) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.MISSING_BIOME_TINT);
        }
        if (key.packedLight < 0 || key.packedLight > 0xFFFF) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.INVALID_LIGHT);
        }
        int color = this.colors.getOrDefault(key.blockStateId, 0);
        if (color == 0 && !this.configuredBlackBlocks.contains(key.blockStateId)) {
            return invalid(key.blockStateId, key.biomeId, key.packedLight, key.pass, VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK);
        }
        return new VoxyResolvedTerrainMaterial(key.blockStateId, key.biomeId, color, key.packedLight, key.pass, modelKey, true, VoxyTerrainFailureReason.NONE);
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

    private record CacheKey(int blockStateId, int biomeId, int packedLight, VoxyTerrainPass pass) {
    }
}
