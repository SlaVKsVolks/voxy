package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        //Note this is where will choose/create e.g. IrisRenderPipeline or normal pipeline
        RenderCorrectnessDiagnostics.call("pipeline_factory", "createPipeline", "start",
                "irisInstalled=" + IrisUtil.IRIS_INSTALLED + " shaderSupport=" + IrisUtil.SHADER_SUPPORT);
        AbstractRenderPipeline pipeline = null;
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            pipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
        }
        if (pipeline == null) {
            RenderCorrectnessDiagnostics.call("pipeline_factory", "createPipeline", "fallback", "NormalRenderPipeline");
            pipeline = new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
        } else {
            RenderCorrectnessDiagnostics.call("pipeline_factory", "createPipeline", "selected", pipeline.getClass().getSimpleName());
        }
        return pipeline;
    }

    private static AbstractRenderPipeline createIrisPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            RenderCorrectnessDiagnostics.call("pipeline_factory", "createIrisPipeline", "unavailable", "iris_pipeline_null");
            Logger.warn("Iris pipeline unavailable at Voxy pipeline creation time");
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                RenderCorrectnessDiagnostics.call("pipeline_factory", "createIrisPipeline", "unavailable", "voxy_pipeline_data_null");
                Logger.warn("Iris pipeline is present but Voxy pipeline data is null. Falling back to NormalRenderPipeline.");
                return null;
            }
            Logger.info("Creating voxy iris render pipeline");
            try {
                RenderCorrectnessDiagnostics.call("pipeline_factory", "createIrisPipeline", "selected", "IrisVoxyRenderPipeline");
                return new IrisVoxyRenderPipeline(properties, pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                RenderCorrectnessDiagnostics.call("pipeline_factory", "createIrisPipeline", "failed", e.getClass().getSimpleName());
                Logger.error("Failed to create iris render pipeline", e);
                IrisUtil.disableIrisShaders();
                return null;
            }
        }
        RenderCorrectnessDiagnostics.call("pipeline_factory", "createIrisPipeline", "unavailable", "missing_bridge_interface:" + irisPipe.getClass().getName());
        Logger.warn("Iris pipeline does not expose Voxy bridge interface: " + irisPipe.getClass().getName());
        return null;
    }
}
