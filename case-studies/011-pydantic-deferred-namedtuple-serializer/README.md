# Case Study 011 — Pydantic deferred model in a NamedTuple hits `MockValSer`

## Upstream issue

- Repository: `pydantic/pydantic`
- Issue: `#13448`
- Area: Python, Pydantic v2, deferred schema construction, nested serialization

## Symptom

A model can validate successfully but fail during JSON serialization when all of the following are true:

1. an inner `BaseModel` uses `defer_build=True`;
2. that model appears as a field inside a `NamedTuple`;
3. an eagerly built outer model contains the `NamedTuple`.

The failure is:

```text
PydanticSerializationError: Error serializing to JSON:
TypeError: 'MockValSer' object cannot be converted to 'SchemaSerializer'
```

The equivalent ordinary tuple does not fail.

## Verified reproduction

I reproduced the report with Pydantic `2.13.4`.

The behavior matrix was:

| Field shape | Nested model | Result |
|---|---|---|
| ordinary tuple | eagerly built model | serializes |
| `NamedTuple` | eagerly built model | serializes |
| ordinary tuple | deferred model | serializes |
| `NamedTuple` | deferred model | fails |

Validation completes in all four cases. The defect appears only when the lazy `NamedTuple` field is serialized.

## Source-level diagnosis

The inner deferred model still exposes a placeholder serializer:

```text
LazyBar.__pydantic_serializer__
    -> pydantic._internal._mock_val_ser.MockValSer
```

The outer model's generated schema contains a complete definition for the deferred inner model. However, the two container forms compile differently:

- a normal tuple receives a positional tuple serializer whose nested item points to the generated definition;
- a `NamedTuple` is represented as a `call_schema` and compiles through an `AnySerializer` path.

The `NamedTuple` schema is generated in `pydantic/_internal/_generate_schema.py`. Its current structure is conceptually:

```python
arguments_schema = ...
return core_schema.call_schema(
    arguments_schema,
    namedtuple_cls,
    ref=namedtuple_ref,
)
```

There is no explicit positional serialization schema attached to that call schema.

At runtime, the `AnySerializer` encounters the nested deferred `BaseModel` and attempts to use the model class's prebuilt serializer. That object is still `MockValSer`, not a concrete `SchemaSerializer`, and the native serializer boundary rejects it before the proxy can rebuild itself.

A useful confirmation is that rebuilding only the inner model repairs serialization through the already-created outer serializer:

```python
LazyBar.model_rebuild()
baz.model_dump_json()
```

That strongly locates the failure at deferred serializer resolution rather than validation or `NamedTuple` construction.

## Focused fix paths

Two implementation boundaries are plausible.

### Option A — Give `NamedTuple` an explicit positional serializer

Generate a tuple-style serializer from the `NamedTuple` field schemas and attach it to the call schema. This would make the serializer follow the same definition-reference path as an ordinary tuple instead of falling back to `AnySerializer`.

The implementation must ensure that deferred nested definitions are compiled from the generated schema rather than replaced with the model class's `MockValSer` placeholder.

### Option B — Harden prebuilt serializer lookup

Where Pydantic or pydantic-core dynamically retrieves `__pydantic_serializer__`, treat `MockValSer` as an unresolved placeholder rather than a valid prebuilt `SchemaSerializer`.

The lookup could rebuild the deferred model or fall back to the schema definition available in the parent serializer graph.

This option may protect other containers that reach the same dynamic `AnySerializer` path, but it has a wider behavioral surface.

## Regression-test matrix

A focused test should cover:

1. an eager outer model containing a `NamedTuple` with a deferred `BaseModel` field;
2. successful `model_dump()` and `model_dump_json()` without manually rebuilding the inner model;
3. the equivalent ordinary tuple as a guard case;
4. an eagerly built nested model as a guard case;
5. explicit `LazyBar.model_rebuild()` remaining valid;
6. repeated serialization to ensure no one-time cache transition regresses.

Likely test location:

```text
tests/test_types_namedtuple.py
```

Likely implementation locations:

```text
pydantic/_internal/_generate_schema.py
pydantic/_internal/_mock_val_ser.py
pydantic-core serializer/prebuilt lookup code
```

## PR scope

The smallest safe PR should begin with the deterministic regression test, then determine whether the project prefers container-specific serialization for `NamedTuple` or a general fix for unresolved prebuilt serializers.

A local experiment that merely disables prebuilt serializer reuse can avoid the exception, but that is not sufficient as a production fix. The patch must preserve serializer reuse for fully built models while excluding unresolved placeholders.

## Draft upstream diagnostic comment

I reproduced this on Pydantic 2.13.4 and narrowed it to the serializer selected for the `NamedTuple`, rather than validation of the nested model.

The outer model's core schema contains a complete definition for `LazyBar`, but the compiled serializers differ by container shape: the ordinary tuple receives a positional tuple serializer with a definition reference, while the `NamedTuple` call schema falls back to an `AnySerializer` path.

At runtime that path reads `LazyBar.__pydantic_serializer__`, which is still a `MockValSer` because of `defer_build=True`. The native serializer boundary expects a concrete `SchemaSerializer`, so it raises before the placeholder can rebuild itself. Calling `LazyBar.model_rebuild()` makes the existing `Baz` serializer work, which confirms the failure is deferred serializer resolution rather than the validated value.

I would start with a regression in `tests/test_types_namedtuple.py` covering `model_dump()` and `model_dump_json()`. The implementation then appears to have two viable boundaries: attach an explicit positional serializer to the generated `NamedTuple` call schema, or make prebuilt/dynamic serializer lookup treat `MockValSer` as unresolved instead of as a usable prebuilt serializer. The first is narrower; the second may cover other `AnySerializer` container paths.

## Commercial relevance

This is representative of production debugging where validation succeeds, the object looks correct in memory, and failure occurs only at an integration boundary such as JSON output, an API response, or a persistence layer. The root cause is a mismatch between eager parent compilation and a deferred nested serializer, not malformed application data.