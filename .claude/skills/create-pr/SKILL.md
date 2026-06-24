---
name: create-pr
description: Create a GitHub pull request for the current branch with a meaningful title and a structured description (gist, work chunks with rationale, acceptance checklist).
disable-model-invocation: false
argument-hint: [optional extra context for the PR]
---

# Create PR

Create a GitHub pull request for the current branch using `gh`. Drive both the title and the body. Extra user context (if any): $ARGUMENTS.

Before starting:

1. Read `CLAUDE.md` — markdown and documentation standards apply to the PR body too (American English, no emoji, sparing bold, dry tone).
2. Confirm `gh` is authenticated (`gh auth status`). If not, stop and tell the user.
3. Confirm the current branch is not `main`. If it is, stop and ask which branch the PR should be created from.

## 1. Gather the diff

Run these in parallel:

- `git status` (no `-uall`)
- `git rev-parse --abbrev-ref HEAD`
- `git fetch origin main` (so the base is current)
- `git log --no-merges origin/main..HEAD --pretty=format:'%h %s'`
- `git diff --stat origin/main...HEAD`
- `git diff origin/main...HEAD` (full diff — read it; do not just skim file names)

If the branch has no commits ahead of `origin/main`, stop and tell the user.

If the branch is not pushed or is behind its remote, push it with `git push -u origin <branch>` before creating the PR. Never force-push without explicit permission.

## 2. Draft the title

One line, under 70 characters, imperative mood, no trailing full stop, no Conventional Commits prefix unless the repo's recent PR titles use one (check with `gh pr list --state all --limit 10`).

The title must name the dominant change. If the PR genuinely spans unrelated changes, say so plainly ("two unrelated fixes: …") and consider asking the user whether to split.

## 3. Draft the body

Use this structure. Headings as written. No emoji. Sparing bold.

```
## Gist

<2-4 sentences. What this PR does and why, at a glance. A reviewer who reads only this paragraph should know whether they need to read further.>

## Work chunks

### <chunk title>

<Purpose: what problem this solves. Rationale: why this approach over alternatives, or what constraint forced it. Mention notable trade-offs, follow-ups, or things deliberately left out.>

### <next chunk>

…

## Acceptance testing

- [ ] <concrete, runnable check — command, adb step, or on-device click/gesture path>
- [ ] <…>
```

Rules for the body:

- The gist is not a summary of the diff. It is the answer to "why does this PR exist?"
- One idea per paragraph, blank line between them. Do not write a wall of text under any heading.
- Do not hard-wrap paragraphs inside the body. GitHub renders single newlines in PR descriptions as `<br>`, so a wrapped paragraph looks ragged. One paragraph = one line; paragraph breaks are blank lines (per `CLAUDE.md` § Markdown Documentation Standards).
- A "work chunk" is a coherent unit of intent, not a file. Group commits by intent, not by path.
- For every chunk, state the rationale. If you cannot, ask the user before writing it — do not invent a reason.
- The acceptance checklist must be specific. "Run the build" is not specific; for Go changes name "`go test -race ./...`", for Android changes "`(cd android && ./gradlew assembleDebug lintDebug)`". For UI/terminal-rendering changes, give the screen and the action to perform (e.g. attach to `./termshare -- htop` and confirm colors/cursor). For protocol changes, point at the `PROTOCOL.md` frame affected.
- Do not add a co-author trailer or any AI-generated marker unless the user asks.

## 4. Confirm with user

Before calling `gh pr create`, show the user the proposed title and body and ask for approval or edits. Wait for a yes.

## 5. Create the PR

Push the branch if needed (see §1), then:

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
<body>
EOF
)"
```

Base branch: `main` unless the user specifies otherwise. Return the PR URL to the user as a markdown link.

## Notes

- If the branch already has an open PR (`gh pr view`), do not create a new one. Offer to update the existing PR's title and body instead, and only proceed on user confirmation.
- If the diff is large, read it in chunks and keep notes — do not guess at what changed.
- If you find changes you cannot explain (unfamiliar files, surprising deletions), ask the user before drafting the body.
