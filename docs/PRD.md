# PRD — ThedalDB

## Why this project
Most AI projects call a vector database as a black box. This one builds it — showing data structures,
systems programming, concurrency, durability and performance engineering, with honest benchmarks.

## Users
| User | Need |
|---|---|
| Developer building RAG/search | Simple API: create collection, upsert vectors with metadata, search with filters |
| Recruiter / interviewer | Clear benchmark showing trade-offs vs established libraries; readable design docs |

## Features
### v1
- Collections with fixed dimension and metric: cosine, dot product, L2.
- Upsert (batch), get by id, delete (tombstones), search top-k with `ef` override.
- Indexes: `flat` (exact) and `hnsw` (M, efConstruction, efSearch configurable).
- Metadata per point (string/number/bool fields) + filters: `eq`, `in`, `range`, `and`, `or`.
- Persistence: WAL with CRC32 per record, configurable fsync (`always` / `batch` every N ms), snapshots with atomic rename, recovery = snapshot + WAL replay, torn-tail handling.
- Compaction: rebuild when tombstones > 20%.
- REST API + OpenAPI spec; Prometheus metrics; Docker image.
- Python client with type hints.

### v2 (stretch)
- SIMD distance via Java Vector API (incubator module).
- Scalar quantization (int8) to cut memory ~4×, with re-ranking.
- Filtered-search strategy selection by estimated selectivity.
- gRPC API.

## Non-goals
Distributed sharding/replication, auth beyond a single API key, SQL.

## Targets (verify and record in RESULTS.md)
- Recall@10 ≥ 0.95 on `sift-128-euclidean` and `glove-100-angular` at a documented efSearch.
- QPS and p50/p99 latency single-threaded and multi-threaded at that recall, alongside hnswlib/FAISS-HNSW on the same machine.
- Memory bytes/vector and index build time.
- Crash test: 100 random `kill -9` runs with zero acknowledged-write loss (`fsync=always`).
- Filter correctness: filtered results ≡ brute-force filtered results on property tests (for the flat path) and recall ≥ 0.9 for HNSW path.
