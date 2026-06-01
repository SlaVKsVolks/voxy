package me.cortex.voxy.commonImpl.serverlod;

public record ServerLodTile(ServerLodTileMetadata metadata, byte[] compressedPayload) {}
