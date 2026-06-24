---
name: review-pr
description: Thorough code review of a GitHub PR. Use when asked to review a pull request by number.
disable-model-invocation: false
argument-hint: <PR number>
---

# review-pr

Thorough code review of a GitHub PR. User provides: PR number.

## Steps

1. FETCH PR CONTEXT
   - Run `gh pr view <number> --json title,body,baseRefName,headRefName,files` to get PR metadata
   - Switch to the PR branch: `gh pr checkout <number>`
   - Run `gh pr diff <number>` to get the full diff
   - If the diff is very large, also fetch the list of changed files with `gh pr diff <number> --name-only`

2. FIRST PASS — scan the entire diff and produce a numbered findings list
   For each finding record:
   - ID: F1, F2, …
   - File: path and line range
   - Category: one of — Bug/Defect, Code Smell, Readability, Clarity, Maintainability, Robustness, Refactoring Opportunity, Performance, Security, Naming, Dead Code, Missing Tests
   - Severity: Critical / Major / Minor / Nit
   - Summary: one-sentence description

   Be thorough. Look for:
   - Logical errors, off-by-ones, coroutine/goroutine/concurrency races (Go: would `go test -race` flag it?), lifecycle and channel leaks
   - Missing error handling or edge cases (unchecked Go errors, dropped WebSocket disconnects, no-network, resize while detached)
   - Guessed or wrong Termux emulator/renderer API names (must match v0.118.0)
   - Unclear naming, convoluted control flow, magic values
   - Code duplication or patterns that should be extracted
   - Inconsistency with the rest of the codebase
   - Violations of project conventions (see `CLAUDE.md`)

3. DEEP ANALYSIS — launch subagents in parallel (use the Agent tool, read-only, fast model)
   Group findings into batches of 3-5. For each batch, launch one subagent with:
   - The relevant code snippets (both old and new from the diff)
   - Surrounding context (read the full file sections if needed)
   - The finding summaries from step 2
   - Instructions to:
     a. Confirm or reject each finding (false positives happen)
     b. Assess actual impact and explain WHY it matters
     c. Propose a concrete fix or improvement (code snippet)
     d. Rate confidence: High / Medium / Low
   - The subagent must return structured output per finding

4. AGGREGATE AND PRESENT
   Compile subagent results. Drop rejected findings. Sort remaining by severity then confidence.
   Present the review as:

   ### PR Review: `<title>` (#<number>)
   Files changed: N | Findings: M (X critical, Y major, Z minor, W nits)

   Then for each confirmed finding (grouped by severity):
   - [F-ID] Category — Summary (`file:lines`)
     Impact: why this matters
     Suggestion: concrete fix (with code block if applicable)

   End with:
   ### Summary
   - Top 3 things to address before merge
   - Overall assessment: approve / request changes / needs discussion

Do not fix code. Do not create commits. This is review only.
If the PR has more than 2000 diff lines, warn the user and ask whether to proceed or focus on specific files.

Leave the user on the PR branch.
