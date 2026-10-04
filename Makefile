# ThedalDB developer commands. Thin wrappers over the Gradle wrapper so the same targets work on
# Windows (cmd) and Unix shells. Later phases add run, crash-test, bench-* and up targets.

ifeq ($(OS),Windows_NT)
# Pin cmd so recipes behave the same whether or not Git Bash's sh is on PATH. The explicit .\
# path is needed when NoDefaultCurrentDirectoryInExePath disables cmd's current-directory lookup.
SHELL := cmd.exe
GRADLEW := .\gradlew.bat
else
GRADLEW := ./gradlew
endif

.PHONY: help build test lint format check clean

help: ## List targets
	@echo build  - compile and assemble all modules
	@echo test   - unit + property tests
	@echo lint   - Spotless format check + SpotBugs
	@echo format - apply google-java-format via Spotless
	@echo check  - lint + compile + tests (must pass before a task is done)
	@echo clean  - delete build outputs

build:
	$(GRADLEW) assemble

test:
	$(GRADLEW) test

lint:
	$(GRADLEW) spotlessCheck spotbugsMain

format:
	$(GRADLEW) spotlessApply

check:
	$(GRADLEW) check

clean:
	$(GRADLEW) clean
