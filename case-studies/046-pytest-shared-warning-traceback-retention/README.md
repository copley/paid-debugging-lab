# Case Study 046 — pytest shared warning instances retain stale tracebacks under `-W error`

## Problem

pytest issue `pytest-dev/pytest#14912` reports a subtle failure mode in the deprecation-warning layer.

Several warnings in `src/_pytest/deprecated.py` are created once, at module import time, and then reused as process-lifetime objects:

```python
YIELD_FIXTURE = PytestDeprecationWarning(...)
PRIVATE = PytestDeprecationWarning(...)
CALLSPEC2_RENAMED = PytestRemovedIn10Warning(...)
```

Call sites can pass those objects directly to `warnings.warn()`. For example, `check_ispytest()` currently does:

```python
warn(PRIVATE, stacklevel=3)
```

That is usually harmless when the warning system records or prints the warning. The important edge case appears when the warning policy is configured as an error, such as with:

```text
pytest -W error
```

At that point the warning object is no longer merely message data. It is raised as an exception.

Reusing the same exception instance across independent warning emissions means exception-owned state such as `__traceback__`, `__context__` and `__cause__` can survive on a module-global object and contaminate later failures.

## Root-cause boundary

The problem is not pytest's traceback renderer. It begins earlier, at **object lifetime**.

The current deprecation module establishes a mixed representation:

```python
PRIVATE = PytestDeprecationWarning("A private pytest class or function was used.")

HOOK_LEGACY_MARKING = UnformattedWarning(
    PytestRemovedIn10Warning,
    "The hook{type} {fullname} uses old-style configuration options ...",
)
```

The first form stores an actual `Warning` instance. The second form stores a template/factory-like value.

pytest's existing `UnformattedWarning.format()` implementation is significant because it returns a **new** warning object for every call:

```python
def format(self, **kwargs):
    return self.category(self.template.format(**kwargs))
```

That gives the project an existing safe pattern: keep immutable warning metadata globally, but create the exception-capable object at emission time.

## Failure model

The problematic lifetime can be represented as:

```text
module import
    ↓
create warning instance W once
    ↓
store W in module global
    ↓
first test: warnings.warn(W)
    ↓
-W error raises W as an exception
    ↓
W now carries traceback state from test A
    ↓
second test: warnings.warn(W)
    ↓
the same exception object is raised again
    ↓
later diagnostics can contain stale state from test A
```

The crucial distinction is:

```text
warning message/category metadata    safe to share
exception instance                   mutable runtime state; should not be shared
```

An exception is not an immutable value object just because its message is immutable.

## Why this matters

### 1. Failure reports can point into the wrong execution

If the same warning object has already been raised, a later failure can carry traceback history associated with an earlier test or call site. That makes a real failure look nondeterministic or incorrectly attributed.

### 2. Traceback frames retain locals

A traceback holds frame objects, and frame objects retain their local variables. A process-lifetime warning singleton that retains old traceback state can therefore keep otherwise-dead object graphs alive much longer than intended.

In a long-running process or a test suite that repeatedly creates large local objects, this becomes more than cosmetic diagnostic noise.

### 3. The object is shared mutable exception state

If separate execution paths can raise the same object, they are mutating state on the same `BaseException` instance. That is an unsafe ownership model even before considering concurrent execution.

## Likely fix path

The fix should preserve three externally observable properties:

- warning category;
- warning message;
- `stacklevel` / reported source location.

But it should change the lifetime so each emission gets a fresh warning instance.

### Option A — use the existing `UnformattedWarning` pattern

Represent the currently shared deprecation instances as immutable warning descriptors:

```python
PRIVATE = UnformattedWarning(
    PytestDeprecationWarning,
    "A private pytest class or function was used.",
)
```

and emit:

```python
warn(PRIVATE.format(), stacklevel=3)
```

`format()` does not require placeholders; with no keyword arguments it still constructs a new category instance.

This has the advantage of reusing an abstraction pytest already owns.

### Option B — clone at the emission boundary

If changing the type of the constants is undesirable, a narrowly scoped helper could construct a fresh warning from the stored category and text before calling `warn()`.

For example, conceptually:

```python
fresh = type(DEPRECATION)(str(DEPRECATION))
warn(fresh, stacklevel=...)
```

This minimizes constant churn but is easier to apply inconsistently, so it would need a single helper and an audit of every direct singleton call site.

## What not to do

### Do not clear `__traceback__` on the shared warning and keep reusing it

That treats a symptom while retaining shared mutable exception state. It also leaves `__context__` / `__cause__` ownership questions and creates race-prone mutation around every emission.

### Do not change warning categories to plain strings

pytest callers and tests may rely on category-specific filtering. The category contract should remain intact.

### Do not fix only `PRIVATE`

`PRIVATE` gives an obvious source example, but the issue is the representation pattern. The correct patch should inventory every module-level warning *instance* that is directly emitted and either convert or clone all affected values.

## Regression strategy

The strongest regression should exercise the policy that turns the warning into an exception.

A deterministic shape is:

```python
with warnings.catch_warnings():
    warnings.simplefilter("error")

    try:
        emit_from_first_call_site()
    except PytestWarning as first:
        first_frames = traceback.extract_tb(first.__traceback__)

    try:
        emit_from_second_call_site()
    except PytestWarning as second:
        second_frames = traceback.extract_tb(second.__traceback__)
```

Then assert:

- `first is not second`;
- both exceptions have the same expected category;
- both have the same expected message;
- the second traceback contains only the second emission path, not marker frames unique to the first;
- normal non-error warning behavior remains unchanged.

The identity assertion is important. A traceback-only assertion could accidentally pass because of an implementation detail while the unsafe singleton ownership remained.

A second focused test can cover an actual pytest deprecation constant rather than only a synthetic helper, ensuring the production call path uses the fresh-instance mechanism.

## Small PR shape

This is a good small upstream contribution because the fix can remain tightly scoped:

1. inventory module-level `PytestWarning` / `PytestRemovedIn10Warning` instances in `deprecated.py`;
2. identify the direct `warnings.warn()` / `warn_explicit` call sites that emit those objects;
3. switch them to fresh per-emission warning instances, preferably through the existing `UnformattedWarning` abstraction or one centralized helper;
4. update the `deprecated.py` module documentation so it no longer recommends process-lifetime warning instances as a general representation;
5. add a `-W error` regression proving two emissions do not share identity or traceback state;
6. run the deprecation/warning unit tests and the focused regression.

The patch should not attempt a broader warning-system redesign.

## General debugging lesson

A configuration switch can change the semantic role of an object.

With ordinary warning filters, a `Warning` instance looks like reusable message data. Under `-W error`, that exact same object becomes an exception with mutable execution history.

Whenever an API can promote a value into an exception, do not store one exception instance globally and reuse it. Store the information needed to create the exception, then instantiate it at the point of use.