package com.example.minicassandra.storage;

import com.example.minicassandra.model.Cell;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SSTableWriter {
    private SSTableWriter() {
    }

    public static SSTableReader write(Path nodeDir, int generation, List<Cell> cells) {
        try {
            Files.createDirectories(nodeDir);
            Path dataPath = dataPath(nodeDir, generation);
            Path indexPath = indexPath(nodeDir, generation);
            List<Cell> sortedCells = new ArrayList<>(cells);
            sortedCells.sort(Comparator.comparing(Cell::storageKey));

            try (RandomAccessFile data = new RandomAccessFile(dataPath.toFile(), "rw");
                 BufferedWriter index = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8)) {
                data.setLength(0);
                for (Cell cell : sortedCells) {
                    long offset = data.getFilePointer();
                    data.write((cell.encode() + "\n").getBytes(StandardCharsets.UTF_8));
                    index.write(cell.storageKey());
                    index.write('\t');
                    index.write(Long.toString(offset));
                    index.newLine();
                }
            }
            return SSTableReader.open(nodeDir, generation);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write SSTable generation " + generation, e);
        }
    }

    static Path dataPath(Path nodeDir, int generation) {
        return nodeDir.resolve(String.format("sstable-%06d.data", generation));
    }

    static Path indexPath(Path nodeDir, int generation) {
        return nodeDir.resolve(String.format("sstable-%06d.index", generation));
    }
}
