package me.cortex.voxy.commonImpl.serverlod;

public enum ServerLodLightKind {
    REAL_SKY(true, true),
    REAL_ZERO_SKY(true, true),
    APPROX_SKY(false, false),
    SYNTHETIC_SURFACE(false, false),
    MISSING(false, false);

    private final boolean trusted;
    private final boolean real;

    ServerLodLightKind(boolean trusted, boolean real) {
        this.trusted = trusted;
        this.real = real;
    }

    public boolean trustedForFinalData() {
        return this.trusted;
    }

    public boolean isReal() {
        return this.real;
    }
}
