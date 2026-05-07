package com.example.minicassandra;

import com.example.minicassandra.storage.BloomFilter;

public final class BloomFilterTest {
    public static void main(String[] args) {
        BloomFilter filter = new BloomFilter(100);
        filter.add("users:user-1:name");
        filter.add("users:user-2:name");
        TestSupport.assertTrue(filter.mightContain("users:user-1:name"), "Expected inserted key to be present");
        TestSupport.assertTrue(filter.mightContain("users:user-2:name"), "Expected inserted key to be present");
        System.out.println("[PASS] BloomFilterTest");
    }
}
