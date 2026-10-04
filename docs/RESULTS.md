# Results Log

Every measured number goes here. CV bullets and README may only quote numbers from this file.

**Hardware "laptop-5800H":** AMD Ryzen 7 5800H (8 cores / 16 threads), 15.3 GB RAM, Windows 11,
Temurin OpenJDK 21.0.12, default JVM flags (Gradle test worker).

| Date | Metric | Value | Conditions (hardware, dataset/split, settings) | How measured (script/command) |
|---|---|---|---|---|
| 2026-10-04 | HNSW recall@10 vs FlatIndex, simple neighbour selection (task 1.4), L2 | 0.9360 | laptop-5800H; 10,000 seeded Gaussian vectors, dim 32, 200 queries; M=16, efConstruction=200, efSearch=64 | `HnswRecallTest` (`make test`), stdout in `thedal-core/build/test-results/test/TEST-dev.thedal.index.HnswRecallTest.xml` |
| 2026-10-04 | same, DOT | 0.9695 | same | same |
| 2026-10-04 | same, COSINE | 0.9605 | same | same |
| 2026-10-04 | HNSW recall@10, **heuristic** selection (task 1.5, now default), L2 / DOT / COSINE | 0.9510 / 0.9645 / 0.9480 | laptop-5800H; same data and params as above (uniform Gaussian, ef=64) | `HnswRecallTest.defaultParamsReachTargetRecallForEveryMetric` |
| 2026-10-04 | Simple vs heuristic, uniform Gaussian, L2, recall@10 at ef=16 / ef=64 | simple 0.7020 / 0.9360; heuristic 0.7085 / 0.9510 | laptop-5800H; 10k vectors dim 32, 200 queries, M=16, efC=200 | `HnswRecallTest.heuristicVersusSimpleSelection` |
| 2026-10-04 | Simple vs heuristic, clustered (50-centre Gaussian mixture), L2, recall@10 at ef=16 / ef=64 | simple 0.9175 / 0.9720; heuristic 0.9775 / 1.0000 | same | same |

Notes on task 1.5: the heuristic's gain is large on clustered data (missed neighbours at ef=16 drop
from 8.3% to 2.3%) and small on uniform L2 data. On uniform data with DOT and COSINE it was slightly
*lower* than simple selection (0.9645 vs 0.9695, 0.9480 vs 0.9605). Real embeddings are clustered,
so the heuristic stays the default.
