# Xianyu Assistant (Xianyu Seller Console) v3.5.17

> Release: 2026-11-04

This release brings an **unattended auto-update** loop for server deployments, fixes how automatic delivery handles ambiguous send results, and hardens the CI supply chain.

## ✨ Highlights

- 🤖 **Unattended auto-update**: periodically checks the official GitHub Release and runs the full Download → Verify signature → Swap → Restart pipeline, so servers can run maintenance-free.
- 📦 **Automatic delivery reliability fix**: an unknown WebSocket send result is no longer treated as a failure — the order moves to "pending manual review" with an alert instead of being mis-logged.
- 🔐 **Supply chain hardening**: CI action pinned to a commit SHA, backend tests now run inside the Docker build, plus a vulnerability disclosure policy and security audit report.
- 🏗️ **Update-check logic extracted** into a standalone `UpdateCheckService`, shared by the controller and the auto-update scheduler — endpoint behavior unchanged.

## New Features

### 🤖 Unattended Auto-Update for Servers

A new `SelfUpdateAutoScheduler` (enabled **by default**):

- Checks the official GitHub Release every **6 hours** (first check 5 minutes after startup).
- On a new release it automatically performs: **download → SHA-256 + Ed25519 signature verification → atomic swap (rollback on failure) → restart per `restart-mode`**.
- The `exit` mode (default) combined with systemd `Restart=always` closes the loop of "auto-update + automatic restart afterwards" — zero manual intervention.

Related configuration (environment variables):

| Variable | Default | Description |
|---|---|---|
| `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED` | `true` | Master switch; set to `false` to disable |
| `XIANYU_ASSISTANT_AUTO_UPDATE_CHECK_INTERVAL_MS` | `21600000` | Check interval in ms (default 6 h) |
| `XIANYU_ASSISTANT_AUTO_UPDATE_INITIAL_DELAY_MS` | `300000` | First-check delay after startup in ms (default 5 min) |

> The in-app manual online update (`XIANYU_ASSISTANT_SELF_UPDATE_*`) is unaffected. Auto-update is only available when running as a standalone JAR.

## Bug Fixes

### 📦 Automatic delivery: unknown send results no longer misjudged

- Added `DeliveryOrderState` to centralize delivery-order state constants and the "awaiting manual review" detection, replacing raw `-1/0/1` literals.
- A WebSocket send result of **UNKNOWN** (the frame may have been written; success unconfirmed) is now persisted as **PENDING — awaiting manual review**, and an alert is raised if persisting that state fails. This prevents duplicate deliveries or lost delivery records for the same order.
- `MessageSendResult` semantics clarified: UNKNOWN means the outcome is unconfirmed and callers must not assume failure.

## Improvements

### 🏗️ Update-check logic extracted into `UpdateCheckService`

- The online-update check in `SystemController` (GitHub/Gitee source resolution, download host allowlist, 5-minute cache, version comparison) moved to a standalone service.
- The `/api/system/checkUpdate` endpoint behaves exactly as before and is now shared by the controller and the auto-update scheduler.

### 📖 Documentation

- `docs/production-deployment.md` now documents the systemd unit restart requirement (`Restart=always`) and the in-app auto-update.
- README configuration reference gained the `XIANYU_ASSISTANT_SELF_UPDATE_*` and `XIANYU_ASSISTANT_AUTO_UPDATE_*` variables.
- Fixed a wrong repository slug in the migration note.

## Performance & Security

### 🔐 CI supply chain hardening

- `subosito/flutter-action` in `ci.yml` is pinned to a full commit SHA to protect against tag hijacking.
- The Dockerfile now **actually runs the backend tests** during image build (no more `-DskipTests`).
- Frontend `minimatch` overridden to `^11.0.1`, clearing npm audit findings.
- Added [`SECURITY.md`](SECURITY.md) (vulnerability disclosure policy) and [`security-audit-report.md`](security-audit-report.md).

## Download & Install

| Platform | File | Architecture | Notes |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.17.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar) | x86_64 / arm64 | Full server JAR (~287 MB), frontend bundled |
| Checksum | [XianYuSellerConsole-3.5.17.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sha256) | — | SHA-256 checksum |
| Signature | [XianYuSellerConsole-3.5.17.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sig) | — | Official Ed25519 signature |

> 📦 No prebuilt Docker image is published: build the multi-stage image locally with the `Dockerfile` (see below).
>
> 📱 The mobile app (Android) ships as Flutter source in the repository; no prebuilt APK is attached to this release.

## Upgrade Instructions

**Fresh install (Linux one-liner)**

```bash
bash install.sh
```

The script installs JDK 21, downloads the latest JAR from GitHub Releases, and enforces SHA-256 + Ed25519 signature verification.

**Docker deployment**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**Upgrading from v3.5.16**

- **Recommended: in-app online update or auto-update** — with v3.5.17 the auto-update scheduler is on by default, so your server keeps itself current from now on.
- The `exit` restart mode (default) requires a process manager: make sure your systemd unit sets `Restart=always` (see `docs/production-deployment.md`); for bare `nohup java -jar` deployments use `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`.
- Alternatively re-run `bash install.sh` for a manual upgrade.
- Data (SQLite database, configuration) lives in the app directory and is preserved; as always, back up the `dbdata/` directory before upgrading.

**Configuration migration**

- No breaking configuration changes in v3.5.17. Set `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false` if you do not want automatic updates.

## System Requirements

- **Java 21** (required; `install.sh` can install it automatically)
- **Docker 20.10+** (container deployment only)
- SQLite is embedded — no external database needed
- Deployment options: JAR / Docker / systemd + Nginx reverse proxy (see [docs/production-deployment.md](docs/production-deployment.md))
- Default port `12400`; access the web console from your browser

## Changelog

Full comparison: [v3.5.16...v3.5.17](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.16...v3.5.17)

| Commit | Type | Summary |
|---|---|---|
| [`417e7cc`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/417e7cc) | feat | Unattended auto-update scheduler + `UpdateCheckService` extraction |
| [`df2d575`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/df2d575) | fix | Auto-delivery: UNKNOWN send results go to "pending manual review" |
| [`ebce71b`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/ebce71b) | chore(security) | CI supply-chain hardening, SECURITY.md and audit report |
| [`f736439`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/f736439) | fix(dev) | `start-dev.ps1` handles project paths containing spaces |
| [`64216ac`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/64216ac) | docs | v3.5.16 doc wrap-up; fixed wrong repo slug in migration note |
| [`56a05a2`](https://github.com/yuchen0x1/Xianyu-Assistant/commit/56a05a2) | chore(release) | Version alignment to 3.5.17 |

**Verification**: added `SelfUpdateAutoSchedulerTest` (13 cases), migrated `UpdateCheckServiceTest`, extended `AutoDeliveryServiceImplTest` and `XianyuWebSocketClientSendResultTest`; the full backend suite, frontend type check and build all pass.
