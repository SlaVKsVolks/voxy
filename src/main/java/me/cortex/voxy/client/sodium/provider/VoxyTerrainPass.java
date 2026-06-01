package me.cortex.voxy.client.sodium.provider;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

public enum VoxyTerrainPass {
    SOLID,
    CUTOUT,
    TRANSLUCENT,
    FLUID,
    DEBUG;

    public static VoxyTerrainPass fromSodium(TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) {
            return SOLID;
        }
        if (pass == DefaultTerrainRenderPasses.CUTOUT) {
            return CUTOUT;
        }
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
            return TRANSLUCENT;
        }
        return DEBUG;
    }
}
