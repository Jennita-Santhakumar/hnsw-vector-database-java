# ThedalDB — A Vector Database Built From Scratch

A vector database written in Java with an HNSW index implemented from the original paper, crash-safe
persistence (write-ahead log + snapshots), metadata filtering, a REST API, a Python client, and a public
benchmark against FAISS, hnswlib and ChromaDB. ("Thedal" = search in Tamil.)

## Repository identity (use exactly these on GitHub)
- **Repo name:** `hnsw-vector-database-java`  (alternatives: `vector-search-engine-hnsw`, `thedaldb-vector-database`)
- **Display name in README:** ThedalDB — keep the brand, but the repo name carries the keywords.
- **GitHub "About" description:** Vector database built from scratch in Java 21: HNSW approximate nearest neighbor index, write-ahead log + snapshot persistence, metadata filtering, REST API, Python client, benchmarks vs FAISS and hnswlib.
- **GitHub Topics:** `vector-database`, `hnsw`, `approximate-nearest-neighbor`, `similarity-search`, `java`, `database`, `storage-engine`, `write-ahead-log`, `data-structures`, `rag`, `embeddings`, `benchmarking`, `rest-api`, `python-client`, `faiss`, `performance`, `docker`, `github-actions`, `systems-programming`, `information-retrieval`
- **Title on CV:** Vector Database from Scratch in Java (ThedalDB)

## Read these before any task
- `docs/PRD.md` — scope, features, non-goals, target numbers
- `docs/ARCHITECTURE.md` — modules, HNSW details, storage format, concurrency, API, benchmark method
- `docs/TECH_STACK.md` — approved tools only (free / open-source)
- `docs/TASKS.md` — build plan; ONE task at a time
- `docs/STANDARDS.md` — production rules
- `docs/DEPLOYMENT.md` — CI/CD and free hosting

## Hard rules
1. **No vector/ANN libraries in the core.** HNSW, distance functions, WAL and snapshot format are written by us. External ANN libraries are allowed ONLY in the benchmark harness for comparison.
2. Correctness before speed: every index change must keep `FlatIndex` (brute force) recall tests passing.
3. Benchmarks are reproducible: fixed seeds, documented hardware, scripts in repo, raw results committed as CSV.
4. Never claim a number that isn't in `docs/RESULTS.md`.
5. Durability: an acknowledged write must survive `kill -9` (with `fsync=always`). There is a test for this.
6. Zero cost: GitHub Actions, GHCR, GitHub Pages, free VM.

## Repo layout (target)
```
thedal-core/     index (Flat, HNSW), distance, storage (WAL, snapshot), collection, filter
thedal-server/   HTTP API (Javalin), config, metrics, main()
thedal-bench/    JMH microbenchmarks + Java ANN benchmark runner (HDF5 datasets)
clients/python/  `thedal` Python client (httpx), published to TestPyPI
bench/compare/   Python scripts: hnswlib, FAISS, ChromaDB on same datasets → CSV → plots
demo/            semantic search demo (FastAPI + sentence-transformers + static page)
site/            GitHub Pages benchmark report
docs/
settings.gradle.kts, build.gradle.kts, docker-compose.yml, Makefile
```

## Commands
`make build`, `make test` (unit + property tests), `make lint` (Spotless check), `make check`,
`make run` (server on :7700), `make crash-test`, `make bench-micro` (JMH), `make bench-ann DATASET=sift-128-euclidean`,
`make bench-compare`, `make plots`, `make up` (server + demo via compose).

## Conventions
- Java 21 LTS, Gradle Kotlin DSL, modules as Gradle subprojects. Records for value types; no Lombok.
- Vectors stored as `float[]` in contiguous arrays (one big array per segment), not `List<Float>`.
- Public API classes in `dev.thedal.*`; internal packages under `dev.thedal.internal.*`.
- JUnit 5 + AssertJ + jqwik; Spotless (google-java-format); SpotBugs.
- Python (client, bench, demo): 3.12, uv, ruff, mypy, pytest.
