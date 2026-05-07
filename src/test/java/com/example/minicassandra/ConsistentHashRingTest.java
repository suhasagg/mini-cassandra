package com.example.minicassandra;

import com.example.minicassandra.cluster.ConsistentHashRing;

import java.util.List;

public final class ConsistentHashRingTest {
    public static void main(String[] args) {
        ConsistentHashRing ring = new ConsistentHashRing(List.of("node-0", "node-1", "node-2"), 32);
        List<String> replicas = ring.replicasFor("user-1", 3);
        TestSupport.assertEquals(3, replicas.size());
        TestSupport.assertTrue(replicas.contains("node-0") || replicas.contains("node-1") || replicas.contains("node-2"), "Expected known node");
        TestSupport.assertEquals(replicas, ring.replicasFor("user-1", 3));
        System.out.println("[PASS] ConsistentHashRingTest");
    }
}
