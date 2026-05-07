# Mini Cassandra Java - Detailed Architecture

This document explains the architecture of the dependency-free Java Cassandra-like database.

---

## 1. Goal

The goal is to implement the core ideas behind a Cassandra-style distributed wide-column database while keeping the code small enough to read in one sitting.

The project focuses on these database concepts:

- LSM-tree storage
- write-ahead logging
- memtables
- immutable SSTables
- bloom filters
- compaction
- consistent hashing
- virtual nodes
- replication factor
- quorum reads and writes
- hinted handoff
- read repair
- tombstones

It does not try to be wire-compatible with Apache Cassandra.

---

## 2. High-level architecture

```text
Client
  |
  | HTTP
  v
MiniCassandraServer
  |
  | Java method call
  v
MiniCassandraCluster  <---- configuration from DatabaseConfig
  |
  | partition key lookup
  v
ConsistentHashRing
  |
  | replica node ids
  v
DatabaseNode(s)
  |
  | local persistence
  v
LsmStorageEngine
  |-- WriteAheadLog
  |-- Memtable
  |-- SSTableReader/SSTableWriter
  |-- BloomFilter
```

The database runs several simulated nodes inside one JVM. Each node has its own storage directory and its own LSM engine.

---

## 3. Component details

### 3.1 `Main`

`Main` is the bootstrap class.

Responsibilities:

- Reads `DatabaseConfig` from environment variables.
- Creates `MiniCassandraServer`.
- Starts the HTTP server.

Why it exists:

- Keeps startup logic separate from server and database logic.
- Makes local configuration easy to override without changing source code.

---

### 3.2 `DatabaseConfig`

`DatabaseConfig` is an immutable Java record that stores runtime configuration.

Important fields:

```text
port
nodeCount
replicationFactor
readQuorum
writeQuorum
memtableMaxEntries
virtualNodes
dataDir
```

Validation rules:

- `replicationFactor <= nodeCount`
- `readQuorum <= replicationFactor`
- `writeQuorum <= replicationFactor`
- all numeric values must be positive

Why it matters:

- Prevents invalid cluster configurations at startup.
- Makes quorum and replication behavior explicit.

---

### 3.3 `MiniCassandraServer`

This is the HTTP/API layer.

Endpoints:

```text
GET    /health
POST   /kv?table=&key=&column=
GET    /kv?table=&key=&column=
DELETE /kv?table=&key=&column=
GET    /row?table=&key=
POST   /admin/flush
POST   /admin/compact
GET    /cluster/ring
POST   /cluster/node/{nodeId}/down
POST   /cluster/node/{nodeId}/up
```

Responsibilities:

- Parse HTTP request method and path.
- Validate required query parameters.
- Read request body for writes.
- Call `MiniCassandraCluster`.
- Convert results to JSON.
- Convert exceptions into HTTP responses.

Why HTTP:

- It keeps the project easy to demo with `curl`.
- It avoids external dependencies.
- It makes the database understandable without implementing Cassandra's native protocol.

---

### 3.4 `MiniCassandraCluster`

This is the coordinator layer.

Responsibilities:

- Create simulated database nodes.
- Build the consistent hash ring.
- Select replicas for every partition key.
- Enforce read/write quorum.
- Perform hinted handoff.
- Perform read repair.
- Expose cluster status and ring status.

In Cassandra terms, a coordinator node is the node that receives a client request and coordinates the operation across replicas. In this project, `MiniCassandraCluster` is always the coordinator.

Write coordination:

```text
put(table, key, column, value)
  -> create Cell with timestamp
  -> replicas = ring.replicasFor(key, RF)
  -> write Cell to each available replica
  -> store hint for down replicas
  -> require acknowledgements >= writeQuorum
```

Read coordination:

```text
get(table, key, column)
  -> replicas = ring.replicasFor(key, RF)
  -> read available replicas
  -> require responses >= readQuorum
  -> choose latest timestamp
  -> repair stale replicas
  -> return value unless latest is tombstone
```

---

### 3.5 `ConsistentHashRing`

This component maps partition keys to replicas.

How the ring is built:

```text
for each physical node:
  for each virtual node index:
    token = hash64(nodeId + ":" + vnodeIndex)
    add token to ring
sort tokens
```

How replicas are selected:

```text
partitionToken = hash64(partitionKey)
start = first ring token >= partitionToken
walk clockwise until RF unique physical nodes are selected
```

Why virtual nodes:

- Better key distribution.
- Easier rebalancing model.
- More even ownership than one token per physical node.

---

### 3.6 `DatabaseNode`

A `DatabaseNode` represents one Cassandra-like node.

Responsibilities:

- Own one `LsmStorageEngine`.
- Track whether the node is up or down.
- Reject reads/writes when down.
- Forward flush and compaction commands to storage.

Why it exists:

- Separates cluster-level coordination from local storage.
- Makes failure simulation easy.

---

### 3.7 `Cell`

A `Cell` is the smallest stored data unit.

Fields:

```text
table
partitionKey
column
value
timestampMicros
tombstone
```

A storage key is created from:

```text
table + partitionKey + column
```

The table/key/column strings are base64-url encoded before being written to disk. This keeps separators safe and makes the storage files readable.

Conflict resolution:

```text
newer timestamp wins
```

Delete behavior:

```text
delete = write a newer Cell with tombstone=true
```

---

### 3.8 `LsmStorageEngine`

This is the main local storage engine.

Responsibilities:

- Apply writes.
- Read cells and rows.
- Flush memtable to SSTable.
- Replay WAL on startup.
- Load existing SSTables on startup.
- Compact SSTables.

Write path inside storage:

```text
apply(Cell)
  -> wal.append(Cell)
  -> memtable.apply(Cell)
  -> if memtable size >= threshold: flush()
```

Read path inside storage:

```text
getCell(table, key, column)
  -> construct storage key
  -> check memtable
  -> check SSTables newest to oldest
  -> choose newest timestamp
```

Important design choice:

- SSTables are immutable.
- Newer SSTables are checked first.
- A value can exist in multiple SSTables; timestamp decides the winner.

---

### 3.9 `WriteAheadLog`

The WAL provides durability before memtable flush.

Responsibilities:

- Append encoded cells to `wal.log`.
- Replay `wal.log` on startup.
- Truncate WAL after successful flush.

Crash recovery model:

```text
process crashes after WAL append but before SSTable flush
  -> startup replays wal.log
  -> memtable is restored
```

Limitation:

- This is a simple line-oriented WAL.
- It does not use checksums, segment rotation, fsync tuning, or corruption recovery.

---

### 3.10 `Memtable`

The memtable is the mutable in-memory write buffer.

Responsibilities:

- Store latest cell by storage key.
- Serve recent reads.
- Produce sorted snapshots for SSTable flush.
- Scan by row prefix for full-row reads.

Why memtable helps:

- Writes are fast because they update memory after append-only WAL write.
- Reads for hot/recent data do not touch disk.

---

### 3.11 `SSTableWriter`

The SSTable writer converts a memtable snapshot into immutable disk files.

Files written:

```text
sstable-000001.data
sstable-000001.index
```

Write process:

```text
sort cells by storage key
for each cell:
  offset = current data file position
  write encoded cell to .data
  write storageKey + offset to .index
```

Why sorted immutable files:

- Avoid random in-place updates.
- Make merge/compaction simple.
- Make prefix scans deterministic.

---

### 3.12 `SSTableReader`

The SSTable reader loads the index and builds a bloom filter.

Point lookup:

```text
get(storageKey)
  -> if bloom filter says no: return empty
  -> offset = index.get(storageKey)
  -> seek to offset in .data file
  -> decode line into Cell
```

Row scan:

```text
scanPrefix(rowPrefix)
  -> scan data file
  -> return cells where storageKey starts with rowPrefix
```

Tradeoff:

- Point reads are efficient because of bloom filter + index.
- Row scans are simple but not optimized; they scan SSTable files.

---

### 3.13 `BloomFilter`

The bloom filter is used to avoid unnecessary SSTable lookups.

Behavior:

```text
mightContain(key) == false  => key is definitely absent
mightContain(key) == true   => key may exist; check index
```

Implementation notes:

- Uses a `BitSet`.
- Uses multiple hash functions derived from `HashUtil.hash32`.
- Rebuilt from SSTable index keys when the SSTable is opened.

---

### 3.14 `Hint`

A hint represents a write that should have gone to a down replica.

Flow:

```text
node-1 down
write key user-2
node-1 is selected as replica
coordinator stores Hint(node-1, cell)
node-1 up
coordinator replays hints to node-1
```

Limitation:

- Hints are in memory only.
- They are lost if the JVM exits before replay.

---

### 3.15 `ReplicaReadResult`

A small record used by the coordinator during reads.

It stores:

```text
nodeId
Optional<Cell>
```

Why it exists:

- The coordinator needs to know which replica returned which version.
- Read repair needs to know which nodes are stale or missing the latest value.

---

## 4. End-to-end write sequence

```text
1. Client sends POST /kv?table=users&key=user-1&column=name with body "Suhas".
2. HTTP server validates table/key/column.
3. Cluster creates Cell(users, user-1, name, Suhas, timestamp, tombstone=false).
4. Ring maps partition key user-1 to RF replicas.
5. Coordinator sends write to each available replica.
6. Each node appends to WAL and updates memtable.
7. Down replicas receive hints instead of direct writes.
8. Coordinator counts successful acknowledgements.
9. If acknowledgements >= WRITE_QUORUM, request succeeds.
10. If acknowledgements < WRITE_QUORUM, request fails.
```

---

## 5. End-to-end read sequence

```text
1. Client sends GET /kv?table=users&key=user-1&column=name.
2. HTTP server validates table/key/column.
3. Ring maps user-1 to RF replicas.
4. Coordinator reads from available replicas.
5. Each replica checks memtable first, then SSTables.
6. SSTable reads use bloom filter and index.
7. Coordinator requires responses >= READ_QUORUM.
8. Coordinator picks latest timestamp across responses.
9. Coordinator performs read repair on stale replicas.
10. If latest cell is tombstone, value is treated as deleted.
11. Otherwise, value is returned.
```

---

## 6. Production gaps

This implementation is intentionally small. A production Cassandra-like system would additionally need:

- Real networked nodes.
- Gossip membership.
- Failure detector.
- Token range ownership metadata.
- Bootstrap and decommission workflows.
- Streaming data movement.
- Persistent hinted handoff.
- Merkle-tree anti-entropy repair.
- Binary SSTable format.
- Checksummed WAL segments.
- Compression.
- Caching layers.
- Backpressure and admission control.
- Authentication and authorization.
- Metrics, tracing, and structured logs.
- Multiple compaction strategies.
- Schema management.
- Secondary indexes or materialized views.
- Lightweight transactions/Paxos.

---


