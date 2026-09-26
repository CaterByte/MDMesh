# Contributing to MDMesh

Thanks for being here. MDMesh aims to be a **genuinely complete, robust, modernized** open-source
Android MDM, and that only happens with good contributions — code, docs, bug reports, and ideas all
count. This guide gets you from clone to a green PR.

- 🐞 **Bug?** → [open a bug report](https://github.com/MDMesh-app/MDMesh/issues/new?template=bug_report.yml)
- 💡 **Idea?** → [open a feature request](https://github.com/MDMesh-app/MDMesh/issues/new?template=feature_request.yml)
- 🔧 **Code?** → read on, then send a focused PR.

---

## Repository layout

MDMesh is a monorepo with four planes (full map in **[STRUCTURE.md](STRUCTURE.md)**):

| Path | Plane | Stack |
|------|-------|-------|
| `common/`, `server/`, `jwt/`, `notification/`, `plugins/` | Control plane (server / REST API) | Java · Jersey · Guice · MyBatis · PostgreSQL |
| `web/` | Admin console | React · TypeScript · Vite |
| `agent-android/` | Device agent | Kotlin · coroutines · WorkManager (Gradle) |
| `supervisor/` | Updater / recovery service | Node (zero deps) |
| `proto/`, `docs/adr/` | Protocol source of truth + decision records | — |

Architecture decisions live in [`docs/adr/`](docs/adr); read the relevant ADR before changing a
load-bearing area (signing, device IDs, the agent contract, etc.).

---

## Dev setup & commands

You only need the toolchain for the plane you're touching.

### Console (`web/`)
```bash
cd web
npm install
npm run dev          # Vite dev server on :5173, proxied to the dev stack (VITE_DEV_PROXY_TARGET to change)
npm run build        # production build
npx tsc -b --noEmit  # type-check (-b: tsconfig.json only references the real projects)
```

### Server (`common/`, `server/`)
Requires **JDK 17** and Maven.
```bash
cp -n server/build.properties.example server/build.properties   # once: the server build reads it
mvn -pl common test          # fast, DB-free unit + contract tests
mvn -pl server -am compile   # type-check the server + its modules
mvn -pl server -am package -DskipTests   # build the WAR (full build)
```
The full server run needs PostgreSQL: use the dev stack below.

### Agent (`agent-android/`)
Requires **JDK 17** + the **Android SDK** (set `ANDROID_SDK_ROOT`). A `local.properties` with `sdk.dir`
also works.
```bash
cd agent-android
./gradlew :proto:test :core:test          # unit tests
./gradlew :app:compileDebugKotlin          # type-check the app + its modules
./gradlew :app:assembleDebug               # build a debug APK
```
Install on an emulator and promote to Device Owner for testing:
`adb install app-debug.apk && adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver`

### Updater/recovery (`supervisor/`)
```bash
cd supervisor
node --test          # pure-logic unit tests (no deps)
cd .. && docker build -f docker/supervisor.Dockerfile -t mdmesh-supervisor:dev . \
  && scripts/supervisor-smoke.sh mdmesh-supervisor:dev   # boots the image the way compose runs it
```

### The whole stack, locally
```bash
docker compose --env-file docker/dev.env up -d --build   # Postgres + server + Caddy/console + supervisor, loopback only
scripts/dev-seed.sh                                       # first run: seed the DB; console http://localhost:8088, admin / admin
scripts/agent-v1-e2e.sh http://localhost:8080             # the Agent v1 end-to-end suite against it
```
Ports, the fast Java loop, the debugger and reset are in **[docs/DEV.md](docs/DEV.md)**. `./setup.sh` is the
production installer (it writes a `.env` for a real host); see **[DEPLOY.md](DEPLOY.md)**.

---

## Workflow

1. **Branch** off `main` (`feat/…`, `fix/…`, `docs/…`). Don't commit to `main` directly.
2. **Make focused changes** — one logical change per PR. Match the style of the code around you.
3. **Test what you touched** with the commands above; add or update tests for behavior changes.
4. **Update docs** when you change behavior, config, or the API.
5. **Open a PR** against `main` using the [PR template](.github/pull_request_template.md).

### Commit messages
Short, imperative, prefixed: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`. Explain the
*why* in the body when it isn't obvious.

### The one hard rule: the agent ↔ server contract is additive-only
The `/agent/v1` REST contract must stay backward-compatible so older agents keep working across server
updates: **new request fields are optional + defaulted; never remove, rename, or repurpose a field, and
never add a required request field.** A genuine break means a new `/agent/v2` (v1 stays). This is
enforced by a golden contract test in CI — see [ADR-0009](docs/adr/0009-agent-v1-contract-stability.md).

---

## Dependency updates

Dependabot opens one grouped PR per plane every Monday ([`.github/dependabot.yml`](.github/dependabot.yml)).

- **T0 green is necessary, not sufficient.** T0 compiles and runs the DB-free tests only. Before merging a group
  that moves a runtime library across a minor/major, run that plane's runtime check too: web —
  `cd web && npm run build && cd ../scripts/shots && npm install && node capture.mjs --check`; server — boot the
  WAR against Postgres and run `scripts/agent-v1-e2e.sh`; agent — install the APK on a device/emulator and
  complete a check-in.
- **Known-bad lines are ignored, with a reason.** Each `ignore` in `dependabot.yml` names the migration that lifts
  it (javax → jakarta / Tomcat 10+, jjwt API port, AGP 9, …). Remove the ignore in that migration's PR — never
  merge a Dependabot PR that crosses one piecemeal. The `agent-toolchain` group is expected to stay red until the
  AGP 9 migration.
- **Licenses:** a bump that changes a dependency's license (e.g. Liquibase 5 → FSL) is blocked until reviewed
  against [ADR-0008](docs/adr/0008-licensing-and-rebrand.md).
- **One JDK, one Node line.** The server builds and runs on **JDK 17** (CI, Docker build + `tomcat:9.0-jdk17`
  runtime, native installer). Node follows the current **LTS** line — **24** (the images still run 20/22 until
  they move) — in `web/package.json` `engines`, both `docker/*.Dockerfile` `FROM node:` lines and `@types/node`;
  move all of them in one PR once the next even line has been LTS for a few months. Docker base images use
  floating tags, so rebuilds pick up patches; a tag change is a deliberate PR.
- **Vendored CI actions** (`.github/actions/ci-kit/`) change only by re-running ci-kit's `scripts/vendor.sh`.

---

## What makes a PR easy to merge

- It's small and does one thing.
- Tests pass for the affected plane; new behavior has a test.
- UI changes include a screenshot or short clip.
- Docs are updated alongside the code.
- It respects the agent contract rule above and any relevant ADR.

Not sure where to start? Look for issues labelled **good first issue**, or open a discussion before a
large change so we can agree on the approach.

### What to expect after you open something
- Issues and PRs get a first maintainer reply within a week. If it has been longer, a polite ping is welcome.
- CI does not run automatically on a **first-time contributor's** PR (GitHub's default); a maintainer approves
  it after a quick read. Nothing you need to do.
- `main` is protected against force-pushes and deletion. Maintainers do land small fixes on `main` directly;
  everything else goes through a PR.
- Security issues: see [SECURITY.md](SECURITY.md) — please don't file them publicly.

---

## Regenerating the docs screenshots

The console screenshots in the README are produced from the real SPA against sample data:
```bash
cd web && npm run build          # build the SPA first
cd ../scripts/shots && npm install && node capture.mjs
```
Output lands in `docs/screenshots/`. See `scripts/shots/` for the harness + fixtures.

---

By contributing, you agree that your contributions are licensed under the project's
[Apache-2.0 license](LICENSE).
