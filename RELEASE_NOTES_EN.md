# Xianyu Assistant (Xianyu Seller Console) v3.5.19

> Release: 2026-11-07

This is an **online self-update availability fix release**: it resolves the false "not running as a standalone JAR (e.g. IDE dev mode); online update unavailable" warning shown in the Version Update dialog when the app is deployed as a standalone JAR on Windows.

## ✨ Highlights

- 🛠️ **Online self-update restored on Windows standalone JAR deployments**: instances started with `java -jar` are no longer mis-detected as IDE dev mode — the full Check update → Update now → auto download & restart pipeline works.
- 🔧 **Self-relaunch hardening**: `restart-mode=self` now relaunches with the currently running JVM's own `java` executable instead of relying on `PATH`.

## Bug Fixes

### 🛠️ Self-update mis-detection on Windows standalone JAR deployments

**Symptom**: running `java -jar XianYuSellerConsole-<version>.jar`, the Version Update dialog showed "未以独立 JAR 方式运行（如 IDE 开发模式），在线更新不可用" (not running as a standalone JAR; online update unavailable).

**Root cause**: the old logic located the current JAR solely via `ProcessHandle.current().info().arguments()`. Windows JVMs frequently cannot read their own process arguments (returns empty), so detection failed and the app reported itself as non-updatable. Linux — the primary deployment platform — exposes arguments, which is why this never surfaced before.

**Fix**: the JAR detection chain now has three levels:

1. Process launch arguments (path after `-jar`, original logic).
2. The `XIANYU_ASSISTANT_JAR_PATH` environment variable (original logic).
3. **New**: Spring Boot `ApplicationHome` — resolves the absolute path of the actually running JAR from the class-loading source; in IDE dev mode this resolves to the classes directory (not a `.jar` file) and is filtered out, so no false positive.

### 🔧 Relaunch command fallback hardening

When `restart-mode=self` relaunches and even `ProcessHandle...command()` is unavailable, the fallback target is now `$JAVA_HOME/bin/java(.exe)` — the exact JVM currently running — instead of a bare `java` resolved from `PATH`, which could pick a different runtime version.

## Upgrade Impact

- **Linux servers (systemd / Docker / install.sh)**: no behavior change; the original logic already worked there.
- **Windows standalone JAR deployments**: "Update now" works again after upgrading; for self-relaunch restarts, keep using a process supervisor or `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`.
- No breaking configuration changes and no database migration.

## Download & Install

| Platform | Asset | Arch | Notes |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.19.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar) | x86_64 / arm64 | Full server JAR (~287 MB), frontend bundled |
| Checksum | [XianYuSellerConsole-3.5.19.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sha256) | — | SHA-256 checksum |
| Signature | [XianYuSellerConsole-3.5.19.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sig) | — | Official Ed25519 signature |

> 📦 No prebuilt Docker image is published: build locally with the multi-stage `Dockerfile`.
>
> 📱 The Android client ships as Flutter source with the repository; no prebuilt APK is attached to the Release.

## Upgrade Notes

**Fresh install (Linux one-liner)**

```bash
bash install.sh
```

**Docker**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**Upgrading from v3.5.18**

- **Recommended: in-app online update or auto-update**. After upgrading, Windows users will no longer see the false warning in the update dialog.
- Back up `dbdata/` beforehand as usual.
- The `exit` restart mode (default) requires a process supervisor: make sure your systemd unit sets `Restart=always`.

## System Requirements

- **Java 21** (required; `install.sh` can install it for you)
- **Docker 20.10+** (container deployments only)
- SQLite is built in — no external database required
- Default port `12400`; open the web console in a browser

## Changelog

Full comparison: [v3.5.18...v3.5.19](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.18...v3.5.19)

**Verification**: 5 new cases in `SelfUpdateServiceTest`; all 655 backend tests pass (0 failures / 0 errors / 3 skipped); verified on real Windows: standalone JAR startup logs "在线自更新已就绪" (self-update ready).
