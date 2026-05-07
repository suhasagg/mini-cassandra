package com.example.minicassandra.cluster;

import com.example.minicassandra.config.DatabaseConfig;
import com.example.minicassandra.model.Cell;
import com.example.minicassandra.util.TimestampGenerator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MiniCassandraCluster {
    private final DatabaseConfig config;
    private final Map<String, DatabaseNode> nodes;
    private final ConsistentHashRing ring;
    private final Map<String, List<Hint>> hintsByTarget;

    public MiniCassandraCluster(DatabaseConfig config, Path dataDir) {
        this.config = config;
        this.nodes = new LinkedHashMap<>();
        List<String> nodeIds = new ArrayList<>();
        for (int i = 0; i < config.nodeCount(); i++) {
            String nodeId = "node-" + i;
            nodeIds.add(nodeId);
            nodes.put(nodeId, new DatabaseNode(nodeId, dataDir.resolve(nodeId), config.memtableMaxEntries()));
        }
        this.ring = new ConsistentHashRing(nodeIds, config.virtualNodes());
        this.hintsByTarget = new ConcurrentHashMap<>();
    }

    public void put(String table, String key, String column, String value) {
        Cell cell = new Cell(table, key, column, value, TimestampGenerator.nextMicros(), false);
        writeToReplicas(cell);
    }

    public void delete(String table, String key, String column) {
        Cell cell = new Cell(table, key, column, null, TimestampGenerator.nextMicros(), true);
        writeToReplicas(cell);
    }

    public Optional<String> get(String table, String key, String column) {
        List<String> replicas = ring.replicasFor(key, config.replicationFactor());
        List<ReplicaReadResult> results = new ArrayList<>();
        int responses = 0;
        for (String nodeId : replicas) {
            DatabaseNode node = nodes.get(nodeId);
            if (!node.isUp()) continue;
            responses++;
            results.add(new ReplicaReadResult(nodeId, node.readCell(table, key, column)));
        }
        if (responses < config.readQuorum()) {
            throw new QuorumException("Read quorum failed. Required=" + config.readQuorum() + ", actual=" + responses);
        }
        Cell latest = latestCell(results);
        performReadRepair(replicas, table, key, column, latest, results);
        if (latest == null || latest.tombstone()) return Optional.empty();
        return Optional.ofNullable(latest.value());
    }

    public Map<String, String> getRow(String table, String key) {
        List<String> replicas = ring.replicasFor(key, config.replicationFactor());
        int responses = 0;
        Map<String, Cell> latestByColumn = new HashMap<>();

        for (String nodeId : replicas) {
            DatabaseNode node = nodes.get(nodeId);
            if (!node.isUp()) continue;
            responses++;
            Map<String, Cell> row = node.readRowCells(table, key);
            for (Map.Entry<String, Cell> entry : row.entrySet()) {
                latestByColumn.merge(entry.getKey(), entry.getValue(), (a, b) -> b.isNewerThan(a) ? b : a);
            }
        }

        if (responses < config.readQuorum()) {
            throw new QuorumException("Row read quorum failed. Required=" + config.readQuorum() + ", actual=" + responses);
        }

        Map<String, String> result = new LinkedHashMap<>();
        latestByColumn.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Cell cell = entry.getValue();
                    if (!cell.tombstone()) result.put(entry.getKey(), cell.value());
                });
        return result;
    }

    public void markNodeDown(String nodeId) {
        node(nodeId).markDown();
    }

    public void markNodeUp(String nodeId) {
        DatabaseNode node = node(nodeId);
        node.markUp();
        replayHints(nodeId);
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeCount", config.nodeCount());
        result.put("replicationFactor", config.replicationFactor());
        result.put("readQuorum", config.readQuorum());
        result.put("writeQuorum", config.writeQuorum());
        List<Map<String, Object>> nodeStatuses = new ArrayList<>();
        for (DatabaseNode node : nodes.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.nodeId());
            item.put("up", node.isUp());
            item.put("memtableSize", node.memtableSize());
            item.put("sstableCount", node.sstableCount());
            item.put("pendingHints", hintsByTarget.getOrDefault(node.nodeId(), Collections.emptyList()).size());
            nodeStatuses.add(item);
        }
        result.put("nodes", nodeStatuses);
        return result;
    }

    public Map<String, Object> ringStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokensPreview", ring.describe(30));
        result.put("config", Map.of(
                "virtualNodes", config.virtualNodes(),
                "replicationFactor", config.replicationFactor()
        ));
        return result;
    }

    public void flushAll() {
        nodes.values().forEach(DatabaseNode::flush);
    }

    public void compactAll() {
        nodes.values().forEach(DatabaseNode::compact);
    }

    public List<String> replicasFor(String key) {
        return ring.replicasFor(key, config.replicationFactor());
    }

    private void writeToReplicas(Cell cell) {
        List<String> replicas = ring.replicasFor(cell.partitionKey(), config.replicationFactor());
        int acknowledgements = 0;
        for (String nodeId : replicas) {
            DatabaseNode node = nodes.get(nodeId);
            if (node.isUp()) {
                node.write(cell);
                acknowledgements++;
            } else {
                storeHint(nodeId, cell);
            }
        }
        if (acknowledgements < config.writeQuorum()) {
            throw new QuorumException("Write quorum failed. Required=" + config.writeQuorum() + ", actual=" + acknowledgements);
        }
    }

    private Cell latestCell(List<ReplicaReadResult> results) {
        Cell latest = null;
        for (ReplicaReadResult result : results) {
            if (result.cell().isPresent() && result.cell().get().isNewerThan(latest)) {
                latest = result.cell().get();
            }
        }
        return latest;
    }

    private void performReadRepair(
            List<String> replicas,
            String table,
            String key,
            String column,
            Cell latest,
            List<ReplicaReadResult> observed
    ) {
        if (latest == null) return;
        Map<String, Optional<Cell>> byNode = new HashMap<>();
        for (ReplicaReadResult result : observed) {
            byNode.put(result.nodeId(), result.cell());
        }
        for (String nodeId : replicas) {
            DatabaseNode node = nodes.get(nodeId);
            if (!node.isUp()) continue;
            Optional<Cell> existing = byNode.getOrDefault(nodeId, Optional.empty());
            if (existing.isEmpty() || latest.isNewerThan(existing.get())) {
                node.write(latest);
            }
        }
    }

    private void storeHint(String nodeId, Cell cell) {
        hintsByTarget.computeIfAbsent(nodeId, ignored -> new CopyOnWriteArrayList<>()).add(new Hint(nodeId, cell));
    }

    private void replayHints(String nodeId) {
        List<Hint> hints = hintsByTarget.remove(nodeId);
        if (hints == null || hints.isEmpty()) return;
        DatabaseNode node = nodes.get(nodeId);
        if (!node.isUp()) {
            hintsByTarget.put(nodeId, hints);
            return;
        }
        for (Hint hint : hints) {
            node.write(hint.cell());
        }
    }

    private DatabaseNode node(String nodeId) {
        DatabaseNode node = nodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        return node;
    }
}
