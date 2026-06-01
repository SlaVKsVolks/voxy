package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.sodium.VoxySodiumSectionLifecycleBridge;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.util.HashSet;

public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    private static FileOutputStream EXCLUSIVE_LOCK_STREAM;
    private static volatile String visualAttributionMode = "normal";
    private static volatile boolean rendererLifecycleResetPending = false;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called
        Capabilities.INSTANCE.writeRenderStackCapabilities(
                Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("testharness")
                        .resolve("render_stack_capabilities.json")
        );

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean requireOpenGl46 = Boolean.parseBoolean(System.getProperty("voxy.requireOpenGL46", "true"));
        if (requireOpenGl46 && !Capabilities.INSTANCE.meetsOpenGl46) {
            Logger.error("Voxy disabled: OpenGL 4.6 is required but the current context is " + Capabilities.INSTANCE.glVersion);
        }

        boolean systemSupported = Capabilities.INSTANCE.compute
                && Capabilities.INSTANCE.indirectParameters
                && !Capabilities.INSTANCE.hasBrokenDepthSampler
                && (!requireOpenGl46 || Capabilities.INSTANCE.meetsOpenGl46);
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            try {
                Files.createDirectories(vf);
                EXCLUSIVE_LOCK_STREAM = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = EXCLUSIVE_LOCK_STREAM.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                if (EXCLUSIVE_LOCK_STREAM != null) {
                    try {
                        EXCLUSIVE_LOCK_STREAM.close();
                    } catch (IOException closeError) {
                        Logger.warn("Failed to close exclusive voxy lock stream after lock failure", closeError);
                    }
                    EXCLUSIVE_LOCK_STREAM = null;
                }
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public static void initNeoForge() {
        VoxyDiagnosticsHotkey.init();
        VoxySodiumSectionLifecycleBridge.init();

        NeoForge.EVENT_BUS.addListener(VoxyCommands::register);
        NeoForge.EVENT_BUS.addListener(VoxySurfacePregen::onClientTick);
        NeoForge.EVENT_BUS.addListener(me.cortex.voxy.client.serverlod.ClientServerLodSync::onClientTick);
        NeoForge.EVENT_BUS.addListener(VoxyClient::onClientTick);
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    private static boolean isHarnessVoxyOnlyModeAllowed() {
        return Boolean.parseBoolean(System.getProperty("voxy.allowHarnessVoxyOnlyMode", "false"));
    }

    public static boolean disableSodiumChunkRender() {
        return isHarnessVoxyOnlyModeAllowed() && "voxy_only".equals(visualAttributionMode);
    }

    public static boolean sodiumChunkRenderingEnabled() {
        return !disableSodiumChunkRender();
    }

    public static boolean isVisualAttributionNoop() {
        return "noop".equals(visualAttributionMode);
    }

    public static String getVisualAttributionMode() {
        return visualAttributionMode;
    }

    public static void setVisualAttributionMode(String mode) {
        String normalized = mode == null ? "normal" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"normal".equals(normalized)
                && !"voxy_only".equals(normalized)
                && !"noop".equals(normalized)
                && !"voxy_idbuffer".equals(normalized)
                && !"voxy_depth_probe".equals(normalized)) {
            normalized = "normal";
        }
        if ("voxy_only".equals(normalized) && !isHarnessVoxyOnlyModeAllowed()) {
            Logger.warn("Ignoring voxy_only visual attribution mode because voxy.allowHarnessVoxyOnlyMode is not enabled");
            normalized = "normal";
        }
        if (!visualAttributionMode.equals(normalized)) {
            Logger.info("Voxy visual attribution mode changed: " + visualAttributionMode + " -> " + normalized);
        }
        visualAttributionMode = normalized;
    }

    public static void resetVisualAttributionModeForRendererLifecycle(String reason) {
        if (!"normal".equals(visualAttributionMode)) {
            Logger.info("Resetting Voxy visual attribution mode to normal after renderer lifecycle event: " + reason);
            rendererLifecycleResetPending = true;
        }
        visualAttributionMode = "normal";
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (rendererLifecycleResetPending) {
            rendererLifecycleResetPending = false;
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        }
        applyMergedRenderDistancePolicy(mc);
        VoxyHandoffPolicy.updateCamera(
                mc.gameRenderer.getMainCamera().getPosition().x,
                mc.gameRenderer.getMainCamera().getPosition().z,
                VoxyConfig.CONFIG.isRenderingEnabled()
                        ? VoxyConfig.CONFIG.visualTerrainDistanceChunks
                        : mc.options.getEffectiveRenderDistance(),
                VoxyConfig.CONFIG.isRenderingEnabled()
                        ? VoxyConfig.CONFIG.maxRealChunkRadiusChunks
                        : mc.options.getEffectiveRenderDistance(),
                VoxyConfig.CONFIG.isRenderingEnabled()
                        ? VoxyConfig.CONFIG.handoffOverlapChunks
                        : VoxyHandoffPolicy.configuredOverlapChunks(),
                VoxyConfig.CONFIG.isRenderingEnabled() ? "voxy_merged" : "vanilla"
        );
    }

    private static void applyMergedRenderDistancePolicy(Minecraft mc) {
        VoxyMergedRenderDistance.applyMergedRenderDistancePolicy(mc);
    }
}
