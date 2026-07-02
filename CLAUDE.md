# nothing-terminal. Project Context Baseline

A voice-driven terminal cockpit for the user's home server: operate the home machine's shell (rich TUIs) from an Android phone, hands-free. The deliverable in this repo is the **Android client** under `android/`. The real backend is the HOME-only Python `remote_coding` service in the sibling repo `nothing-serious`, reached through a hosted relay; the phone talks to it over the frozen contract in `specs/`. A Go `termshare` stand-in server used to live at the root to exercise the client before that backend shipped; it has been retired (history in git). The canonical wire contract is `specs/protocol.md`; the architecture overview lives in `memory/` and the per-side READMEs.

## Communication Style
1. Be concise and factually accurate.
2. Sound neutral. Do not flatter.
3. Always consider pro/con when estimating solutions.
4. Accept you might be wrong, but push back at first. Be a faithful sparring partner (assume the user can be wrong or playing devil's advocate intentionally).
5. Prefer asking questions in prose, not using tools.

## Enforcing Control
1. Approach things professionally and without haste.
2. Instead of making assumptions, ask clarifying questions. Ask questions in small portions (one by one or up to five: depending on complexity and logical grouping).
3. Do not make improvements if asked to analyse, just propose.
4. Do not commit/deploy unless instructed.
5. Do not create branches unless explicitly asked; commit to the current branch. The repo's history is direct-to-`main`.
6. Do not introduce anything aspirational. Have an idea? Discuss with the user.
7. When the user floats a 'maybe' or tentative suggestion, do not accept it at face value. Analyse the idea critically: is it conceptually sound, feasible, reasonable, proportionate to the task, and does it avoid unnecessary complexity? Give a professional opinion with concrete reasoning before proceeding. Do not give in easily.

## Documenting and Discovery
1. This repo hosts its own `journal/` for the artifacts it owns — the contract (`specs/`) and the Android client. Entries are flat, named `journal/YYYY-MM-DD_NN_slug.md`. Each repo journals its own half and references the others; never write into a sibling. The sibling design docs — read for context, never edit — are the backend brief `../nothing-serious/journal/v20/2026-06-24_05_terminals.md` and the phone-side UI design `../nothing-to-say/journal/002/2026-06-24_36_terminal_cockpit.md`. Don't duplicate their content; cross-reference it.
2. `specs/protocol.md` is the frozen wire contract shared with the `nothing-serious` backend. Treat it as the source of truth for frames and the back-channel; if a change is needed, change `specs/protocol.md` first and flag that the backend must follow.
3. The root `README.md` and `android/README.md` are the operator-facing manuals for the repo and the client. Keep them current: when a user-facing flag, run step, or behaviour is added, changed, or removed, update the matching README in the same change. Keep them terse and instruction-style.
4. Secrets and machine-specific values live in the gitignored `local.properties` for the Android client (`TERMINALS_BASE_URL`, `TERMINALS_DEVICE_TOKEN`). Check these before asking the user for a value.

## Sibling Repositories
NEVER write to, edit, create, delete, or run mutating commands in any sibling repo (`../nothing-serious`, `../nothing-to-say`, or others). They are READ-ONLY context: read them to understand integration boundaries and conventions, never modify them. They hold the user's active, uncommitted work; dropping changes there corrupts work-in-progress. A task phrased ambiguously is about THIS repo unless the user explicitly says otherwise — if a request seems to require touching a sibling, stop and ask first.

- `../nothing-serious` — the real HOME-only Python backend that implements the same `specs/protocol.md` and serves real PTYs via its `remote_coding` service (reached through a hosted relay). It is the contract co-owner: protocol decisions here must stay compatible with it. Models routed through preset tiers; Anthropic is integration-only.
- `../nothing-to-say` — the source of the Android conventions this client follows (Kotlin 2.3, Compose + Material3, single-activity, singleton-model state, OkHttp) and the voice-recorder component reused for terminal input. The client's portable `net/` + `term/` packages are kept isolated so the terminal can later be lifted into nothing-to-say as a "terminal panel".

## File and folder naming
Lowercase with underscores for non-source files and folders. Kotlin files follow Kotlin convention (PascalCase for a file holding a single public type, matching the type name).

# Android / Kotlin Standards (`android/`)
- Kotlin with coroutines and Jetpack Compose (Material3), single-activity. Target JVM 17. The app package is `com.humanemagica.nothing.terminal`.
- Versions are pinned in `android/gradle/libs.versions.toml` (currently AGP 8.13, Kotlin 2.3.21, `compileSdk`/`targetSdk = 36`, `minSdk = 28`). When bumping, update the catalog — never inline a version in a build file.
- Kotlin 2.3 removed the `kotlinOptions { jvmTarget }` DSL. Configure the JVM target via `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`.
- The terminal engine reuses Termux's `TerminalEmulator` (VT/ANSI) and `TerminalRenderer` (cell drawing) inside a custom `View` — NOT `TerminalSession`/`TerminalView`, which are `final` and hard-wired to a local subprocess with a back-channel we can't redirect to the network (see `android/README.md`). Termux resolves only via JitPack as `com.github.termux.termux-app:terminal-view:v0.118.0` (the `v` prefix is required; the wiki's `com.termux:…` path 401s), and its API is older than GitHub master — verify against the v0.118.0 API, don't guess method names.
- Keep the terminal logic in the portable `net/` and `term/` packages; the standalone `shell/` and `ui/theme/` are the disposable host that gets dropped when the panel merges into nothing-to-say. Don't leak standalone-shell concerns into the portable packages.
- Secrets (`TERMINALS_BASE_URL`, `TERMINALS_DEVICE_TOKEN`) are injected from `local.properties`; never hard-code or commit them.
- Prefer editing existing files over adding new ones. Don't add abstractions or shims the task doesn't need. Default to no comments: add one only when the *why* is non-obvious (a hidden constraint, a workaround, surprising behaviour); never comment what the code already says.

## Building and running
- Android: the gradle project is rooted at `android/`, so its wrapper is `android/gradlew`. Build the debug APK with `(cd android && ./gradlew assembleDebug)`; lint before committing with `(cd android && ./gradlew lintDebug)` — a pre-commit hook enforces lint on commits that touch `android/`.
- Android builds use the Android Studio JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` as `JAVA_HOME`.
- Install on the phone: `(cd android && ./gradlew :app:installDebug)`, or `adb install -r <apk>` then launch via `monkey -p com.humanemagica.nothing.terminal -c android.intent.category.LAUNCHER 1`. The target device is a Xiaomi `24090RA29G` (`arm64-v8a`). It drops USB constantly — prefer wifi: install over `adb` and point `TERMINALS_BASE_URL` at the backend endpoint. The emulator reaches the host loopback at `10.0.2.2`.
- For UI behaviour, verify on a real device or emulator — type checking and lint confirm code correctness, not feature correctness. If you can't test the UI, say so rather than claiming success.

# Markdown Documentation Standards
Write concise documentation. Keep it simple and readable in a plain-text editor.
- Line wrapping: do not hard-wrap paragraphs. One paragraph = one line; paragraph breaks are blank lines. The same applies inside list items and blockquotes. Structural newlines the syntax requires (between list items, table rows, inside fenced code / YAML / ASCII diagrams) are unaffected. Rationale: edit stability under str-replace edits, cleaner sentence-level diffs, no `\n`→`<br>` breakage when reused in GitHub issues/PRs.
- Headers `#`, `##`, `###`, `####`; lists, code blocks, inline code.
- Use bold very sparingly — only where a term genuinely needs to jump off the page. Prefer `inline code`, *italics*, or plain text. Elevate a `**Subheader:**` to `#### Subheader`.
- Prefer simple lists to tables.
- Diagrams: prefer ASCII; double-check spacing so vertical lines (`|`) line up.
- When choosing between JSON and YAML for data structures, choose YAML.
- Write in American English with correct typographics (’ ” — ).
- Tone: dry, understated, matter-of-fact. Avoid exclamations, flattery, and forced enthusiasm.

# Working with Strings and Constants
- Before hard-coding a string as a constant, consider whether it belongs in config, a resource, or a Compose string. Clarify if unsure.
- Do not use emoji in UI or messaging; Unicode symbols are acceptable.
