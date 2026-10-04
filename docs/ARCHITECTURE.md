# Architecture — ThedalDB

## Modules
```
            HTTP (Javalin)                      Python client / demo / bench
                 │                                         │
         ┌───────▼────────┐                                │
         │ thedal-server  │◄───────────────────────────────┘
         │  routes, JSON, │
         │  metrics, cfg  │
         └───────┬────────┘
                 │
         ┌───────▼─────────────────────────────────────────────┐
         │ thedal-core                                          │
         │  CollectionManager ─► Collection                     │
         │      ├─ VectorStore   (contiguous float[] segments)  │
         │      ├─ Index         (FlatIndex | HnswIndex)        │
         │      ├─ MetadataStore (id → fields; inverted index)  │
         │      ├─ IdMap         (external string id ↔ int ord) │
         │      ├─ Tombstones    (RoaringBitmap)                │
         │      └─ Storage       (WAL + Snapshot)               │
         └──────────────────────────────────────────────────────┘
```

## Core interfaces
```java
public interface Index {
  void add(int ord, float[] vector);
  SearchResult search(float[] query, int k, SearchParams params, IntPredicate allowed);
  long memoryBytes();
}
public interface Distance { float distance(float[] a, int aOffset, float[] b, int bOffset, int dim); }
```
- Cosine: normalize vectors on insert, then use negative dot product (smaller = closer).
- Internal ordinal (`int`) for each point; external ids are strings mapped via `IdMap`.

## HNSW (Malkov & Yashunin, 2016/2018)
- Parameters: `M` (default 16), `M0 = 2M` on layer 0, `efConstruction` (default 200), `efSearch` (default 64), level multiplier `mL = 1/ln(M)`.
- Level of a new node: `floor(-ln(uniform(0,1)) * mL)` with a seeded RNG (reproducible builds).
- Insert: greedy descent from entry point through upper layers (ef = 1), then at each layer ≤ node level run `searchLayer` with `efConstruction`, select neighbors with the **heuristic** (Algorithm 4: keep candidate only if closer to the new node than to any already-selected neighbor), add bidirectional links, shrink neighbor lists that exceed `M`/`M0` using the same heuristic.
- Search: greedy descent to layer 0, then `searchLayer(ef = max(efSearch, k))`, return top-k.
- Data layout: neighbor lists per layer as `int[]` with count prefix; visited set as a reusable generation-stamped `int[]` (no allocation per query).
- Deletes: tombstone bitmap; search continues traversing through deleted nodes but excludes them from results. Compaction rebuilds the graph when tombstones > 20%.

## Filtering
- `MetadataStore` keeps per-field inverted indexes (value → RoaringBitmap of ords) for `eq`/`in`; sorted arrays for numeric `range`.
- Filter → bitmap of allowed ords. Strategy by selectivity `s = allowed / total`:
  - `s < 0.02` (configurable): exact brute-force over allowed ords (fast and exact).
  - otherwise: HNSW search with `allowed` predicate, expanding ef by `ceil(1/s)` up to a cap; document recall trade-off in an ADR.

## Storage format
**WAL** `wal-<seq>.log`, append-only records:
```
| u32 length | u32 crc32 | u8 op | u64 lsn | payload... |
op: 1=UPSERT(id, vector[dim], metadata-json) 2=DELETE(id) 3=CREATE_COLLECTION 4=DROP_COLLECTION
```
- `fsync=always`: fsync before acknowledging the write. `fsync=batch`: group commit every N ms (default 10); acknowledge after the batch fsync.
- Recovery reads records until a length/CRC mismatch → treat as torn tail, truncate there.

**Snapshot** `snapshot-<lsn>/` with `header.bin` (magic `THDL`, format version, dim, metric, params), `vectors.bin`, `graph.bin`, `ids.bin`, `metadata.bin`, `tombstones.bin`.
Write to a temp dir → fsync files → fsync dir → atomic rename → delete WAL segments with lsn ≤ snapshot lsn.
Startup = load latest valid snapshot + replay WAL records with lsn > snapshot lsn.

## Concurrency
- v1: `ReentrantReadWriteLock` per collection — many concurrent searches, one writer. Writes batched to amortize lock cost.
- Searches allocate nothing per query beyond result arrays (thread-local visited sets and heaps).
- v2 stretch: fine-grained per-node locks for concurrent inserts.

## HTTP API (port 7700, JSON)
| Method | Path | Body / notes |
|---|---|---|
| POST | `/v1/collections` | `{name, dim, metric, index: {type, m, ef_construction}}` |
| GET | `/v1/collections` | list with sizes |
| GET | `/v1/collections/{c}` | stats: count, tombstones, memory, params |
| DELETE | `/v1/collections/{c}` | |
| PUT | `/v1/collections/{c}/points` | `{points: [{id, vector, metadata}]}` (≤ 1000 per request) |
| GET | `/v1/collections/{c}/points/{id}` | |
| DELETE | `/v1/collections/{c}/points/{id}` | |
| POST | `/v1/collections/{c}/search` | `{vector, k, ef?, filter?, include_vectors?}` → `[{id, score, metadata}]` |
| POST | `/v1/collections/{c}/snapshot` | force snapshot |
| POST | `/v1/collections/{c}/compact` | |
| GET | `/healthz`, `/readyz`, `/metrics` | Micrometer → Prometheus |
Optional single API key via `THEDAL_API_KEY` header check. Error shape per STANDARDS.md.

Filter JSON:
```json
{"and": [{"field": "lang", "eq": "ta"}, {"field": "year", "range": {"gte": 2020}}, {"field": "tag", "in": ["news","blog"]}]}
```

## Benchmark methodology
- Datasets: ann-benchmarks HDF5 files (`sift-128-euclidean`, `glove-100-angular`, `fashion-mnist-784-euclidean`), which include ground-truth neighbors.
- **In-process benchmark** (Java, `thedal-bench`, HDF5 via jHDF): build time, memory, recall@10 vs QPS curve over efSearch ∈ {16, 32, 64, 128, 256}, single and N threads.
- **Comparison** (Python, `bench/compare`): hnswlib and FAISS (`IndexHNSWFlat`) with same M/efConstruction, same machine; ChromaDB via its client as an end-to-end reference.
- **API benchmark** separately (k6 or Python asyncio) — HTTP overhead reported, not mixed into index numbers.
- Output CSV → matplotlib recall-vs-QPS plots → `site/` (GitHub Pages). Record CPU model, cores, RAM, JVM flags.

## Demo app (`demo/`)
FastAPI + sentence-transformers `all-MiniLM-L6-v2` (Apache 2.0) embeds a public CC0 corpus (e.g. arXiv paper metadata/abstracts) into ThedalDB; a static search page with filters (year, category). Shows "search powered by an engine I wrote".
