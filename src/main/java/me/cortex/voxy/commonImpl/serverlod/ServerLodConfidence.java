package me.cortex.voxy.commonImpl.serverlod;

public enum ServerLodConfidence {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    REAL(3);

    private final int precedence;

    ServerLodConfidence(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return this.precedence;
    }
}
