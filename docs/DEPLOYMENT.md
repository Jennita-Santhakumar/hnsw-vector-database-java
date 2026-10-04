# Deployment — ThedalDB

## What gets deployed
1. **Docker image** → GHCR (`ghcr.io/<user>/hnsw-vector-database-java`) on every version tag; multi-arch (amd64 + arm64) via buildx.
2. **Benchmark report** → GitHub Pages from `site/` (static — GitHub Pages is perfect here).
3. **Python client** → TestPyPI on tag (PyPI when stable) using trusted publishing (no tokens).
4. **Live demo** → free VM (Option A): caddy + thedal-server (data volume) + demo API. Or Option B: Render free web service for demo + server with a small dataset (note: free instances have limited RAM and ephemeral disk — load data on startup from a snapshot artifact).

## Notes
- JVM flags for small instances: `-XX:MaxRAMPercentage=70 -XX:+UseZGC` (or G1); document what you used in RESULTS.md.
- Data directory on a persistent volume; nightly snapshot copy as backup.

---

## Common setup (same for every project)

### 1. GitHub repository
1. Create a **public** repo on GitHub named `hnsw-vector-database-java`, paste the About description and Topics from `CLAUDE.md` (gear icon next to "About") (public = unlimited free GitHub Actions minutes and free GHCR storage for public images).
2. Copy this project folder's contents to the repo root so `CLAUDE.md` sits at the top level.
3. Add: `LICENSE` (MIT, or AGPL-3.0 if the project says so), `.gitignore`, `.env.example`, `README.md`.
4. Settings → Branches → protect `main`: require PR + passing status checks.
5. Settings → Secrets and variables → Actions: add deploy secrets (`VM_HOST`, `VM_USER`, `VM_SSH_KEY`, app secrets).
6. Enable Dependabot (`.github/dependabot.yml` for pip/uv, npm, gradle, github-actions, docker).

### 2. CI pipeline — `.github/workflows/ci.yml` (runs on every push and PR)
Jobs:
- `lint` — ruff / eslint / spotless check
- `typecheck` — mypy / tsc
- `test` — unit + integration (use `services:` for Postgres/Redis containers)
- `e2e` — Playwright against `docker compose up` (main branch only, to save time)
- `secrets` — gitleaks
- `build` — docker build for every service (no push on PRs)
Add the CI status badge to `README.md`.

### 3. CD pipeline — `.github/workflows/deploy.yml` (on push to `main` or on a version tag)
1. Build and push images to `ghcr.io/<github-user>/<repo>-<service>:<git-sha>` and `:latest`.
2. SSH into the VM (`appleboy/ssh-action`), `docker compose -f docker-compose.prod.yml pull && up -d`.
3. Run migrations container, then hit `/readyz`; if it fails, roll back to previous tag.

### 4. Free hosting options (verify current limits before relying on them — free tiers change)
**Option A — one free VM running everything (recommended for these projects)**
- Oracle Cloud "Always Free" Ampere A1 ARM VM (multi-core, generous RAM). Sign-up asks for card verification; Always Free resources are not charged. Build images for `linux/arm64` (use `docker buildx` in CI).
- Docker Compose for all services + **Caddy** reverse proxy for automatic HTTPS (Let's Encrypt).
- Free domain: DuckDNS subdomain (e.g. `yourapp.duckdns.org`) or an `.is-a.dev` subdomain via GitHub PR.
- Backups: nightly `pg_dump` cron to a second disk / downloaded artifact.
- Monitoring: Prometheus + Grafana + Uptime Kuma in the same compose file (`docker-compose.ops.yml`).

**Option B — managed free tiers (if you can't get a VM)**
- Frontend: Vercel Hobby or Netlify. Static sites: GitHub Pages.
- API: Render free web service (sleeps when idle; first request is slow) or Koyeb free.
- Postgres: Neon or Supabase free (both support PostGIS / pgvector extensions).
- Redis: Upstash free.
- ML demos: Hugging Face Spaces (free CPU hardware, Gradio or Docker Space).
- Trade-off: no always-on background workers on most free tiers — this doc says per project what changes.

### 5. Release checklist
- [ ] CI green on `main`; latest images deployed; `/readyz` OK on the live URL
- [ ] README: live link, GIF/screenshot, architecture diagram, results table, setup steps
- [ ] `docs/RESULTS.md` has real measured numbers
- [ ] Demo account / sample data so a recruiter can try it in 30 seconds
- [ ] Git tag `vX.Y.Z` + GitHub Release with notes
- [ ] 2-minute demo video (OBS Studio, free) linked in README
- [ ] Short blog post (LinkedIn / Medium / dev.to) linked in README
