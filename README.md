# Paid Debugging Lab

**Enterprise Java production rescue, incident diagnosis, and reliability engineering.**

This repository is a proof-of-work storefront for taking ambiguous or brittle backend failures and turning them into reproducible incidents, bounded root causes, tested fixes, and operational guardrails. My primary contract focus is Java/Spring Boot systems where failure cost is high: concurrency, lifecycle and resource leaks, integration resilience, data consistency, JVM/runtime diagnosis, AWS infrastructure, and CI/CD reliability.

The open-source case studies below also cover Python, TypeScript, Docker, GitHub Actions, AWS, Playwright, and API integrations. They demonstrate the same engineering method I apply to enterprise Java systems: reproduce first, identify the ownership boundary, make the smallest safe change, and prove the fix with regression coverage.

## Executive focus

I am most useful when a team has a system that is technically running but operationally untrustworthy: intermittent deadlocks, unexplained latency, memory growth, fragile third-party integrations, CI failures, non-deterministic tests, or production behaviour that cannot be reproduced locally.

A serious debugging engagement should end with more than a patch. It should leave behind:

- a deterministic or well-instrumented reproduction
- evidence that isolates the failing subsystem
- a precise root-cause explanation
- a bounded implementation change
- regression tests at the correct ownership boundary
- observability or prevention that makes recurrence easier to detect

## Enterprise Java capability

- Java 17/21/25, Spring Boot, Maven and Gradle
- thread contention, deadlock, starvation and executor-lifecycle analysis
- `jstack`/thread-dump reasoning and concurrency-state reconstruction
- heap/resource leak diagnosis, listener ownership and unmanaged lifecycle cleanup
- `ConcurrentHashMap`, atomics, locks and contention-aware state design
- defensive HTTP/API integration, validation, retry/backoff and idempotency
- transaction/data-consistency failure analysis
- AWS deployment and runtime diagnosis
- Docker/Kubernetes/CI reproducibility and environment isolation
- regression, stress and integration testing around production failure contracts

## Incident-report standard

Each substantial fix is treated like an enterprise incident rather than a coding exercise:

1. **Failure contract** — what broke, under which workload/environment, and why it matters.
2. **Evidence** — logs, traces, thread/heap evidence, source inspection, or a minimal reproducer.
3. **Root cause** — the specific lifecycle, concurrency, serialization, transport, or ownership boundary that failed.
4. **Stabilization** — the smallest change that restores the intended invariant without broad collateral behaviour changes.
5. **Verification** — regression, integration, stress, or artifact-level tests that would have caught the defect before release.
6. **Prevention** — observability, validation, or architectural guardrails that make the same class of failure harder to reintroduce.

## Flagship project

[**PySherlock**](https://github.com/copley/PySherlock) — an evidence-first debugging CLI that captures reproducible command failures and produces structured reports. Built as the foundation for safe AI-assisted diagnosis and verification.

## Broader debugging scope

I also diagnose and repair:

- broken Python scripts and tracebacks
- TypeScript / Node.js build and runtime errors
- Docker and Docker Compose failures
- GitHub Actions / CI failures
- AWS deployment and runtime problems
- API integration bugs
- Playwright scraper/browser automation issues
- repositories that will not run reproducibly in a clean environment

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

## Upstream contribution workflow

New portfolio case studies follow an evidence-gated sequence:

```text
Search issue
-> read every comment
-> search open and closed pull requests
-> announce intent
-> reproduce
-> implement and test
-> open upstream pull request
-> create portfolio case study
```

A diagnosis or issue comment is not presented as an upstream contribution. New case studies must link a real upstream pull request and identify it as either `upstream contribution in progress` or `merged upstream contribution`. See [UPSTREAM_CONTRIBUTION_WORKFLOW.md](UPSTREAM_CONTRIBUTION_WORKFLOW.md) and [the case-study template](case-studies/CASE_STUDY_TEMPLATE.md).

## Case studies

- `case-studies/001-github-actions-esm-jest-debugging/`
- `case-studies/002-pnpm-electron-playwright-ci/`
- `case-studies/003-django-cross-resource-validation/`
- `case-studies/004-wrangler-pages-summary-labels/`
- `case-studies/005-aws-oidc-token-refresh-on-retry/`
- `case-studies/006-playwright-firefox-worker-websocket/`
- `case-studies/007-docker-cancelled-build-post-summary-export/`
- `case-studies/008-pytest-doctest-skip-location/`
- `case-studies/009-playwright-client-cert-cached-rejection/`
- `case-studies/010-poetry-core-marker-constraint-implication/`
- `case-studies/011-pydantic-deferred-namedtuple-serializer/`
- `case-studies/012-docker-buildkit-otel-env-injection/`
- `case-studies/013-poetry-request-timeout-import-crash/`
- `case-studies/014-wrangler-secret-binding-overwrite/`
- `case-studies/015-aws-cdk-lambda-vpc-hash-order/`
- `case-studies/016-wrangler-failed-command-outputs/`
- `case-studies/017-aws-cdk-lambda-target-type-docs/`
- `case-studies/018-setup-python-pip-cache-absolute-interpreter/`
- `case-studies/019-poetry-symlinked-interpreter-venv-detection/`
- `case-studies/020-playwright-accessible-name-distillation/`
- `case-studies/021-playwright-tsconfig-bare-extends-resolution/`
- `case-studies/022-aws-cdk-construct-dependency-nested-stack-blowup/`
- `case-studies/023-cloudformation-icmpv6-security-group-validation/`
- `case-studies/024-cloudformation-getstackoutput-duplicate-detection/`
- `case-studies/025-vitest-pool-workers-dispose-rejection-leak/`
- `case-studies/026-buildx-transient-feature-probe-cache/`
- `case-studies/027-cloudformation-route53-token-ip-validation/`
- `case-studies/028-wrangler-pages-account-cache-precedence/`
- `case-studies/029-c3-workerd-compatibility-date-clamp/`
- `case-studies/030-setup-python-pypy-pip-overlay-downgrade/`
- `case-studies/031-miniflare-exif-auto-orientation/`
- `case-studies/032-vitest-pool-workers-space-path-redirect-sentinel/`
- `case-studies/033-playwright-firefox-disable-app-update-policy/`
- `case-studies/034-pydantic-gated-model-serializer-exclude-state/`
- `case-studies/035-vitest-pool-workers-proxy-prototype-growth/`
- `case-studies/036-actions-runner-orphan-process-snapshot-race/`
- `case-studies/037-actions-languageserver-workflow-call-secret-completion/`
- `case-studies/038-wrangler-r2-bucket-list-pagination/`
- `case-studies/039-actions-workflow-parser-json-import-attributes/`
- `case-studies/040-wrangler-temporary-auth-idempotency/`
- `case-studies/041-workers-sdk-vite-access-dev-missing-wiring/`
- `case-studies/042-workers-sdk-vite-remote-bindings-session-teardown/`
- `case-studies/043-actions-runner-run-service-renewal-lease-state/`
- `case-studies/044-actions-runner-event-json-background-lock/`
- `case-studies/045-wrangler-asset-manifest-hash-delay/`
- `case-studies/046-pytest-shared-warning-traceback-retention/`
- `case-studies/047-buildx-policy-eval-auth-session/`
- `case-studies/048-wrangler-secret-tool-presence-probe/`
- `case-studies/049-pydantic-secret-none-json-serializer/`
- `case-studies/050-setup-python-cross-os-toolcache-collision/`
- `case-studies/051-wrangler-disabled-metrics-agent-skills-fetch/`
- `case-studies/052-miniflare-bun-server-close-semantics/`
- `case-studies/053-miniflare-declaration-rollup-dangling-shared-import/`
- `case-studies/054-vitest-tomatchobject-arraycontaining-equality-context/`
- `case-studies/055-setup-python-graalpy-four-part-version/`
- `case-studies/056-miniflare-multi-browser-sigint-signal-ownership/`
- `case-studies/057-vitest-it-fails-retry-contract/` — research-only; no upstream PR by this repository's author

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
