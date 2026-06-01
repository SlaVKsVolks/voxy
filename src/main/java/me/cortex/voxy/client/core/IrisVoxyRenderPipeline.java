package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_FUNC;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_REF;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_VALUE_MASK;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_WRITEMASK;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL11C.glGetBooleanv;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());

    private final FullscreenBlit shaderDepthHackFixTransformBlit;

    private final GlBuffer shaderUniforms;

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        this.attachCurrentDrawTargets("constructor");

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }

        if (!this.data.skipShaderDepthHackFix) {
            this.shaderDepthHackFixTransformBlit = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
        } else {
            this.shaderDepthHackFixTransformBlit = null;
        }

        this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();

        if (this.shaderDepthHackFixTransformBlit != null) {
            this.shaderDepthHackFixTransformBlit.delete();
        }

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        RenderCorrectnessDiagnostics.call("iris_pipeline", "preSetup", "start", this.shaderUniforms == null ? "no_shader_uniforms" : "shader_uniforms");
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
        RenderCorrectnessDiagnostics.call("iris_pipeline", "preSetup", "done", this.shaderUniforms == null ? "no_shader_uniforms" : "shader_uniforms_uploaded");
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "setup", "start", "sourceFb=" + sourceFramebuffer + " src=" + srcWidth + "x" + srcHeight);
        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);
        this.attachCurrentDrawTargets("setup");

        if (false) {//TODO: only do this if shader specifies
            //Clear the colour component
            glBindFramebuffer(GL_FRAMEBUFFER, this.fb.framebuffer.id);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        if (!this.initDepthStencil(sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height)) {
            RenderCorrectnessDiagnostics.call("iris_pipeline", "setup", "failed", "initDepthStencil");
            return 0;
        }
        RenderCorrectnessDiagnostics.call("iris_pipeline", "setup", "done", "depthTex=" + this.fb.getDepthTex().id);
        return this.fb.getDepthTex().id;
    }

    private void attachCurrentDrawTargets(String reason) {
        boolean changed = this.data.refreshDrawTargets();

        var oDT = this.data.opaqueDrawTargets;
        int[] binding = new int[oDT.length];
        for (int i = 0; i < oDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

        var tDT = this.data.translucentDrawTargets;
        binding = new int[tDT.length];
        for (int i = 0; i < tDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

        this.fb.framebuffer.verify();
        this.fbTranslucent.framebuffer.verify();
        RenderCorrectnessDiagnostics.call("iris_pipeline", "attachDrawTargets", changed ? "changed" : "stable", reason);
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "postOpaquePreTranslucent", "start", "sourceFb=" + sourceFrameBuffer);
        if (this.shaderDepthHackFixTransformBlit != null) {
            var state = IrisPassState.capture();
            try {
                this.fb.bind();
                glEnable(GL_DEPTH_TEST);
                glColorMask(false, false, false, false);
                glDepthMask(true);
                glDepthFunc(GL_ALWAYS);
                glStencilFunc(GL_EQUAL, 0, 0xFF);//set the depth to 1 where the mask is 0
                this.shaderDepthHackFixTransformBlit.blit();
            } finally {
                state.restore();
            }
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        if (true) {//TODO: make shader specified
            if (false) {//TODO: only do this if shader specifies
                glBindFramebuffer(GL_FRAMEBUFFER, this.fbTranslucent.framebuffer.id);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
        } else {
            msk |= GL_COLOR_BUFFER_BIT;
        }
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
        RenderCorrectnessDiagnostics.call("iris_pipeline", "postOpaquePreTranslucent", "done", "translucentFb=" + this.fbTranslucent.framebuffer.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "finish", "start", "sourceFb=" + sourceFrameBuffer);
        if (this.data.renderToVanillaDepth && srcWidth == viewport.width  && srcHeight == viewport.height) {//We can only depthblit out if destination size is the same
            var state = IrisPassState.capture();
            try {
                glColorMask(false, false, false, false);
                AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                        this.fbTranslucent.getDepthTex().id, sourceFrameBuffer,
                        viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
            } finally {
                state.restore();
            }
        } else {
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
        RenderCorrectnessDiagnostics.call("iris_pipeline", "finish", "done", "sourceFb=" + sourceFrameBuffer);
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);
            RenderCorrectnessDiagnostics.call("iris_pipeline", "bindUniforms", "bound", "bindingPoint=" + bindingPoint);
        } else {
            RenderCorrectnessDiagnostics.call("iris_pipeline", "bindUniforms", "skip", "no_shader_uniforms");
        }
    }

    private void doBindings() {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "doBindings", "start", "");
        this.bindUniforms();
        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(SSBO_BINDING_BASE);
            RenderCorrectnessDiagnostics.call("iris_pipeline", "doBindings", "ssbo_bound", "base=" + SSBO_BINDING_BASE);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(IMAGE_BINDING_BASE);
            RenderCorrectnessDiagnostics.call("iris_pipeline", "doBindings", "image_bound", "base=" + IMAGE_BINDING_BASE);
        }
    }

    private record IrisPassState(
            boolean depthTest,
            boolean stencilTest,
            boolean blend,
            boolean depthMask,
            int depthFunc,
            int stencilFunc,
            int stencilRef,
            int stencilValueMask,
            int stencilWriteMask,
            boolean[] colorMask
    ) {
        private static IrisPassState capture() {
            boolean[] colorMask = new boolean[4];
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < colorMask.length; i++) {
                    colorMask[i] = mask.get(i) != 0;
                }
            }
            return new IrisPassState(
                    glIsEnabled(GL_DEPTH_TEST),
                    glIsEnabled(GL_STENCIL_TEST),
                    glIsEnabled(GL_BLEND),
                    glGetBoolean(GL_DEPTH_WRITEMASK),
                    glGetInteger(GL_DEPTH_FUNC),
                    glGetInteger(GL_STENCIL_FUNC),
                    glGetInteger(GL_STENCIL_REF),
                    glGetInteger(GL_STENCIL_VALUE_MASK),
                    glGetInteger(GL_STENCIL_WRITEMASK),
                    colorMask
            );
        }

        private void restore() {
            restoreCapability(GL_DEPTH_TEST, this.depthTest);
            restoreCapability(GL_STENCIL_TEST, this.stencilTest);
            restoreCapability(GL_BLEND, this.blend);
            glDepthMask(this.depthMask);
            glDepthFunc(this.depthFunc);
            glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilValueMask);
            glStencilMask(this.stencilWriteMask);
            glColorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);
        }

        private static void restoreCapability(int capability, boolean enabled) {
            if (enabled) {
                glEnable(capability);
            } else {
                glDisable(capability);
            }
        }
    }
    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "setupAndBindOpaque", "start", "fb=" + this.fb.framebuffer.id);
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        RenderCorrectnessDiagnostics.call("iris_pipeline", "setupAndBindTranslucent", "start", "fb=" + this.fbTranslucent.framebuffer.id);
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    private static final int UNIFORM_BINDING_POINT = readBindingBase("voxy.iris.uniformBindingPoint", 7);
    private static final int SSBO_BINDING_BASE = readBindingBase("voxy.iris.ssboBindingBase", 10);
    private static final int IMAGE_BINDING_BASE = readBindingBase("voxy.iris.imageBindingBase", 6);

    private static int readBindingBase(String property, int fallback) {
        int value = Integer.getInteger(property, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(property + " must be >= 0, got " + value);
        }
        return value;
    }

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE ").append(SSBO_BINDING_BASE).append("\n");
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX ").append(IMAGE_BINDING_BASE).append("\n");
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }



    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        builder.append(this.data.opaqueFragPatch());

        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        if (this.data.TAA == null) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}
