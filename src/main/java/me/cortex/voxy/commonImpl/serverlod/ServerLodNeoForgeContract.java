package me.cortex.voxy.commonImpl.serverlod;

import java.util.List;
import java.util.StringJoiner;

public final class ServerLodNeoForgeContract {
    public static final String CONTRACT_VERSION = "voxy.neoforge.server_authored_lod_contract.v1";
    public static final String ADAPTER_ID = "neoforge_chunk_section_lod";
    public static final String FAMILY_ID = "terrain_lod";

    private ServerLodNeoForgeContract() {}

    public static ContractSnapshot snapshot() {
        List<ContractStage> stages = List.of(
                ContractStage.SERVER_THREAD_SNAPSHOT,
                ContractStage.WORKER_COMPUTE_ONLY,
                ContractStage.SERVER_THREAD_PUBLISH
        );
        boolean pass = forbidWorkerMinecraftMutation()
                && stages.contains(ContractStage.SERVER_THREAD_SNAPSHOT)
                && stages.contains(ContractStage.WORKER_COMPUTE_ONLY)
                && stages.contains(ContractStage.SERVER_THREAD_PUBLISH);
        return new ContractSnapshot(
                CONTRACT_VERSION,
                FAMILY_ID,
                ADAPTER_ID,
                stages,
                forbidWorkerMinecraftMutation(),
                true,
                true,
                true,
                true,
                pass
        );
    }

    public static boolean forbidWorkerMinecraftMutation() {
        return true;
    }

    public static String json() {
        return snapshot().toJson();
    }

    public enum ContractStage {
        SERVER_THREAD_SNAPSHOT,
        WORKER_COMPUTE_ONLY,
        SERVER_THREAD_PUBLISH
    }

    public record ContractSnapshot(
            String schema,
            String family,
            String adapter,
            List<ContractStage> stages,
            boolean workerMinecraftMutationForbidden,
            boolean immutableSnapshotsRequired,
            boolean serverThreadPublishRequired,
            boolean boundedApplyBudgetRequired,
            boolean validatedTilesOnly,
            boolean pass
    ) {
        public String toJson() {
            return "{"
                    + "\"schema\":\"" + escape(this.schema) + "\","
                    + "\"family\":\"" + escape(this.family) + "\","
                    + "\"adapter\":\"" + escape(this.adapter) + "\","
                    + "\"stages\":" + stagesJson(this.stages) + ","
                    + "\"worker_minecraft_mutation_forbidden\":" + this.workerMinecraftMutationForbidden + ","
                    + "\"immutable_snapshots_required\":" + this.immutableSnapshotsRequired + ","
                    + "\"server_thread_publish_required\":" + this.serverThreadPublishRequired + ","
                    + "\"bounded_apply_budget_required\":" + this.boundedApplyBudgetRequired + ","
                    + "\"validated_tiles_only\":" + this.validatedTilesOnly + ","
                    + "\"pass\":" + this.pass
                    + "}";
        }
    }

    private static String stagesJson(List<ContractStage> stages) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (ContractStage stage : stages) {
            joiner.add("\"" + stage.name() + "\"");
        }
        return joiner.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
