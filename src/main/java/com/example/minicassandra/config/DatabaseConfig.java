package com.example.minicassandra.config;

public record DatabaseConfig(
        int port,
        String dataDir,
        int nodeCount,
        int replicationFactor,
        int readQuorum,
        int writeQuorum,
        int memtableMaxEntries,
        int virtualNodes
) {
    public DatabaseConfig {
        if (port <= 0) throw new IllegalArgumentException("port must be positive");
        if (nodeCount <= 0) throw new IllegalArgumentException("nodeCount must be positive");
        if (replicationFactor <= 0) throw new IllegalArgumentException("replicationFactor must be positive");
        if (replicationFactor > nodeCount) throw new IllegalArgumentException("replicationFactor cannot exceed nodeCount");
        if (readQuorum <= 0) throw new IllegalArgumentException("readQuorum must be positive");
        if (writeQuorum <= 0) throw new IllegalArgumentException("writeQuorum must be positive");
        if (readQuorum > replicationFactor) throw new IllegalArgumentException("readQuorum cannot exceed replicationFactor");
        if (writeQuorum > replicationFactor) throw new IllegalArgumentException("writeQuorum cannot exceed replicationFactor");
        if (memtableMaxEntries <= 0) throw new IllegalArgumentException("memtableMaxEntries must be positive");
        if (virtualNodes <= 0) throw new IllegalArgumentException("virtualNodes must be positive");
    }

    public static DatabaseConfig fromEnvironment() {
        int port = intEnv("PORT", 8080);
        String dataDir = stringEnv("DATA_DIR", "data");
        int nodes = intEnv("NODES", 3);
        int rf = intEnv("REPLICATION_FACTOR", Math.min(3, nodes));
        int readQuorum = intEnv("READ_QUORUM", rf / 2 + 1);
        int writeQuorum = intEnv("WRITE_QUORUM", rf / 2 + 1);
        int memtableMaxEntries = intEnv("MEMTABLE_MAX_ENTRIES", 1_000);
        int virtualNodes = intEnv("VIRTUAL_NODES", 64);
        return new DatabaseConfig(port, dataDir, nodes, rf, readQuorum, writeQuorum, memtableMaxEntries, virtualNodes);
    }

    private static String stringEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return value.trim();
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value.trim());
    }
}
