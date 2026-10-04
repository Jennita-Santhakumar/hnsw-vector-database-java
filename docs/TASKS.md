# Build Plan — ThedalDB

One task per Claude Code session (`/next-task`). Tick `[x]` when done.
Learn as you build: before each HNSW task, read the matching section of the HNSW paper (arXiv:1603.09320) and ask Claude Code to explain the algorithm first.

## Phase 0 — Foundation
- [x] 0.1 Gradle multi-module skeleton (core, server, bench), Java 21 toolchain, Spotless, SpotBugs, JUnit 5/AssertJ/jqwik; Makefile; `.gitignore` (datasets, data dirs).
- [x] 0.2 CI: build, Spotless check, tests, gitleaks, Docker build; cache Gradle.

## Phase 1 — Correct in-memory engine
- [x] 1.1 `Distance` implementations (L2, dot, cosine-normalized) + tests incl. edge cases (zero vector, dimension mismatch).
- [x] 1.2 `VectorStore` with contiguous segments + `IdMap` + tests.
- [x] 1.3 `FlatIndex` (exact top-k with bounded heap) + tests vs naive sort.
- [x] 1.4 HNSW: data structures, level generation (seeded), `searchLayer`, insert with simple neighbor selection. Recall test vs FlatIndex on 10k random vectors.
- [x] 1.5 HNSW: heuristic neighbor selection (Algorithm 4) + neighbor shrinking; show recall improvement in a test log → RESULTS.md.
- [x] 1.6 HNSW search with `efSearch`, reusable visited set, thread-local heaps; jqwik property: recall@10 ≥ 0.9 on random data with default params.
- [x] 1.7 Tombstone deletes + compaction rebuild + tests.
- [x] 1.8 `MetadataStore` + filter parser/evaluator (eq, in, range, and, or) + selectivity-based strategy; property test: flat-filtered result ≡ brute force.
- [x] 1.9 `Collection` + `CollectionManager` with read/write locking; concurrent search+write stress test.

## Phase 2 — Durability
- [ ] 2.1 WAL writer/reader with CRC32, LSNs, fsync modes, segment rotation + unit tests (torn tail, corrupt CRC).
- [ ] 2.2 Snapshot writer/reader (binary format, magic + version, atomic rename) + round-trip tests.
- [ ] 2.3 Recovery = snapshot + WAL replay; WAL truncation after snapshot; tests.
- [ ] 2.4 `make crash-test`: script starts server, streams acknowledged writes, `kill -9` at random times, restarts, verifies every acknowledged id exists. 100 iterations → RESULTS.md.

## Phase 3 — Server & client
- [ ] 3.1 Javalin server: all endpoints per ARCHITECTURE.md, validation, error shape, OpenAPI spec file, optional API key.
- [ ] 3.2 Micrometer metrics (search latency histogram, QPS, index size, WAL fsync latency), health/ready, JSON logs.
- [ ] 3.3 Dockerfile (multi-stage, jlink slim runtime, non-root) + compose; integration tests hitting the container.
- [ ] 3.4 Python client package `thedal` (typed, retries, batching helper) + tests against the container; publish to TestPyPI from CI on tag.

## Phase 4 — Benchmarks (the showcase)
- [ ] 4.1 Dataset downloader for ann-benchmarks HDF5 files (checksums) + jHDF loader.
- [ ] 4.2 JMH microbenchmarks for distance functions and heap operations → RESULTS.md.
- [ ] 4.3 Java ANN runner: build time, memory, recall@10/QPS/p50/p99 sweep over efSearch, 1 and N threads → CSV.
- [ ] 4.4 `bench/compare`: hnswlib + FAISS-HNSW with matched params on same machine → CSV; ChromaDB end-to-end reference.
- [ ] 4.5 Plots (recall vs QPS per dataset) + `site/` static report → GitHub Pages via Actions.
- [ ] 4.6 Profile (JFR / async-profiler) and optimize the top 2 hotspots; before/after numbers in RESULTS.md + ADR.

## Phase 5 — Demo & launch
- [ ] 5.1 Demo: ingest script (CC0 corpus → embeddings → ThedalDB), FastAPI search endpoint, static search page with filters.
- [ ] 5.2 Deploy server + demo (DEPLOYMENT.md); Docker image on GHCR.
- [ ] 5.3 v2 stretch (pick one): Vector API SIMD distances, or int8 scalar quantization with re-ranking — measure memory/recall/QPS change.
- [ ] 5.4 ADRs (Java choice, HNSW heuristic, WAL/fsync design, filter strategy, locking), README via `/ship` with benchmark plot, blog post "I built a vector database from scratch — here's how it compares to FAISS".
