#!/usr/bin/env bash
# Manual dispatch only. Uses a GitHub user-to-server token with Copilot access.
# Never echo or store a token. Agent assignment is not proof of a running session.
set -euo pipefail
repo="${GITHUB_REPOSITORY:-exerateanalytical/resuseable-emr-ehr-hie-features}"
base="${TARGET_BRANCH:-fix/healthcore-java25-full-verification-20261120}"
dry_run="${DRY_RUN:-true}"
limit="${MAX_ISSUES:-20}"

if ! [[ "$limit" =~ ^[0-9]+$ ]] || (( limit < 1 || limit > 20 )); then
  echo "MAX_ISSUES must be from 1 through 20" >&2
  exit 2
fi
if [[ "$dry_run" != "true" && "$dry_run" != "false" ]]; then
  echo "DRY_RUN must be true or false" >&2
  exit 2
fi
if ! gh --version >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required" >&2
  exit 2
fi
if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GitHub read token is unavailable." >&2
  exit 2
fi
if [[ "$dry_run" == "false" && "${USER_TOKEN_CONFIGURED:-false}" != "true" ]]; then
  echo "Real agent dispatch requires a configured COPILOT_ASSIGN_TOKEN user-to-server secret. Default GITHUB_TOKEN is insufficient." >&2
  exit 2
fi
if ! gh api --method GET "repos/$repo/branches/$base" --jq '.name' >/dev/null; then
  echo "Target base branch '$base' is not accessible." >&2
  exit 2
fi

submitted=0
failed=0
for (( issue=3; issue<3+limit; issue++ )); do
  title="$(gh api --method GET "repos/$repo/issues/$issue" --jq '.title' 2>/dev/null || true)"
  if [[ ! "$title" =~ ^\[HealthCore\ W[0-9][0-9]/20\] ]]; then
    echo "SKIP #$issue: not a numbered HealthCore agent issue" >&2
    ((failed+=1))
    continue
  fi
  if [[ "$dry_run" == "true" ]]; then
    echo "DRY RUN: would assign #$issue: $title"
    continue
  fi
  if gh api --method GET "repos/$repo/issues/$issue" --jq '.assignees[].login' | grep -Fxq "copilot-swe-agent[bot]"; then
    echo "ALREADY ASSIGNED: #$issue"
    continue
  fi
  instructions="Read AGENTS.md and issue #$issue; scope to one workstream. Start from $base, reuse existing SDK contracts, implement real Testcontainers workflows and docs, open a reviewable PR. Never merge or claim unverified clinical/regulatory completeness."
  payload="$(jq -nc \
    --arg repo "$repo" --arg base "$base" --arg instructions "$instructions" \
    '{assignees:["copilot-swe-agent[bot]"],agent_assignment:{target_repo:$repo,base_branch:$base,custom_instructions:$instructions}}')"
  if result="$(printf '%s' "$payload" | gh api --method POST \
     "repos/$repo/issues/$issue/assignees" --input - 2>&1)"; then
    if printf '%s' "$result" | jq -e '.assignees[]? | select(.login=="copilot-swe-agent[bot]")' >/dev/null; then
      echo "ACCEPTED: Copilot assignee on #$issue (verify a running session or draft PR separately)"
      ((submitted+=1))
    else
      echo "ERROR #$issue: API did not confirm Copilot assignee" >&2
      ((failed+=1))
    fi
  else
    echo "ERROR #$issue: Copilot assignment request denied; check token privileges and GitHub Copilot cloud availability" >&2
    ((failed+=1))
  fi
  sleep 1
done

echo "Dispatch API accepted: $submitted; failed: $failed; dry-run: $dry_run"
if (( failed > 0 )); then exit 1; fi
