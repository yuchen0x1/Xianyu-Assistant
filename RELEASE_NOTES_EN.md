# Xianyu Assistant (Xianyu Seller Console) v3.5.22

> Release: 2026-11-12

This release adds a verifiable online update pipeline with stable, beta, and dev channels.

## ✨ Highlights

- Online updates now support download retries, cancellation, disk-space preflight, real speed, and ETA reporting.
- Downloads must pass SHA-256 and official Ed25519 signature verification before atomic replacement and restart.
- The GitHub Release workflow runs backend tests, frontend tests, and TypeScript checks before publishing.
- Added [`docs/AUTO_UPDATE.md`](docs/AUTO_UPDATE.md) with configuration, release, rollback, and external-validation guidance.

## Verification

- 690 backend tests passed, with 3 skipped.
- 40 frontend tests passed; type-check and production build passed.

# Xianyu Assistant (Xianyu Seller Console) v3.5.21

> Release: 2026-11-12

This release focuses on account isolation, security defaults, and reliable batch operations. It fixes a high-severity data-panel scope issue, adds rate and timeout protection to batch workflows, and improves Windows browser discovery, error responses, and build diagnostics.

## ✨ Highlights

- 🔐 **Account-scope security fix**: data-panel statistics, trends, rankings, and operation logs now strictly follow the current user's authorized accounts.
- ⚙️ **Reliable batch operations**: batch polish, off-shelf, and price update now share explicit size limits, rate limits, and request timeouts; one-click polishing submits bounded batches.
- 🪟 **Windows browser compatibility**: cookie fallback refresh can discover Chrome / Edge and Playwright browser caches on Windows and macOS.
- 🩺 **More accurate diagnostics**: unknown API paths return HTTP 404, Actuator paths bypass SPA fallback, and `/actuator/info` exposes build version and time.

## Account Isolation & Security

- Fixed a MyBatis same-name overload issue that silently discarded scoped data-panel queries. Non-admin users can no longer see statistics or rankings for unauthorized accounts.
- Added mapper overload guard tests and two-account SQLite assembly regressions covering admin, empty-scope, and single-account results.
- Registration and password changes now share one password policy, so the change-password flow cannot bypass the registration minimum length.

## Batch Operations & Platform Compatibility

- Batch polishing has a 50-item per-request limit and a dedicated rate limit; the “polish all on-sale items” branch is capped as well.
- Batch off-shelf, polishing, and price update use a 120-second request timeout, preventing a frontend timeout from hiding continued server-side work and causing duplicate retries.
- One-click polishing submits serial batches of 50 items, avoiding oversized requests for large accounts.
- Windows and macOS discovery checks system Chrome, Edge, and Playwright cache locations and reports installation guidance when no browser is found.

## Error Responses & Observability

- Unknown `/api/` paths now return HTTP 404 with `code=404` instead of an HTTP 200 error envelope.
- `/actuator/` paths are excluded from SPA fallback. Prometheus remains closed by default and can be enabled explicitly with `XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS`.
- `/actuator/info` now includes `build.version` and `build.time` so operators can identify the running build.
- CI adds a frontend lint gate, and unused Pinia code, dead code, and unreferenced components were removed.

## Upgrade Impact

- No database migration and no breaking configuration changes.
- Batch price update changes only the assistant's local goods records; verify remote Xianyu goods on the official page.
- Actuator exposure remains `health,info` by default; set the exposure list explicitly when Prometheus is needed.

## Downloads

| Platform | File | Arch | Notes |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.21.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar) | x86_64 / arm64 | Full server JAR with the web console included |
| Checksum | [XianYuSellerConsole-3.5.21.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sha256) | — | SHA-256 checksum |
| Signature | [XianYuSellerConsole-3.5.21.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sig) | — | Official Ed25519 signature |

> 📦 No pre-built Docker image: build locally from the multi-stage `Dockerfile`.
>
> 📱 The mobile (Android) client ships as Flutter source; no pre-built APK is attached to the release.

## Installation & Upgrade

**Fresh install (Linux one-liner)**

```bash
bash install.sh
```

**Docker**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**Upgrading from v3.5.20**

- Recommended: use the in-app online update, auto-update, or run `bash install.sh` again.
- Back up `dbdata/`, especially the SQLite database, JWT secret, and credential key files.
- With the default `exit` restart mode, ensure systemd / Docker / your supervisor restarts the process automatically.

## System Requirements

- **Java 21** (required; `install.sh` can install it)
- **Docker 20.10+** (container deployments only)
- SQLite is built in — no external database required
- Default port `12400`; open the web console in a browser

## Changelog

Full comparison: [v3.5.20...v3.5.21](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.20...v3.5.21)

**Verification**: the full backend suite, frontend Vitest / type-check / ESLint / production build, Flutter `analyze` / `test`, and the Android debug APK build passed. The Docker Linux engine remains blocked by a host-level stale socket in Docker Desktop and is not treated as a project build failure.
