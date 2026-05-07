package com.example.minicassandra.cluster;

import com.example.minicassandra.model.Cell;

public record Hint(String targetNodeId, Cell cell) {
}
