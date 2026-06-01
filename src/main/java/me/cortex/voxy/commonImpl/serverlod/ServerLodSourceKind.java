package me.cortex.voxy.commonImpl.serverlod;

public enum ServerLodSourceKind {
    CLIENT_PROVISIONAL(0, false),
    VALIDATED_CLIENT_UPLOAD(1, false),
    SERVER_SURFACE_PREVIEW(2, false),
    SERVER_REFINED_PREGEN(3, false),
    REAL_SERVER_CHUNK(4, true);

    private final int precedence;
    private final boolean real;

    ServerLodSourceKind(int precedence, boolean real) {
        this.precedence = precedence;
        this.real = real;
    }

    public int precedence() {
        return this.precedence;
    }

    public boolean isReal() {
        return this.real;
    }

    public boolean canReplace(ServerLodSourceKind current) {
        return this.precedence >= current.precedence;
    }
}
