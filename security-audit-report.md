# Project Security Audit and Release Readiness Report

> Current product scope (2026-11-13): web only. Android/iOS sources and standalone desktop launch packaging have been removed. Native-mobile features and checks below are historical records, not current client offerings. Desktop and mobile users access the same web application in a browser.

Audit date: 2026-11-03  
Repository: `C:\Users\lenmo\Desktop\Xianyu Assistant`  
Scope: static repository review and local automated checks. No third-party targets were tested.

## Executive Summary

The repository contains a Java 21 / Spring Boot 3.5.16 backend, Vue 3 / TypeScript frontend, Flutter mobile client, SQLite storage, and a multi-stage Docker build. Review of authentication/session checks, RBAC and account scoping, image upload and remote image fetching, backup import/export, credential encryption, self-update validation, frontend sinks, and deployment files did not establish a remotely exploitable P0/P1 application vulnerability from the reviewed evidence.

One release-process issue was confirmed and fixed: the Docker build explicitly skipped backend tests. Docker packaging now runs Maven tests before producing the JAR. Backend tests, frontend tests/type-check/build, and Flutter analyze/tests pass locally. Docker validation could not run because the Docker Desktop Linux engine was unavailable. `npm audit` reports 14 moderate findings in the ESLint/minimatch dependency tree; the audit command configured in CI only fails for high severity, and the suggested automatic fix requires a breaking ESLint config upgrade, so dependencies were not force-upgraded.

This is not a certification that the system is secure. Runtime deployment, reverse-proxy headers, historical Git objects, third-party credentials, and live multi-user behavior were not externally validated.

## A. Project Analysis

- Backend: Java 21, Spring Boot 3.5.16, MyBatis-Plus, SQLite, JWT bearer authentication, server-side session validation, RBAC permission annotations, account-scope enforcement, WebSocket services, scheduled jobs, backups, and AI integrations.
- Frontend: Vue 3 and TypeScript built by Vite; Axios requests carry bearer tokens. The token is stored in browser `localStorage` (`vue-code/src/utils/request.ts`), so same-origin script execution could read it. No `v-html`, `innerHTML`, `insertAdjacentHTML`, or untrusted route redirect sink was found in the searched source tree.
- Mobile: Flutter app wrapping the local service in a WebView. Release Android configuration disables cleartext traffic; debug builds intentionally accept HTTP for local development.
- Data and secrets: SQLite under ignored `dbdata/`; credential values use AES-GCM support and local key files; JWT secret can be supplied by environment or generated into a local file.
- Deployment: multi-stage Dockerfile builds frontend and backend, and the runtime uses a non-root user. Application defaults bind to loopback; Docker overrides to `0.0.0.0`. No reverse-proxy or compose deployment configuration was present for review.
- Security boundaries: backend interceptor and service-side authorization are the enforcement boundary; frontend route guards are UX only. Image URL fetching applies host/protocol/address checks and avoids redirects. Sensitive backup endpoints require administrator authorization.
- Repository state: pre-existing user changes were present in six modified files and one untracked Java file before this audit. They were left untouched. The only tracked-file change from this audit is the Dockerfile test-gate change.

## B. Bug and Quality Fixes

| ID | Priority | Finding | Location | Fix and verification |
|---|---|---|---|---|
| Q-001 | P3 | Docker packaging used `-DskipTests`, allowing an image to be built with failing backend tests. | `Dockerfile`, Maven package step | Removed test skipping and enabled batch/no-transfer-progress Maven packaging. `clean package` completed with 638 tests, 0 failures, 3 skipped. |

No other functional bug was established from the scoped static checks. A broad claim that every possible project bug has been found is not supportable from static review and automated tests alone.

## C. Security Findings

### Confirmed release concern

- **SEC-001, Low / P3: unpinned Flutter GitHub Action.** `.github/workflows/ci.yml` uses `subosito/flutter-action@v2`, a movable tag, while other actions are SHA-pinned. This creates avoidable action supply-chain drift. Not modified because a trusted full commit SHA was not verified during this local audit. Pin to a verified upstream commit and use Dependabot to maintain it.

### Defense-in-depth and risk notes

- **SEC-002, Low: browser-readable session token.** `vue-code/src/utils/request.ts` stores the bearer token in `localStorage`. This is not by itself an authentication bypass, but any successful same-origin script injection can exfiltrate it. Consider a backend-managed HttpOnly session cookie with CSRF defenses, or shorten token lifetime and add strict CSP/XSS hardening. A storage migration is not safe to make without coordinating backend behavior and compatibility.
- **SEC-003, Low: client-side API secrets are visible while settings are open.** Settings screens request notification/SMTP credentials and keep values in browser state to support editing. Keep this behavior restricted to authorized administrators, return masked values by default, and require explicit replacement for secrets where feasible. No exploit was confirmed in the backend authorization path during this review.
- **SEC-004, Low: frontend security headers not visible.** No repository-owned reverse proxy/header policy was found. Verify production hosting configures suitable CSP, `frame-ancestors`/clickjacking protection, `X-Content-Type-Options`, and `Referrer-Policy` without breaking WebView/local use.
- **SEC-005, Informational: debug mobile build allows HTTP.** This is deliberate for local development and release builds reject HTTP. Do not distribute debug builds or use HTTP for credentials on untrusted networks.
- **SEC-006, Informational: local credential key is ignored.** `dbdata/credential-secret.key` exists locally but `dbdata/` is ignored by Git and Docker build context. This audit did not print its value. Full Git object/history scanning was not performed, so historical secret absence is not proven.

### Reviewed controls with no confirmed exploit

- JWT signature/expiration and server-side session state checks; authentication interceptor and permission annotations.
- Account-scope aspect and indirect-resource ownership checks.
- SSRF-sensitive remote image loading with HTTPS/host allowlist, DNS/private-address checks, pinned resolution, and redirects disabled.
- Upload size and image decode/dimension constraints.
- Administrator authorization, validation, and transactional handling for backup import/export.
- AES-GCM credential encryption and signed self-update verification for the official channel.
- Sensitive actuator endpoints are not exposed in the checked application configuration; health detail defaults to never.

These are static code/config observations, not proof against all runtime paths.

## D. Deployment Security

- Defaults bind the standalone service to `127.0.0.1`; container deployment listens on all container interfaces as expected. Public exposure must be controlled by host firewall/reverse proxy.
- Docker runtime uses `pwuser`, and the database is not declared as a published host port in the inspected Dockerfile.
- Dockerfile now runs tests before packaging. Docker syntax/build verification was not possible because the local Docker Desktop Linux engine was not running.
- No reverse-proxy or compose file was found; TLS termination, forwarded-header trust, request body limits, rate limiting, and security headers require deployment-owner verification.
- SQLite and backups are local files. Production operators must protect the data volume, key files, backups, and logs with filesystem permissions and encrypted/off-host backup policy appropriate to their data.
- Multi-user isolation and runtime public exposure were not exercised against a live deployment.

## E. Open-Source Release Security

- Root Apache-2.0 `LICENSE` is present.
- `.gitignore` and `.dockerignore` exclude `dbdata/`, database files, logs, and environment files.
- Pattern-based source scan found no recognizable private key header, GitHub token, AWS access key, or common API-key pattern; the one match was release signing code. This does not replace entropy scanning or full Git history review.
- `SECURITY.md` now documents the private vulnerability-reporting channel, supported versions, testing boundaries, and deployment self-check items.
- No generated third-party license inventory was found. Generate and review dependency notices/license obligations before redistributing packaged binaries.
- The Flutter GitHub Action is now pinned to `1a449444c387b1966244ae4d4f8c696479add0b2` (v2.23.0).
- The project `AGENTS.md` names `C:\Users\lenmo\Desktop\xianyu`, while the active workspace is `C:\Users\lenmo\Desktop\Xianyu Assistant`; resolve this discrepancy to avoid future tooling operating on the wrong tree.

## F. Automated Test Report

| Area | Result | Details |
|---|---|---|
| Backend Maven tests | PASS | 638 tests, 0 failures, 0 errors, 3 skipped. |
| Backend package build | PASS | `clean package` produced the Spring Boot JAR with tests enabled. |
| Frontend tests | PASS | 8 test files, 34 tests passed. |
| Frontend type-check | PASS | `vue-tsc --build`. |
| Frontend production build | PASS | Vite production assets generated. |
| Dependency audit | PASS | `npm audit --audit-level=high` reports `found 0 vulnerabilities` after the `minimatch` override. |
| Frontend lint | PASS | `eslint . --cache` passes with `minimatch@11.0.4` resolved via override. |
| Flutter analyze | PASS | No issues found. |
| Flutter tests | PASS | 2 tests passed. |
| Docker Buildx check | NOT RUN | Docker daemon unavailable: Docker Desktop Linux engine pipe not found. |
| Android release build | NOT RUN | Not required by the executed local Flutter check; CI defines a debug Android build. |
| Historical secret scan | NOT RUN | Git history/object database was not scanned. |
| Live/API attack simulation | NOT RUN | No local service instance was started; no external target was tested. |

## G. Unresolved and Unverified Items

- Docker build validation and runtime image smoke test await a running Docker engine.
- Generate a dependency license/notice inventory before distributing packaged binaries.
- Verify production reverse-proxy TLS, trusted proxy configuration, request limits, security headers, and firewall rules.
- Perform a full historical secret scan and rotate any credential discovered in Git history or deployed systems.
- Conduct authorized runtime tests for unauthenticated access, role boundaries, cross-account object access, upload limits, token revocation, and rate limits against an isolated test deployment.
- Review whether sensitive setting values should be masked on read and clear them from frontend state after use.
- The workspace path differs from the path recorded in `AGENTS.md`.

## H. Release Recommendation

- **Public release:** Conditional; no confirmed P0/P1 issue was established in reviewed application paths, but this audit is not a full penetration test.
- **Blocking critical/high application issue:** None confirmed in this audit.
- **Core hardening:** Existing backend controls reviewed positively; Docker test bypass fixed.
- **Automated regression tests:** Backend, frontend, and Flutter checks pass. Docker image checks remain unverified.
- **Remaining release work:** Generate a license inventory, scan Git history, and validate production edge configuration before recommending public internet deployment.

## Change Record

- Modified `Dockerfile`: removed `-DskipTests`; Maven package now runs tests.
- Modified `.github/workflows/ci.yml`: pinned `subosito/flutter-action` to v2.23.0 commit SHA.
- Added `vue-code/package.json` `overrides.minimatch` and refreshed `package-lock.json`; `npm audit` now reports 0 vulnerabilities and lint/tests/build still pass.
- Added `SECURITY.md` with the vulnerability-reporting process and deployment self-check.
- Added this audit report.
- No production data, credentials, database files, or user-preexisting source changes were modified.
