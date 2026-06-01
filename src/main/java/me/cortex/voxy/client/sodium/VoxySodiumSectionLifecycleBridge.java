package me.cortex.voxy.client.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.mixin.sodium.AccessorChunkTracker;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.NeoForgeModStatus;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionLifecycleHooks;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Stable Voxy ingestion boundary for the owned Sodium 0.8.x backport.
 *
 * <p>This replaces the old redirect of {@code RenderSection.setInfo}. The
 * lifecycle hook fires after Sodium has committed the new section info, so Voxy
 * can observe a coherent built-section state without altering Sodium's return
 * value or relying on fragile bytecode call-site shape.</p>
 */
public final class VoxySodiumSectionLifecycleBridge implements RenderSectionLifecycleHooks.Listener {
    private static final VoxySodiumSectionLifecycleBridge INSTANCE = new VoxySodiumSectionLifecycleBridge();
    private static final boolean BOBBY_INSTALLED = NeoForgeModStatus.isLoaded("bobby");

    private static boolean registered;

    private VoxySodiumSectionLifecycleBridge() {
    }

    public static synchronized void init() {
        if (registered) {
            return;
        }

        RenderSectionLifecycleHooks.addListener(INSTANCE);
        registered = true;
        Logger.info("Registered Voxy Sodium section lifecycle bridge");
    }

    @Override
    public void onSectionInfoUpdated(SectionPos position, RenderSection section, @Nullable BuiltSectionInfo info, boolean changed) {
        if (!VoxyConfig.CONFIG.ingestEnabled || BOBBY_INSTALLED) {
            return;
        }

        var level = Minecraft.getInstance().level;
        if (level == null) {
            RenderCorrectnessDiagnostics.uploadIngestSkippedNoWorld.increment();
            return;
        }

        var system = getRenderSystem(level);
        if (system == null) {
            RenderCorrectnessDiagnostics.uploadIngestSkippedNoWorld.increment();
            RenderCorrectnessDiagnostics.ingest("sodium_upload", position.x(), position.y(), position.z(), false, false, "missing_render_system");
            return;
        }
        var provider = system.getFarTerrainProvider();
        if (provider == null) {
            RenderCorrectnessDiagnostics.ingest("sodium_upload", position.x(), position.y(), position.z(), false, false, "missing_far_terrain_provider");
            return;
        }

        var x = section.getChunkX();
        var y = section.getChunkY();
        var z = section.getChunkZ();
        provider.invalidateSection(SectionPos.asLong(x, y, z));

        if (changed) {
            if (section.getFlags() != 0) {
                system.chunkBoundRenderer.addSection(SectionPos.asLong(x, y, z));
            } else {
                system.chunkBoundRenderer.removeSection(SectionPos.asLong(x, y, z));
            }
        }

        if (info == null) {
            RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, false, false, "null_info");
            return;
        }

        ingestCommittedSection(level, system, x, y, z);
    }

    @Override
    public void onSectionRemoved(SectionPos position, RenderSection section) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        var system = getRenderSystem(level);
        if (system != null) {
            system.getFarTerrainProvider().invalidateSection(position.asLong());
            system.chunkBoundRenderer.removeSection(position.asLong());
        }
    }

    private static VoxyRenderSystem getRenderSystem(ClientLevel level) {
        if (!(level.levelRenderer instanceof IGetVoxyRenderSystem accessor)) {
            return null;
        }
        return accessor.voxy$getRenderSystem();
    }

    private static void ingestCommittedSection(ClientLevel level, VoxyRenderSystem system, int x, int y, int z) {
        var chunkTracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(level)).getChunkStatus();
        var chunkKey = ChunkPos.asLong(x, z);
        var chunkStatus = chunkTracker.getOrDefault(chunkKey, 0);
        if (chunkStatus != 3) {
            RenderCorrectnessDiagnostics.uploadIngestSkippedChunkStatus.increment();
            RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, false, false, "chunk_status_" + chunkStatus);
            return;
        }

        var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) {
            RenderCorrectnessDiagnostics.uploadIngestSkippedNoWorld.increment();
            RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, true, false, "missing_chunk_wait_for_chunk_data");
            return;
        }

        var bottomSectionY = level.getMinBuildHeight() >> 4;
        var sectionIndex = y - bottomSectionY;
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, true, false, "section_out_of_range_" + sectionIndex);
            return;
        }

        var section = chunk.getSection(sectionIndex);
        var lightEngine = level.getLightEngine();
        var chunkSectionPos = SectionPos.of(x, y, z);
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(chunkSectionPos);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(chunkSectionPos);
        var skySectionType = lightEngine.getDebugSectionType(LightLayer.SKY, chunkSectionPos);
        var fallbackSkyLight = VoxelIngestService.fallbackSkyLightFor(skySectionType, section, skyLight, y);
        var missingSkyLight = skyLight == null
                && skySectionType != LayerLightSectionStorage.SectionType.LIGHT_ONLY
                && fallbackSkyLight < 0;

        if (missingSkyLight && system.getEngine().instanceIn.getIngestService().rejectMissingSkyLightSurfacePreview(section, x, y, z)) {
            RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, section.hasOnlyAir(), false, "missing_sky_light_surface_preview_deferred");
            return;
        }

        var worldId = WorldIdentifier.of(level);
        var queued = VoxelIngestService.rawIngest(
                system.getEngine(),
                worldId,
                section,
                x,
                y,
                z,
                blockLight == null ? null : blockLight.copy(),
                skyLight == null ? null : skyLight.copy(),
                fallbackSkyLight);
        RenderCorrectnessDiagnostics.ingest("sodium_upload", x, y, z, section.hasOnlyAir(), queued, queued ? "lifecycle_queued" : "raw_ingest_rejected");
    }
}
