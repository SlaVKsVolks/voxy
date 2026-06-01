package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import net.minecraft.client.Minecraft;

public final class VoxyMergedRenderDistance {
    private static int lastAppliedRealRenderDistance = Integer.MIN_VALUE;

    private VoxyMergedRenderDistance() {
    }

    public static boolean usesMergedSlider() {
        return VoxyConfig.CONFIG.isRenderingEnabled();
    }

    public static int sliderValue() {
        if (!usesMergedSlider()) {
            return Minecraft.getInstance().options.renderDistance().get();
        }
        VoxyConfig.CONFIG.normalizeMergedRenderDistanceSettings();
        updatePolicyState();
        return VoxyConfig.CONFIG.visualTerrainDistanceChunks;
    }

    public static void setVisualFromSlider(int visualTerrainDistanceChunks) {
        if (!usesMergedSlider()) {
            Minecraft.getInstance().options.renderDistance().set(Math.clamp(visualTerrainDistanceChunks, 2, 32));
            return;
        }
        VoxyConfig.CONFIG.visualTerrainDistanceChunks = visualTerrainDistanceChunks;
        VoxyConfig.CONFIG.normalizeMergedRenderDistanceSettings();
        updatePolicyState();
        VoxyConfig.CONFIG.save();
        applyMergedRenderDistancePolicy(Minecraft.getInstance());
    }

    public static VoxyHandoffPolicy.MergedRenderDistance currentDistance() {
        VoxyConfig.CONFIG.normalizeMergedRenderDistanceSettings();
        VoxyHandoffPolicy.MergedRenderDistance distance = VoxyHandoffPolicy.mergedRenderDistance(
                VoxyConfig.CONFIG.visualTerrainDistanceChunks,
                VoxyConfig.CONFIG.maxRealChunkRadiusChunks,
                VoxyConfig.CONFIG.handoffOverlapChunks
        );
        VoxyHandoffPolicy.updateDistance(
                distance.visualTerrainDistanceChunks(),
                distance.maxRealRenderDistanceChunks(),
                distance.handoffOverlapChunks(),
                usesMergedSlider() ? "voxy_merged" : "vanilla"
        );
        return distance;
    }

    public static void updatePolicyState() {
        currentDistance();
    }

    public static void applyMergedRenderDistancePolicy(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return;
        }
        if (!usesMergedSlider()) {
            lastAppliedRealRenderDistance = Integer.MIN_VALUE;
            return;
        }
        VoxyHandoffPolicy.MergedRenderDistance distance = currentDistance();
        int current = mc.options.getEffectiveRenderDistance();
        int real = distance.realRenderDistanceChunks();
        if (current != real) {
            mc.options.renderDistance().set(real);
            lastAppliedRealRenderDistance = real;
            Logger.info("Applied merged Voxy render distance: visual="
                    + distance.visualTerrainDistanceChunks()
                    + " real=" + real
                    + " lod_start=" + distance.voxyLodStartChunks()
                    + " lod_end=" + distance.voxyLodEndChunks());
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        } else {
            lastAppliedRealRenderDistance = current;
        }

        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer != null) {
            renderer.setVisualRenderDistanceChunks(distance.visualTerrainDistanceChunks());
        }
    }

    public static int lastAppliedRealRenderDistance() {
        return lastAppliedRealRenderDistance;
    }
}
