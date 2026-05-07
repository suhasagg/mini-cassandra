package com.example.minicassandra.model;

import com.example.minicassandra.util.Encoding;

import java.util.Objects;

public record Cell(
        String table,
        String partitionKey,
        String column,
        String value,
        long timestampMicros,
        boolean tombstone
) implements Comparable<Cell> {
    private static final String SEPARATOR = "\u0001";

    public Cell {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(column, "column");
        if (table.isBlank()) throw new IllegalArgumentException("table cannot be blank");
        if (partitionKey.isBlank()) throw new IllegalArgumentException("partitionKey cannot be blank");
        if (column.isBlank()) throw new IllegalArgumentException("column cannot be blank");
        if (tombstone) value = null;
    }

    public String storageKey() {
        return table + SEPARATOR + partitionKey + SEPARATOR + column;
    }

    public static String rowPrefix(String table, String partitionKey) {
        return table + SEPARATOR + partitionKey + SEPARATOR;
    }

    public boolean isNewerThan(Cell other) {
        return other == null || this.timestampMicros >= other.timestampMicros;
    }

    @Override
    public int compareTo(Cell other) {
        return this.storageKey().compareTo(other.storageKey());
    }

    public String encode() {
        String encodedValue = value == null ? "" : Encoding.b64(value);
        return String.join("\t",
                Encoding.b64(table),
                Encoding.b64(partitionKey),
                Encoding.b64(column),
                Long.toString(timestampMicros),
                Boolean.toString(tombstone),
                encodedValue
        );
    }

    public static Cell decode(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid cell line: " + line);
        }
        String table = Encoding.unb64(parts[0]);
        String partitionKey = Encoding.unb64(parts[1]);
        String column = Encoding.unb64(parts[2]);
        long timestampMicros = Long.parseLong(parts[3]);
        boolean tombstone = Boolean.parseBoolean(parts[4]);
        String value = parts[5].isEmpty() ? null : Encoding.unb64(parts[5]);
        return new Cell(table, partitionKey, column, value, timestampMicros, tombstone);
    }
}
