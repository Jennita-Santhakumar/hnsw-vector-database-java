// Index (Flat, HNSW), distance, storage (WAL, snapshot), collection and filter code.
plugins {
  `java-library`
  id("thedal.java-conventions")
}

// STANDARDS.md §8: at least 80% coverage on core domain logic. Glue modules are not gated.
tasks.jacocoTestCoverageVerification {
  violationRules { rule { limit { minimum = "0.80".toBigDecimal() } } }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
