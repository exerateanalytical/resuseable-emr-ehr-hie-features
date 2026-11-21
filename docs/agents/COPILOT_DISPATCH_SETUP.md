# Starting the HealthCore workstream coding agents

**Current state (2026-11-21):** 20 issues #3–#22 created, **0 verified autonomous coding sessions**. The connected GitHub app returned HTTP 422 when creating an issue with the Copilot assignee and HTTP 403 when adding the assignee.

GitHub Copilot cloud agent assignment via the REST API requires appropriate **paid Copilot access** and a **user-to-server token**; an installation token from a managed GitHub integration can be insufficient. The following workflow is opt-in and **does not run or spend agent time by itself**.

## Option A — GitHub UI (no token required in ChatGPT)
1. Verify that your GitHub account has an eligible paid Copilot plan and that Copilot cloud agent is allowed for this repository.
2. Open the [20 issues](https://github.com/exerateanalytical/resuseable-emr-ehr-hie-features/issues) and choose **Assignees → Copilot** on each issue.
3. In assignment settings choose the existing base branch `fix/healthcore-java25-full-verification-20261120` (draft PR #2), not an unrelated product repository, and optionally select custom agent `healthcore-sdk-engineer`.
4. Verify a linked Copilot session/draft PR before counting an agent as started. Review PRs individually.

## Option B — Controlled batch dispatch with GitHub Actions
1. Configure a **GitHub user-to-server token** with access to this repo, a paid Copilot seat and the permissions specified in GitHub's current Copilot cloud-agent assignment API documentation (metadata read; actions, contents, issues and PRs read/write).
2. In the repository, go to **Settings → Secrets and variables → Actions → New repository secret**. Name it `COPILOT_ASSIGN_TOKEN`; paste the token **there, never in ChatGPT or a commit**.
3. Open **Actions → HealthCore — assign 20 workstream issues to Copilot cloud agent → Run workflow**.
4. First run with **dry_run = true** and inspect the issue scope. For a real dispatch, explicitly rerun with **dry_run = false** and `max_issues = 20`.
5. Follow the workflow logs and each issue's agent session. A green action may confirm **API assignment**, not that 20 agents are actively coding. Check for linked agent-created branches and pull requests.
6. If a request is forbidden, stop and resolve Copilot seat or token/organization policy access. Do not replace the request with a bot mention and then claim it worked.

## Reconciliation and safety
All agents must read `AGENTS.md`, follow their isolated workstream boundaries, and open unmerged PRs. Do not auto-merge 20 independent PRs into a broken release candidate; integrate in dependency order after full Java 25 reactor, 8 pack isolation, multi-DB, 11 consumer-app, mobile, security and clinical gate verification.
