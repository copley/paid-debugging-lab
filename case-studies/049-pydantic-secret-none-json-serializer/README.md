# Case Study 049 — Pydantic secret fields crash on unvalidated `None` during JSON serialization

## Upstream issue

- Pydantic: https://github.com/pydantic/pydantic/issues/13692

## Symptom

A model can legally carry an unvalidated default such as `s: SecretStr = None` when default validation is disabled, but `model_dump_json()` crashes with a `PydanticSerializationError` wrapping:

```text
AttributeError: 'NoneType' object has no attribute 'get_secret_value'
```

The same model can be dumped in Python mode, and ordinary scalar fields with an unvalidated `None` default serialize as JSON `null`.

## Source diagnosis

`SecretStr` and `SecretBytes` share `_serialize_secret_field()` in `pydantic/types.py`:

```python
def _serialize_secret_field(value, info):
    if info.mode == 'json':
        return _secret_display(value.get_secret_value())
    return value
```

The serializer is registered with:

```python
when_used='always'
```

That means the custom serializer is invoked even when the runtime value is `None`. The JSON branch then assumes the value is a secret wrapper and dereferences `get_secret_value()` unconditionally.

Pydantic Core already supports a serializer mode designed for this boundary: `when_used='unless-none'`. Skipping the custom secret serializer for `None` lets the normal serializer emit JSON `null`, while preserving masking behavior for real `SecretStr`/`SecretBytes` values.

## Root cause

The serializer's declared applicability is broader than the values it can safely handle.

The type-specific function assumes `_SecretField`, but `when_used='always'` permits unvalidated runtime values such as `None` to reach it. Because defaults are not necessarily validated, serializers must either tolerate those values or declare a narrower invocation condition.

## Likely fix

Prefer narrowing the serializer contract rather than adding a one-off `if value is None` branch inside `_serialize_secret_field()`.

Change the `_SecretField` serializer registrations from:

```python
when_used='always'
```

to:

```python
when_used='unless-none'
```

There are two relevant registration points in `_SecretField`: the schema returned for model fields and the class-level `__pydantic_serializer__`.

This keeps the masking logic focused on actual secret values and delegates `None` handling back to the generic serializer.

## Verification

Add regression coverage for both secret field types:

```python
class Model(BaseModel):
    text: SecretStr = None
    data: SecretBytes = None

assert Model().model_dump() == {'text': None, 'data': None}
assert Model().model_dump_json() == '{"text":null,"data":null}'
```

Also retain checks that validated secret values still serialize masked in JSON mode and that `Optional[SecretStr] = None` remains unchanged.

The focused test should verify:

- unvalidated `None` default does not raise;
- JSON output is `null`;
- Python-mode output remains `None`;
- real `SecretStr` values remain masked;
- real `SecretBytes` values remain masked;
- optional secret fields preserve existing behavior.

## Engineering lesson

Custom serializers need an explicit runtime-value contract. When a framework permits unvalidated defaults, an annotation alone does not guarantee the serializer will receive an instance of that annotated type. Narrowing serializer applicability is often safer than teaching the serializer about every out-of-contract value.