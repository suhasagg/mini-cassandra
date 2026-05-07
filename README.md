# Mini Cassandra Java

A dependency-free implementation of a Cassandra-like distributed database in Java.

Contents

- LSM-tree style storage engine
- Memtable
- Write-ahead log / WAL
- Immutable SSTables
- Sparse index per SSTable
- Bloom filter per SSTable
- Tombstones for deletes
- Compaction
- Consistent hashing ring
- Virtual nodes
- Replication factor
- Tunable read/write quorum
- Read repair
- Hinted handoff when a node is down
- REST API using Java built-in `HttpServer`
- No Maven / Gradle / external dependency required
- Plain `javac` build scripts
- Simple test runner


---

## 1. Architecture

```text
                 ┌──────────────────────────┐
                 │      REST API Server      │
                 │  POST/GET/DELETE /kv      │
                 └─────────────┬────────────┘
                               │
                               ▼
                 ┌──────────────────────────┐
                 │   MiniCassandraCluster   │
                 │ - quorum coordination    │
                 │ - read repair            │
                 │ - hinted handoff         │
                 └─────────────┬────────────┘
                               │
                               ▼
                 ┌──────────────────────────┐
                 │ Consistent Hash Ring      │
                 │ - virtual nodes           │
                 │ - RF replica selection    │
                 └─────────────┬────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
 ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
 │     node-0     │   │     node-1     │   │     node-2     │
 │ LSM Engine     │   │ LSM Engine     │   │ LSM Engine     │
 └───────┬────────┘   └───────┬────────┘   └───────┬────────┘
         │                    │                    │
         ▼                    ▼                    ▼
 ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
 │ WAL            │   │ WAL            │   │ WAL            │
 │ Memtable       │   │ Memtable       │   │ Memtable       │
 │ SSTables       │   │ SSTables       │   │ SSTables       │
 │ Bloom filters  │   │ Bloom filters  │   │ Bloom filters  │
 └────────────────┘   └────────────────┘   └────────────────┘
```

---

## 2. Cassandra-like concepts implemented

| Cassandra concept | Implemented here |
|---|---|
| Partition key | `key` query parameter |
| Column family / table | `table` query parameter |
| Column | `column` query parameter |
| LSM tree | `LsmStorageEngine` |
| Memtable | `Memtable` |
| Commit log | `WriteAheadLog` |
| SSTable | `SSTableWriter`, `SSTableReader` |
| Bloom filter | `BloomFilter` |
| Consistent hashing | `ConsistentHashRing` |
| Virtual nodes | `DatabaseConfig.virtualNodes` |
| Replication factor | `DatabaseConfig.replicationFactor` |
| Quorum read/write | `readQuorum`, `writeQuorum` |
| Hinted handoff | `MiniCassandraCluster.hintsByTarget` |
| Read repair | `MiniCassandraCluster.performReadRepair` |
| Tombstones | `Cell.tombstone` |
| Compaction | `LsmStorageEngine.compact` |

---

## 3. Requirements

- Java 17+
- Bash shell

No Maven or Gradle is required.

Check Java:

```bash
java -version
```

---

## 4. Build

```bash
./scripts/build.sh
```

This creates:

```text
build/mini-cassandra.jar
```

---

## 5. Run tests

```bash
./scripts/test.sh
```

Expected output:

```text
[PASS] BloomFilterTest
[PASS] ConsistentHashRingTest
[PASS] LsmStorageEngineTest
[PASS] MiniCassandraClusterTest
All tests passed.
```

---

## 6. Run server

```bash
./scripts/run.sh
```

Default server:

```text
http://localhost:8080
```

Default cluster:

```text
nodes = 3
replicationFactor = 3
readQuorum = 2
writeQuorum = 2
```

You can override settings:

```bash
PORT=9090 \
DATA_DIR=/tmp/mini-cassandra-data \
NODES=5 \
REPLICATION_FACTOR=3 \
READ_QUORUM=2 \
WRITE_QUORUM=2 \
MEMTABLE_MAX_ENTRIES=100 \
./scripts/run.sh
```

---

## 7. API examples

### 7.1 Health

```bash
curl -s http://localhost:8080/health | jq
```

Example response:

```json
{
  "status": "UP",
  "nodes": 3,
  "replicationFactor": 3
}
```

---

### 7.2 Put a column value

```bash
curl -i -X POST \
  'http://localhost:8080/kv?table=users&key=user-1&column=name' \
  --data 'Suhas'
```

Example response:

```json
{
  "status": "OK",
  "operation": "PUT",
  "table": "users",
  "key": "user-1",
  "column": "name"
}
```

---

### 7.3 Get a column value

```bash
curl -s \
  'http://localhost:8080/kv?table=users&key=user-1&column=name' | jq
```

Example response:

```json
{
  "found": true,
  "table": "users",
  "key": "user-1",
  "column": "name",
  "value": "Suhas"
}
```

---

### 7.4 Add more columns to same row

```bash
curl -s -X POST \
  'http://localhost:8080/kv?table=users&key=user-1&column=email' \
  --data 'suhas@example.com'

curl -s -X POST \
  'http://localhost:8080/kv?table=users&key=user-1&column=city' \
  --data 'Delhi NCR'
```

---

### 7.5 Read full row

```bash
curl -s \
  'http://localhost:8080/row?table=users&key=user-1' | jq
```

Example response:

```json
{
  "found": true,
  "table": "users",
  "key": "user-1",
  "columns": {
    "city": "Delhi NCR",
    "email": "suhas@example.com",
    "name": "Suhas"
  }
}
```

---

### 7.6 Delete a column

```bash
curl -i -X DELETE \
  'http://localhost:8080/kv?table=users&key=user-1&column=city'
```

---

### 7.7 Flush memtables to SSTables

```bash
curl -s -X POST http://localhost:8080/admin/flush | jq
```

---

### 7.8 Compact SSTables

```bash
curl -s -X POST http://localhost:8080/admin/compact | jq
```

---

### 7.9 View ring

```bash
curl -s http://localhost:8080/cluster/ring | jq
```

---

### 7.10 Simulate node failure

```bash
curl -s -X POST http://localhost:8080/cluster/node/node-1/down | jq
```

Write while `node-1` is down:

```bash
curl -s -X POST \
  'http://localhost:8080/kv?table=users&key=user-2&column=name' \
  --data 'Alice' | jq
```

Bring node back up. Hinted handoff will replay missed writes:

```bash
curl -s -X POST http://localhost:8080/cluster/node/node-1/up | jq
```

---

## 8. Demo script

```bash
./scripts/demo.sh
```

---

## 9. Source code map

```text
src/main/java/com/example/minicassandra
├── Main.java
├── cluster
│   ├── ConsistentHashRing.java
│   ├── DatabaseNode.java
│   ├── Hint.java
│   ├── MiniCassandraCluster.java
│   ├── QuorumException.java
│   └── ReplicaReadResult.java
├── config
│   └── DatabaseConfig.java
├── model
│   └── Cell.java
├── server
│   └── MiniCassandraServer.java
├── storage
│   ├── BloomFilter.java
│   ├── LsmStorageEngine.java
│   ├── Memtable.java
│   ├── SSTableReader.java
│   ├── SSTableWriter.java
│   └── WriteAheadLog.java
└── util
    ├── Encoding.java
    ├── HashUtil.java
    ├── JsonUtil.java
    └── TimestampGenerator.java
```

---

## 10. Write path

```text
Client POST /kv
    ↓
MiniCassandraCluster.write()
    ↓
ConsistentHashRing.replicasFor(partitionKey)
    ↓
For each replica:
    node.write(cell)
        ↓
        WAL append
        ↓
        Memtable apply
        ↓
        Optional flush if memtable threshold reached
    ↓
Return success if writeQuorum acknowledgements reached
```

---

## 11. Read path

```text
Client GET /kv
    ↓
MiniCassandraCluster.read()
    ↓
ConsistentHashRing.replicasFor(partitionKey)
    ↓
Read from available replicas
    ↓
Pick latest timestamp
    ↓
Perform read repair on stale replicas
    ↓
Return latest value if not tombstoned
```

---

## 12. Storage path

```text
Put key
  -> append WAL
  -> update memtable
  -> flush when memtable is large

Flush
  -> sort memtable keys
  -> write immutable SSTable data file
  -> write sparse index
  -> create bloom filter
  -> truncate WAL

Get key
  -> check memtable
  -> check SSTables newest to oldest
  -> bloom filter avoids unnecessary disk lookups
  -> choose newest timestamp

Compact
  -> merge SSTables
  -> keep latest record per cell key
  -> drop tombstones in this toy implementation
```

---

## 13. Important limitations

This repo is designed for learning and interview explanation. It intentionally avoids many production Cassandra features:

- No real network protocol between nodes
- No anti-entropy Merkle tree repair
- No gossip membership protocol
- No hinted handoff persistence after process restart
- No token range streaming
- No schema migrations
- No compression
- No secondary indexes
- No authentication / authorization
- No backpressure
- No real compaction strategies like STCS/LCS/TWCS
- No cluster bootstrap/decommission workflow

---

## 15. Clean generated data

```bash
rm -rf data build
```
