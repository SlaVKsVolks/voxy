package me.cortex.voxy.commonImpl.serverlod;

public record ServerLodTileKey(String dimension, int lodLevel, int sectionX, int sectionY, int sectionZ, int generatorVersion) {
    public String stableId() {
        return this.dimension + "|" + this.lodLevel + "|" + this.sectionX + "|" + this.sectionY + "|" + this.sectionZ + "|" + this.generatorVersion;
    }

    public String safeFileName() {
        return ServerLodConstants.sha256Hex(stableId());
    }
}
