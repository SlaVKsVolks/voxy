package me.cortex.voxy.client.sodium.provider;

public record VoxyProviderMeshApproval(
        boolean approved,
        VoxyTerrainFailureReason reason,
        VoxyTerrainOwnership ownership,
        int passMask
) {
    public static VoxyProviderMeshApproval approved(VoxyTerrainOwnership ownership, int passMask) {
        return new VoxyProviderMeshApproval(true, VoxyTerrainFailureReason.NONE, ownership, passMask);
    }

    public static VoxyProviderMeshApproval rejected(VoxyTerrainFailureReason reason, VoxyTerrainOwnership ownership) {
        return new VoxyProviderMeshApproval(false, reason, ownership, 0);
    }
}
