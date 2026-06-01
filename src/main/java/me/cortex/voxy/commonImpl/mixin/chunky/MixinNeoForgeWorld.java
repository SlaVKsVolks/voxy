package me.cortex.voxy.commonImpl.mixin.chunky;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.NeoForgeWorld", remap = false)
public abstract class MixinNeoForgeWorld {
    @Unique
    private static volatile Method voxy$getWorldMethod;

    @Inject(method = "getChunkAtAsync", at = @At("RETURN"), remap = false)
    private void voxy$captureChunkyGeneratedChunk(int chunkX, int chunkZ, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        CompletableFuture<Void> future = cir.getReturnValue();
        if (future == null) {
            RenderCorrectnessDiagnostics.ingest("chunky_neoforge", chunkX, 0, chunkZ, false, false, "null_future");
            return;
        }

        future.thenRun(() -> voxy$ingestChunkyChunk(chunkX, chunkZ));
    }

    @Unique
    private void voxy$ingestChunkyChunk(int chunkX, int chunkZ) {
        ServerLevel level = voxy$getServerLevel();
        if (level == null) {
            RenderCorrectnessDiagnostics.ingest("chunky_neoforge", chunkX, 0, chunkZ, false, false, "missing_server_level");
            return;
        }

        var chunkAccess = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (!(chunkAccess instanceof LevelChunk chunk)) {
            RenderCorrectnessDiagnostics.ingest("chunky_neoforge", chunkX, 0, chunkZ, false, false, "missing_full_chunk");
            return;
        }

        boolean queued = VoxelIngestService.tryAutoIngestChunk(chunk);
        RenderCorrectnessDiagnostics.ingest("chunky_neoforge", chunkX, 0, chunkZ, false, queued, queued ? "queued" : "rejected");
    }

    @Unique
    private ServerLevel voxy$getServerLevel() {
        try {
            Method method = voxy$getWorldMethod;
            if (method == null) {
                method = this.getClass().getMethod("getWorld");
                method.setAccessible(true);
                voxy$getWorldMethod = method;
            }
            Object level = method.invoke(this);
            return level instanceof ServerLevel serverLevel ? serverLevel : null;
        } catch (ReflectiveOperationException | LinkageError error) {
            Logger.warn("Failed to resolve Chunky NeoForge world for Voxy ingest", error);
            return null;
        }
    }
}
