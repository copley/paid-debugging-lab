# Case Study 054 — Vitest `toMatchObject` leaks subset equality into `arrayContaining`

## Problem

Vitest issue `vitest-dev/vitest#11071` reports that this assertion incorrectly passes:

```ts
expect({
  records: [{ id: 1 }],
}).toMatchObject({
  records: expect.arrayContaining([
    { id: 1, required: "must exist" },
  ]),
});
```

The expected array element requires a `required` property, but the actual element does not contain it. Jest correctly fails the same assertion.

## Root cause

`toMatchObject` intentionally invokes `jestEquals()` with `subsetEquality` in its custom-tester list so the **outer object** can be matched as a subset.

When Vitest's equality engine encounters an asymmetric matcher, it forwards that entire current custom-tester list into `asymmetricMatch()`.

`ArrayContaining.asymmetricMatch()` then reuses those passed testers when comparing each expected array element against each actual array element:

```ts
equals(item, another, customTesters)
```

That accidentally propagates `toMatchObject`'s subset semantics across the asymmetric-matcher boundary.

The argument direction makes the failure especially subtle. `arrayContaining` compares its expected sample item first and the actual array element second. `subsetEquality(object, subset)` therefore sees:

```text
object = { id: 1, required: "must exist" }
subset = { id: 1 }
```

and returns true because every property in the smaller actual object exists in the larger expected object. The missing `required` field is never tested.

## Why this is a context-leak bug

`subsetEquality` is not a globally configured user equality tester. It is a transient implementation detail of the outer `toMatchObject` operation. It should not change the semantics of a nested `arrayContaining` matcher.

Jest provides a useful reference contract. Its `ArrayContaining` obtains the globally registered custom equality testers from its own matcher context instead of inheriting the outer equality call's transient tester list.

The useful abstraction is therefore:

```text
outer matcher-specific equality rules != global/user custom equality rules
```

Crossing an asymmetric-matcher boundary should not accidentally inherit the former.

## Likely fix

A small compatibility fix should make asymmetric matchers use their own matcher-context custom testers rather than blindly reusing the transient testers supplied by the enclosing equality operation.

For `ArrayContaining`, the core change is conceptually:

```ts
const { customTesters } = this.getMatcherContext()

this.sample.every(item =>
  other.some(another => equals(item, another, customTesters)),
)
```

The implementation should audit `ObjectContaining` and other asymmetric matchers at the same boundary so the fix is consistent rather than special-casing one assertion spelling.

## Regression coverage

Add tests proving all of these invariants:

1. `toMatchObject` + `arrayContaining` fails when an expected array-object property is missing.
2. It passes when the actual object contains every expected property plus additional properties.
3. `expect.objectContaining()` nested inside `arrayContaining()` still provides explicit subset semantics.
4. User-registered custom equality testers still work inside asymmetric matchers.
5. Plain `toMatchObject` continues to use subset matching for the outer object.

The tests should compare behavior with Jest where practical because this surface is explicitly Jest-compatible.

## PR shape

Likely files:

```text
packages/expect/src/jest-asymmetric-matchers.ts
packages/expect/test/... matcher regression tests
```

The patch should stay in the equality/matcher layer; no runner or reporter changes are needed.

## Prevention rule

When an equality engine passes callback/tester state through nested matchers, distinguish **user configuration** from **operation-local policy**. Local policies such as subset matching, iterable normalization, or strictness flags should not leak into an independently defined nested matcher unless that composition is explicitly part of its contract.

## Verification

A focused verification should run the expectation package tests plus a direct regression equivalent to issue `#11071`, and confirm the same assertion outcome in Jest.
