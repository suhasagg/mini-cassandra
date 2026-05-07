package com.example.minicassandra;

import com.example.minicassandra.model.Cell;
import com.example.minicassandra.storage.LsmStorageEngine;
import com.example.minicassandra.util.TimestampGenerator;

import java.nio.file.Path;
import java.util.Map;

public final class LsmStorageEngineTest {
    public static void main(String[] args) {
        Path dir = TestSupport.tempDir("lsm-test-");
        try {
            LsmStorageEngine engine = new LsmStorageEngine(dir, 2);
            engine.apply(new Cell("users", "u1", "name", "Suhas", TimestampGenerator.nextMicros(), false));
            engine.apply(new Cell("users", "u1", "city", "Delhi", TimestampGenerator.nextMicros(), false));
            TestSupport.assertEquals("Suhas", engine.get("users", "u1", "name").orElseThrow());
            TestSupport.assertTrue(engine.sstableCount() >= 1, "Expected flush after memtable threshold");

            engine.apply(new Cell("users", "u1", "city", null, TimestampGenerator.nextMicros(), true));
            TestSupport.assertTrue(engine.get("users", "u1", "city").isEmpty(), "Expected deleted city");

            Map<String, String> row = engine.getRow("users", "u1");
            TestSupport.assertEquals("Suhas", row.get("name"));
            TestSupport.assertFalse(row.containsKey("city"), "Expected tombstoned column to be hidden");

            engine.flush();
            LsmStorageEngine reopened = new LsmStorageEngine(dir, 2);
            TestSupport.assertEquals("Suhas", reopened.get("users", "u1", "name").orElseThrow());
            reopened.compact();
            TestSupport.assertTrue(reopened.sstableCount() <= 1, "Expected compaction to leave <= 1 SSTable");
            System.out.println("[PASS] LsmStorageEngineTest");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }
}
