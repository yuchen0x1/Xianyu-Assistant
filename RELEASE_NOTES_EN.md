# Release notes

Version-by-version notes for v3.5.16–v3.5.24. See the [complete history](docs/releases/release-notes.md) for older versions. Dates follow the project’s Asia/Shanghai convention; historical verification records were not rerun for this documentation update.

## v3.5.24

> Release: 2026-11-13

Webhook history and delivery reliability.

### Changes

- Delivery history shows the latest 100 records with status filters, refresh, cumulative attempts, HTTP status, failure reasons and retry times.
- An empty event selection subscribes to all events. Successful deliveries clear stale errors and retry times while retaining actual attempt counts.
- Initial dispatch and retries share an in-process guard and recheck delivery status and attempt limits. Invalid payloads stop retrying; queue rejection does not consume a send attempt.
- Delivery results no longer overwrite recently edited settings. Stable delivery IDs, accurate attempt headers and hashed long idempotency keys support receiver deduplication.
- Saving, testing and history loading provide clear feedback on desktop and mobile. Request errors omit credentials embedded in URLs.

### Upgrade notes

- No new database migration or dependency upgrade. Existing configuration and signature formats remain compatible. Receivers still need idempotency; the in-process guard does not provide cross-instance exactly-once delivery.

### Verification and limits

- At release time, 707 of 710 backend tests passed with 3 conditional skips, and 43 frontend tests passed. Desktop/mobile browser checks, main/tag CI and the release workflow passed. The downloaded release JAR passed SHA-256, Ed25519 and packaged-version checks. A real old-client installation upgrade and live transaction callbacks were not tested.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.24/XianYuSellerConsole-3.5.24.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.24) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.24) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.23...v3.5.24)

## v3.5.23

> Release: 2026-11-12

Multi-part delivery templates and reply matching.

### Changes

- Bind delivery templates to goods or SKUs, render order, buyer, item and card-key variables, and send messages in order with persisted segment checkpoints.
- Reserve keys before sending, commit on confirmed success, roll back on explicit failure, and retain unknown outcomes for manual review without consuming inventory again on retry.
- Backup import/export includes templates and template messages while retaining compatibility with older backups and single-message delivery settings.
- Auto-replies support multiple keywords with fuzzy, exact and regex matching. Similar-resource checks support forced refresh and include this project’s release baseline.

### Upgrade notes

- Additive migration V3_27 creates template tables and configuration fields. Back up dbdata, including the database and key files, before upgrading. Existing single-message configurations remain usable.
- Unknown delivery outcomes still require manual review. Checkpoint recovery and inventory protection do not prove receipt by the external platform.

### Verification and limits

- Historical release records report backend checks, 40 frontend tests, type checking, lint and production build passing. Historical tests were not rerun for this documentation update.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.23/XianYuSellerConsole-3.5.23.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.23) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.23) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.22...v3.5.23)

## v3.5.22

> Release: 2026-11-12

Update channels and Windows replacement fixes.

### Changes

- Stable, Beta and Dev channels preserve complete prerelease SemVer suffixes and select matching release assets.
- Downloads support retries, cancellation, disk-space checks, actual speed and ETA reporting, and temporary-file cleanup.
- Windows updates stage the new package before replacing the locked JAR after exit. The installer’s embedded Ed25519 public key now matches the application updater.
- Official packages continue to require SHA-256 and Ed25519 verification. Release preflight adds backend, frontend and type checks, with update configuration and troubleshooting documentation.

### Upgrade notes

- No new database migration. Windows replacement occurs after process exit; exit mode needs an external supervisor, while self mode schedules a restart.
- If an old installer fails signature verification due to its embedded key, obtain the current official installer rather than disabling verification. Beta/Dev updates require an actual matching prerelease.

### Verification and limits

- Historical release records report backend checks, 40 frontend tests, type checking and production build passing. Workflow success and package verification do not replace old-client installation testing.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.22/XianYuSellerConsole-3.5.22.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.22) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.22) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.21...v3.5.22)

## v3.5.21

> Release: 2026-11-12

Account isolation and batch-operation fixes.

### Changes

- Fix data-panel account filtering so non-admin statistics, trends, rankings and operations follow authorized account scope.
- Batch polishing is limited to 50 items with rate limiting and serial batches. Off-shelf, polishing and local price updates use 120-second request timeouts to reduce duplicate submissions after premature timeouts.
- Windows/macOS browser discovery checks installed Chrome, Edge and Playwright caches and reports missing-browser guidance.
- Registration and password changes share one password policy. Unknown APIs return HTTP 404, monitoring endpoints no longer fall back to HTML, and build information identifies the running version.

### Upgrade notes

- No new database migration. Batch price updates still affect local assistant records only. Prometheus remains disabled unless explicitly exposed through XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS.

### Verification and limits

- Historical records cover backend checks, 40 frontend tests, lint/type/build checks, Flutter analysis/tests, an Android debug build and isolated API/monitoring checks. Local Docker verification was limited by the host environment.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.21/XianYuSellerConsole-3.5.21.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.21) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.21) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.20...v3.5.21)

## v3.5.20

> Release: 2026-11-08

Todo center and native mobile dashboard.

### Changes

- The todo center groups pending shipments, replies, delivery reviews and rating confirmations within authorized accounts, with filtered lists and direct navigation. One failed source does not hide other categories.
- Bulk off-shelf operations accept up to 50 items with per-item results. Account, filter or page changes clear selection, and destructive actions require confirmation.
- Bulk price edits change only local price and update time, preserving titles, descriptions and images. The UI directs users to verify remote prices on the official platform.
- The Flutter home screen adds a saved server address, health/version display, pull-to-refresh and four todo shortcuts. The full console opens in a WebView restricted to the configured server origin.

### Upgrade notes

- No new database migration. Mobile requires a deployed server and does not store login tokens in the native layer. The release supplies the server JAR; mobile builds require source.

### Verification and limits

- Historical release records report backend checks, 34 frontend tests/build, Flutter analysis/tests and an Android debug build passing. Local price-edit success does not mean the remote platform price changed.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.20) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.20) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.19...v3.5.20)

## v3.5.19

> Release: 2026-11-07

Windows standalone-JAR update detection.

### Changes

- Fix Windows instances started with java -jar being incorrectly classified as IDE mode with online updates unavailable.
- JAR detection falls back from process arguments to XIANYU_ASSISTANT_JAR_PATH and the Spring Boot application location, while excluding classes directories.
- Self-restart falls back to the launcher of the current Java runtime when the process command is unavailable, avoiding a different Java version from PATH.

### Upgrade notes

- No new database migration. If an older Windows version cannot start an online update, manually replace the JAR and restart. This release fixes detection; locked-JAR replacement was hardened in v3.5.22.

### Verification and limits

- Historical records confirm updater tests and readiness detection for a Windows standalone JAR. This is not evidence of a full download/replacement/restart test on every platform.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.19/XianYuSellerConsole-3.5.19.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.19) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.19) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.18...v3.5.19)

## v3.5.18

> Release: 2026-11-06

Item polishing and console visual refresh.

### Changes

- Goods management adds single-item and bulk polishing with per-item results. Scheduled tasks support Cron-based polishing for selected items.
- Refresh light/dark themes, translucent panels, gradient buttons and navigation states, respecting reduced-motion and transparency preferences.
- AI replies add random variation to configured delays with a three-second minimum. Version fallbacks are aligned across the frontend and backend.

### Upgrade notes

- No new database migration. Polishing results and limits depend on platform responses; randomized timing does not guarantee risk-control avoidance or increased exposure. Large-batch limits and timeout protection were added in v3.5.21.

### Verification and limits

- Historical release records report backend checks, 34 frontend tests, lint/type/build checks and Flutter analysis/tests passing. Exposure gains and platform risk-control effects were not verified.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.18) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.18) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.17...v3.5.18)

## v3.5.17

> Release: 2026-11-04

Scheduled updates and uncertain-delivery review.

### Changes

- Scheduled updates check official releases five minutes after startup and every six hours thereafter, then download, verify, replace and restart according to configuration.
- Unknown delivery outcomes enter manual review instead of being treated as confirmed failures, with warnings if review status cannot be persisted.
- Manual and scheduled updates share version-check logic. Development startup handles JAR paths with spaces, and build checks strengthen dependency and supply-chain handling.

### Upgrade notes

- No new database migration. Scheduled updates require a standalone JAR and can be disabled with XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false. Default exit mode needs a restarting supervisor; manual mode requires a manual restart.
- Review order and chat history before retrying an unknown delivery. Pending review does not mean the platform confirmed failure.

### Verification and limits

- Historical records cover scheduler switches, mutual exclusion, failures/timeouts, unknown-delivery handling and authentication regressions. Cross-version installation still depends on process supervision and file permissions.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.17/XianYuSellerConsole-3.5.17.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.17) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.17) · [提交对比 / Changes](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.16...v3.5.17)

## v3.5.16

> Release: 2026-11-03

GitHub release migration and job lease protection.

### Changes

- Official releases move to GitHub at yuchen0x1/Xianyu-Assistant, with a full JAR, SHA-256 and Ed25519 signature. Compatibility with historical split archives remains available.
- Job lease fencing prevents stale workers from renewing or overwriting jobs already claimed by another worker, reducing duplicate-execution risks during recovery.
- Card keys distinguish reservations awaiting confirmation from completed deliveries. Previously used keys are conservatively backfilled as delivered to prevent accidental release.
- Rotate the release signing key and embed its new verification key. Align backend, frontend and mobile versions and update dependencies and release checks.

### Upgrade notes

- Compared with v3.4.10, migrations V3_25 and V3_26 add card-key delivery timestamps and job lease fencing. Back up the database and keys; old used keys are not automatically returned to available inventory.
- Older clients using the old public key or Gitee-only source need a manual migration with a trusted package from official GitHub. Do not bypass key rotation by disabling signature verification.

### Verification and limits

- Tagged code, migrations and the three existing GitHub release assets were checked. Historical records include backend/frontend checks; this old version and its cross-version installation were not rerun.

### 下载 / Downloads

[JAR](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar) · [SHA-256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar.sha256) · [Ed25519](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.16/XianYuSellerConsole-3.5.16.jar.sig)

Java 21 is required; the JAR includes the web console. This release has no prebuilt APK or Docker image; build those from the corresponding source tag.

[Release](https://github.com/yuchen0x1/Xianyu-Assistant/releases/tag/v3.5.16) · [源码 / Source](https://github.com/yuchen0x1/Xianyu-Assistant/tree/v3.5.16) · [迁仓后提交历史 / Post-migration history](https://github.com/yuchen0x1/Xianyu-Assistant/commits/v3.5.16/)
