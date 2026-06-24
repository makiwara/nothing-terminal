---
name: refactoring-opportunities
description: Analyse the codebase for issues and refactoring opportunities across readability, maintainability, duplication, code smells, and pattern correctness. Use when asked to find, analyse, or review refactoring opportunities.
disable-model-invocation: false
argument-hint: [optional: path or area to scope, e.g. android/term/ or hub.go]
---

Analyse the codebase for issues and refactoring opportunities.

Scope: $ARGUMENTS

If a path or area (a Go file like `hub.go`/`ws.go`, the Android portable packages `android/.../net/` or `android/.../term/`, or a specific file) is given, narrow to it. If nothing is given, default to the Go server sources at the repo root plus `android/app/src/main/java/com/humanemagica/nothing/terminal/`. Do not silently expand beyond the requested scope.

Before analysing:

1. Read `CLAUDE.md` — use its Go Server, Android/Kotlin, strings/constants, and documentation standards as the reference for what correct looks like.
2. Read the scoped files. Understand the code before forming opinions.

Analyse across these dimensions:

1. Readability, clarity, and expressiveness
   - Confusing names, misleading abstractions, unclear intent
   - Comments that duplicate code or compensate for unclear code
   - Overly complex expressions that could be simplified

2. Maintainability and testability
   - Hidden dependencies, side effects, untestable units
   - Fragile logic that would break under normal extension
   - Lifecycle and coroutine-scope correctness (leaks, work outside a scope, swallowed cancellation)

3. Duplication and code reuse
   - Repeated logic that could be extracted
   - Inconsistent implementations of the same concept in different places
   - Missed opportunities to use existing utilities or patterns

4. Code smells
   - Long functions, deep nesting, large classes
   - Magic strings, hardcoded values that belong in config/resources (per `CLAUDE.md` string/constant rules)
   - Dead code, unused imports, unreachable branches

5. Pattern correctness
   - Go: unchecked errors, goroutine/channel leaks, data races (would `go test -race` catch it?), mishandled WebSocket read/write lifecycles in `ws.go`/`hub.go`
   - Android: guessed Termux `TerminalEmulator`/`TerminalRenderer` API names (verify against v0.118.0), standalone-shell concerns leaking into the portable `net/`/`term/` packages
   - Wire contract: frames or back-channel behaviour that drift from `PROTOCOL.md`
   - Inconsistent use of established conventions (error reporting, coroutine dispatchers, resize/snapshot handling)

Output format — group findings by dimension. For each finding:

- Location: file and line number
- Issue: what the problem is and why it matters
- Proposal: concrete suggestion for improvement

Do not make any changes. Explore, explain, propose only.
