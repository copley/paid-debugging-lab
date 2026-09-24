# Case Study 063 — Vitest seeded file shuffling depends on discovery order

**Status:** Source-inspected investigation. No upstream comment or pull request has been posted from this work.

**Upstream issue:** [vitest-dev/vitest#11340](https://github.com/vitest-dev/vitest/issues/11340)

## Failure contract

With `sequence.shuffle.files` enabled, repeated Vitest runs using the same explicit seed can execute test files in different orders. That defeats the main debugging value of a seed: reproducing an order-dependent failure after it has been discovered.

The reporter's reproduction holds the file set, seed, worker count, and configuration constant while repeated runs produce different file orders.

## Source evidence

`RandomSequencer.sort()` currently passes the discovered `TestSpecification[]` directly to the seeded `shuffle()` helper:

```ts
public async sort(files: TestSpecification[]): Promise<TestSpecification[]> {
  const { sequence } = this.ctx.config
  return shuffle(files, sequence.seed)
}
```

A seeded shuffle is deterministic for a particular ordered input sequence. It is not a deterministic permutation of an unordered set. If file discovery returns the same specifications in a different initial order, the same pseudo-random choices are applied to different elements.

The existing unit test fixes one input order (`['b', 'a', 'c']`) and checks one expected shuffled result. It therefore verifies deterministic PRNG behavior for that input, but it does not verify the user-facing invariant that the same seed and same specification set produce the same execution order when discovery order differs.

`TestSpecification` already carries stable identity material including `taskId`, `moduleId`, project identity, and pool information, so the sequencer has enough information to establish a canonical pre-shuffle order.

## Root cause

The seed controls the random-number stream, but `RandomSequencer` does not canonicalize its input before consuming that stream. Filesystem/glob discovery order therefore leaks into the final execution order.

The missing invariant is:

> For the same set of test specifications and the same seed, file sequencing should be independent of the order in which those specifications were discovered.

## Bounded fix path

Canonicalize the specifications by a stable identity before applying the seeded shuffle. The identity must distinguish projects as well as modules; sorting only by `moduleId` can retain discovery-order dependence when multiple projects resolve the same module path.

A practical implementation should use the specification's existing stable identity (`taskId`, with an explicit tie-breaker if needed) rather than introduce another ad-hoc hash.

Then apply the existing `shuffle(..., seed)` unchanged. The defect is the unstable input boundary, not the PRNG algorithm.

## Regression matrix

1. Same specifications, same seed, original discovery order -> deterministic result.
2. Same specifications, same seed, different discovery order -> exactly the same final order.
3. Same specifications, different seed -> shuffling still changes order normally.
4. Multi-project specifications with overlapping module paths -> canonicalization remains project-aware.
5. Existing unseeded/default behavior -> no accidental contract change beyond the random sequencer's input normalization.

The highest-value unit regression is to construct one specification set, feed two different permutations of that set into separate `RandomSequencer.sort()` calls with the same seed, and compare the resulting stable identities.

## Verification plan

- Add the focused sequencer regression in `test/unit/test/sequencers.test.ts`.
- Run the sequencer unit tests.
- Run adjacent node/config tests that select `RandomSequencer` through normal Vitest configuration.
- Run lint/typecheck required by the repository.
- Confirm the reporter's repeated-run reproduction collapses to one distinct order.

## Commercial debugging analogue

This is the same class of defect seen in distributed jobs, batch processing, and integration pipelines when a supposedly reproducible operation combines a deterministic algorithm with a nondeterministic input boundary. The repair is not "more randomness control"; it is to identify and normalize the unstable upstream ordering before deterministic processing begins.
