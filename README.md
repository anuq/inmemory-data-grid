# In-Memory Distributed Cache

A production-quality, pure Java 17 distributed in-memory cache with no external dependencies at runtime. Implements the core internals of systems like Memcached/Redis from first principles.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENTS                                 │
│              (CacheCLI / your application)                      │
└───────────┬───────────────────┬────────────────────────────────┘
            │ Binary Protocol   │ Binary Protocol
            ▼                   ▼
┌───────────────────┐  ┌───────────────────┐  ┌────────────────┐
│   Node 1 :6379    │  │   Node 2 :6380    │  │  Node 3 :6381  │
│                   │  │                   │  │                │
│  NIO Selector     │  │  NIO Selector     │  │  NIO Selector  │
│  Loop             │  │  Loop             │  │  Loop          │
│  ┌─────────────┐  │  │  ┌─────────────┐  │  │ ┌───────────┐ │
│  │ LRU or LFU  │  │  │  │ LRU or LFU  │  │  │ │LRU or LFU │ │
│  │   Cache     │  │  │  │   Cache     │  │  │ │  Cache    │ │
│  └─────────────┘  │  │  └─────────────┘  │  │ └───────────┘ │
│  ┌─────────────┐  │  │                   │  │               │
│  │ Replication │──┼──┼──────────────────►│  │               │
│  │  Manager    │──┼──┼──────────────────────►               │
│  └─────────────┘  │  │                   │  │               │
└───────────────────┘  └───────────────────┘  └───────────────┘
         ▲                      ▲                      ▲
         └──────────────────────┴──────────────────────┘
                  Consistent Hash Ring
              (150 virtual nodes per physical)
```

## Key Design Decisions

| Component | Choice | Rationale |
|-----------|--------|-----------|
| **LRU Cache** | DoublyLinkedList + HashMap | O(1) get/put/evict, thread-safe via RW lock |
| **LFU Cache** | Frequency-bucket LinkedHashSets | O(1) all ops; LRU tie-breaking within same frequency |
| **Hashing** | MurmurHash3 + ConcurrentSkipListMap | Uniform distribution, O(log n) lookup, no dependencies |
| **I/O** | Java NIO Selector (single thread) | Non-blocking, handles 10k+ connections efficiently |
| **Replication** | Fire-and-forget with bounded queue | Eventual consistency; drops under extreme pressure vs OOM |
| **Protocol** | Custom binary framing | Lower overhead than text protocols; deterministic parsing |

## Binary Protocol

```
Request Frame:
  Byte  0      : command  (0x01=GET, 0x02=SET, 0x03=DEL, 0x04=STATS)
  Bytes 1-4    : key length (big-endian int)
  Bytes 5..N   : key (UTF-8)
  Bytes N+1..4 : value length (0 for GET/DEL)
  Bytes ..M    : value bytes
  Bytes M+1..8 : TTL millis (0 = no expiry)

Response Frame:
  Byte  0      : status  (0x00=OK, 0x01=NOT_FOUND, 0x02=ERROR)
  Bytes 1-4    : payload length
  Bytes 5..N   : payload bytes
```

## Build

Requires Java 17+ and Maven 3.9+.

```bash
mvn clean package
# Produces: target/cache.jar
```

Run tests:

```bash
mvn test
```

## Running

### Single Node

```bash
java -jar target/cache.jar
# Default: port 6379, LRU, 10,000 max entries
```

Override via CLI flags:

```bash
java -jar target/cache.jar --port=6380 --nodeId=node-2 --policy=LFU
```

Or via `cache.properties` (loaded from current directory or classpath):

```properties
cache.node.id=node-1
cache.host=127.0.0.1
cache.port=6379
cache.max.entries=50000
cache.eviction.policy=LRU
cache.replication.factor=2
cache.peers=node-2:127.0.0.1:6380,node-3:127.0.0.1:6381
```

### Multi-Node Cluster (3 nodes)

Terminal 1:
```bash
java -Dcache.node.id=node-1 \
     -Dcache.port=6379 \
     -Dcache.peers=node-2:127.0.0.1:6380,node-3:127.0.0.1:6381 \
     -Dcache.replication.factor=2 \
     -jar target/cache.jar
```

Terminal 2:
```bash
java -Dcache.node.id=node-2 \
     -Dcache.port=6380 \
     -Dcache.peers=node-1:127.0.0.1:6379,node-3:127.0.0.1:6381 \
     -Dcache.replication.factor=2 \
     -jar target/cache.jar
```

Terminal 3:
```bash
java -Dcache.node.id=node-3 \
     -Dcache.port=6381 \
     -Dcache.peers=node-1:127.0.0.1:6379,node-2:127.0.0.1:6380 \
     -Dcache.replication.factor=2 \
     -jar target/cache.jar
```

### CLI Client

```bash
java -cp target/cache.jar com.cache.cli.CacheCLI [host] [port]
# Default: 127.0.0.1 6379
```

```
cache> set user:1 John 60000       # store with 60s TTL
OK
cache> get user:1
John
cache> set counter 42
OK
cache> get counter
42
cache> del counter
OK
cache> stats
CacheStats{hits=2, misses=0, hitRate=100.00%, evictions=0, expirations=0, size=1/10000}
cache> exit
Bye!
```

## Project Structure

```
inmemory-data-grid/
├── pom.xml
└── src/
    ├── main/java/com/cache/
    │   ├── server/
    │   │   ├── CacheServer.java        NIO selector loop, main entry point
    │   │   ├── ConnectionHandler.java  Per-connection read/write state machine
    │   │   └── ServerConfig.java       Config loaded from cache.properties / system props
    │   ├── cache/
    │   │   ├── Cache.java              Interface (get/put/delete/stats/clear)
    │   │   ├── CacheEntry.java         Value + TTL + access metadata
    │   │   ├── CacheStats.java         Hits, misses, evictions, hit rate (record)
    │   │   ├── EvictionPolicy.java     Enum: LRU | LFU
    │   │   ├── LRUCache.java           O(1) doubly-linked list + HashMap
    │   │   └── LFUCache.java           O(1) frequency-bucket LinkedHashSets
    │   ├── hashing/
    │   │   ├── ConsistentHashRing.java MurmurHash3, 150 virtual nodes, ConcurrentSkipListMap
    │   │   └── Node.java               Physical node record (nodeId, host, port)
    │   ├── protocol/
    │   │   └── BinaryProtocol.java     Frame encoder/decoder, Request/Response records
    │   ├── replication/
    │   │   ├── ReplicationManager.java Async, bounded-queue fire-and-forget replication
    │   │   └── PeerClient.java         Blocking NIO client for peer writes
    │   └── cli/
    │       └── CacheCLI.java           Interactive terminal client
    └── test/java/com/cache/
        ├── LRUCacheTest.java           Eviction order, TTL, concurrent access
        ├── LFUCacheTest.java           Frequency eviction, tie-breaking
        ├── ConsistentHashRingTest.java Node distribution, add/remove, replicas
        └── BinaryProtocolTest.java     Encode/decode round-trips, partial frames
```

## Extending

- **Persistence**: Implement `Cache` with an append-only log (AOF) for durability
- **Cluster discovery**: Replace static peer list with Zookeeper/etcd membership
- **Metrics**: Expose JMX MBeans or Prometheus endpoint from `CacheStats`
- **Compression**: Compress values > threshold in `ConnectionHandler` before storing
