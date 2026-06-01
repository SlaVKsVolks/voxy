package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyMergedRenderDistance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.debug.RenderStateDiagnostics;
import me.cortex.voxy.client.core.debug.VoxyGpuAttribution;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.RenderPassGuard;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProvider;
import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProviderSnapshot;
import me.cortex.voxy.client.sodium.provider.VoxyProviderDrawDecision;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainPass;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainProviderDiagnostics;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.MemoryStack;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_BINDING_CUBE_MAP;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private static final int TRACKED_TEXTURE_UNITS = 12;
    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;


    private final RenderDistanceTracker renderDistanceTracker;
    private final RenderDistanceTracker[] handoffRootTrackers;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;
    private final VoxyFarTerrainProvider farTerrainProvider;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    // Fog parameters captured before modification by MixinFogRenderer, for Voxy's own fog pass
    private float capturedFogStart;
    private float capturedFogEnd;
    private final float[] capturedFogColor = new float[4];
    private boolean loggedZeroViewportSetup;
    private boolean loggedZeroViewportRender;
    private int lastViewportWidth = -1;
    private int lastViewportHeight = -1;
    private int resizeSettleFrames;
    private boolean loggedResizeSettle;
    private static boolean loggedNonFiniteBaseProjection;
    private static boolean loggedNonFiniteDepthProjection;
    private static final boolean DISABLE_CHUNK_BOUND_DEPTH = Boolean.parseBoolean(
            System.getProperty("voxy.disableChunkBoundDepth", "false")
    );
    private static final boolean ENABLE_HANDOFF_ROOT_TRACKERS = Boolean.parseBoolean(
            System.getProperty("voxy.enableHandoffRootTrackers", "true")
    );
    private static final int HANDOFF_ROOT_MIN_LEVEL = Math.max(0, Integer.getInteger("voxy.handoffRootMinLevel", 0));
    private static final int HANDOFF_ROOT_MAX_LEVEL = Math.min(
            WorldEngine.MAX_LOD_LAYER - 1,
            Integer.getInteger("voxy.handoffRootMaxLevel", 2)
    );
    private static final int HANDOFF_ROOT_RADIUS_SECTIONS = Math.max(
            0,
            Integer.getInteger("voxy.handoffRootRadiusSections", 10)
    );

    public void setCapturedFog(float fogStart, float fogEnd, float[] fogColor) {
        this.capturedFogStart = fogStart;
        this.capturedFogEnd = fogEnd;
        if (fogColor == null || fogColor.length < 4) {
            this.capturedFogColor[0] = 0f;
            this.capturedFogColor[1] = 0f;
            this.capturedFogColor[2] = 0f;
            this.capturedFogColor[3] = 1f;
            return;
        }
        System.arraycopy(fogColor, 0, this.capturedFogColor, 0, 4);
    }

    public float getCapturedFogStart() { return this.capturedFogStart; }
    public float getCapturedFogEnd()   { return this.capturedFogEnd; }
    public float[] getCapturedFogColor() { return this.capturedFogColor; }

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        RenderCorrectnessDiagnostics.call("render_system", "VoxyRenderSystem", "construct_start", "world_acquire");
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }
        TextureUnitState textureState = TextureUnitState.capture(TRACKED_TEXTURE_UNITS);

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();
            glFinish();

            this.worldIn = world;
            this.farTerrainProvider = new VoxyFarTerrainProvider(this.createProviderSnapshot());

            this.properties = RenderProperties.getRenderProperties();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen, this.farTerrainProvider);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service
            RenderCorrectnessDiagnostics.call("render_system", "VoxyRenderSystem", "pipeline_created", this.pipeline.getClass().getSimpleName());

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.pipeline.setProviderRenderListStaleSkipCallback(this.farTerrainProvider::recordProviderRenderListStaleSkip);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSection = Minecraft.getInstance().level.getMinSection();
                int maxSection = Minecraft.getInstance().level.getMaxSection() - 1;
                int minSec = minSection >> (WorldEngine.MAX_LOD_LAYER + 1);
                int maxSec = maxSection >> (WorldEngine.MAX_LOD_LAYER + 1);

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                var handoffTrackers = new ArrayList<RenderDistanceTracker>();
                if (ENABLE_HANDOFF_ROOT_TRACKERS && !VoxyCommon.IS_MINE_IN_ABYSS) {
                    for (int level = HANDOFF_ROOT_MIN_LEVEL; level <= HANDOFF_ROOT_MAX_LEVEL; level++) {
                        int levelMinSec = minSection >> (level + 1);
                        int levelMaxSec = maxSection >> (level + 1);
                        var tracker = new RenderDistanceTracker(
                                160,
                                level,
                                levelMinSec,
                                levelMaxSec,
                                this.nodeManager::addTopLevel,
                                this.nodeManager::removeTopLevel);
                        tracker.setRenderDistance(HANDOFF_ROOT_RADIUS_SECTIONS);
                        handoffTrackers.add(tracker);
                    }
                }
                this.handoffRootTrackers = handoffTrackers.toArray(RenderDistanceTracker[]::new);
                this.setVisualRenderDistanceChunks(VoxyConfig.CONFIG.visualTerrainDistanceChunks);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
            RenderCorrectnessDiagnostics.call("render_system", "VoxyRenderSystem", "construct_done",
                    "pipeline=" + this.pipeline.getClass().getSimpleName() + " renderer=" + sectionRenderer.getClass().getSimpleName());
        } catch (RuntimeException e) {
            RenderCorrectnessDiagnostics.call("render_system", "VoxyRenderSystem", "construct_failed", e.getClass().getSimpleName());
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }
        textureState.restore();
    }


    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "start", "camera=" + cameraX + "," + cameraY + "," + cameraZ);
        var viewport = this.getViewport();
        if (viewport == null) {
            RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "skip", "viewport_null_or_shadow_active");
            return null;
        }
        if (!isFiniteMatrix(vanillaProjection) || !isFiniteMatrix(modelView)) {
            if (viewport.frameId > 0) {
                RenderCorrectnessDiagnostics.projectionGuard("setupViewport", "non_finite_input_projection_or_modelview");
            }
            RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "skip", "non_finite_input_matrix");
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection);
        if (voxyProjection == null) {
            RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "skip", "projection_null");
            return null;
        }

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            if (!this.loggedZeroViewportSetup) {
                this.loggedZeroViewportSetup = true;
                Logger.warn("Skipping Voxy viewport setup because GL viewport is zero during render transition");
            }
            RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "skip", "zero_viewport");
            return null;
        }
        if (this.lastViewportWidth != width || this.lastViewportHeight != height) {
            if (this.lastViewportWidth > 0 && this.lastViewportHeight > 0) {
                this.resizeSettleFrames = Math.max(this.resizeSettleFrames, 3);
                RenderCorrectnessDiagnostics.call("render_system", "setupViewport", "viewport_resize_detected",
                        this.lastViewportWidth + "x" + this.lastViewportHeight + " -> " + width + "x" + height);
                if (!this.loggedResizeSettle) {
                    this.loggedResizeSettle = true;
                    Logger.info("Voxy detected a framebuffer/viewport resize; settling Voxy composition for a few frames");
                }
            }
            this.lastViewportWidth = width;
            this.lastViewportHeight = height;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update()
                .captureRenderStateSnapshot(VoxyHandoffPolicy.topLevelRenderDistanceSections(
                        VoxyConfig.CONFIG.visualTerrainDistanceChunks));

        RenderStateDiagnostics.captureViewport(
                "setupViewport",
                viewport,
                this.properties,
                VoxyHandoffPolicy.topLevelRenderDistanceSections(VoxyConfig.CONFIG.visualTerrainDistanceChunks)
        );

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }
        RenderCorrectnessDiagnostics.updateCameraPose(
                viewport.frameId,
                viewport.cameraX,
                viewport.cameraY,
                viewport.cameraZ,
                viewport.width,
                viewport.height
        );

        RenderCorrectnessDiagnostics.renderFrame(
                "setupViewport",
                viewport.frameId,
                this.pipeline.getClass().getSimpleName(),
                viewport.width,
                viewport.height,
                this.nodeManager.getManagedTopLevelNodeCount(),
                this.nodeManager.getManagedActiveSectionCount(),
                this.nodeManager.getRenderedGeometrySectionCount(),
                this.nodeManager.getActiveNodeRequestCount(),
                this.nodeManager.getUsedGeometryCapacity(),
                "viewport_ready"
        );
        if (RenderCorrectnessDiagnostics.shouldSampleGeometryCoverage(viewport.frameId)) {
            this.nodeManager.emitGeometryCoverageDiagnostics(viewport.frameId, "setupViewport_sample");
        }

        return viewport;
    }

    public void renderOpaque(Viewport<?> viewport) {
        this.pipeline.clearProviderRenderList();
        this.renderOpaqueInternal(viewport);
    }

    private void renderProviderOpaque(Viewport<?> viewport, VoxyProviderDrawDecision drawDecision) {
        this.pipeline.setProviderRenderList(drawDecision.renderList());
        this.renderOpaqueInternal(viewport);
    }

    private void renderOpaqueInternal(Viewport<?> viewport) {
        if (viewport == null) {
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "skip", "viewport_null");
            return;
        }
        if (VoxyClient.isVisualAttributionNoop()) {
            VoxyGpuAttribution.recordNoop(
                    viewport,
                    this.nodeManager.getRenderedGeometrySectionCount(),
                    this.nodeManager.getActiveNodeRequestCount()
            );
            RenderCorrectnessDiagnostics.renderFrame(
                    "renderOpaque_noop",
                    viewport.frameId,
                    this.pipeline.getClass().getSimpleName(),
                    viewport.width,
                    viewport.height,
                    this.nodeManager.getManagedTopLevelNodeCount(),
                    this.nodeManager.getManagedActiveSectionCount(),
                    this.nodeManager.getRenderedGeometrySectionCount(),
                    this.nodeManager.getActiveNodeRequestCount(),
                    this.nodeManager.getUsedGeometryCapacity(),
                    "visual_attribution_noop"
            );
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            if (!this.loggedZeroViewportRender) {
                this.loggedZeroViewportRender = true;
                Logger.warn("Skipping Voxy frame because viewport is zero during render transition");
            }
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "skip", "invalid_viewport");
            return;//Only render on valid viewport
        }
        if (this.resizeSettleFrames > 0) {
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "skip",
                    "resize_settle_remaining=" + this.resizeSettleFrames);
            this.resizeSettleFrames--;
            return;
        }

        RenderCorrectnessDiagnostics.renderFrame(
                "renderOpaque_start",
                viewport.frameId,
                this.pipeline.getClass().getSimpleName(),
                viewport.width,
                viewport.height,
                this.nodeManager.getManagedTopLevelNodeCount(),
                this.nodeManager.getManagedActiveSectionCount(),
                this.nodeManager.getRenderedGeometrySectionCount(),
                this.nodeManager.getActiveNodeRequestCount(),
                this.nodeManager.getUsedGeometryCapacity(),
                "begin"
        );

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }
        TextureUnitState textureState = TextureUnitState.capture(TRACKED_TEXTURE_UNITS);


        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;
        int oldProgram = GL11.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
        int oldVertexArray = GL11.glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean oldDepthTest = GL11.glIsEnabled(GL_DEPTH_TEST);
        boolean oldStencilTest = GL11.glIsEnabled(GL_STENCIL_TEST);
        boolean oldBlend = GL11.glIsEnabled(GL_BLEND);
        boolean oldCullFace = GL11.glIsEnabled(GL_CULL_FACE);
        boolean oldScissorTest = GL11.glIsEnabled(GL_SCISSOR_TEST);
        boolean oldFramebufferSrgb = GL11.glIsEnabled(GL_FRAMEBUFFER_SRGB);
        boolean oldDepthMask = GL11.glGetBoolean(GL_DEPTH_WRITEMASK);
        int oldDepthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);
        int oldStencilFunc = GL11.glGetInteger(GL_STENCIL_FUNC);
        int oldStencilRef = GL11.glGetInteger(GL_STENCIL_REF);
        int oldStencilValueMask = GL11.glGetInteger(GL_STENCIL_VALUE_MASK);
        int oldStencilSFail = GL11.glGetInteger(GL_STENCIL_FAIL);
        int oldStencilDPFail = GL11.glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL);
        int oldStencilDPPass = GL11.glGetInteger(GL_STENCIL_PASS_DEPTH_PASS);
        int oldStencilWriteMask = GL11.glGetInteger(GL_STENCIL_WRITEMASK);
        int oldBlendSrcRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB);
        int oldBlendDstRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB);
        int oldBlendSrcAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA);
        int oldBlendDstAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA);
        PresentationGlState presentationState = PresentationGlState.capture(TRACKED_TEXTURE_UNITS, oldBufferBindings.length);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        RenderPassGuard passGuard = RenderPassGuard.capture("renderOpaque", oldFB, dims);

        try {
            glViewport(0,0, viewport.width, viewport.height);

            //var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
            //boundFB = ((net.minecraft.client.texture.GlTexture) target.getColorAttachment()).getOrCreateFramebuffer(((GlBackend) RenderSystem.getDevice()).getFramebufferManager(), target.getDepthAttachment());
            if (!passGuard.validateFramebuffer(boundFB, dims[2], dims[3])) {
                return;
            }

            //this.autoBalanceSubDivSize();

            this.pipeline.preSetup(viewport);
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "pipeline_pre_setup_done", this.pipeline.getClass().getSimpleName());

            TimingStatistics.E.start();
            if (!DISABLE_CHUNK_BOUND_DEPTH && (!VoxyClient.disableSodiumChunkRender())&&!IrisUtil.irisShadowActive()) {
                this.chunkBoundRenderer.render(viewport);
            } else {
                viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            }
            TimingStatistics.E.stop();


            GPUTiming.INSTANCE.marker();
            VoxyGpuAttribution.CaptureRequest gpuAttributionRequest = VoxyGpuAttribution.takePendingRequest();
            VoxyGpuAttribution.CaptureResult gpuAttributionResult = null;
            if (gpuAttributionRequest != null) {
                gpuAttributionResult = VoxyGpuAttribution.captureDiagnosticBuffer(
                        gpuAttributionRequest,
                        this.pipeline,
                        viewport,
                        boundFB,
                        dims[2],
                        dims[3],
                        this.nodeManager.getRenderedGeometrySectionCount(),
                        this.nodeManager.getActiveNodeRequestCount()
                );
            }
            //The entire rendering pipeline (excluding the chunkbound thing)
            this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);
            VoxyGpuAttribution.captureFinalFramebuffer(gpuAttributionResult, boundFB, dims[2], dims[3]);
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "pipeline_run_done", this.pipeline.getClass().getSimpleName());
            GPUTiming.INSTANCE.marker();


            TimingStatistics.main.stop();
            TimingStatistics.postDynamic.start();

            PrintfDebugUtil.tick();

            //As much dynamic runtime stuff here
            {
                //Tick upload stream (this is ok to do here as upload ticking is just memory management)
                UploadStream.INSTANCE.tick();

                VoxyHandoffPolicy.MergedRenderDistance distance = VoxyMergedRenderDistance.currentDistance();
                VoxyHandoffPolicy.updateCamera(
                        viewport.cameraX,
                        viewport.cameraY,
                        viewport.cameraZ,
                        distance.visualTerrainDistanceChunks(),
                        distance.maxRealRenderDistanceChunks(),
                        distance.handoffOverlapChunks(),
                        VoxyMergedRenderDistance.usesMergedSlider() ? "voxy_merged" : "vanilla"
                );
                this.renderGen.setPriorityCenter(viewport.cameraX, viewport.cameraY, viewport.cameraZ);
                while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraY, viewport.cameraZ) && VoxyClient.isFrexActive());//While FF is active, run until everything is processed
                for (RenderDistanceTracker tracker : this.handoffRootTrackers) {
                    while (tracker.setCenterAndProcess(viewport.cameraX, viewport.cameraY, viewport.cameraZ) && VoxyClient.isFrexActive()) {
                        // Keep lower-level cache roots settled in diagnostic fast-forward mode.
                    }
                }
                TimingStatistics.H.start();
                //Done here as is allows less gl state resetup
                do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
                TimingStatistics.H.stop();
            }
            GPUTiming.INSTANCE.marker();
            TimingStatistics.postDynamic.stop();

            GPUTiming.INSTANCE.tick();
        } finally {
            passGuard.restore();
            presentationState.restore();
            RenderCorrectnessDiagnostics.call("render_system", "renderOpaque", "presentation_state_restore", "complete");
        }

        TimingStatistics.all.stop();
        RenderCorrectnessDiagnostics.renderFrame(
                "renderOpaque_end",
                viewport.frameId,
                this.pipeline.getClass().getSimpleName(),
                viewport.width,
                viewport.height,
                this.nodeManager.getManagedTopLevelNodeCount(),
                this.nodeManager.getManagedActiveSectionCount(),
                this.nodeManager.getRenderedGeometrySectionCount(),
                this.nodeManager.getActiveNodeRequestCount(),
                this.nodeManager.getUsedGeometryCapacity(),
                "end"
        );

        //TimingStatistics.I.start();
        //glFlush();
        //TimingStatistics.I.stop();

        /*
        TimingStatistics.F.start();
        this.postProcessing.setup(viewport.width, viewport.height, boundFB);
        TimingStatistics.F.stop();

        this.renderer.renderFarAwayOpaque(viewport, this.chunkBoundRenderer.getDepthBoundTexture());


        TimingStatistics.F.start();
        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(viewport.MVP);
        TimingStatistics.F.stop();

        TimingStatistics.G.start();
        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport, this.chunkBoundRenderer.getDepthBoundTexture());
        TimingStatistics.G.stop();


        TimingStatistics.F.start();
        this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
        TimingStatistics.F.stop();
         */
    }



    private void autoBalanceSubDivSize() {
        //only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        //Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 1);
        }
    }

    public void renderSodiumProviderPass(
            VoxyTerrainPass pass,
            ChunkRenderMatrices matrices,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        this.farTerrainProvider.updateSnapshot(this.createProviderSnapshot());
        if (IrisUtil.irisShaderPackEnabled() && !this.isProviderIrisStateReady()) {
            this.farTerrainProvider.recordIrisFailClosed(VoxyFarTerrainProvider.SKIPPED_INCOMPLETE_IRIS_STATE);
            RenderCorrectnessDiagnostics.call("sodium_provider", "render_pass", "skip", VoxyFarTerrainProvider.SKIPPED_INCOMPLETE_IRIS_STATE);
            return;
        }
        Viewport<?> viewport;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = this.getViewport();
        } else {
            viewport = this.setupViewport(matrices.projection(), matrices.modelView(), cameraX, cameraY, cameraZ);
        }
        if (viewport == null) {
            RenderCorrectnessDiagnostics.call("sodium_provider", "render_pass", "skip", "viewport_null:" + pass.name());
            return;
        }
        if (pass == VoxyTerrainPass.SOLID) {
            this.tickProviderRuntimeState(viewport);
            this.prepareProviderTraversal(viewport);
            this.refreshRendererProviderAuthority();
        }
        VoxyProviderDrawDecision drawDecision = this.farTerrainProvider.drawDecision(pass);
        if (!drawDecision.draw()) {
            RenderCorrectnessDiagnostics.call("sodium_provider", "render_pass", "skip",
                    drawDecision.reason().name() + ":" + drawDecision.authorityVerdict() + ":" + pass.name());
            return;
        }
        this.farTerrainProvider.recordProviderRenderListDispatch(drawDecision.renderList());
        this.renderProviderOpaque(viewport, drawDecision);
        this.farTerrainProvider.recordRenderedPass(pass);
    }

    private void prepareProviderTraversal(Viewport<?> viewport) {
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);
        if (dims[2] <= 0 || dims[3] <= 0) {
            RenderCorrectnessDiagnostics.call("sodium_provider", "prepare_provider_traversal", "skip", "invalid_viewport");
            return;
        }
        int boundFramebuffer = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            this.pipeline.prepareProviderTraversal(viewport, boundFramebuffer, dims[2], dims[3]);
        } catch (RuntimeException exception) {
            RenderCorrectnessDiagnostics.call(
                    "sodium_provider",
                    "prepare_provider_traversal",
                    "skip",
                    exception.getClass().getSimpleName() + ":" + exception.getMessage());
            Logger.error("Skipping Voxy provider traversal preparation after runtime failure", exception);
        }
    }

    private void tickProviderRuntimeState(Viewport<?> viewport) {
        UploadStream.INSTANCE.tick();
        VoxyHandoffPolicy.MergedRenderDistance distance = VoxyMergedRenderDistance.currentDistance();
        VoxyHandoffPolicy.updateCamera(
                viewport.cameraX,
                viewport.cameraY,
                viewport.cameraZ,
                distance.visualTerrainDistanceChunks(),
                distance.maxRealRenderDistanceChunks(),
                distance.handoffOverlapChunks(),
                VoxyMergedRenderDistance.usesMergedSlider() ? "voxy_merged" : "vanilla"
        );
        this.renderGen.setPriorityCenter(viewport.cameraX, viewport.cameraY, viewport.cameraZ);
        while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraY, viewport.cameraZ)
                && VoxyClient.isFrexActive()) {
            // Drain tracker work in diagnostic fast-forward mode.
        }
        for (RenderDistanceTracker tracker : this.handoffRootTrackers) {
            while (tracker.setCenterAndProcess(viewport.cameraX, viewport.cameraY, viewport.cameraZ)
                    && VoxyClient.isFrexActive()) {
                // Keep lower-level handoff roots settled in diagnostic fast-forward mode.
            }
        }
        this.modelService.tick(900_000);
    }

    private boolean isProviderIrisStateReady() {
        return this.pipeline != null && this.viewportSelector != null && this.getViewport() != null;
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    private record PresentationGlState(
            int program,
            int vertexArray,
            boolean depthTest,
            boolean stencilTest,
            boolean blend,
            boolean cullFace,
            boolean scissorTest,
            boolean framebufferSrgb,
            boolean dither,
            boolean multisample,
            boolean sampleAlphaToCoverage,
            boolean rasterizerDiscard,
            boolean colorLogicOp,
            boolean depthMask,
            int depthFunc,
            int stencilFunc,
            int stencilRef,
            int stencilValueMask,
            int stencilSFail,
            int stencilDPFail,
            int stencilDPPass,
            int stencilWriteMask,
            int blendSrcRgb,
            int blendDstRgb,
            int blendSrcAlpha,
            int blendDstAlpha,
            int blendEquationRgb,
            int blendEquationAlpha,
            int cullFaceMode,
            int frontFace,
            int[] scissorBox,
            boolean[] colorMask,
            IndexedBlendState indexedBlendState,
            TextureUnitState textureState,
            int[] shaderStorageBindings
    ) {
        private static PresentationGlState capture(int textureUnitCount, int shaderStorageBindingCount) {
            int[] scissorBox = new int[4];
            glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
            boolean[] colorMask = new boolean[4];
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < colorMask.length; i++) {
                    colorMask[i] = mask.get(i) != 0;
                }
            }

            int[] shaderStorageBindings = new int[shaderStorageBindingCount];
            for (int i = 0; i < shaderStorageBindings.length; i++) {
                shaderStorageBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            }

            return new PresentationGlState(
                    glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                    glGetInteger(GL_VERTEX_ARRAY_BINDING),
                    glIsEnabled(GL_DEPTH_TEST),
                    glIsEnabled(GL_STENCIL_TEST),
                    glIsEnabled(GL_BLEND),
                    glIsEnabled(GL_CULL_FACE),
                    glIsEnabled(GL_SCISSOR_TEST),
                    glIsEnabled(GL_FRAMEBUFFER_SRGB),
                    glIsEnabled(GL_DITHER),
                    glIsEnabled(GL_MULTISAMPLE),
                    glIsEnabled(GL_SAMPLE_ALPHA_TO_COVERAGE),
                    glIsEnabled(GL_RASTERIZER_DISCARD),
                    glIsEnabled(GL_COLOR_LOGIC_OP),
                    glGetBoolean(GL_DEPTH_WRITEMASK),
                    glGetInteger(GL_DEPTH_FUNC),
                    glGetInteger(GL_STENCIL_FUNC),
                    glGetInteger(GL_STENCIL_REF),
                    glGetInteger(GL_STENCIL_VALUE_MASK),
                    glGetInteger(GL_STENCIL_FAIL),
                    glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL),
                    glGetInteger(GL_STENCIL_PASS_DEPTH_PASS),
                    glGetInteger(GL_STENCIL_WRITEMASK),
                    glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB),
                    glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB),
                    glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA),
                    glGetInteger(org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA),
                    glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                    glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA),
                    glGetInteger(GL_CULL_FACE_MODE),
                    glGetInteger(GL_FRONT_FACE),
                    scissorBox,
                    colorMask,
                    IndexedBlendState.capture(),
                    TextureUnitState.capture(textureUnitCount),
                    shaderStorageBindings
            );
        }

        private void restore() {
            glUseProgram(this.program);
            GlStateManager._glBindVertexArray(this.vertexArray);

            restoreCapability(GL_DEPTH_TEST, this.depthTest);
            restoreCapability(GL_STENCIL_TEST, this.stencilTest);
            restoreCapability(GL_BLEND, this.blend);
            restoreCapability(GL_CULL_FACE, this.cullFace);
            restoreCapability(GL_SCISSOR_TEST, this.scissorTest);
            restoreCapability(GL_FRAMEBUFFER_SRGB, this.framebufferSrgb);
            restoreCapability(GL_DITHER, this.dither);
            restoreCapability(GL_MULTISAMPLE, this.multisample);
            restoreCapability(GL_SAMPLE_ALPHA_TO_COVERAGE, this.sampleAlphaToCoverage);
            restoreCapability(GL_RASTERIZER_DISCARD, this.rasterizerDiscard);
            restoreCapability(GL_COLOR_LOGIC_OP, this.colorLogicOp);

            glDepthMask(this.depthMask);
            glDepthFunc(this.depthFunc);
            glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilValueMask);
            glStencilOp(this.stencilSFail, this.stencilDPFail, this.stencilDPPass);
            glStencilMask(this.stencilWriteMask);
            glBlendFuncSeparate(this.blendSrcRgb, this.blendDstRgb, this.blendSrcAlpha, this.blendDstAlpha);
            GL20C.glBlendEquationSeparate(this.blendEquationRgb, this.blendEquationAlpha);
            glCullFace(this.cullFaceMode);
            glFrontFace(this.frontFace);
            glScissor(this.scissorBox[0], this.scissorBox[1], this.scissorBox[2], this.scissorBox[3]);
            glColorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);

            this.indexedBlendState.restore();
            this.textureState.restore();
            for (int i = 0; i < this.shaderStorageBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.shaderStorageBindings[i]);
            }
        }
    }

    private record IndexedBlendState(
            boolean[] enabled,
            int[] srcRgb,
            int[] dstRgb,
            int[] srcAlpha,
            int[] dstAlpha,
            int[] equationRgb,
            int[] equationAlpha
    ) {
        private static IndexedBlendState capture() {
            int count = Math.min(8, glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS));
            boolean[] enabled = new boolean[count];
            int[] srcRgb = new int[count];
            int[] dstRgb = new int[count];
            int[] srcAlpha = new int[count];
            int[] dstAlpha = new int[count];
            int[] equationRgb = new int[count];
            int[] equationAlpha = new int[count];
            for (int i = 0; i < count; i++) {
                enabled[i] = glIsEnabledi(GL_BLEND, i);
                srcRgb[i] = glGetIntegeri(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB, i);
                dstRgb[i] = glGetIntegeri(org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB, i);
                srcAlpha[i] = glGetIntegeri(org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA, i);
                dstAlpha[i] = glGetIntegeri(org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA, i);
                equationRgb[i] = glGetIntegeri(GL20C.GL_BLEND_EQUATION_RGB, i);
                equationAlpha[i] = glGetIntegeri(GL20C.GL_BLEND_EQUATION_ALPHA, i);
            }
            return new IndexedBlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha);
        }

        private void restore() {
            for (int i = 0; i < this.enabled.length; i++) {
                if (this.enabled[i]) {
                    glEnablei(GL_BLEND, i);
                } else {
                    glDisablei(GL_BLEND, i);
                }
                ARBDrawBuffersBlend.glBlendFuncSeparateiARB(i, this.srcRgb[i], this.dstRgb[i], this.srcAlpha[i], this.dstAlpha[i]);
                GL40C.glBlendEquationSeparatei(i, this.equationRgb[i], this.equationAlpha[i]);
            }
        }
    }

    private record TextureUnitState(
            int activeTexture,
            int[] texture2d,
            int[] texture2dArray,
            int[] textureCubeMap,
            int[] sampler
    ) {
        private static TextureUnitState capture(int unitCount) {
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            int[] texture2d = new int[unitCount];
            int[] texture2dArray = new int[unitCount];
            int[] textureCubeMap = new int[unitCount];
            int[] sampler = new int[unitCount];
            for (int i = 0; i < unitCount; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                texture2d[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                texture2dArray[i] = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
                textureCubeMap[i] = glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP);
                sampler[i] = glGetIntegeri(GL_SAMPLER_BINDING, i);
            }
            glActiveTexture(activeTexture);
            return new TextureUnitState(activeTexture, texture2d, texture2dArray, textureCubeMap, sampler);
        }

        private void restore() {
            for (int i = 0; i < this.texture2d.length; i++) {
                glActiveTexture(GL_TEXTURE0 + i);
                glBindTexture(GL_TEXTURE_2D, this.texture2d[i]);
                glBindTexture(GL_TEXTURE_2D_ARRAY, this.texture2dArray[i]);
                glBindTexture(GL_TEXTURE_CUBE_MAP, this.textureCubeMap[i]);
                glBindSampler(i, this.sampler[i]);
            }
            glActiveTexture(this.activeTexture);
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0 + (this.activeTexture - GL_TEXTURE0));
        }
    }

    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    /*
    private static float getGameFoV() {
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;
        return gameRenderer.getMainCamera().getFov();
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        projection.setPerspective(getGameFoV() * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float)client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        //THis is a wild and insane problem to have
        // at short render distances the vanilla terrain doesnt end up covering the 16f near plane voxy uses
        // meaning that it explodes (due to near plane clipping).. _badly_ with the rastered culling being wrong in rare cases for the immediate
        // sections rendered after the vanilla render distance
        float nearVoxy = getRenderDistance()<=32.0f?8f:16f;
        nearVoxy = VoxyClient.disableSodiumChunkRender()?0.1f:nearVoxy;

        return base.mulLocal(
                Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix.invert(new Matrix4f()),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(nearVoxy, 16*3000));
    }*/

    private static boolean isFiniteMatrix(Matrix4fc matrix) {
        return matrix != null && new Matrix4f(matrix).isFinite();
    }

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base) {
        if (base == null || !base.isFinite()) {
            RenderCorrectnessDiagnostics.projectionGuard("computeProjectionMat", "non_finite_base_projection");
            if (!loggedNonFiniteBaseProjection) {
                loggedNonFiniteBaseProjection = true;
                Logger.warn("Skipping Voxy frame due to non-finite base projection matrix during render transition");
            }
            return null;
        }

        float near = getRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;

        float far = 16*3000;

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        // Keep the camera/FOV terms from Sodium's per-pass matrix, but extend only the
        // depth range. Inverting RenderSystem's global projection is not reliable here:
        // on the NeoForge/Sodium 0.8.12 path it can be stale or singular during terrain
        // layer rendering, which produced non-finite matrices and Voxy depth mismatch.
        Matrix4f result = new Matrix4f(base)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far));
        if (!result.isFinite()) {
            RenderCorrectnessDiagnostics.projectionGuard("computeProjectionMat", "non_finite_depth_extended_projection");
            if (!loggedNonFiniteDepthProjection) {
                loggedNonFiniteDepthProjection = true;
                Logger.warn("Skipping Voxy frame due to non-finite depth-extended projection matrix during render transition");
            }
            return null;
        }
        return result;
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.setVisualRenderDistanceChunks(Math.round(renderDistance * 32.0f));
    }

    public void setVisualRenderDistanceChunks(int visualTerrainDistanceChunks) {
        VoxyMergedRenderDistance.updatePolicyState();
        int policyVisualTerrainDistanceChunks = VoxyHandoffPolicy.visualTerrainDistanceChunks() > 0
                ? VoxyHandoffPolicy.visualTerrainDistanceChunks()
                : visualTerrainDistanceChunks;
        this.farTerrainProvider.updateSnapshot(this.createProviderSnapshot());
        this.renderDistanceTracker.setRenderDistance(
                VoxyHandoffPolicy.topLevelRenderDistanceSections(policyVisualTerrainDistanceChunks));
        int boundaryChunks = VoxyHandoffPolicy.realRenderDistanceChunks() + 4;
        for (RenderDistanceTracker tracker : this.handoffRootTrackers) {
            tracker.setRenderDistance(Math.max(
                    HANDOFF_ROOT_RADIUS_SECTIONS,
                    VoxyHandoffPolicy.topLevelRenderDistanceSections(boundaryChunks)));
        }
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public int getRendererActiveSectionCount() {
        return this.nodeManager.getManagedActiveSectionCount();
    }

    public int getRendererTopLevelNodeCount() {
        return this.nodeManager.getManagedTopLevelNodeCount();
    }

    public int getRendererCommittedTopLevelNodeIdCount() {
        return this.nodeManager.getCommittedTopLevelNodeIdCount();
    }

    public int getRendererActiveNodeRequestCount() {
        return this.nodeManager.getActiveNodeRequestCount();
    }

    public int getRendererGeometrySectionCount() {
        return this.nodeManager.getRenderedGeometrySectionCount();
    }

    public int getRendererCurrentMaxNodeId() {
        return this.nodeManager.getCurrentMaxNodeId();
    }

    public long getRendererUsedGeometryBytes() {
        return this.nodeManager.getUsedGeometryCapacity();
    }

    public int getRendererMissingActiveChildChangeCount() {
        return this.nodeManager.getMissingActiveChildChangeCount();
    }

    public int getRendererInnerNodeZeroChildExistenceCount() {
        return this.nodeManager.getInnerNodeZeroChildExistenceCount();
    }

    public int getRendererDeferredInnerNodeZeroChildCollapseCount() {
        return this.nodeManager.getDeferredInnerNodeZeroChildCollapseCount();
    }

    public int getRendererZeroChildLeafRequestSkipCount() {
        return this.nodeManager.getZeroChildLeafRequestSkipCount();
    }

    public String getRendererPipelineName() {
        return this.pipeline.getClass().getSimpleName();
    }

    public String getRendererVisualAttributionMode() {
        return VoxyClient.getVisualAttributionMode();
    }

    public boolean getRendererSodiumChunkRenderingEnabled() {
        return VoxyClient.sodiumChunkRenderingEnabled();
    }

    public int getRendererVanillaRenderDistanceChunks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance();
    }

    public String getRendererRenderDistanceSliderMode() {
        return VoxyHandoffPolicy.renderDistanceSliderMode();
    }

    public int getRendererVisualTerrainDistanceChunks() {
        return VoxyHandoffPolicy.visualTerrainDistanceChunks();
    }

    public int getRendererVanillaRealRenderDistanceChunks() {
        return VoxyHandoffPolicy.realRenderDistanceChunks();
    }

    public int getRendererHandoffStartChunks() {
        return VoxyHandoffPolicy.handoffStartChunks();
    }

    public int getRendererLodEndChunks() {
        return VoxyHandoffPolicy.voxyLodEndChunks();
    }

    public int getRendererHandoffOverlapChunks() {
        return VoxyHandoffPolicy.overlapChunks();
    }

    public VoxyFarTerrainProvider getFarTerrainProvider() {
        return this.farTerrainProvider;
    }

    public VoxyTerrainProviderDiagnostics getRendererProviderDiagnostics() {
        this.refreshRendererProviderAuthority();
        return this.farTerrainProvider.diagnostics();
    }

    public boolean refreshRendererProviderAuthority() {
        this.farTerrainProvider.updateSnapshot(this.createProviderSnapshot());
        return this.nodeManager.refreshProviderCoverageNow("renderer_provider_authority_probe", 3000L);
    }

    public String getRendererProviderMode() {
        this.refreshRendererProviderAuthority();
        return "SODIUM_COMPATIBLE_FAR_TERRAIN_PROVIDER";
    }

    public boolean getRendererProviderSnapshotValid() {
        return this.farTerrainProvider.snapshot().hasMergedDistanceOwnership();
    }

    public long getRendererProviderExactOwnedCells() {
        return this.farTerrainProvider.diagnostics().exactOwnedCells();
    }

    public long getRendererProviderLodOwnedCells() {
        return this.farTerrainProvider.diagnostics().lodOwnedCells();
    }

    public long getRendererProviderParentFallbackCells() {
        return this.farTerrainProvider.diagnostics().parentFallbackCells();
    }

    public long getRendererProviderInvalidRejectedCells() {
        return this.farTerrainProvider.diagnostics().invalidRejectedCells();
    }

    public long getRendererProviderParentChildConflictCells() {
        return this.farTerrainProvider.diagnostics().parentChildConflictCells();
    }

    public long getRendererProviderBoundaryQueueDepth() {
        return this.farTerrainProvider.diagnostics().boundaryQueueDepth();
    }

    public long getRendererProviderFarQueueDepth() {
        return this.farTerrainProvider.diagnostics().farQueueDepth();
    }

    public long getRendererProviderBoundaryExactSections() {
        return this.farTerrainProvider.diagnostics().boundaryExactSections();
    }

    public long getRendererProviderBoundaryParentFallbackSections() {
        return this.farTerrainProvider.diagnostics().boundaryParentFallbackSections();
    }

    public long getRendererProviderBoundaryRejectedSections() {
        return this.farTerrainProvider.diagnostics().boundaryRejectedSections();
    }

    public long getRendererProviderBoundaryMissingSections() {
        return this.farTerrainProvider.diagnostics().boundaryMissingSections();
    }

    public long getRendererProviderBoundaryMissingRequiredCells() {
        return this.farTerrainProvider.diagnostics().boundaryMissingRequiredCells();
    }

    public long getRendererProviderInvalidRenderedSections() {
        return this.farTerrainProvider.diagnostics().invalidRenderedSections();
    }

    public long getRendererProviderBoundarySourceRealChunkSections() {
        return this.farTerrainProvider.diagnostics().boundarySourceRealChunkSections();
    }

    public long getRendererProviderBoundarySourceSurfacePreviewSections() {
        return this.farTerrainProvider.diagnostics().boundarySourceSurfacePreviewSections();
    }

    public long getRendererProviderBoundarySourceSyntheticPreviewSections() {
        return this.farTerrainProvider.diagnostics().boundarySourceSyntheticPreviewSections();
    }

    public long getRendererProviderBoundarySourceUnknownSections() {
        return this.farTerrainProvider.diagnostics().boundarySourceUnknownSections();
    }

    public long getRendererProviderBoundaryDegradedPreviewSections() {
        return this.farTerrainProvider.diagnostics().boundaryDegradedPreviewSections();
    }

    public long getRendererProviderOreLeakSurfaceRepresentatives() {
        return RenderCorrectnessDiagnostics.oreLeakSurfaceRepresentatives.sum();
    }

    public long getRendererProviderParentChildVisualConflicts() {
        return this.farTerrainProvider.diagnostics().parentChildVisualConflicts();
    }

    public long getRendererProviderUnsupportedPassSkips() {
        return this.farTerrainProvider.diagnostics().unsupportedPassSkips();
    }

    public long getRendererProviderUnsupportedPassRenderedSections() {
        return this.farTerrainProvider.diagnostics().unsupportedPassRenderedSections();
    }

    public long getRendererProviderIrisFailClosedSkips() {
        return this.farTerrainProvider.diagnostics().irisFailClosedSkips();
    }

    public long getRendererProviderSolidPassMeshes() {
        return this.farTerrainProvider.diagnostics().solidPassMeshes();
    }

    public long getRendererProviderRenderIndexSize() {
        return this.farTerrainProvider.renderIndexSize();
    }

    public long getRendererProviderRenderOwnedSections() {
        return this.farTerrainProvider.renderOwnedSections();
    }

    public long getRendererProviderDrawnSolidSections() {
        return this.farTerrainProvider.providerDrawnSolidSections();
    }

    public long getRendererProviderDrawnCutoutSections() {
        return this.farTerrainProvider.providerDrawnCutoutSections();
    }

    public long getRendererProviderStaleUploadRejections() {
        return this.farTerrainProvider.staleUploadRejections();
    }

    public long getRendererProviderParentSuppressedSections() {
        return this.farTerrainProvider.parentSuppressedSections();
    }

    public long getRendererProviderRenderListLength() {
        return this.farTerrainProvider.providerRenderListLength();
    }

    public long getRendererProviderRenderListEpoch() {
        return this.farTerrainProvider.providerRenderListEpoch();
    }

    public long getRendererProviderRenderListStaleSkips() {
        return this.farTerrainProvider.providerRenderListStaleSkips();
    }

    public long getRendererProviderCommandGenerationCount() {
        return this.farTerrainProvider.providerCommandGenerationCount();
    }

    public long getRendererProviderTraversalBypassCount() {
        return this.farTerrainProvider.providerTraversalBypassCount();
    }

    public String getRendererProviderRenderAuthorityVerdict() {
        return this.farTerrainProvider.providerRenderAuthorityVerdict();
    }

    public long getRendererProviderCutoutPassMeshes() {
        return this.farTerrainProvider.diagnostics().cutoutPassMeshes();
    }

    public long getRendererProviderFluidPassMeshes() {
        return this.farTerrainProvider.diagnostics().fluidPassMeshes();
    }

    public long getRendererProviderTranslucentPassMeshes() {
        return this.farTerrainProvider.diagnostics().translucentPassMeshes();
    }

    public String getRendererProviderTerrainCoverageVerdict() {
        return this.farTerrainProvider.diagnostics().terrainCoverageVerdict();
    }

    public String getRendererProviderVisualSourceVerdict() {
        return this.farTerrainProvider.diagnostics().visualSourceVerdict();
    }

    public String getRendererProviderSodiumMaterialParityVerdict() {
        return this.farTerrainProvider.diagnostics().sodiumMaterialParityVerdict();
    }

    public String getRendererProviderFogDepthParityVerdict() {
        if (RenderCorrectnessDiagnostics.renderFrameTotal.sum() == 0L) {
            return "UNKNOWN_NO_RENDER_FRAMES";
        }
        if (RenderCorrectnessDiagnostics.depthGuardSkips.sum() > 0L
                || RenderCorrectnessDiagnostics.projectionGuardSkips.sum() > 0L
                || RenderCorrectnessDiagnostics.renderOpaqueStartTotal.sum()
                != RenderCorrectnessDiagnostics.renderOpaqueEndTotal.sum()) {
            return "FAIL";
        }
        return "PASS";
    }

    public String getRendererProviderVisualArtifactVerdict() {
        VoxyTerrainProviderDiagnostics diagnostics = this.farTerrainProvider.diagnostics();
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(diagnostics.terrainCoverageVerdict())) {
            return diagnostics.terrainCoverageVerdict();
        }
        if (!VoxyFarTerrainProvider.PASS_VISUAL_SOURCE_CORRECTNESS.equals(diagnostics.visualSourceVerdict())) {
            return diagnostics.visualSourceVerdict();
        }
        if (diagnostics.boundaryMissingRequiredCells() > 0) {
            return VoxyFarTerrainProvider.FAIL_GAP;
        }
        if (diagnostics.invalidRenderedSections() > 0 || diagnostics.unsupportedPassRenderedSections() > 0) {
            return VoxyFarTerrainProvider.FAIL_INVALID_RENDERED;
        }
        if (diagnostics.parentChildVisualConflicts() > 0) {
            return VoxyFarTerrainProvider.FAIL_OWNERSHIP_CONFLICT;
        }
        if (RenderCorrectnessDiagnostics.oreLeakSurfaceRepresentatives.sum() > 0L) {
            return VoxyFarTerrainProvider.FAIL_ORE_LEAK;
        }
        if (!"PASS".equals(diagnostics.sodiumMaterialParityVerdict())) {
            return VoxyFarTerrainProvider.FAIL_MATERIAL_PARITY;
        }
        if (!"PASS".equals(this.getRendererProviderFogDepthParityVerdict())) {
            return VoxyFarTerrainProvider.FAIL_FOG_DEPTH_PARITY;
        }
        return VoxyFarTerrainProvider.PASS_VISUAL_SOURCE_CORRECTNESS;
    }

    private VoxyFarTerrainProviderSnapshot createProviderSnapshot() {
        return new VoxyFarTerrainProviderSnapshot(
                VoxyHandoffPolicy.renderDistanceSliderMode(),
                VoxyHandoffPolicy.visualTerrainDistanceChunks(),
                VoxyHandoffPolicy.realRenderDistanceChunks(),
                VoxyHandoffPolicy.overlapChunks(),
                VoxyHandoffPolicy.handoffStartChunks(),
                VoxyHandoffPolicy.voxyLodEndChunks(),
                VoxyClient.sodiumChunkRenderingEnabled(),
                IrisUtil.irisShaderPackEnabled()
        );
    }

    public String getRendererGpuAttributionJson() {
        return VoxyGpuAttribution.latestJson();
    }

    public void shutdown() {
        RenderCorrectnessDiagnostics.call("render_system", "shutdown", "start", this.pipeline.getClass().getSimpleName());
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                RenderResourceReuse.giveBackGeometryBuffer(((BasicSectionGeometryData)this.geometryData).getGeometryBuffer());
            }

            this.chunkBoundRenderer.free();

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
        RenderCorrectnessDiagnostics.call("render_system", "shutdown", "done", this.pipeline.getClass().getSimpleName());
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
