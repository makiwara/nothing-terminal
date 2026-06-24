---
name: github-branch-status
description: Show status of all local/origin branches, compare current vs origin/main, show GitHub CLI auth, and advise on push safety.
disable-model-invocation: false
argument-hint:
---

# GitHub Branch Status

Show a concise status report for this repository. Follow these steps exactly.

## 1. Branch overview

Run `git branch -a -v` and present a compact table of all local and remote branches with their last commit and tracking status (ahead/behind/gone).

## 2. Diff summary

Compare these refs (skip any that do not exist):

- Current local branch HEAD
- Its remote tracking branch (`origin/<current>`)
- `origin/main`

For each adjacent pair that exists, run `git rev-list --left-right --count <A>...<B>` and `git log --oneline <A>..<B>` (limit to 15). Write one short paragraph (3-5 sentences) summarising what changed: the number of commits, the broad topics, and whether anything looks risky.

## 3. GitHub CLI auth

Run `gh auth status` and report the account, active scopes, and token expiry in a few lines.

## 4. Push safety assessment

Answer with brief justification:

1. Is the current branch ahead of, behind, or diverged from `origin/main`?
2. Is it safe to push the current branch (fast-forward, or would it need a force-push)?

Consider: are there commits on the remote that are not local (would be lost on force-push)? Is the local branch a fast-forward of the remote? Flag any risk clearly. Never recommend a force-push to `main`.
