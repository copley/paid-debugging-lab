# Case Study 057 — Vitest `it.fails` retry contract

## Upstream issue

- Repository: `vitest-dev/vitest`
- Issue: `#11068` — `it.fails` tests are retried even after the expected failure occurs
- Area: TypeScript test runner semantics, retry lifecycle, expected-failure handling

## User-visible failure

A test declared with `it.fails(..., { retry: 2 }, fn)` is expected to succeed when its first attempt fails. Instead, Vitest runs the body three times: the first failure, plus two retries.

Minimal reported shape:

```ts
import { expect, it } from 'vitest';

it.fails('vitest bug', { retry: 2 }, async () => {
  console.log('attempt!');
  expect(1).toBe(2);
});
```

Expected output: one `attempt!`.

Observed output: three `attempt!` lines.

## Root-cause hypothesis

The runner applies retry policy before applying the expected-failure contract.

In `packages/vitest/src/runtime/runner/run.ts`, `runTest()` performs nested loops over repeat count and retry count. During each attempt it:

1. runs the test body;
2. records ordinary `pass` or `fail` state;
3. decides whether to retry if `test.result.state !== 'pass'`;
4. only after all attempts are finished, applies the `test.fails` inversion.

The expected-failure logic currently sits after the retry loops:

```ts
if (test.fails) {
  if (test.result.state === 'pass') {
    test.result.state = 'fail';
  }
  else if (!test.result.errors?.some(e => e.__vitest_test_syntax_error__)) {
    test.result.state = 'pass';
    test.result.errors = undefined;
  }
}
```

That means an expected failing attempt is still seen by the retry loop as an ordinary failed attempt. The retry engine has no chance to know that this failure is already the terminal successful outcome for `it.fails`.

## Correct ownership boundary

`it.fails` is not merely a reporting transform. It changes the terminal success condition of the test.

Therefore, expected-failure classification should occur before the retry decision, or retry should explicitly treat a non-syntax failure from `test.fails` as terminal.

## Likely fix path

A focused patch could introduce a helper around the retry decision:

```ts
function isExpectedFailureSatisfied(test: Test): boolean {
  return Boolean(
    test.fails
    && test.result?.state === 'fail'
    && !test.result.errors?.some(e => e.__vitest_test_syntax_error__)
  );
}
```

Then, after a test attempt has completed and all hooks/cleanup have run, the retry branch should stop when this helper is true.

The final result conversion can either remain at the existing end-of-test location or be centralized through one result-normalization helper. The important invariant is that retry scheduling must not happen after a satisfied expected failure.

## Regression tests

Add coverage for:

1. `it.fails(..., { retry: 2 })` with a failing body runs exactly once and finishes as pass.
2. Ordinary `it(..., { retry: 2 })` with a failing body still runs three times.
3. `it.fails(..., { retry: 2 })` with a passing body runs once and finishes as fail.
4. `it.fails` with a syntax/collection-style Vitest error preserves the current special handling.
5. `retry.condition` is not evaluated for an already satisfied expected failure.
6. Reporter retry counters do not show fake retries for the satisfied expected-failure case.

## Diagnostic comment draft

```md
I traced this into the runner lifecycle. `runTest()` decides retry eligibility before the `test.fails` result inversion is applied.

A failing `it.fails` attempt is therefore first recorded as an ordinary failure. The retry branch sees `state !== 'pass'`, consults the retry configuration, and schedules another attempt. Only after the retry loop finishes does Vitest convert the failure into the expected passing result.

I would treat expected-failure satisfaction as part of the retry decision, not only as final reporting. After one complete attempt, including hooks and cleanup, a non-syntax failure from `test.fails` should be terminal and should not enter the retry branch.

The regression should assert that `it.fails(..., { retry: 2 })` with a failing body executes once, while a normal failing `it(..., { retry: 2 })` still executes three times. I would also cover the unexpected-pass case and preserve the existing special handling for Vitest syntax errors.
```

## Why this is a strong paid-debugging signal

This is not a broad rewrite. It is a small lifecycle-ordering bug that requires understanding how runner state, retries, hooks, and final reporting interact.

The business analogue is common in enterprise systems: a later normalization step is treated as cosmetic, but it actually changes whether the upstream scheduler should continue work. Fixing that class of bug prevents duplicate work, misleading metrics, and noisy recovery behavior.
