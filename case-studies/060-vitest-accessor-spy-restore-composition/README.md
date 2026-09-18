# Case Study 060 — Vitest accessor spies restore stale sibling descriptors

## Contribution status

- Upstream issue: https://github.com/vitest-dev/vitest/issues/11306
- Checked issue comments: 2026-09-18
- Checked open PRs: 2026-09-18
- Assignment status: unassigned
- Intent comment: none
- Reproduction: reporter-provided reproduction inspected; not independently executed in this investigation
- Upstream PR: none at time of review
- Portfolio classification: source-inspected investigation, not an upstream contribution

## User-visible failure

If `vi.spyOn()` installs both a getter spy and a setter spy on the same property, restoring one spy can remove the still-active sibling spy, and `vi.restoreAllMocks()` can restore a descriptor that still contains one of the mocks. For inherited accessors, restoring one spy can also delete the synthetic own descriptor while the sibling spy still depends on it.

This is high impact because the failure can be silent: a getter may continue returning a mocked value after cleanup and contaminate later tests when an environment is reused.

## Evidence

Current `packages/spy/src/index.ts` captures `originalDescriptor` independently for each `spyOn()` call. The second accessor spy therefore snapshots a descriptor that already contains the first accessor's mock.

`reassign()` reconstructs the whole property descriptor from that per-spy snapshot and replaces only its own accessor slot. `restore()` either calls `reassign(original)` or, for an inherited descriptor, deletes the own property entirely. Those operations are correct for an isolated accessor spy but do not compose when getter and setter spies coexist on one descriptor.

Existing unit coverage proves getter and setter restoration separately in `test/unit/test/mocking/vi-spyOn.test.ts`; the missing contract is sibling composition.

## Root cause

The ownership unit is wrong. A getter spy and setter spy are tracked as independent mocks, but JavaScript stores both accessors in one property descriptor. Each spy consequently owns only one slot while its restore operation mutates the complete descriptor.

A stale snapshot can therefore overwrite a sibling's current state:

```text
original       { get: G0,    set: S0 }
after get spy  { get: mockG, set: S0 }
set snapshot   { get: mockG, set: S0 }
after set spy  { get: mockG, set: mockS }
restore get    { get: G0,    set: S0 }
restore set    { get: mockG, set: S0 }   <- stale sibling state returns
```

Changing `restoreAllMocks()` to reverse order would address only the aggregate-cleanup case. It would not fix an individual `getSpy.mockRestore()` wiping a live setter spy.

## Bounded fix path

For own properties, accessor restoration should merge the original accessor into the **current** descriptor rather than rebuild the whole descriptor from the per-spy snapshot. That preserves the sibling slot's current state.

The inherited-accessor branch needs one additional ownership rule: the synthetic own descriptor cannot be deleted until no sibling accessor spy still depends on it. A robust implementation should coordinate restoration per `(object, property)` descriptor, or otherwise record enough state to distinguish the final accessor-spy release from the first one.

Avoid using only `isMockFunction(current.get/set)` as the ownership test: an original accessor may legitimately already be a mock, so mock-ness alone is weaker than explicit spy ownership.

## Regression matrix

Add focused tests beside the existing `vi.spyOn()` getter/setter restoration coverage:

1. own accessor: get spy then set spy, `restoreAllMocks()` restores both originals;
2. own accessor: set spy then get spy, same result;
3. individual restore of getter leaves setter spy active;
4. individual restore of setter leaves getter spy active;
5. inherited accessor: restoring one sibling keeps the other active and the final restore removes the synthetic own descriptor;
6. original getter or setter already being a mock does not get mistaken for Vitest's sibling-spy ownership.

## Verification plan

Run the focused spy/mocking unit suites first, then the package-level tests and repository-required type/lint checks. The reporter's four-case reproducer should be retained as an external control, but upstream regression tests should encode the descriptor-level invariant directly.

## Engineering lesson

When multiple wrappers mutate different fields of one composite runtime object, cleanup cannot safely be modeled as independent whole-object snapshot restoration. Restoration ownership must match the granularity of mutation, or a later cleanup operation can resurrect stale sibling state.
