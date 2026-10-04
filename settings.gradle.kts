rootProject.name = "hnsw-vector-database-java"

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { mavenCentral() }
}

include("thedal-core", "thedal-server", "thedal-bench")
