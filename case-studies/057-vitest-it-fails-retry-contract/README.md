# Case Study 057 — Vitest `it.fails` retry contract

> **Portfolio status: research-only diagnostic.** This entry is not an upstream contribution and no Vitest pull request was authored by `copley` for this issue.

## Upstream activity

- Repository: `vitest-dev/vitest`
- Issue: [#11068](https://github.com/vitest-dev/vitest/issues/11068)
- Earlier implementation: [#11080](https://github.com/vitest-dev/vitest/pull/11080), opened by another contributor and later closed without merge
- `copley` activity: one diagnostic issue comment, posted after the earlier contributor had already published the same lifecycle diagnosis and opened the implementation PR
- Current classification: useful research, but not evidence of upstream ownership or contribution

This record is retained to show the technical analysis and the workflow failure that prompted the repository's new upstream contribution gate. It must not be presented as a merged or active Vitest contribution.

## User-visible failure

A test declared with `it.fails(..., { retry: 2 }, fn)` runs three times when its body fails immediately, even though the first failure satisfies the expected-failure contract.

```ts
import { expect, it } from 'vitest'

it.fails('vitest bug', { retry: 2 }, () => {
  console.log('attempt!')
  expect(1).toBe(2)
})
```

Expected for this path: one `attempt!` and a satisfied expected failure.

Observed: three `attempt!` lines and misleading retry reporting.

The inverse edge case also matters: when a test marked `fails` unexpectedly passes, that is its real failure mode and is the outcome retries should re-check.

## Root-cause analysis

The runner applies retry policy before applying the expected-failure contract.

In `packages/vitest/src/runtime/runner/run.ts`, `runTest()` performs the attempt, records the raw `pass` or `fail` state, and makes its retry decision. Only after the retry loop finishes does it invert the final result for `test.fails`.

Consequently:

- an attempt that throws and satisfies `test.fails` is seen as an ordinary failure and retried unnecessarily;
- an attempt that unexpectedly passes breaks the retry loop as a raw pass even though it is the failure condition of `test.fails`.

Expected-failure semantics therefore affect scheduling, not only final reporting.

## Correct ownership boundary

The retry decision should ask whether the completed attempt met the test's expectation. For ordinary tests that means a raw pass. For `test.fails`, a non-syntax failure satisfies the expectation, while an unexpected pass is eligible for retry.

Any implementation must preserve the runner's existing syntax-error behavior and avoid mutating attempt state in a way that changes repeat handling or lifecycle hooks.

## Required regression coverage

1. An erroring `it.fails(..., { retry: 2 })` runs once and is reported as an expected failure.
2. A passing `it.fails(..., { retry: 2 })` is retried and ultimately reported as failed.
3. An ordinary failing test retains normal retry behavior.
4. `retry.condition` receives the correct expected-outcome error.
5. Vitest syntax errors remain non-invertible.
6. Repeats, `onTestFailed`, cleanup hooks, and reporter retry counters retain correct state.

## Workflow lesson

The candidate was technically strong but operationally stale. Before the portfolio entry and `copley` comment were published, another contributor had already posted the same diagnosis and opened a pull request.

The corrective rule is now repository policy:

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

The engineering analysis remains useful, but contribution credit depends on timely, independently verified upstream work.

