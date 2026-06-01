package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import me.cortex.voxy.commonImpl.NeoForgeModStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = NeoForgeModStatus.isLoaded("bobby");

    @Shadow @Final private ClientLevel level;

    @Shadow @Final private ChunkBuilder builder;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.levelRenderer)).voxy$getRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache)this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    /*
    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$trackChunkRemove(int x, int z, CallbackInfo ci) {
        if (this.level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.removeSection(ChunkPos.toLong(x, z));
            }
        }
    }*/

}
