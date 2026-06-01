package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL11C.GL_COLOR_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_WRITEMASK;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL11C.glGetBooleanv;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glViewport;

public final class RenderPassGuard {
    private final String passName;
    private final int previousDrawFramebuffer;
    private final int previousReadFramebuffer;
    private final int[] previousViewport;
    private final boolean[] previousColorMask;
    private final boolean previousDepthMask;
    private final int previousStencilMask;
    private boolean restored;

    private RenderPassGuard(String passName, int previousDrawFramebuffer, int previousReadFramebuffer, int[] previousViewport, boolean[] previousColorMask, boolean previousDepthMask, int previousStencilMask) {
        this.passName = passName;
        this.previousDrawFramebuffer = previousDrawFramebuffer;
        this.previousReadFramebuffer = previousReadFramebuffer;
        this.previousViewport = previousViewport;
        this.previousColorMask = previousColorMask;
        this.previousDepthMask = previousDepthMask;
        this.previousStencilMask = previousStencilMask;
    }

    public static RenderPassGuard capture(String passName, int previousFramebuffer, int[] previousViewport) {
        boolean[] colorMask = new boolean[4];
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < colorMask.length; i++) {
                colorMask[i] = mask.get(i) != 0;
            }
        }
        return new RenderPassGuard(
                passName,
                previousFramebuffer,
                glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
                previousViewport.clone(),
                colorMask,
                glGetBoolean(GL_DEPTH_WRITEMASK),
                glGetInteger(GL_STENCIL_WRITEMASK)
        );
    }

    public boolean validateFramebuffer(int framebuffer, int width, int height) {
        if (width <= 0 || height <= 0) {
            RenderCorrectnessDiagnostics.depthGuard(this.passName, "invalid_viewport", framebuffer, framebuffer, width, height);
            return false;
        }
        if (framebuffer == 0) {
            RenderCorrectnessDiagnostics.depthGuard(this.passName, "default_framebuffer_source", framebuffer, framebuffer, width, height);
            Logger.warn("Skipping Voxy render pass because the source framebuffer is the default framebuffer");
            return false;
        }
        int status = glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            RenderCorrectnessDiagnostics.depthGuard(this.passName, "incomplete_framebuffer_" + status, framebuffer, framebuffer, width, height);
            Logger.warn("Skipping Voxy render pass because framebuffer is incomplete", status);
            return false;
        }
        return true;
    }

    public void restore() {
        if (this.restored) {
            return;
        }
        this.restored = true;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, this.previousReadFramebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.previousDrawFramebuffer);
        glColorMask(this.previousColorMask[0], this.previousColorMask[1], this.previousColorMask[2], this.previousColorMask[3]);
        glDepthMask(this.previousDepthMask);
        glStencilMask(this.previousStencilMask);
        glViewport(this.previousViewport[0], this.previousViewport[1], this.previousViewport[2], this.previousViewport[3]);
    }
}
