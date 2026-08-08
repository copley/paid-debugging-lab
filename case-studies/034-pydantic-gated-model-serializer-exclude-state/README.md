# Case Study 034 — Pydantic: gated model serializer drops runtime include/exclude state

## Incident

A Pydantic model using `@model_serializer(..., when_used="json")` can ignore a caller's runtime `exclude=` when serialized in Python mode.

Upstream issue: [pydantic/pydantic#13601](https://github.com/pydantic/pydantic/issues/13601)

The minimal symptom is:

```python
class Gated(BaseModel):
    a: int
    b: int

    @model_serializer(mode="wrap", when_used="json")
    def _serialize(self, handler: SerializerFunctionWrapHandler) -> object:
        return handler(self)

Gated(a=1, b=2).model_dump(exclude={"b"})
# Actual:   {'a': 1, 'b': 2}
# Expected: {'a': 1}
```

The JSON path works because the custom serializer is active there. The bug appears when `when_used` decides not to call the custom serializer and Pydantic falls back to the model's normal serializer.

A longstanding open report, [pydantic/pydantic#6575](https://github.com/pydantic/pydantic/issues/6575), describes the same class of failure for gated model serializers, so this is not merely a recent API misunderstanding.

## Source-backed boundary

Pydantic's schema generator correctly records `when_used` on the core serializer schema. For model wrap serializers it creates:

```python
core_schema.wrap_serializer_function_ser_schema(
    serializer.func,
    info_arg=info_arg,
    return_schema=return_schema,
    when_used=serializer.info.when_used,
)
```

The fault is lower in `pydantic-core/src/serializers/type_serializers/function.rs`.

`FunctionWrapSerializer.call()` returns `(false, value)` when the `when_used` predicate says the function should not run. The fallback serializer is the wrapped model serializer itself:

```rust
fn get_fallback_serializer(&self) -> &CombinedSerializer {
    self.serializer.as_ref()
}
```

However, the shared `function_type_serializer!` macro handles both the custom-function path and the fallback path identically after that decision. It replaces the caller's include/exclude scope with an empty one before invoking the selected serializer:

```rust
let state = &mut state.scoped_include_exclude(IncludeExclude::empty());
ret_serializer.to_python(v.bind(py), state)
```

That reset is valid only after a custom serializer has actually run and filtering has already been delegated through its handler/return path. When `when_used` rejects the custom serializer, no filtering has happened yet. Clearing the state at that point causes the fallback model serializer to see no runtime `include` or `exclude` request.

## Root cause

The serializer dispatch code conflates two different control-flow states:

```text
custom serializer ran
    -> returned value should not be filtered a second time

custom serializer was skipped by when_used
    -> fallback serializer still must receive the original filtering state
```

Both currently enter the same empty-include/exclude scope.

This also means the bug boundary is probably wider than the single wrap example in #13601. `FunctionPlainSerializer` uses the same macro and also creates a fallback serializer when `when_used != Always`, so gated plain model serializers should be included in regression coverage.

## Small PR candidate

### Likely fix direction

Split the macro's post-`call()` handling according to whether the custom function actually ran.

Conceptually:

```rust
match self.call(value, state) {
    Ok((true, v)) => {
        // Custom function handled serialization/filtering semantics.
        let state = &mut state.scoped_include_exclude(IncludeExclude::empty());
        self.return_serializer.to_python(v.bind(py), state)
    }
    Ok((false, v)) => {
        // when_used skipped the function; preserve the caller's state.
        self.get_fallback_serializer().to_python(v.bind(py), state)
    }
    ...
}
```

The exact implementation should preserve the existing error/warning behavior and equivalent logic in `serde_serialize()` and `json_key()` where relevant.

The important invariant is:

```text
when_used must choose whether the custom serializer runs;
it must not erase model_dump() filtering options when falling back.
```

## Regression matrix

Add focused coverage for:

1. `mode="wrap", when_used="json"` + Python-mode `exclude`.
2. The same serializer + Python-mode `include`.
3. Nested include/exclude mappings, not just one top-level field.
4. `mode="plain", when_used="json"` if the same fallback path reproduces.
5. `when_used="json-unless-none"` fallback behavior.
6. JSON mode, proving the custom serializer path remains unchanged.
7. `exclude_unset`, `exclude_defaults`, and `exclude_none`, because they are carried in the same serialization state and should not regress.
8. An always-enabled serializer, proving its current filtering semantics remain intact.

## Verification

Primary implementation area:

```text
pydantic-core/src/serializers/type_serializers/function.rs
```

High-level regression coverage should also exercise the public `BaseModel.model_dump()` API rather than testing only core-schema internals.

Before submission, run the focused serializer tests in `pydantic-core/tests/serializers/` and the relevant Pydantic `tests/test_serialize.py` coverage.

## Diagnostic comment draft

> I traced this past the decorator/schema-generation layer into `pydantic-core`'s function serializer dispatch.
>
> `when_used` itself is being recorded correctly. The failure happens after `FunctionWrapSerializer.call()` returns `(false, value)` to indicate that the JSON-only serializer should be skipped. The shared `function_type_serializer!` macro then selects the fallback model serializer, but immediately enters `scoped_include_exclude(IncludeExclude::empty())` before calling it.
>
> That empty scope makes sense after the custom serializer actually ran, because filtering is expected to have been handled through the serializer/handler path. It is incorrect on the `when_used == false` fallback path: no filtering has occurred yet, so the normal model serializer needs the original `include`/`exclude` state.
>
> I would split those two branches so the fallback serializer receives the existing state, while the custom-function return path keeps the current empty-filter scope. The same macro is also used by `FunctionPlainSerializer`, so I would regression-test both plain and wrap serializers with `when_used="json"`, plus nested include/exclude cases and `exclude_unset/defaults/none`.

## Status

- The root-cause boundary is supported by current Pydantic and pydantic-core source.
- The behavior overlaps the longstanding open issue #6575, which is already assigned, so coordination with that issue should happen before opening a competing PR.
- No matching open PR for #13601 was found.
- No public upstream comment was posted.
- No patch or upstream test run is claimed here.