package com.example.minicassandra.cluster;

import com.example.minicassandra.model.Cell;

import java.util.Optional;

public record ReplicaReadResult(String nodeId, Optional<Cell> cell) {
}
