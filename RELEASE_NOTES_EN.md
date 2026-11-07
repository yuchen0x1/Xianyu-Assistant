# Xianyu Assistant (Xianyu Seller Console) v3.5.20

> Release: 2026-11-08

This is an **operational efficiency release**: it introduces the Todo Center that consolidates the four most frequent seller tasks into a single entry, completes batch off-shelf and batch price update in Goods management, and upgrades the mobile client from a plain WebView shell to a native workbench.

## ✨ Highlights

- 📋 **Todo Center**: one page aggregates pending shipment, pending reply, delivery review, and review confirmation counts, filtered by account permissions, with one click to the matching work page.
- 📦 **Batch off-shelf**: select goods in the list and take them offline in one action — up to 50 items per batch with per-item success/failure details.
- 💰 **Batch price update**: apply one new price to all selected goods, using the same money rules as single-item editing, with per-item failure isolation and no touching of titles or descriptions.
- 📱 **Mobile native workbench**: the Flutter home screen now provides service health, connection settings, and four high-frequency shortcuts, with one tap into the full console.

## New Features

### 📋 Todo Center

- New aggregate endpoint `GET /api/todo/overview`: summarizes four categories of todos within the current user's RBAC account scope — pending shipment orders, sessions awaiting reply, delivery records awaiting review, and reviews awaiting confirmation; a failure in one data source never breaks the others.
- New **Todo Center** page in the web console (first entry of the Workbench group): four stat cards, category tabs, auto refresh, time formatting; clicking any item jumps directly to `Pending orders` / `Messages` / `Orders` / `Reviews`.
- The mobile home screen exposes a Todo Center entry that reuses the full console session; no credentials are stored in the native layer.

### 📦 Batch off-shelf

- New `POST /api/publish/batchOffShelf`: max 50 items per batch, 1-second delay between items to avoid platform rate limits, a dedicated rate-limit bucket (3 calls/minute), per-item success/failure details; a single failure never aborts the batch.
- Goods list gains multi-select: only on-sale items are selectable, both the desktop table and mobile cards support row checkboxes and select-all (with indeterminate state); switching account, status filter, or page automatically clears the selection to prevent cross-account/cross-page mistakes.
- The action bar shows the selected count; off-shelf is a destructive action guarded by confirmation.

### 💰 Batch price update

- New `POST /api/items/batchUpdatePrice`: applies one new price to all selected goods with `goods:write` permission, RBAC account validation, and a dedicated rate-limit bucket; returns per-item success/failure details.
- A dedicated `updateItemPrice` at the data layer touches only the price and update time — titles, descriptions, and images are never overwritten; money validation stays as before (greater than 0, at most two decimals, capped at 100,000,000).
- **Explicit boundary**: this only updates the assistant's *local* goods records. Both the UI and the API message state "verify the remote Xianyu item on the official page" — remote price changes are never faked.

### 📱 Mobile native workbench

- The Flutter home screen upgrades from a plain WebView shell to a native workbench: service health and version display, service URL configuration with persistence, pull-to-refresh health probe (`/api/system/health`), Todo Center entry, shortcuts for pending shipment / pending reply / pending review / pending review-confirmation, and an "Open full console" button.
- The full console supports deep-link loading of a specific business route; the WebView navigation allowlist remains strictly limited to the configured service scheme/host/port.
- The native layer stores no login token — authentication stays inside the WebView console session.

## Upgrade Impact

- No breaking configuration changes, no database migrations.
- New endpoints reuse the existing permission model: `dashboard:read` for the todo center, `publish:write` for batch off-shelf, `goods:write` for batch price update.
- Batch price update writes only local records; no goods re-sync is required after upgrading.

## Downloads

| Platform | File | Arch | Notes |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.20.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar) | x86_64 / arm64 | Full server JAR (~287 MB), web console included |
| Checksum | [XianYuSellerConsole-3.5.20.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sha256) | — | SHA-256 checksum |
| Signature | [XianYuSellerConsole-3.5.20.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.20/XianYuSellerConsole-3.5.20.jar.sig) | — | Ed25519 official signature |

> 📦 No pre-built Docker image: build locally from the multi-stage `Dockerfile`.
>
> 📱 The mobile (Android) client ships as Flutter source in the repository; no pre-built APK is attached to the release.

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

**Upgrading from v3.5.19**

- **Recommended: in-app online update or auto-update** — the flow is unchanged from previous releases.
- Back up the `dbdata/` directory beforehand as usual.
- The `exit` restart mode (default) requires a process supervisor: make sure your systemd unit sets `Restart=always`.

## System Requirements

- **Java 21** (required; `install.sh` can install it for you)
- **Docker 20.10+** (container deployments only)
- SQLite is built in — no external database required
- Default port `12400`; open the web console in a browser

## Changelog

Full comparison: [v3.5.19...v3.5.20](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.19...v3.5.20)

**Verification**: three new regression suites (`TodoServiceImplTest`, `PublishServiceImplBatchOffShelfTest`, `ItemServiceImplBatchUpdatePriceTest`); all 662 backend tests pass (0 failures / 0 errors / 3 skipped); frontend type-check and 34 Vitest cases, Flutter `analyze`/`test`, and the Android debug APK build all pass.
