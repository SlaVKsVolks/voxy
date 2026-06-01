package me.cortex.voxy.common.world.other;

import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    public enum MaterialClass {
        AIR,
        SOLID,
        FOLIAGE,
        WATER,
        ICE,
        TRANSLUCENT,
        EMISSIVE
    }

    private static final HashMap<Block, Float> BLOCK_WEIGHTS = new HashMap<>();
    static {
        BLOCK_WEIGHTS.put(Blocks.AIR, 0.0f);
        BLOCK_WEIGHTS.put(Blocks.CAVE_AIR, 0.0f);
        BLOCK_WEIGHTS.put(Blocks.VOID_AIR, 0.0f);
        BLOCK_WEIGHTS.put(Blocks.SHORT_GRASS, 0.25f);
        BLOCK_WEIGHTS.put(Blocks.TALL_GRASS, 0.5f);
        BLOCK_WEIGHTS.put(Blocks.WATER, 1.25f);
        BLOCK_WEIGHTS.put(Blocks.ICE, 1.25f);
        BLOCK_WEIGHTS.put(Blocks.PACKED_ICE, 1.25f);
        BLOCK_WEIGHTS.put(Blocks.BLUE_ICE, 1.25f);
        BLOCK_WEIGHTS.put(Blocks.SNOW, 1.1f);
        BLOCK_WEIGHTS.put(Blocks.SNOW_BLOCK, 1.2f);
        BLOCK_WEIGHTS.put(Blocks.DIRT_PATH, 1.5f);
        BLOCK_WEIGHTS.put(Blocks.LAVA, 1.5f);
    }
    private static final BlockGetter LIGHT_QUERY_WORLD = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState().getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    };

    private static final class MipCache {
        private final int[] blockIds = new int[8];
        private final float[] weights = new float[8];
        private final long[] samples = new long[8];
        private final MaterialClass[] materials = new MaterialClass[8];

        private void reset() {
            for (int i = 0; i < 8; i++) {
                this.blockIds[i] = -1;
                this.weights[i] = 0.0f;
                this.samples[i] = 0L;
                this.materials[i] = MaterialClass.AIR;
            }
        }
    }

    private static final ThreadLocal<MipCache> CACHE = ThreadLocal.withInitial(MipCache::new);

    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        return mip(
                I000, I100, I001, I101,
                I010, I110, I011, I111,
                mapper,
                VoxelizedSection.SourceKind.UNKNOWN,
                VoxelizedSection.Confidence.UNKNOWN,
                VoxelizedSection.LightSourceKind.UNKNOWN
        );
    }

    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                           Mapper mapper,
                           VoxelizedSection.SourceKind sourceKind,
                           VoxelizedSection.Confidence confidence,
                           VoxelizedSection.LightSourceKind lightSourceKind) {
        MipCache cache = CACHE.get();
        cache.reset();
        cache.samples[0] = I000;
        cache.samples[1] = I100;
        cache.samples[2] = I001;
        cache.samples[3] = I101;
        cache.samples[4] = I010;
        cache.samples[5] = I110;
        cache.samples[6] = I011;
        cache.samples[7] = I111;

        long best = Mapper.AIR;
        int bestBlockId = Mapper.getBlockId(Mapper.AIR);
        float bestWeight = -1.0f;
        int bestPriority = -1;
        int bestSkyLight = -1;
        boolean hasVisibleNonAir = false;
        long firstVisibleNonAir = Mapper.AIR;
        long bestSkyExposedNonAir = Mapper.AIR;
        float bestSkyExposedWeight = -1.0f;
        int nonAirSamples = 0;
        int solidSamples = 0;
        int fluidSamples = 0;
        int foliageSamples = 0;
        int emissiveSamples = 0;
        int iceSamples = 0;

        // Iterate from upper samples to lower samples so ties prefer visible top surface data.
        for (int i = 7; i >= 0; i--) {
            long sample = cache.samples[i];
            int blockId = Mapper.getBlockId(sample);
            BlockState state = mapper.getBlockStateFromBlockId(blockId);
            MaterialClass material = materialClass(state);
            cache.materials[i] = material;
            if (!Mapper.isAir(sample)) {
                nonAirSamples++;
                if (!state.getFluidState().isEmpty()) {
                    fluidSamples++;
                }
                if (state.canOcclude()) {
                    solidSamples++;
                }
                if (material == MaterialClass.FOLIAGE) {
                    foliageSamples++;
                } else if (material == MaterialClass.EMISSIVE) {
                    emissiveSamples++;
                } else if (material == MaterialClass.ICE) {
                    iceSamples++;
                }
            }
            if (!Mapper.isAir(sample) && isSurfacePreviewCandidate(state)) {
                hasVisibleNonAir = true;
                firstVisibleNonAir = sample;
            }
            float cumulativeWeight = addWeight(cache, blockId, sample, blockWeight(state) * materialWeight(material));
            int sampleSkyLight = Mapper.getLightId(sample) & 0x0F;
            if (!Mapper.isAir(sample) && sampleSkyLight >= 12 && cumulativeWeight > bestSkyExposedWeight) {
                bestSkyExposedWeight = cumulativeWeight;
                bestSkyExposedNonAir = sample;
            }
            int samplePriority = materialPriority(material);
            boolean betterTie = Math.abs(cumulativeWeight - bestWeight) < 0.0001f
                    && (samplePriority > bestPriority
                    || (samplePriority == bestPriority && sampleSkyLight > bestSkyLight));
            if (cumulativeWeight > bestWeight || betterTie) {
                bestWeight = cumulativeWeight;
                best = sample;
                bestBlockId = blockId;
                bestPriority = samplePriority;
                bestSkyLight = sampleSkyLight;
            }
        }

        VoxelizedSection.SourceKind safeSourceKind = sourceKind == null ? VoxelizedSection.SourceKind.UNKNOWN : sourceKind;
        VoxelizedSection.Confidence safeConfidence = confidence == null ? VoxelizedSection.Confidence.UNKNOWN : confidence;
        VoxelizedSection.LightSourceKind safeLightSourceKind = lightSourceKind == null ? VoxelizedSection.LightSourceKind.UNKNOWN : lightSourceKind;
        boolean surfacePreview = safeSourceKind == VoxelizedSection.SourceKind.SURFACE_PREVIEW
                || safeSourceKind == VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW;
        boolean surfaceRepresentativePolicy = MipperRepresentativePolicy.shouldUseSurfaceRepresentativePolicy(
                safeSourceKind,
                safeConfidence,
                safeLightSourceKind
        );
        boolean lowConfidencePreview = surfacePreview && !safeConfidence.atLeast(VoxelizedSection.Confidence.MEDIUM);
        MaterialClass bestMaterial = materialClass(mapper.getBlockStateFromBlockId(bestBlockId));

        if (Mapper.isAir(best) && hasVisibleNonAir) {
            best = firstVisibleNonAir;
            bestBlockId = Mapper.getBlockId(best);
            bestMaterial = materialClass(mapper.getBlockStateFromBlockId(bestBlockId));
            if (surfaceRepresentativePolicy) {
                RenderCorrectnessDiagnostics.mipperRepresentative(
                        "air_over_surface_rejected",
                        "surface_representative_visible_non_air_selected"
                );
            }
        }
        if (MipperRepresentativePolicy.shouldPreferFoliageCanopyOverSolid(
                surfaceRepresentativePolicy,
                foliageSamples,
                solidSamples,
                fluidSamples,
                emissiveSamples,
                bestMaterial == MaterialClass.SOLID
        )) {
            long foliage = brightestSampleOf(cache, mapper, MaterialClass.FOLIAGE);
            if (foliage != Mapper.AIR) {
                best = foliage;
                bestBlockId = Mapper.getBlockId(best);
                bestMaterial = MaterialClass.FOLIAGE;
                RenderCorrectnessDiagnostics.mipperRepresentative(
                        "buried_solid_over_foliage_rejected",
                        "surface_representative_foliage_canopy_selected"
                );
            }
        }
        int bestSky = Mapper.getLightId(best) & 0x0F;
        int exposedSky = Mapper.getLightId(bestSkyExposedNonAir) & 0x0F;
        boolean bestCanRepresentSurface = !Mapper.isAir(best)
                && (bestMaterial == MaterialClass.SOLID
                || bestMaterial == MaterialClass.TRANSLUCENT
                || bestMaterial == MaterialClass.FOLIAGE);
        boolean preferSkyExposedSurface = MipperRepresentativePolicy.shouldPreferSkyExposedSurface(
                safeSourceKind,
                safeConfidence,
                safeLightSourceKind,
                bestCanRepresentSurface,
                bestSky,
                exposedSky,
                bestSkyExposedNonAir != Mapper.AIR,
                fluidSamples,
                emissiveSamples
        );
        if (preferSkyExposedSurface) {
            best = bestSkyExposedNonAir;
            bestBlockId = Mapper.getBlockId(best);
            bestMaterial = materialClass(mapper.getBlockStateFromBlockId(bestBlockId));
            if (surfaceRepresentativePolicy) {
                RenderCorrectnessDiagnostics.mipperRepresentative(
                        "buried_shell_over_surface_rejected",
                        "brighter_sky_exposed_surface_selected"
                );
            }
        }

        if (surfacePreview && fluidSamples > 0 && bestMaterial == MaterialClass.AIR) {
            long fluid = firstSampleOf(cache, mapper, MaterialClass.WATER, MaterialClass.ICE);
            if (fluid != Mapper.AIR) {
                best = fluid;
                bestBlockId = Mapper.getBlockId(best);
                bestMaterial = materialClass(mapper.getBlockStateFromBlockId(bestBlockId));
                RenderCorrectnessDiagnostics.mipperRepresentative(
                        "water_floor_preserved",
                        "surface_preview_fluid_selected"
                );
            }
        }
        if (emissiveSamples > 0 && bestMaterial != MaterialClass.EMISSIVE) {
            long emissive = brightestSampleOf(cache, mapper, MaterialClass.EMISSIVE);
            if (emissive != Mapper.AIR) {
                best = emissive;
                bestBlockId = Mapper.getBlockId(best);
                bestMaterial = MaterialClass.EMISSIVE;
            }
        }

        int totalBlockLight = 0;
        int totalSkyLight = 0;
        int maxBlockLight = 0;
        int maxSkyLight = 0;
        int lightSamples = 0;
        boolean bestIsAir = Mapper.isAir(best);
        if (bestIsAir) {
            return withLight(best, averageLightForAllAir(cache));
        }
        BlockState bestState = mapper.getBlockStateFromBlockId(bestBlockId);
        if (surfaceRepresentativePolicy
                && bestMaterial == MaterialClass.SOLID
                && foliageSamples >= 2
                && (Mapper.getLightId(best) & 0x0F) < 8) {
            RenderCorrectnessDiagnostics.mipperRepresentative(
                    "ore_leak_surface_representative",
                    "surface_representative_dark_solid_selected_with_visible_foliage"
            );
        }
        if (shouldSuppressSparsePreviewBlock(bestState, nonAirSamples, solidSamples, fluidSamples, foliageSamples, iceSamples, lowConfidencePreview)) {
            return Mapper.airWithLight(averageLightForAllAir(cache));
        }
        for (int i = 0; i < 8; i++) {
            long sample = cache.samples[i];
            if (Mapper.isAir(sample) || Mapper.getBlockId(sample) != bestBlockId) {
                continue;
            }

            int light = Mapper.getLightId(sample);
            int blockLight = (light >>> 4) & 0x0F;
            int skyLight = skyLightForMipSample(sample, i, cache, mapper);
            totalBlockLight += blockLight;
            totalSkyLight += skyLight;
            maxBlockLight = Math.max(maxBlockLight, blockLight);
            maxSkyLight = Math.max(maxSkyLight, skyLight);
            lightSamples++;
        }

        if (lightSamples == 0) {
            return best;
        }

        int averageBlockLight = clampLight(Math.max(totalBlockLight / lightSamples, maxBlockLight));
        int averageSkyLight = clampLight(Math.max((int) Math.ceil(totalSkyLight / (double) lightSamples), maxSkyLight));
        return withLight(best, (averageBlockLight << 4) | averageSkyLight);
    }

    private static float addWeight(MipCache cache, int blockId, long sample, float weight) {
        for (int i = 0; i < 8; i++) {
            if (cache.blockIds[i] == blockId) {
                cache.weights[i] += weight;
                return cache.weights[i];
            }
            if (cache.blockIds[i] == -1) {
                cache.blockIds[i] = blockId;
                cache.weights[i] = weight;
                return weight;
            }
        }
        return weight;
    }

    private static int skyLightForMipSample(long sample, int sampleIndex, MipCache cache, Mapper mapper) {
        int sky = Mapper.getLightId(sample) & 0x0F;
        BlockState state = mapper.getBlockStateFromBlockId(Mapper.getBlockId(sample));
        if (state.is(Blocks.SNOW) && sampleIndex >= 4) {
            return Math.max(sky, Mapper.getLightId(cache.samples[sampleIndex - 4]) & 0x0F);
        }
        return sky;
    }

    private static float blockWeight(BlockState state) {
        int luminance = state.getLightEmission(LIGHT_QUERY_WORLD, BlockPos.ZERO);
        float brightnessBoost = 1.0f + (luminance / 31.0f);
        return BLOCK_WEIGHTS.getOrDefault(state.getBlock(), 1.0f) * brightnessBoost;
    }

    private static MaterialClass materialClass(BlockState state) {
        if (state == null || state.isAir()) {
            return MaterialClass.AIR;
        }
        if (!state.getFluidState().isEmpty() || state.is(Blocks.WATER) || state.is(Blocks.LAVA)) {
            return MaterialClass.WATER;
        }
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE)) {
            return MaterialClass.ICE;
        }
        if (state.getBlock() instanceof LeavesBlock || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) {
            return MaterialClass.FOLIAGE;
        }
        if (state.getLightEmission(LIGHT_QUERY_WORLD, BlockPos.ZERO) > 0) {
            return MaterialClass.EMISSIVE;
        }
        if (!state.canOcclude()) {
            return MaterialClass.TRANSLUCENT;
        }
        return MaterialClass.SOLID;
    }

    private static float materialWeight(MaterialClass material) {
        return switch (material) {
            case AIR -> 0.0f;
            case WATER, ICE -> 1.35f;
            case EMISSIVE -> 1.6f;
            case SOLID -> 1.2f;
            case FOLIAGE -> 0.85f;
            case TRANSLUCENT -> 0.75f;
        };
    }

    private static int materialPriority(MaterialClass material) {
        return switch (material) {
            case AIR -> 0;
            case TRANSLUCENT -> 1;
            case FOLIAGE -> 2;
            case SOLID -> 3;
            case WATER, ICE -> 4;
            case EMISSIVE -> 5;
        };
    }

    private static boolean isSurfacePreviewCandidate(BlockState state) {
        return !state.isAir();
    }

    private static boolean shouldSuppressSparsePreviewBlock(
            BlockState state,
            int nonAirSamples,
            int solidSamples,
            int fluidSamples,
            int foliageSamples,
            int iceSamples,
            boolean lowConfidencePreview
    ) {
        if (nonAirSamples > 2 || solidSamples > 0 || fluidSamples > 0) {
            return false;
        }
        if (iceSamples > 0) {
            return false;
        }
        if (lowConfidencePreview && state.canOcclude()) {
            return false;
        }
        if (foliageSamples > 1) {
            return false;
        }
        return state.getBlock() instanceof LeavesBlock
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || !state.canOcclude();
    }

    private static int averageLightForAllAir(MipCache cache) {
        int block = 0;
        int sky = 0;
        int maxBlock = 0;
        int maxSky = 0;
        for (int i = 0; i < 8; i++) {
            int light = Mapper.getLightId(cache.samples[i]);
            int blockLight = (light >>> 4) & 0x0F;
            int skyLight = light & 0x0F;
            block += blockLight;
            sky += skyLight;
            maxBlock = Math.max(maxBlock, blockLight);
            maxSky = Math.max(maxSky, skyLight);
        }
        return (clampLight(Math.max(block / 8, maxBlock)) << 4)
                | clampLight(Math.max((int) Math.ceil(sky / 8.0), maxSky));
    }

    private static long firstSampleOf(MipCache cache, Mapper mapper, MaterialClass primary, MaterialClass secondary) {
        for (int i = 7; i >= 0; i--) {
            long sample = cache.samples[i];
            MaterialClass material = materialClass(mapper.getBlockStateFromBlockId(Mapper.getBlockId(sample)));
            if (material == primary || material == secondary) {
                return sample;
            }
        }
        return Mapper.AIR;
    }

    private static long brightestSampleOf(MipCache cache, Mapper mapper, MaterialClass target) {
        long best = Mapper.AIR;
        int bestLight = -1;
        for (int i = 7; i >= 0; i--) {
            long sample = cache.samples[i];
            MaterialClass material = materialClass(mapper.getBlockStateFromBlockId(Mapper.getBlockId(sample)));
            if (material != target) {
                continue;
            }
            int light = Mapper.getLightId(sample);
            int total = ((light >>> 4) & 0x0F) + (light & 0x0F);
            if (total > bestLight) {
                bestLight = total;
                best = sample;
            }
        }
        return best;
    }

    private static int clampLight(int light) {
        return Math.max(0, Math.min(15, light));
    }
}
