# CV Bullets — ThedalDB (fill [X] from RESULTS.md)

**Vector Database from Scratch in Java (ThedalDB)** | github.com/<username>/hnsw-vector-database-java | Benchmarks | 2026
*Java 21, HNSW, Javalin, RoaringBitmap, JMH, Docker, Python, FAISS/hnswlib (benchmarks)*

- Built a vector database from scratch in Java with an HNSW index implemented from the original paper, reaching recall@10 of [X] at [X] QPS on SIFT-1M — [X]% of hnswlib's throughput at equal recall on the same hardware.
- Designed crash-safe persistence with a CRC-checked write-ahead log, group commit and atomic snapshots; 100 randomized `kill -9` tests showed zero loss of acknowledged writes.
- Implemented metadata filtering with Roaring-bitmap inverted indexes and selectivity-based query planning (exact scan below [X]% selectivity, filtered HNSW above).
- Profiled with JFR and optimized distance computation and memory layout, improving QPS by [X]% and reducing memory to [X] bytes/vector.
- Shipped a REST API, typed Python client (TestPyPI), Docker image and a public benchmark site via GitHub Actions and GitHub Pages.

## Interview talking points
- Why HNSW works (small-world graphs, hierarchical skip-list idea) and what M / ef trade off.
- Durability: fsync semantics, torn writes, why CRC + LSN, group commit latency vs throughput.
- Filtered ANN is hard: why naive post-filtering kills recall, and your strategy.
- Benchmark honesty: matched parameters, same machine, warmup, what you did NOT optimize.
