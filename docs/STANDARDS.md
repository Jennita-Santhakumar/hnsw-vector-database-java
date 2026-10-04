# Production Standards (applies to every task)

These rules are non-negotiable. A task is NOT done until it meets them.

## 1. Definition of Done (per task)
- [ ] Code implements exactly what the task in `docs/TASKS.md` says — no extra features.
- [ ] Unit tests written for new logic; integration test if it touches DB, queue, or HTTP.
- [ ] `make check` passes locally (lint + format + type-check + tests).
- [ ] No secrets, tokens, or personal data committed. New config goes in `.env.example`.
- [ ] Public functions/classes have docstrings; non-obvious decisions have a short comment.
- [ ] Task checkbox ticked in `docs/TASKS.md`.
- [ ] If the task produced a measurable number (latency, accuracy, recall), record it in `docs/RESULTS.md`.
- [ ] A conventional commit message is proposed.

## 2. Zero-cost rule
- Only free and open-source libraries, models, and datasets. Hosted services must be on a permanent free tier.
- If a task appears to need a paid service or API key with billing, STOP and ask the user. Do not add it.
- Record every dataset/model and its license in `docs/DATA_CARD.md` (create it when the first one is added).

## 3. Git workflow
- `main` is always deployable. Work on branches: `feat/<short-name>`, `fix/<short-name>`, `chore/<short-name>`.
- Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, `chore:`, `ci:`, `perf:`.
- Small commits — one logical change each. Open a PR to `main`; merge only when CI is green.

## 4. Configuration (12-factor)
- All config from environment variables, loaded through ONE typed settings module
  (Python: `pydantic-settings`; TypeScript: `zod`-validated env; Java: a single `Config` record).
- App must fail fast at startup with a clear message if required config is missing.
- `.env.example` lists every variable with a safe dummy value and a comment.

## 5. APIs
- Versioned under `/api/v1`. OpenAPI/Swagger docs auto-generated and reachable.
- One error shape everywhere: `{"error": {"code": "SNAKE_CASE", "message": "human text", "details": {}}}`.
- Validate every input at the boundary. Return 4xx for client errors, never 500.
- List endpoints are paginated (cursor or limit/offset with a max limit).
- Rate-limit public write endpoints.

## 6. Logging, health, metrics
- Structured JSON logs with a `request_id` on every line. Never log passwords, tokens, raw audio, phone numbers, or exact home locations.
- `GET /healthz` (process alive) and `GET /readyz` (DB/queue reachable).
- `GET /metrics` in Prometheus format: request count, latency histogram, error count, plus domain metrics.

## 7. Security baseline
- Passwords hashed with argon2 or bcrypt. JWTs short-lived; secrets from env.
- CORS allowlist (no `*` in production).
- Containers run as non-root. Dependencies scanned (Dependabot + `pip-audit` / `pnpm audit`).
- Uploaded files: check type and size, store under random keys, never trust the filename.
- `gitleaks` runs in CI to block committed secrets.

## 8. Testing
- Test pyramid: many unit tests, fewer integration tests, a few end-to-end tests.
- Integration tests use real Postgres/Redis via Docker (Testcontainers or the compose stack), not mocks.
- Target ≥ 80% coverage on core domain logic (not on glue code).
- Every bug fix adds a regression test first.

## 9. Docker
- Multi-stage builds, slim base images, pinned versions, `HEALTHCHECK`, non-root user.
- `docker compose up` must start the full stack locally with no manual steps beyond copying `.env.example` to `.env`.

## 10. Language tooling
- **Python 3.12**: `uv` for deps, `ruff` (lint + format), `mypy --strict` on `src/`, `pytest`, `pytest-cov`.
- **TypeScript**: `pnpm`, `strict: true`, ESLint, Prettier, Vitest for units, Playwright for E2E.
- **Java 21**: Gradle (Kotlin DSL), Spotless (google-java-format), JUnit 5, AssertJ.

## 11. Documentation the repo must end with
- `README.md`: one-line pitch, live demo link, demo GIF/screenshot, architecture diagram, features,
  tech stack, results table (from `docs/RESULTS.md`), local setup in ≤ 5 commands, license.
- `docs/adr/NNNN-title.md`: one short Architecture Decision Record per major choice.
- `docs/RESULTS.md`: every measured number with date, hardware, and how it was measured.
