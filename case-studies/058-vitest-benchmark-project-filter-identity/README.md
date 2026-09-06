# Case Study 058 — Vitest benchmark project-filter identity regression

## Status

**Open upstream pull request.** This is a verified contribution in review, not a claim of merged work.

- Upstream issue: [`vitest-dev/vitest#11149`](https://github.com/vitest-dev/vitest/issues/11149)
- Upstream PR: [`vitest-dev/vitest#11155`](https://github.com/vitest-dev/vitest/pull/11155)
- Contributor: [`copley`](https://github.com/copley)
- Area: TypeScript, CLI filtering, derived project identity, benchmark execution

## Failure contract

Vitest 5 expands a configured project into a benchmark-only project whose generated name carries a `" (bench)"` suffix. The `--project` filter is then applied to that derived name.

A command that previously worked in Vitest 4 therefore stopped selecting the benchmark project:

```text
vitest bench --project=@workspace/test
```

The configured logical project is still `@workspace/test`, but the filter sees only `@workspace/test (bench)`.

## Evidence

The issue was reproduced against upstream commit `7c818153add03b0bca54453a54e76961dd5be18d` before implementation.

Source tracing showed the ordering defect:

1. benchmark expansion creates the derived benchmark project;
2. the derived project receives the synthetic `" (bench)"` name;
3. project filtering runs afterwards;
4. the filter no longer has the configured logical identity as a matchable name.

The configured parent identity was already available during benchmark expansion, so the fix did not require suffix stripping or a benchmark-specific regular-expression workaround.

## Root cause

This is an **identity-boundary bug**.

The generated benchmark name is useful as an execution/display identity, but it was accidentally treated as the only selection identity. The CLI contract is defined in terms of the configured project name, so derived projects must retain a relationship to that logical parent identity when filtering occurs after expansion.

The broader production analogue is common in enterprise systems: a derived runtime identifier replaces a stable business/logical identifier too early, and downstream routing, caching, authorization, or reconciliation begins matching against the wrong namespace.

## Stabilization

PR #11155 lets benchmark-only project clones match their configured parent project name while preserving matching by the generated benchmark name.

The implementation reuses Vitest's existing project-filter matcher rather than creating separate benchmark filtering semantics. This keeps positive and negative filtering behavior aligned with ordinary projects.

## Regression coverage

The contribution covers:

- selecting a benchmark clone using its configured parent project name;
- excluding that benchmark clone using the parent project name;
- preserving matching by the generated benchmark name;
- preserving ordinary non-benchmark project filtering behavior.

## Verification

The upstream PR records the following verification:

```text
focused benchmark regression: passed
adjacent benchmark/project suites: 112 passed
pnpm --filter vitest build: passed
pnpm build: passed
CI=true pnpm lint: passed
pnpm typecheck: passed
pnpm test: 216 files passed; 2576 tests passed;
           21 expected failures; 75 skipped; 25 todo
```

## Engineering signal

The patch is deliberately small, but the engineering value is in locating the correct ownership boundary: **configured identity versus derived runtime identity**.

A weaker fix could have stripped a presentation suffix or special-cased benchmark CLI parsing. The submitted fix instead preserves the invariant that a derived project remains selectable through its stable configured parent identity while retaining its distinct runtime name.

That is the same discipline required when repairing enterprise routing keys, cache identities, tenant IDs, deployment aliases, generated resource names, and other systems where derived identifiers must not silently replace stable logical identity.
