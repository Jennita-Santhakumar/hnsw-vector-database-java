plugins { `kotlin-dsl` }

// Same opt-in build-output redirect as the java conventions (see thedal.buildRoot there).
providers.gradleProperty("thedal.buildRoot").orNull?.let { root ->
  layout.buildDirectory = file("$root/buildSrc")
}

dependencies {
  implementation(libs.spotless.gradle.plugin)
  implementation(libs.spotbugs.gradle.plugin)
}
