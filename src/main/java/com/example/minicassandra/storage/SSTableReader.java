package com.example.minicassandra.storage;

import com.example.minicassandra.model.Cell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SSTableReader {
    private final int generation;
    private final Path dataPath;
    private final Path indexPath;
    private final Map<String, Long> index;
    private final BloomFilter bloomFilter;

    private SSTableReader(int generation, Path dataPath, Path indexPath, Map<String, Long> index, BloomFilter bloomFilter) {
        this.generation = generation;
        this.dataPath = dataPath;
        this.indexPath = indexPath;
        this.index = index;
        this.bloomFilter = bloomFilter;
    }

    public static SSTableReader open(Path nodeDir, int generation) {
        Path dataPath = SSTableWriter.dataPath(nodeDir, generation);
        Path indexPath = SSTableWriter.indexPath(nodeDir, generation);
        Map<String, Long> index = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                int tab = line.lastIndexOf('\t');
                String key = line.substring(0, tab);
                long offset = Long.parseLong(line.substring(tab + 1));
                index.put(key, offset);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open SSTable index " + indexPath, e);
        }

        BloomFilter bloomFilter = new BloomFilter(index.size());
        index.keySet().forEach(bloomFilter::add);
        return new SSTableReader(generation, dataPath, indexPath, index, bloomFilter);
    }

    public Optional<Cell> get(String storageKey) {
        if (!bloomFilter.mightContain(storageKey)) {
            return Optional.empty();
        }
        Long offset = index.get(storageKey);
        if (offset == null) {
            return Optional.empty();
        }
        try (RandomAccessFile data = new RandomAccessFile(dataPath.toFile(), "r")) {
            data.seek(offset);
            String line = data.readLine();
            if (line == null || line.isBlank()) return Optional.empty();
            return Optional.of(Cell.decode(line));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SSTable " + dataPath, e);
        }
    }

    public List<Cell> scanPrefix(String prefix) {
        List<Cell> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Cell cell = Cell.decode(line);
                if (cell.storageKey().startsWith(prefix)) {
                    result.add(cell);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan SSTable " + dataPath, e);
        }
        return result;
    }

    public List<Cell> allCells() {
        List<Cell> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) result.add(Cell.decode(line));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SSTable " + dataPath, e);
        }
        return result;
    }

    public void deleteFiles() {
        try {
            Files.deleteIfExists(dataPath);
            Files.deleteIfExists(indexPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete SSTable files", e);
        }
    }

    public int generation() {
        return generation;
    }
}
