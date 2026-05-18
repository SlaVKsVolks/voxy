package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final PreviousTracker PREV_VIEW_PROJ = new PreviousTracker(VoxyUniforms::getViewProjection);
    private static final PreviousTracker PREV_MODEL_VIEW = new PreviousTracker(VoxyUniforms::getModelView);
    private static final PreviousTracker PREV_PROJ = new PreviousTracker(VoxyUniforms::getProjection);

    private static IGetVoxyRenderSystem getRenderSystemAccessor() {
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (!(levelRenderer instanceof IGetVoxyRenderSystem accessor)) {
            return null;
        }
        return accessor;
    }

    private static Matrix4f safeCopy(Matrix4fc source) {
        Matrix4f copy = new Matrix4f(source);
        if (!copy.isFinite()) {
            return new Matrix4f(IDENTITY);
        }
        return copy;
    }

    private static Matrix4f safeInvert(Matrix4fc source) {
        Matrix4f copy = safeCopy(source);
        if (!copy.invert().isFinite()) {
            return new Matrix4f(IDENTITY);
        }
        return copy;
    }

    private static void addUniformSafely(String name, Runnable registration) {
        try {
            registration.run();
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.contains("Already added uniform")) {
                Logger.warn("Skipping duplicate uniform registration: ", name);
                return;
            }
            throw ex;
        }
    }

    public static Matrix4f getViewProjection() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = getRenderSystemAccessor();
        if (getVrs == null || getVrs.voxy$getRenderSystem() == null) {
            return new Matrix4f(IDENTITY);
        }
        var vrs = getVrs.voxy$getRenderSystem();
        return safeCopy(vrs.getViewport().MVP);
    }

    public static Matrix4f getModelView() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = getRenderSystemAccessor();
        if (getVrs == null || getVrs.voxy$getRenderSystem() == null) {
            return new Matrix4f(IDENTITY);
        }
        var vrs = getVrs.voxy$getRenderSystem();
        return safeCopy(vrs.getViewport().modelView);
    }

    public static Matrix4f getProjection() {//This is 1 frame late ;-; cries, since the update occurs _before_ the voxy render pipeline
        var getVrs = getRenderSystemAccessor();
        if (getVrs == null || getVrs.voxy$getRenderSystem() == null) {
            return new Matrix4f(IDENTITY);
        }
        var vrs = getVrs.voxy$getRenderSystem();
        var mat = vrs.getViewport().projection;
        if (mat == null) {
            return new Matrix4f(IDENTITY);
        }
        return safeCopy(mat);
    }

    public static Matrix4f getPreviousViewProjection() {
        return PREV_VIEW_PROJ.previous();
    }

    public static Matrix4f getPreviousModelView() {
        return PREV_MODEL_VIEW.previous();
    }

    public static Matrix4f getPreviousProjection() {
        return PREV_PROJ.previous();
    }

    public static void addUniforms(UniformHolder uniforms) {
        addUniformSafely("vxRenderDistance", () -> uniforms.uniform1i(PER_FRAME, "vxRenderDistance", () -> Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32)));
        addUniformSafely("vxViewProj", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection));
        addUniformSafely("vxViewProjInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProjInv", new Inverted(VoxyUniforms::getViewProjection)));
        addUniformSafely("vxViewProjPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProjPrev", new PreviousMat(VoxyUniforms::getViewProjection)));
        addUniformSafely("vxModelView", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView));
        addUniformSafely("vxModelViewInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelViewInv", new Inverted(VoxyUniforms::getModelView)));
        addUniformSafely("vxModelViewPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelViewPrev", new PreviousMat(VoxyUniforms::getModelView)));
        addUniformSafely("vxProj", () -> uniforms.uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection));
        addUniformSafely("vxProjInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxProjInv", new Inverted(VoxyUniforms::getProjection)));
        addUniformSafely("vxProjPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxProjPrev", new PreviousMat(VoxyUniforms::getProjection)));

        /*
        if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
            uniforms
                    .uniform1f(PER_FRAME, "dhNearPlane", ()->16)//Presently hardcoded in voxy
                    .uniform1f(PER_FRAME, "dhFarPlane", ()->16*3000)//Presently hardcoded in voxy

                    .uniform1i(PER_FRAME, "dhRenderDistance", ()->Math.round(VoxyConfig.CONFIG.sectionRenderDistance*32*16))//In blocks
                    .uniformMatrix(PER_FRAME, "dhProjection", VoxyUniforms::getProjection)
                    .uniformMatrix(PER_FRAME, "dhProjectionInverse", new Inverted(VoxyUniforms::getProjection))
                    .uniformMatrix(PER_FRAME, "dhPreviousProjection", new PreviousMat(VoxyUniforms::getProjection));
        }*/
    }




    private record Inverted(Supplier<Matrix4fc> parent) implements Supplier<Matrix4fc> {
        private Inverted(Supplier<Matrix4fc> parent) {
            this.parent = parent;
        }

        public Matrix4fc get() {
            return safeInvert(this.parent.get());
        }

        public Supplier<Matrix4fc> parent() {
            return this.parent;
        }
    }

    private static class PreviousMat implements Supplier<Matrix4fc> {
        private final Supplier<Matrix4fc> parent;
        private Matrix4f previous;

        PreviousMat(Supplier<Matrix4fc> parent) {
            this.parent = parent;
            this.previous = new Matrix4f();
        }

        public Matrix4fc get() {
            Matrix4f previous = this.previous;
            this.previous = new Matrix4f(this.parent.get());
            return previous;
        }
    }

    private static final class PreviousTracker {
        private final Supplier<Matrix4f> current;
        private Matrix4f previous = new Matrix4f();

        private PreviousTracker(Supplier<Matrix4f> current) {
            this.current = current;
        }

        private Matrix4f previous() {
            Matrix4f out = new Matrix4f(this.previous);
            this.previous = new Matrix4f(this.current.get());
            return out;
        }
    }
}
