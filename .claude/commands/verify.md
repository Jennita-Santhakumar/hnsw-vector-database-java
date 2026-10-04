Run the full quality gate and report results without changing code unless I approve:

1. `make check` (lint, format check, type check, tests with coverage).
2. `docker compose build` and `docker compose up -d`, then hit `/healthz` and `/readyz`.
3. Scan for committed secrets, TODOs without an issue reference, and debug prints.
4. Compare `docs/TASKS.md` ticked items against the code — flag anything ticked but not actually implemented.

Output a pass/fail table and a prioritized list of fixes.
