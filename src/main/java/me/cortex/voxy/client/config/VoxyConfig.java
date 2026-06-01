package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public int visualTerrainDistanceChunks = 0;
    public int maxRealChunkRadiusChunks = VoxyHandoffPolicy.defaultMaxRealRenderDistanceChunks();
    public int handoffOverlapChunks = VoxyHandoffPolicy.defaultOverlapChunks();
    /**
     * Deprecated migration field. Rendering must use visualTerrainDistanceChunks
     * through VoxyHandoffPolicy instead of reading this value directly.
     */
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 8;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public boolean renderStateDebug = false;
    public boolean uniformBridgeDebug = false;
    public boolean lodCullingDebug = false;
    public boolean depthCompositionDebug = false;
    public boolean amdHizReadSideFilter = true;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        // The NeoForge client config can be initialized before ModList reports
        // Voxy as loaded. Always try the real config path first so early static
        // initialization cannot silently force rendering off for the whole launch.
        try {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.normalizeMergedRenderDistanceSettings();
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.normalizeMergedRenderDistanceSettings();
            config.save();
            return config;
        } catch (RuntimeException e) {
            Logger.error("Could not access voxy config path", e);
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        this.normalizeMergedRenderDistanceSettings();
        if (!VoxyCommon.IS_IN_MINECRAFT) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return this.enabled && this.enableRendering;
    }

    public void normalizeMergedRenderDistanceSettings() {
        if (this.visualTerrainDistanceChunks <= 0) {
            this.visualTerrainDistanceChunks = Math.max(2, Math.round(this.sectionRenderDistance * 32.0f));
        }
        this.visualTerrainDistanceChunks = Math.clamp(this.visualTerrainDistanceChunks, 2, 512);
        this.maxRealChunkRadiusChunks = Math.clamp(this.maxRealChunkRadiusChunks, 2, 32);
        this.handoffOverlapChunks = Math.clamp(this.handoffOverlapChunks, 0, 32);
        VoxyHandoffPolicy.updateDistance(
                this.visualTerrainDistanceChunks,
                this.maxRealChunkRadiusChunks,
                this.handoffOverlapChunks,
                this.isRenderingEnabled() ? "voxy_merged" : "vanilla"
        );
    }
}
