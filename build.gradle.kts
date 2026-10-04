// Root project has no code. Shared Java, test, Spotless and SpotBugs settings live in
// buildSrc/src/main/kotlin/thedal.java-conventions.gradle.kts and are applied by each module.

// Same opt-in build-output redirect as the java conventions (see thedal.buildRoot there).
providers.gradleProperty("thedal.buildRoot").orNull?.let { root ->
  layout.buildDirectory = file("$root/root")
}
