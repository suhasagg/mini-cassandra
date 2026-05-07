package com.example.minicassandra.storage;

import com.example.minicassandra.model.Cell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LsmStorageEngine {
    private static final Pattern SSTABLE_DATA_PATTERN = Pattern.compile("sstable-(\\d+)\\.data");

    private final Path nodeDir;
    private final int memtableMaxEntries;
    private final Memtable memtable;
    private final WriteAheadLog wal;
    private final List<SSTableReader> sstables;
    private final AtomicInteger nextGeneration;

    public LsmStorageEngine(Path nodeDir, int memtableMaxEntries) {
        try {
            Files.createDirectories(nodeDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create node dir " + nodeDir, e);
        }
        this.nodeDir = nodeDir;
        this.memtableMaxEntries = memtableMaxEntries;
        this.memtable = new Memtable();
        try {
            this.wal = new WriteAheadLog(nodeDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create WAL", e);
        }
        this.sstables = loadSSTables(nodeDir);
        int maxGeneration = this.sstables.stream().mapToInt(SSTableReader::generation).max().orElse(0);
        this.nextGeneration = new AtomicInteger(maxGeneration + 1);
        recoverWal();
    }

    public synchronized void apply(Cell cell) {
        wal.append(cell);
        memtable.apply(cell);
        if (memtable.size() >= memtableMaxEntries) {
            flush();
        }
    }

    public synchronized Optional<Cell> getCell(String table, String partitionKey, String column) {
        Cell probe = new Cell(table, partitionKey, column, null, 0, true);
        String storageKey = probe.storageKey();
        Cell latest = null;

        Optional<Cell> memResult = memtable.get(storageKey);
        if (memResult.isPresent()) latest = memResult.get();

        for (SSTableReader reader : sstables) {
            Optional<Cell> diskResult = reader.get(storageKey);
            if (diskResult.isPresent() && diskResult.get().isNewerThan(latest)) {
                latest = diskResult.get();
            }
        }
        return Optional.ofNullable(latest);
    }

    public synchronized Optional<String> get(String table, String partitionKey, String column) {
        Optional<Cell> cell = getCell(table, partitionKey, column);
        if (cell.isEmpty() || cell.get().tombstone()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cell.get().value());
    }

    public synchronized Map<String, Cell> getRowCells(String table, String partitionKey) {
        String prefix = Cell.rowPrefix(table, partitionKey);
        Map<String, Cell> latestByColumn = new HashMap<>();
        for (Cell cell : memtable.scanPrefix(prefix)) {
            latestByColumn.merge(cell.column(), cell, (a, b) -> b.isNewerThan(a) ? b : a);
        }
        for (SSTableReader reader : sstables) {
            for (Cell cell : reader.scanPrefix(prefix)) {
                latestByColumn.merge(cell.column(), cell, (a, b) -> b.isNewerThan(a) ? b : a);
            }
        }
        return latestByColumn;
    }

    public synchronized Map<String, String> getRow(String table, String partitionKey) {
        Map<String, Cell> cells = getRowCells(table, partitionKey);
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Cell> entry : cells.entrySet()) {
            Cell cell = entry.getValue();
            if (!cell.tombstone()) result.put(entry.getKey(), cell.value());
        }
        return result;
    }

    public synchronized void flush() {
        if (memtable.isEmpty()) return;
        List<Cell> snapshot = memtable.snapshotSorted();
        int generation = nextGeneration.getAndIncrement();
        SSTableReader reader = SSTableWriter.write(nodeDir, generation, snapshot);
        sstables.add(0, reader);
        sstables.sort(Comparator.comparingInt(SSTableReader::generation).reversed());
        memtable.clear();
        wal.truncate();
    }

    public synchronized void compact() {
        flush();
        if (sstables.size() <= 1) return;
        Map<String, Cell> latestByKey = new HashMap<>();
        for (SSTableReader reader : sstables) {
            for (Cell cell : reader.allCells()) {
                latestByKey.merge(cell.storageKey(), cell, (a, b) -> b.isNewerThan(a) ? b : a);
            }
        }
        List<Cell> compacted = latestByKey.values().stream()
                .filter(cell -> !cell.tombstone())
                .sorted(Comparator.comparing(Cell::storageKey))
                .toList();

        List<SSTableReader> old = new ArrayList<>(sstables);
        sstables.clear();
        if (!compacted.isEmpty()) {
            SSTableReader reader = SSTableWriter.write(nodeDir, nextGeneration.getAndIncrement(), compacted);
            sstables.add(reader);
        }
        old.forEach(SSTableReader::deleteFiles);
    }

    public synchronized int sstableCount() {
        return sstables.size();
    }

    public synchronized int memtableSize() {
        return memtable.size();
    }

    private void recoverWal() {
        for (Cell cell : wal.replay()) {
            memtable.apply(cell);
        }
    }

    private static List<SSTableReader> loadSSTables(Path nodeDir) {
        List<SSTableReader> readers = new ArrayList<>();
        try (var paths = Files.list(nodeDir)) {
            paths.forEach(path -> {
                Matcher matcher = SSTABLE_DATA_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    int generation = Integer.parseInt(matcher.group(1));
                    readers.add(SSTableReader.open(nodeDir, generation));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list SSTables", e);
        }
        readers.sort(Comparator.comparingInt(SSTableReader::generation).reversed());
        return readers;
    }
}
