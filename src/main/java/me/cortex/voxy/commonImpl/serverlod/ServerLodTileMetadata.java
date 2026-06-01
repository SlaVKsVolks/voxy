package me.cortex.voxy.commonImpl.serverlod;

public record ServerLodTileMetadata(
        ServerLodTileKey key,
        long epoch,
        String contentHash,
        ServerLodSourceKind sourceKind,
        ServerLodConfidence confidence,
        ServerLodLightKind lightKind,
        boolean parentComplete,
        boolean childComplete,
        int compressedBytes
) {
    public boolean trustedFinal() {
        return this.sourceKind.isReal() && this.confidence == ServerLodConfidence.REAL && this.lightKind.trustedForFinalData();
    }

    public boolean canReplace(ServerLodTileMetadata current) {
        if (current == null) {
            return true;
        }
        if (this.epoch < current.epoch) {
            return false;
        }
        if (!this.sourceKind.canReplace(current.sourceKind)) {
            return false;
        }
        if (this.confidence.precedence() < current.confidence.precedence() && this.epoch <= current.epoch) {
            return false;
        }
        if (current.trustedFinal() && !this.trustedFinal()) {
            return false;
        }
        return this.epoch > current.epoch || !this.contentHash.equals(current.contentHash);
    }
}
