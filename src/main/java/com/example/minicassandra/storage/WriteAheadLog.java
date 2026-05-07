package com.example.minicassandra.storage;

import com.example.minicassandra.model.Cell;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class WriteAheadLog {
    private final Path walPath;

    public WriteAheadLog(Path nodeDir) throws IOException {
        Files.createDirectories(nodeDir);
        this.walPath = nodeDir.resolve("wal.log");
        if (!Files.exists(walPath)) {
            Files.createFile(walPath);
        }
    }

    public synchronized void append(Cell cell) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                walPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(cell.encode());
            writer.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append WAL record", e);
        }
    }

    public synchronized List<Cell> replay() {
        List<Cell> cells = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) cells.add(Cell.decode(line));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to replay WAL", e);
        }
        return cells;
    }

    public synchronized void truncate() {
        try {
            Files.writeString(walPath, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to truncate WAL", e);
        }
    }
}
