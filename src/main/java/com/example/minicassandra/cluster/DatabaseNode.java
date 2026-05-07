package com.example.minicassandra.cluster;

import com.example.minicassandra.model.Cell;
import com.example.minicassandra.storage.LsmStorageEngine;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseNode {
    private final String nodeId;
    private final LsmStorageEngine storage;
    private final AtomicBoolean up = new AtomicBoolean(true);

    public DatabaseNode(String nodeId, Path nodeDir, int memtableMaxEntries) {
        this.nodeId = nodeId;
        this.storage = new LsmStorageEngine(nodeDir, memtableMaxEntries);
    }

    public String nodeId() {
        return nodeId;
    }

    public boolean isUp() {
        return up.get();
    }

    public void markDown() {
        up.set(false);
    }

    public void markUp() {
        up.set(true);
    }

    public void write(Cell cell) {
        ensureUp();
        storage.apply(cell);
    }

    public Optional<Cell> readCell(String table, String key, String column) {
        ensureUp();
        return storage.getCell(table, key, column);
    }

    public Map<String, Cell> readRowCells(String table, String key) {
        ensureUp();
        return storage.getRowCells(table, key);
    }

    public void flush() {
        storage.flush();
    }

    public void compact() {
        storage.compact();
    }

    public int sstableCount() {
        return storage.sstableCount();
    }

    public int memtableSize() {
        return storage.memtableSize();
    }

    private void ensureUp() {
        if (!isUp()) throw new IllegalStateException(nodeId + " is down");
    }
}
