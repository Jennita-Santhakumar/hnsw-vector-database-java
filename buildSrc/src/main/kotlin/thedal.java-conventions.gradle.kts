// Shared build conventions for every ThedalDB Java module: Java 21 toolchain, strict compiler
// warnings, JUnit 5 + AssertJ + jqwik, Spotless (google-java-format) and SpotBugs.

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  java
  jacoco
  id("com.diffplug.spotless")
  id("com.github.spotbugs")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "dev.thedal"

version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
  // Compile-time only: @SuppressFBWarnings with a written justification at the exact site.
  compileOnly(lib("spotbugs-annotations"))
  testCompileOnly(lib("spotbugs-annotations"))
  testImplementation(platform(lib("junit-bom")))
  testImplementation(lib("junit-jupiter"))
  testImplementation(lib("assertj-core"))
  testImplementation(lib("jqwik"))
  testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  // -parameters lets jqwik report parameter names in shrunk counterexamples.
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
  testLogging {
    events("failed", "skipped")
    exceptionFormat = TestExceptionFormat.FULL
  }
}

jacoco { toolVersion = version("jacoco") }

tasks.named<Test>("test") { finalizedBy(tasks.named("jacocoTestReport")) }

tasks.named<JacocoReport>("jacocoTestReport") {
  reports {
    xml.required = true
    html.required = true
  }
}

spotless {
  java {
    target("src/**/*.java")
    googleJavaFormat(version("google-java-format"))
  }
}

spotbugs {
  toolVersion = version("spotbugs")
  effort = Effort.MAX
  reportLevel = Confidence.MEDIUM
}

tasks.withType<SpotBugsTask>().configureEach { reports.create("html") { required = true } }

// Test code uses idioms (fixed seeds, intentional nulls, unchecked fixtures) that SpotBugs flags
// as false positives; static analysis gates production code only.
tasks.named("spotbugsTest") { enabled = false }
