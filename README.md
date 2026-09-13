# Paid Debugging Lab

**Enterprise Java production rescue, incident diagnosis, and reliability engineering.**

This repository is a proof-of-work storefront for taking ambiguous or brittle backend failures and turning them into reproducible incidents, bounded root causes, tested fixes, and operational guardrails.

My primary contract focus is **Java / Spring Boot systems where failure cost is high**: concurrency, lifecycle and resource leaks, integration resilience, data consistency, JVM/runtime diagnosis, AWS infrastructure, and CI/CD reliability.

The open-source investigations in this repository also cover Python, TypeScript, Docker, GitHub Actions, AWS, Playwright, and API integrations. They demonstrate the same engineering method I apply to enterprise Java systems: **reproduce first, identify the ownership boundary, make the smallest safe change, and prove the fix at that boundary.**

## Start here

For an engineering manager or contract recruiter, these investigations show the failure classes I am strongest at diagnosing:

| Failure class | Representative investigation | Enterprise Java analogue |
| --- | --- | --- |
| Concurrency / lifecycle race | [`036-actions-runner-orphan-process-snapshot-race`](case-studies/036-actions-runner-orphan-process-snapshot-race/) | executor shutdown, worker ownership, process lifecycle races |
| Shared-resource synchronization | [`044-actions-runner-event-json-background-lock`](case-studies/044-actions-runner-event-json-background-lock/) | job-scoped vs request-scoped locks, shared-file ownership |
| Authentication context propagation | [`047-buildx-policy-eval-auth-session`](case-studies/047-buildx-policy-eval-auth-session/) | Spring Security context, authenticated HTTP clients, downstream session propagation |
| Environment / runtime compatibility | [`050-setup-python-cross-os-toolcache-collision`](case-studies/050-setup-python-cross-os-toolcache-collision/) | JVM/native-library compatibility, CI cache poisoning, platform-specific artifacts |
| Resource teardown semantics | [`052-miniflare-bun-server-close-semantics`](case-studies/052-miniflare-bun-server-close-semantics/) | idempotent shutdown, lifecycle contracts, graceful service termination |
| Multi-process signal ownership | [`056-miniflare-multi-browser-sigint-signal-ownership`](case-studies/056-miniflare-multi-browser-sigint-signal-ownership/) | coordinated worker shutdown, child-process cleanup, service supervision |

These are investigations, not automatically claims of merged upstream contribution. Where an upstream PR exists, the case study should identify it explicitly.

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
- `jstack` / thread-dump reasoning and concurrency-state reconstruction
- heap/resource leak diagnosis, listener ownership and unmanaged lifecycle cleanup
- `ConcurrentHashMap`, atomics, locks and contention-aware state design
- defensive HTTP/API integration, validation, retry/backoff and idempotency
- transaction and data-consistency failure analysis
- AWS deployment and runtime diagnosis
- Docker/Kubernetes/CI reproducibility and environment isolation
- regression, stress and integration testing around production failure contracts

## Contract-ready rescue scenarios

The commercial value is not the language feature used in the patch; it is the production failure removed and the evidence left behind.

| Incident | What I isolate | Typical stabilization outcome |
| --- | --- | --- |
| JVM service freezes under load | thread dumps, lock ownership, executor saturation, blocked dependency calls | bounded concurrency, corrected lock/lifecycle ownership, stress regression |
| Spring Boot memory grows until restart | heap/JFR evidence, retained listeners/tasks/caches, resource ownership | explicit lifecycle cleanup, bounded retention, repeatable leak test |
| Third-party API intermittently corrupts a workflow | malformed payload boundary, timeout/retry semantics, idempotency and error visibility | validated adapter, defensive parsing, observable retry/failure contract |
| CI passes locally but fails on runners | runtime/dependency/cache/environment delta | reproducible build, corrected cache identity, deterministic CI checks |
| AWS deployment succeeds but runtime is unhealthy | IAM/network/configuration/runtime boundary | minimal infrastructure/runtime correction plus verification runbook |
| Background worker duplicates or loses work | transaction boundary, retry ownership, shutdown race, deduplication state | idempotent processing contract and concurrency/integration regression |

This is the work I want a contract lead to evaluate: **can the engineer turn an expensive, ambiguous failure into a reproducible engineering fact and a low-risk fix?**

## Incident-report standard

Each substantial fix is treated like an enterprise incident rather than a coding exercise:

1. **Failure contract** — what broke, under which workload/environment, and why it matters.
2. **Evidence** — logs, traces, thread/heap evidence, source inspection, or a minimal reproducer.
3. **Root cause** — the specific lifecycle, concurrency, serialization, transport, or ownership boundary that failed.
4. **Stabilization** — the smallest change that restores the intended invariant without broad collateral behaviour changes.
5. **Verification** — regression, integration, stress, or artifact-level tests that would have caught the defect before release.
6. **Prevention** — observability, validation, or architectural guardrails that make the same class of failure harder to reintroduce.

## Typical contract deliverable

A bounded production-rescue engagement should produce an incident package that another senior engineer can independently verify:

```text
incident/
├── REPRODUCTION.md
├── ROOT_CAUSE.md
├── patch-or-pr.diff
├── verification/
│   ├── regression-test
│   └── stress-or-integration-test
└── PREVENTION.md
```

For Java systems, evidence may additionally include thread dumps, heap/JFR observations, GC or connection-pool metrics, executor state, dependency/runtime matrices, and before/after load measurements.

## Flagship project

[**PySherlock**](https://github.com/copley/PySherlock) — an evidence-first debugging CLI that captures reproducible command failures and produces structured reports. Built as a foundation for safe AI-assisted diagnosis and verification.

## Broader debugging scope

I also diagnose and repair:

- broken Python scripts and tracebacks
- TypeScript / Node.js build and runtime errors
- Docker and Docker Compose failures
- GitHub Actions / CI failures
- AWS deployment and runtime problems
- API integration bugs
- Playwright browser automation issues
- repositories that will not run reproducibly in a clean environment

## How a paid debugging request works

1. Open a paid debug request issue.
2. Include the repo link, failing command, logs, expected behavior, and environment details.
3. I reproduce the problem in a clean environment where practical.
4. I isolate the root cause.
5. I submit a branch, PR, patch, or written diagnosis.
6. I document how to verify the fix and avoid the same problem next time.

## Upstream contribution workflow

New upstream portfolio work follows an evidence-gated sequence:

```text
Search issue
-> read every comment
-> search open and closed pull requests
-> announce intent when useful
-> reproduce
-> implement and test
-> open upstream pull request
-> create contribution case study
```

A diagnosis or issue comment is not presented as a merged upstream contribution. See [UPSTREAM_CONTRIBUTION_WORKFLOW.md](UPSTREAM_CONTRIBUTION_WORKFLOW.md) and [the case-study template](case-studies/CASE_STUDY_TEMPLATE.md).

## Verified upstream contribution in review

The current strongest proof-of-work item has crossed the investigation gate and is now an upstream PR:

- **[Vitest #11155](https://github.com/vitest-dev/vitest/pull/11155) — benchmark project-filter identity regression:** [`case-studies/058-vitest-benchmark-project-filter-identity/`](case-studies/058-vitest-benchmark-project-filter-identity/) documents reproduction, root cause, bounded TypeScript fix, regression coverage, and full-suite verification. The upstream PR is open, so this is presented as **contribution in review**, not merged work.

## Active upstream investigations

_Last reviewed: 2026-09-13._ These are source-inspected candidates in the engineering queue, not claims of contribution.

| Upstream issue | Failure class | Current evidence |
| --- | --- | --- |
| [`pytest-dev/pytest#15009`](https://github.com/pytest-dev/pytest/issues/15009) | Python assertion diagnostics / mixed-type formatting | `ApproxMapping._repr_compare()` subtracts every same-key mismatch while computing diagnostic maxima and catches only `ZeroDivisionError`; unequal strings therefore raise `TypeError` inside failure rendering. `ApproxSequenceLike` already treats `TypeError` as a non-numeric diff while preserving the mismatch row, giving a narrow existing contract to mirror |
| [`cloudflare/workers-sdk#15582`](https://github.com/cloudflare/workers-sdk/issues/15582) | GitHub Actions release recovery / child-process status | `deployPackage()` wraps `spawnSync()` in `try/catch` but ignores its returned `status`, `signal`, and `error`; a normal nonzero child exit can therefore be counted as deployed with no recorded error. Recovery is then compounded by deriving downstream work only from attempt-local `PUBLISHED_PACKAGES`, which can be empty after tags already exist on a rerun |
| [`microsoft/playwright#42698`](https://github.com/microsoft/playwright/issues/42698) | HTTP keep-alive / reused-socket reset | Playwright 1.58.2 uses shared custom keep-alive agents for API requests, but the reported premise that Node 26 ignores `Keep-Alive: timeout=` needs re-verification: modern Node agents already support the server timeout hint and a keep-alive timeout buffer. The useful next experiment is therefore deterministic agent-level instrumentation of free-socket expiry and `request.reusedSocket` around the failure boundary before changing retry policy |

A candidate leaves this table and becomes a contribution case study only after the repository workflow has produced reproducible verification and an upstream patch/PR.

## Case-study archive

The full investigation archive is under [`case-studies/`](case-studies/). It currently spans GitHub Actions, AWS/CDK, Docker/Buildx, Python packaging, pytest/Pydantic, Playwright, Vitest, Wrangler/Miniflare, CI lifecycle failures, authentication propagation, serialization and runtime compatibility.

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
