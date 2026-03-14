package com.cache.cache;

public enum EvictionPolicy {
    LRU,
    LFU;

    public static EvictionPolicy from(String s) {
        return switch (s.toUpperCase()) {
            case "LFU" -> LFU;
            default    -> LRU;
        };
    }
}
