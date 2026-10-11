package com.creator01.simpleitemcleaner.policy;

public enum ItemPolicy {
    CONTROLLED, VANILLA, PERMANENT;

    public boolean preventsExpiration() { return this != VANILLA; }
    public boolean allowsCleanup() { return this == CONTROLLED; }
}
