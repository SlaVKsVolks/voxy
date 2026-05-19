package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.debug.RenderStateDiagnostics;
import me.cortex.voxy.client.core.rendering.RenderStateSnapshot;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {
    private static IGetVoxyRenderSystem getRenderSystemAccessor() {
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (!(levelRenderer instanceof IGetVoxyRenderSystem accessor)) {
            return null;
        }
        return accessor;
    }

    private static RenderStateSnapshot getSnapshot() {
        var getVrs = getRenderSystemAccessor();
        if (getVrs == null || getVrs.voxy$getRenderSystem() == null) {
            return RenderStateSnapshot.identity();
        }
        var viewport = getVrs.voxy$getRenderSystem().getViewport();
        if (viewport == null) {
            return RenderStateSnapshot.identity();
        }
        RenderStateSnapshot snapshot = viewport.getRenderStateSnapshot();
        RenderStateDiagnostics.captureUniformBridge("voxy_uniforms", snapshot);
        return snapshot;
    }

    private static Matrix4f safeCopy(Matrix4fc source) {
        return RenderStateSnapshot.safeCopy(source);
    }

    private static Matrix4f safeInvert(Matrix4fc source) {
        return RenderStateSnapshot.safeInvert(source);
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

    public static Matrix4f getViewProjection() {
        return safeCopy(getSnapshot().viewProjection());
    }

    public static Matrix4f getModelView() {
        return safeCopy(getSnapshot().modelView());
    }

    public static Matrix4f getProjection() {
        return safeCopy(getSnapshot().projection());
    }

    public static Matrix4f getPreviousViewProjection() {
        return safeCopy(getSnapshot().previousViewProjection());
    }

    public static Matrix4f getPreviousModelView() {
        return safeCopy(getSnapshot().previousModelView());
    }

    public static Matrix4f getPreviousProjection() {
        return safeCopy(getSnapshot().previousProjection());
    }

    public static Matrix4f getViewProjectionInverse() {
        return safeInvert(getSnapshot().viewProjection());
    }

    public static Matrix4f getModelViewInverse() {
        return safeInvert(getSnapshot().modelView());
    }

    public static Matrix4f getProjectionInverse() {
        return safeInvert(getSnapshot().projection());
    }

    public static int getRenderDistance() {
        RenderStateSnapshot snapshot = getSnapshot();
        if (snapshot.sequence() == 0) {
            return Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32);
        }
        return Math.round(snapshot.sectionRenderDistance() * 32);
    }

    public static void addUniforms(UniformHolder uniforms) {
        addUniformSafely("vxRenderDistance", () -> uniforms.uniform1i(PER_FRAME, "vxRenderDistance", VoxyUniforms::getRenderDistance));
        addUniformSafely("vxViewProj", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection));
        addUniformSafely("vxViewProjInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProjInv", VoxyUniforms::getViewProjectionInverse));
        addUniformSafely("vxViewProjPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxViewProjPrev", VoxyUniforms::getPreviousViewProjection));
        addUniformSafely("vxModelView", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView));
        addUniformSafely("vxModelViewInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelViewInv", VoxyUniforms::getModelViewInverse));
        addUniformSafely("vxModelViewPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxModelViewPrev", VoxyUniforms::getPreviousModelView));
        addUniformSafely("vxProj", () -> uniforms.uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection));
        addUniformSafely("vxProjInv", () -> uniforms.uniformMatrix(PER_FRAME, "vxProjInv", VoxyUniforms::getProjectionInverse));
        addUniformSafely("vxProjPrev", () -> uniforms.uniformMatrix(PER_FRAME, "vxProjPrev", VoxyUniforms::getPreviousProjection));

        /*
        if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
            uniforms
                    .uniform1f(PER_FRAME, "dhNearPlane", ()->16)//Presently hardcoded in voxy
                    .uniform1f(PER_FRAME, "dhFarPlane", ()->16*3000)//Presently hardcoded in voxy

                    .uniform1i(PER_FRAME, "dhRenderDistance", ()->Math.round(VoxyConfig.CONFIG.sectionRenderDistance*32*16))//In blocks
                    .uniformMatrix(PER_FRAME, "dhProjection", VoxyUniforms::getProjection)
                    .uniformMatrix(PER_FRAME, "dhProjectionInverse", VoxyUniforms::getProjectionInverse)
                    .uniformMatrix(PER_FRAME, "dhPreviousProjection", VoxyUniforms::getPreviousProjection);
        }*/
    }
}
