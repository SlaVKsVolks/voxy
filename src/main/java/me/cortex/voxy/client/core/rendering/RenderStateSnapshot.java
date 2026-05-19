package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public record RenderStateSnapshot(
        long sequence,
        int frameId,
        int width,
        int height,
        double cameraX,
        double cameraY,
        double cameraZ,
        float sectionRenderDistance,
        Matrix4f vanillaProjection,
        Matrix4f projection,
        Matrix4f modelView,
        Matrix4f viewProjection,
        Matrix4f previousProjection,
        Matrix4f previousModelView,
        Matrix4f previousViewProjection
) {
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final RenderStateSnapshot IDENTITY_SNAPSHOT = new RenderStateSnapshot(
            0,
            0,
            1,
            1,
            0.0,
            0.0,
            0.0,
            0.0f,
            new Matrix4f(),
            new Matrix4f(),
            new Matrix4f(),
            new Matrix4f(),
            new Matrix4f(),
            new Matrix4f(),
            new Matrix4f()
    );

    public static RenderStateSnapshot identity() {
        return IDENTITY_SNAPSHOT;
    }

    public static RenderStateSnapshot capture(Viewport<?> viewport, RenderStateSnapshot previous, float sectionRenderDistance) {
        RenderStateSnapshot safePrevious = previous == null ? IDENTITY_SNAPSHOT : previous;
        Matrix4f projection = safeCopy(viewport.projection);
        Matrix4f modelView = safeCopy(viewport.modelView);
        Matrix4f viewProjection = safeCopy(viewport.MVP);
        return new RenderStateSnapshot(
                safePrevious.sequence + 1,
                viewport.frameId,
                viewport.width,
                viewport.height,
                viewport.cameraX,
                viewport.cameraY,
                viewport.cameraZ,
                sectionRenderDistance,
                safeCopy(viewport.vanillaProjection),
                projection,
                modelView,
                viewProjection,
                new Matrix4f(safePrevious.projection),
                new Matrix4f(safePrevious.modelView),
                new Matrix4f(safePrevious.viewProjection)
        );
    }

    public static Matrix4f safeCopy(Matrix4fc source) {
        if (source == null) {
            return new Matrix4f(IDENTITY);
        }
        Matrix4f copy = new Matrix4f(source);
        if (!copy.isFinite()) {
            return new Matrix4f(IDENTITY);
        }
        return copy;
    }

    public static Matrix4f safeInvert(Matrix4fc source) {
        Matrix4f copy = safeCopy(source);
        if (!copy.invert().isFinite()) {
            return new Matrix4f(IDENTITY);
        }
        return copy;
    }
}
