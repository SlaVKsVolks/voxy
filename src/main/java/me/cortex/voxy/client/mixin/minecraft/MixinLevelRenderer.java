package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.sodium.VoxySodiumSectionLifecycleBridge;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void voxy$reloadVoxyRenderer(CallbackInfo ci) {
        if (this.level == null) {
            this.voxy$shutdownRenderer();
            VoxyClient.resetVisualAttributionModeForRendererLifecycle("level null");
            return;
        }
        if (this.renderer == null) {
            this.voxy$createRenderer();
            return;
        }

        VoxyRenderSystem oldRenderer = this.renderer;
        VoxyRenderSystem newRenderer;
        try {
            newRenderer = this.voxy$buildRenderer();
        } catch (RuntimeException e) {
            Logger.error("Keeping previous Voxy renderer because renderer reload failed", e);
            return;
        }
        if (newRenderer != null) {
            this.renderer = newRenderer;
            try {
                oldRenderer.shutdown();
            } catch (RuntimeException e) {
                Logger.error("Previous Voxy renderer failed during post-swap shutdown", e);
            }
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            VoxySodiumSectionLifecycleBridge.clearForWorldChange("level change");
            this.voxy$shutdownRenderer();
            VoxyClient.resetVisualAttributionModeForRendererLifecycle("level change");
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
        VoxyClient.resetVisualAttributionModeForRendererLifecycle("level renderer close");
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        this.renderer = this.voxy$buildRenderer();
    }

    @Unique
    private @Nullable VoxyRenderSystem voxy$buildRenderer() {
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return null;
        }
        VoxyClient.resetVisualAttributionModeForRendererLifecycle("renderer build");
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering enabled="
                    + VoxyConfig.CONFIG.enabled
                    + " enableRendering=" + VoxyConfig.CONFIG.enableRendering
                    + " voxyAvailable=" + VoxyCommon.isAvailable()
                    + " instancePresent=" + (VoxyCommon.getInstance() != null));
            return null;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return null;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return null;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return null;
        }
        try {
            VoxyRenderSystem newRenderer = new VoxyRenderSystem(world, instance.getServiceManager());
            instance.updateDedicatedThreads();
            return newRenderer;
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        return null;
    }
}
