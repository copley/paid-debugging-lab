# Case Study 058 — Preserve benchmark parent project filtering

## Contribution status

- Upstream issue: https://github.com/vitest-dev/vitest/issues/11149
- Checked issue comments: 2026-09-06
- Checked open and closed PRs: 2026-09-06
- Assignment status: unassigned
- Intent comment: https://github.com/vitest-dev/vitest/issues/11149#issuecomment-5555724171
- Reproduction: confirmed
- Upstream PR: https://github.com/vitest-dev/vitest/pull/11155
- PR state at publication: open
- AI assistance disclosed upstream: yes
- Portfolio classification: upstream contribution in progress

## User-visible failure

Vitest 5 creates a benchmark-only counterpart for each configured project and appends ` (bench)` to its name. As a result, a command that selects the configured project in normal test mode fails in benchmark mode:

```console
vitest bench --project=@workspace/test
```

The command reports that no project matched, even though `@workspace/test` is the project's configured identity. Users must know and select Vitest's generated benchmark name instead of reusing their project filter.

## Reproduction

The issue was reproduced against upstream commit `7c818153add03b0bca54453a54e76961dd5be18d` on Linux with Node 24.19.0 and pnpm 11.24.0.

A two-project fixture configured `@workspace/test` and `@workspace/other`, each with a benchmark. The configured-name command failed before the change:

```console
node ../../packages/vitest/vitest.mjs bench --run --project=@workspace/test
Error: No projects matched the filter "@workspace/test".
```

Controls confirmed that benchmark execution without a filter worked, normal test-mode selection by `@workspace/test` worked, and explicitly selecting the generated `@workspace/test (bench)` name worked.

## Root cause

`expandBenchmarksInEntries()` in `packages/vitest/src/node/projects/resolveProjects.ts` creates the benchmark counterpart before project filtering and changes its name to `<configured name> (bench)`.

The later project-filter pass therefore sees only the generated benchmark name. The original configured name is not part of the clone's matchable project identity, so an exact positive filter misses it and an exclusion filter cannot refer to it consistently.

## Implementation

The benchmark-only clone now retains the configured parent project name in its ancestor identity list. This uses Vitest's existing project-filter matcher rather than adding a benchmark-specific matching path.

The change is limited to benchmark-only expansion and named projects. Generated-name matching remains available, ordinary test-mode project resolution is unchanged, and positive and negative filters share the same matching semantics.

## Verification

The end-to-end regression covers:

- configured parent-name selection in benchmark-only mode
- unchanged configured-name selection in ordinary test mode
- parent-name exclusion across two benchmark projects

Verification completed before publication:

```console
pnpm --filter vitest build
pnpm build
CI=true pnpm lint
pnpm typecheck
pnpm test
```

The focused regression passed. The adjacent benchmarking and project suites passed 112 tests. The full suite passed 216 test files and 2,576 tests, with 21 expected failures, 75 skipped tests, and 25 todo tests.

Two ANSI snapshot assertions initially differed because the execution environment set `NO_COLOR=1`. Re-running those adjacent suites with color enabled passed all 112 tests; this was unrelated to the production change.

## Upstream outcome

The patch is under review in [vitest-dev/vitest#11155](https://github.com/vitest-dev/vitest/pull/11155). At publication it is open and contains one commit changing two files with 54 additions and one deletion.

This case study must not be described as a merged contribution unless the upstream PR is merged.

## Engineering lesson

When a framework derives internal execution projects from user-configured projects, generated names should not erase the logical identity users target through CLI filters. Carrying the parent identity through the derivation boundary keeps selection and exclusion rules composable without duplicating filter logic.
