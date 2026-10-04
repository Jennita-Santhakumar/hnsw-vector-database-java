# Results Log

Every measured number goes here. CV bullets and README may only quote numbers from this file.

**Hardware "laptop-5800H":** AMD Ryzen 7 5800H (8 cores / 16 threads), 15.3 GB RAM, Windows 11,
Temurin OpenJDK 21.0.12, default JVM flags (Gradle test worker).

| Date | Metric | Value | Conditions (hardware, dataset/split, settings) | How measured (script/command) |
|---|---|---|---|---|
| 2026-10-04 | HNSW recall@10 vs FlatIndex, simple neighbour selection (task 1.4), L2 | 0.9360 | laptop-5800H; 10,000 seeded Gaussian vectors, dim 32, 200 queries; M=16, efConstruction=200, efSearch=64 | `HnswRecallTest` (`make test`), stdout in `thedal-core/build/test-results/test/TEST-dev.thedal.index.HnswRecallTest.xml` |
| 2026-10-04 | same, DOT | 0.9695 | same | same |
| 2026-10-04 | same, COSINE | 0.9605 | same | same |
