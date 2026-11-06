# Xianyu Assistant (Xianyu Seller Console) v3.5.18

> Release: 2026-11-06

This release adds **item polishing** (single / batch / scheduled), delivers a full **deep-space cyber visual overhaul** of the web console, and hardens AI reply timing against platform risk control.

## ✨ Highlights

- ✨ **Item polishing**: the platform's free exposure mechanism — polish one item, polish every on-sale item of an account, or schedule it with Cron.
- 🛰️ **Deep-space cyber UI overhaul**: neon cyan / cyber blue / electric violet light system, deep-space surfaces with ultra-thin glass, matrix grid backdrop, shimmering buttons, radar pulse indicators.
- 🛡️ **Humanized reply delay**: Gaussian-distributed jitter breaks the fixed-cadence automation fingerprint that platform risk control looks for.
- 🐛 **Unified version fallbacks**: fixes possible version mis-detection in the self-update checker when `app.version` is not injected.

## New Features

### ✨ Item Polishing

Polishing pushes an item back to the front of the platform's search and recommendation lists — a **free** source of public exposure. Three entry points are available:

| Entry | Where | Description |
|---|---|---|
| Polish one item | Goods → row action button "擦亮" | Visible for on-sale items only, calls the platform H5 polish API |
| Polish all on sale | Goods → header button "一键擦亮在售" | Polishes every on-sale item of the current account, 1s spacing between items to avoid rate limits |
| Scheduled polishing | Schedule Tasks → task type "自动擦亮" | Cron-driven batch polishing, optionally restricted to specific item IDs |

Backend endpoints:

- `POST /api/items/polish` — polish a single item, requires `goods:write`.
- `POST /api/items/batchPolish` — batch polish, requires `goods:write`.

The protocol layer uses the Xianyu H5 endpoint `mtop.taobao.idle.item.polish` (version `2.0`) and reuses the existing per-account rate limiting, concurrency caps and risk-control circuit breaker. The platform caps daily polish counts per item; repeated calls return a business error, and the UI reports per-item success/failure details.

> Tip: the platform caps polish counts per day — a daily scheduled task is enough, no need to repeat manually.

### 🛰️ Deep-Space Cyber Visual Overhaul

The entire console was visually rebuilt, with both light and dark themes reworked:

- **Neon token system**: introduces neon cyan `#00f2fe`, cyber blue `#4facfe`, electric violet `#9d4edd` and pulse emerald `#10b981`, forming a consistent glow / halo / outline language.
- **Deep-space surfaces & ultra-thin glass**: the dark theme base is upgraded to `#060a12`; panels use 24px ultra-thin frosted glass with neon-cyan outlines, layered edge lights and inner shadows for depth.
- **Matrix grid backdrop**: a 32px geometric dot grid plus three radial light halos create a console atmosphere.
- **Micro-interactions**: shimmer sweep on primary buttons, card lift with ambient glow, magnetic sidebar navigation with a neon energy rail, radar-style breathing status lights, glowing scrollbars, gradient-clipped headings.
- **Accessibility respected**: fully honours `prefers-reduced-motion` and `prefers-reduced-transparency`, degrading to a static solid-color interface when those preferences are enabled.

## Performance & Security

### 🛡️ Humanized AI Reply Delay

- Previously AI replies fired at a strictly fixed configured interval — a classic automation signature.
- `ReplyConfigProvider.getDelaySeconds()` now applies a **Gaussian** random factor (σ = 0.15, roughly 95% within ±25%, floor of 3 seconds) on top of the base delay, producing a natural human-like cadence.
- Platform-side rate limits, concurrency caps and risk-control circuit breaking are unchanged.

### 🐛 Unified Version Fallbacks

- Stale fallback version values in `application.yaml`, `SystemController`, `McpServerController`, `SelfUpdateService`, `UpdateCheckService`, `AppLayout.vue`, `UpdateDialog.vue` and `AboutPanel.vue` are now aligned with the current release.
- This fixes version info and the self-update check reporting an outdated version when `app.version` is not provided.

### 🧹 Code Quality

- `WebSocketController.CaptchaMouseReqDTO` now declares `@EqualsAndHashCode(callSuper = false)`, removing the Lombok subclass compiler warning.
- Frontend `useGoodsManager.ts` replaces `catch (err: any)` with `catch (err: unknown)` and strict narrowing — ESLint reports zero warnings.

## Download & Install

| Platform | Asset | Arch | Notes |
|---|---|---|---|
| Linux / Windows / macOS | [XianYuSellerConsole-3.5.18.jar](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar) | x86_64 / arm64 | Full server JAR (~287 MB), frontend bundled |
| Checksum | [XianYuSellerConsole-3.5.18.jar.sha256](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sha256) | — | SHA-256 checksum |
| Signature | [XianYuSellerConsole-3.5.18.jar.sig](https://github.com/yuchen0x1/Xianyu-Assistant/releases/download/v3.5.18/XianYuSellerConsole-3.5.18.jar.sig) | — | Official Ed25519 signature |

> 📦 No prebuilt Docker image is published: build locally with the multi-stage `Dockerfile` (see install notes below).
>
> 📱 The Android client ships as Flutter source with the repository; no prebuilt APK is attached to the Release.

## Upgrade Notes

**Fresh install (Linux one-liner)**

```bash
bash install.sh
```

The script installs JDK 21, downloads the latest JAR from the GitHub Release and verifies both SHA-256 and the Ed25519 signature.

**Docker**

```bash
docker build -t xianyu-seller-console:local .
docker run -d -p 12400:12400 xianyu-seller-console:local
```

**Upgrading from v3.5.17**

- **Recommended: in-app online update or auto-update** — the auto-update scheduler is on by default, so your server keeps itself current from now on.
- The `exit` restart mode (default) requires a process supervisor: make sure your systemd unit sets `Restart=always` (see `docs/production-deployment.md`); for bare `nohup java -jar` deployments use `XIANYU_ASSISTANT_SELF_UPDATE_RESTART_MODE=self`.
- You can also re-run `bash install.sh` for a manual upgrade.
- Data (SQLite database, settings) lives inside the application directory and is preserved across upgrades; back up `dbdata/` beforehand as usual.

**Configuration migration**

- No breaking configuration changes in v3.5.18; no new Flyway migration was added and the database schema stays compatible.
- Set `XIANYU_ASSISTANT_AUTO_UPDATE_ENABLED=false` if you do not want automatic updates.

## System Requirements

- **Java 21** (required; `install.sh` can install it for you)
- **Docker 20.10+** (container deployments only)
- SQLite is built in — no external database required
- Deployment shapes: JAR / Docker / systemd + Nginx reverse proxy (see [docs/production-deployment.md](docs/production-deployment.md))
- Default port `12400`; open the web console in a browser

## Changelog

Full comparison: [v3.5.17...v3.5.18](https://github.com/yuchen0x1/Xianyu-Assistant/compare/v3.5.17...v3.5.18)

**Verification**: all 651 backend unit tests pass (0 failures / 0 errors / 3 skipped); frontend ESLint, TypeScript type-check, 34 Vitest cases and the Vite production build pass; Flutter `analyze` and `flutter test` are green.
