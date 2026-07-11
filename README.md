# Paid Debugging Lab

Paid Debugging Lab is my public storefront and proof-of-work repository for debugging, repo rescue, CI/CD repair, Docker fixes, AWS runtime diagnosis, Python/TypeScript bug fixing, API integration repair, and production-style troubleshooting.

## What I fix

I help developers and small teams with blocked engineering work:

- broken Python scripts and tracebacks
- TypeScript / Node.js build and runtime errors
- Docker and Docker Compose failures
- GitHub Actions / CI failures
- AWS deployment and runtime problems
- API integration bugs
- Playwright scraper/browser automation issues
- repos that will not run locally

## How a paid debugging request works

1. Open a paid debug request issue.
2. Include the repo link, failing command, logs, expected behavior, and environment details.
3. I reproduce the problem in a clean environment where practical.
4. I isolate the root cause.
5. I submit a branch, PR, patch, or written diagnosis.
6. I document how to verify the fix and avoid the same problem next time.

## Deliverables

Every serious fix should include:

- reproduction notes
- root-cause analysis
- patch or fix direction
- verification command
- prevention note

## Case studies

- `case-studies/001-github-actions-esm-jest-debugging/`
- `case-studies/002-pnpm-electron-playwright-ci/`
- `case-studies/003-django-cross-resource-validation/`
- `case-studies/004-wrangler-pages-summary-labels/`
- `case-studies/005-aws-oidc-token-refresh-on-retry/`
- `case-studies/006-playwright-firefox-worker-websocket/`

## Scope rules

I do not take vague unlimited-scope work. Good requests are specific:

- one repo
- one failing command or behavior
- one error log or symptom
- one expected outcome

## Request format

```text
Repository:
Branch/commit:
Command run:
Expected behavior:
Actual behavior:
Error output:
Environment:
Deadline/urgency:
```

## Public issue comments

If I comment on your open-source issue, I aim to provide real diagnostic value first: likely root cause, reproduction path, affected file, or PR direction. I do not use generic spam comments.
