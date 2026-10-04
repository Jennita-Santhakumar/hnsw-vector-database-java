# Tech Stack — ThedalDB (all free)

| Area | Choice | License | Why |
|---|---|---|---|
| Language | Java 21 LTS (Temurin) | GPLv2+CE | you know Java; strong concurrency + JIT |
| Build | Gradle Kotlin DSL | Apache 2.0 | multi-module |
| HTTP | Javalin | Apache 2.0 | tiny, fast, simple |
| JSON | Jackson | Apache 2.0 | |
| Bitmaps | RoaringBitmap | Apache 2.0 | tombstones, filters |
| Metrics | Micrometer + Prometheus registry | Apache 2.0 | |
| Logging | SLF4J + Logback (JSON encoder) | MIT / EPL | |
| HDF5 | jHDF | MIT | read ann-benchmarks files in Java |
| Microbenchmarks | JMH | GPLv2+CE | correct JVM benchmarking |
| Tests | JUnit 5, AssertJ, jqwik (property-based) | EPL / Apache | |
| Quality | Spotless (google-java-format), SpotBugs | Apache / LGPL | |
| Comparison libs | hnswlib, faiss-cpu, chromadb (bench only) | Apache / MIT / Apache | baselines |
| Python client | httpx, pydantic | BSD / MIT | typed client |
| Demo | FastAPI, sentence-transformers (all-MiniLM-L6-v2) | MIT / Apache | |
| Plots | matplotlib, pandas | BSD | |
| CI/CD | GitHub Actions, GHCR, GitHub Pages, TestPyPI/PyPI | free | |
| Hosting | Free VM (Option A) for demo | free | |
