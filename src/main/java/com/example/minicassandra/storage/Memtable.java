package com.example.minicassandra.storage;

import com.example.minicassandra.model.Cell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

public final class Memtable {
    private final ConcurrentSkipListMap<String, Cell> cells = new ConcurrentSkipListMap<>();

    public void apply(Cell cell) {
        cells.compute(cell.storageKey(), (ignored, existing) -> cell.isNewerThan(existing) ? cell : existing);
    }

    public Optional<Cell> get(String storageKey) {
        return Optional.ofNullable(cells.get(storageKey));
    }

    public List<Cell> scanPrefix(String prefix) {
        List<Cell> result = new ArrayList<>();
        NavigableMap<String, Cell> tail = cells.tailMap(prefix, true);
        for (Map.Entry<String, Cell> entry : tail.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) break;
            result.add(entry.getValue());
        }
        return result;
    }

    public int size() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public List<Cell> snapshotSorted() {
        return new ArrayList<>(cells.values());
    }

    public void clear() {
        cells.clear();
    }
}
