package com.example.minicassandra;

import com.example.minicassandra.cluster.MiniCassandraCluster;
import com.example.minicassandra.config.DatabaseConfig;

import java.nio.file.Path;
import java.util.Map;

public final class MiniCassandraClusterTest {
    public static void main(String[] args) {
        Path dir = TestSupport.tempDir("cluster-test-");
        try {
            DatabaseConfig config = new DatabaseConfig(8080, dir.toString(), 3, 3, 2, 2, 10, 32);
            MiniCassandraCluster cluster = new MiniCassandraCluster(config, dir);
            cluster.put("users", "u1", "name", "Suhas");
            cluster.put("users", "u1", "city", "Delhi");
            TestSupport.assertEquals("Suhas", cluster.get("users", "u1", "name").orElseThrow());
            Map<String, String> row = cluster.getRow("users", "u1");
            TestSupport.assertEquals("Delhi", row.get("city"));

            cluster.markNodeDown("node-1");
            cluster.put("users", "u2", "name", "Alice");
            TestSupport.assertEquals("Alice", cluster.get("users", "u2", "name").orElseThrow());
            cluster.markNodeUp("node-1");
            TestSupport.assertEquals("Alice", cluster.get("users", "u2", "name").orElseThrow());

            cluster.delete("users", "u1", "city");
            TestSupport.assertTrue(cluster.get("users", "u1", "city").isEmpty(), "Expected deleted value");
            System.out.println("[PASS] MiniCassandraClusterTest");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }
}
