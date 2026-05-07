package com.example.minicassandra.storage;

import com.example.minicassandra.util.HashUtil;

import java.util.BitSet;

public final class BloomFilter {
    private final BitSet bits;
    private final int bitSize;
    private final int hashFunctions;

    public BloomFilter(int expectedItems) {
        int safeExpectedItems = Math.max(1, expectedItems);
        this.bitSize = Math.max(128, safeExpectedItems * 16);
        this.hashFunctions = 7;
        this.bits = new BitSet(bitSize);
    }

    public void add(String key) {
        for (int i = 0; i < hashFunctions; i++) {
            bits.set(index(key, i));
        }
    }

    public boolean mightContain(String key) {
        for (int i = 0; i < hashFunctions; i++) {
            if (!bits.get(index(key, i))) {
                return false;
            }
        }
        return true;
    }

    private int index(String key, int seed) {
        int hash = HashUtil.hash32(key, seed);
        return Math.floorMod(hash, bitSize);
    }
}
