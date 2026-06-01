/**
 * @file VoxyHandoffPolicy.java
 * @ai_context
 * PURPOSE: Defines the handoff boundaries and render distance calculations between vanilla and Voxy.
 * CONCEPTS:
 *  - Calculates camera-relative chunk distances for sections.
 *  - Computes the merged render distance policy and handoff gap verdicts.
 * CRITICAL:
 *  - The boundary gap verdict must correctly check for missing boundary sections (boundaryMisses) first.
 */
package me.cortex.voxy.common;

import me.cortex.voxy.common.world.WorldSection;

public final class VoxyHandoffPolicy {
    private static final int DEFAULT_OVERLAP_CHUNKS = 2;
    private static final int DEFAULT_MAX_REAL_RENDER_DISTANCE_CHUNKS = 8;
    private static final int TOP_LEVEL_CHUNKS = 32;
    private static final int BOUNDARY_EXTRA_CHUNKS = Integer.getInteger("voxy.handoffBoundaryExtraChunks", 4);
    private static volatile String renderDistanceSliderMode = "vanilla";
    private static volatile int visualTerrainDistanceChunks = 0;
    private static volatile int vanillaRadiusChunks = 0;
    private static volatile int voxyLodEndChunks = 0;
    private static volatile int handoffStartChunks = 0;
    private static volatile int overlapChunks = DEFAULT_OVERLAP_CHUNKS;
    private static volatile int maxRealRenderDistanceChunks = DEFAULT_MAX_REAL_RENDER_DISTANCE_CHUNKS;
    private static volatile double cameraBlockX;
    private static volatile double cameraBlockZ;

    private VoxyHandoffPolicy() {
    }

    public static int configuredOverlapChunks() {
        return Math.max(0, Integer.getInteger("voxy.handoffOverlapChunks", DEFAULT_OVERLAP_CHUNKS));
    }

    public static int defaultOverlapChunks() {
        return DEFAULT_OVERLAP_CHUNKS;
    }

    public static int defaultMaxRealRenderDistanceChunks() {
        return DEFAULT_MAX_REAL_RENDER_DISTANCE_CHUNKS;
    }

    public static int handoffStartChunks(int vanillaRadiusChunks, int overlapChunks) {
        int sanitizedVanillaRadius = Math.max(0, vanillaRadiusChunks);
        int sanitizedOverlap = Math.max(0, overlapChunks);
        return Math.max(0, sanitizedVanillaRadius - sanitizedOverlap);
    }

    public static MergedRenderDistance mergedRenderDistance(
            int visualTerrainDistanceChunks,
            int maxRealRenderDistanceChunks,
            int overlapChunks
    ) {
        int visual = Math.max(0, visualTerrainDistanceChunks);
        int maxReal = Math.max(0, maxRealRenderDistanceChunks);
        int overlap = Math.max(0, overlapChunks);
        int real = Math.min(visual, maxReal);
        int start = handoffStartChunks(real, overlap);
        return new MergedRenderDistance(visual, real, start, visual, overlap, maxReal);
    }

    public static int topLevelRenderDistanceSections(int visualTerrainDistanceChunks) {
        return Math.max(2, (int) Math.ceil(Math.max(0, visualTerrainDistanceChunks) / (double) TOP_LEVEL_CHUNKS) + 1);
    }

    public static int visualTerrainDistanceBlocks() {
        return Math.max(0, visualTerrainDistanceChunks) * 16;
    }

    public static void updateCamera(double cameraX, double cameraZ, int vanillaRadiusChunks) {
        updateCamera(cameraX, cameraZ, vanillaRadiusChunks, vanillaRadiusChunks, configuredOverlapChunks(), "vanilla");
    }

    public static void updateCamera(
            double cameraX,
            double cameraZ,
            int visualTerrainDistanceChunks,
            int maxRealRenderDistanceChunks,
            int overlapChunks,
            String sliderMode
    ) {
        VoxyHandoffPolicy.cameraBlockX = cameraX;
        VoxyHandoffPolicy.cameraBlockZ = cameraZ;
        updateDistance(visualTerrainDistanceChunks, maxRealRenderDistanceChunks, overlapChunks, sliderMode);
    }

    public static void updateDistance(
            int visualTerrainDistanceChunks,
            int maxRealRenderDistanceChunks,
            int overlapChunks,
            String sliderMode
    ) {
        MergedRenderDistance distance = mergedRenderDistance(
                visualTerrainDistanceChunks,
                maxRealRenderDistanceChunks,
                overlapChunks
        );
        VoxyHandoffPolicy.renderDistanceSliderMode = sliderMode == null ? "vanilla" : sliderMode;
        VoxyHandoffPolicy.visualTerrainDistanceChunks = distance.visualTerrainDistanceChunks();
        VoxyHandoffPolicy.vanillaRadiusChunks = distance.realRenderDistanceChunks();
        VoxyHandoffPolicy.voxyLodEndChunks = distance.voxyLodEndChunks();
        VoxyHandoffPolicy.overlapChunks = distance.handoffOverlapChunks();
        VoxyHandoffPolicy.maxRealRenderDistanceChunks = distance.maxRealRenderDistanceChunks();
        VoxyHandoffPolicy.handoffStartChunks = distance.voxyLodStartChunks();
    }

    public static String renderDistanceSliderMode() {
        return renderDistanceSliderMode;
    }

    public static int visualTerrainDistanceChunks() {
        return visualTerrainDistanceChunks;
    }

    public static int vanillaRadiusChunks() {
        return vanillaRadiusChunks;
    }

    public static int realRenderDistanceChunks() {
        return vanillaRadiusChunks;
    }

    public static int handoffStartChunks() {
        return handoffStartChunks;
    }

    public static int voxyLodEndChunks() {
        return voxyLodEndChunks;
    }

    public static int overlapChunks() {
        return overlapChunks;
    }

    public static int maxRealRenderDistanceChunks() {
        return maxRealRenderDistanceChunks;
    }

    public static boolean isBoundaryRingSection(WorldSection section) {
        return isBoundaryRingSection(section.lvl, section.x, section.z);
    }

    public static boolean isBoundaryRingSection(int level, int sectionX, int sectionZ) {
        double minDistanceChunks = sectionMinDistanceChunks(level, sectionX, sectionZ);
        double maxDistanceChunks = sectionMaxDistanceChunks(level, sectionX, sectionZ);
        double start = handoffStartChunks;
        double end = vanillaRadiusChunks + BOUNDARY_EXTRA_CHUNKS;
        return maxDistanceChunks >= start && minDistanceChunks <= end;
    }

    public static boolean hasRequiredVoxyCoverageBeyondVanilla() {
        return voxyLodEndChunks > vanillaRadiusChunks;
    }

    public static boolean isRequiredVoxyCoverageSection(int level, int sectionX, int sectionZ) {
        if (!hasRequiredVoxyCoverageBeyondVanilla()) {
            return false;
        }
        double minDistanceChunks = sectionMinDistanceChunks(level, sectionX, sectionZ);
        double maxDistanceChunks = sectionMaxDistanceChunks(level, sectionX, sectionZ);
        return maxDistanceChunks > vanillaRadiusChunks && minDistanceChunks <= voxyLodEndChunks;
    }

    public static double sectionMinDistanceChunks(int level, int sectionX, int sectionZ) {
        int sizeBlocks = 1 << (level + 5);
        double minX = (double) sectionX * sizeBlocks;
        double minZ = (double) sectionZ * sizeBlocks;
        double maxX = minX + sizeBlocks;
        double maxZ = minZ + sizeBlocks;
        return Math.sqrt(squaredDistanceToAabb(cameraBlockX, cameraBlockZ, minX, minZ, maxX, maxZ)) / 16.0;
    }

    public static double sectionMaxDistanceChunks(int level, int sectionX, int sectionZ) {
        int sizeBlocks = 1 << (level + 5);
        double minX = (double) sectionX * sizeBlocks;
        double minZ = (double) sectionZ * sizeBlocks;
        double maxX = minX + sizeBlocks;
        double maxZ = minZ + sizeBlocks;
        return Math.sqrt(Math.max(
                squaredDistance(cameraBlockX, cameraBlockZ, minX, minZ),
                Math.max(
                        squaredDistance(cameraBlockX, cameraBlockZ, minX, maxZ),
                        Math.max(
                                squaredDistance(cameraBlockX, cameraBlockZ, maxX, minZ),
                                squaredDistance(cameraBlockX, cameraBlockZ, maxX, maxZ)
                        )
                )
        )) / 16.0;
    }

    public static String boundaryGapVerdict(long boundaryLoads, long boundaryParentFallbacks, long boundaryMisses) {
        if (boundaryMisses > 0) {
            return "FAIL_MERGED_RENDER_DISTANCE_GAP";
        }
        if (boundaryLoads > 0 || boundaryParentFallbacks > 0) {
            return "PASS_MERGED_RENDER_DISTANCE_NO_GAP";
        }
        return "UNKNOWN_NO_BOUNDARY_REQUESTS";
    }

    private static double squaredDistanceToAabb(double x, double z, double minX, double minZ, double maxX, double maxZ) {
        double dx = 0.0;
        if (x < minX) {
            dx = minX - x;
        } else if (x > maxX) {
            dx = x - maxX;
        }
        double dz = 0.0;
        if (z < minZ) {
            dz = minZ - z;
        } else if (z > maxZ) {
            dz = z - maxZ;
        }
        return dx * dx + dz * dz;
    }

    private static double squaredDistance(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    public record MergedRenderDistance(
            int visualTerrainDistanceChunks,
            int realRenderDistanceChunks,
            int voxyLodStartChunks,
            int voxyLodEndChunks,
            int handoffOverlapChunks,
            int maxRealRenderDistanceChunks
    ) {}
}
