# Case Study 010 — Poetry-core marker simplification drops a required inequality

## Upstream issue

- Issue repository: `python-poetry/poetry`
- Implementation repository: `python-poetry/poetry-core`
- Issue: `#10977`
- Area: Python, dependency markers, constraint algebra, semantic simplification

## Symptom

Poetry-core can simplify this environment marker incorrectly:

```python
os_name != "a" and "b" not in os_name
```

The parser drops `os_name != "a"` and retains only:

```python
"b" not in os_name
```

That changes the meaning. With `os_name == "a"`, the original expression is false, while the simplified expression is true.

## Source-level diagnosis

The implementation boundary is `Constraint.allows_all()` in:

```text
src/poetry/core/constraints/generic/constraint.py
```

For a left constraint `x != V` and a right constraint `W not in x`, the current branch is:

```python
if other.operator == "not in":
    if self._operator == "not in":
        return other.value in self.value
    if self._operator == "!=":
        return self.value not in other.value
```

`allows_all()` is answering an implication question: does every value accepted by the right-hand constraint also satisfy the left-hand constraint?

For:

```text
self  = x != V
other = W not in x
```

that implication is true only when `W` is contained in `V`.

Why: if `W` is not contained in `V`, then `x = V` satisfies `W not in x` but violates `x != V`. That single counterexample proves the right-hand constraint does not imply the left-hand constraint.

The implementation checks the containment relation in the opposite direction.

## Focused fix path

The narrow source change is:

```python
if self._operator == "!=":
    return other.value in self.value
```

For the reported values:

```text
V = "a"
W = "b"
```

`"b" in "a"` is false, so Poetry-core correctly declines to discard the inequality.

For an existing valid implication such as:

```text
x != "1.2.3-tegra"
and
"tegra" not in x
```

`"tegra" in "1.2.3-tegra"` is true. Any value that excludes `tegra` cannot equal `1.2.3-tegra`, so the simplification remains valid.

## Small PR scope

This is a strong small-PR candidate because the likely patch is one source line plus focused tests.

Suggested files:

```text
src/poetry/core/constraints/generic/constraint.py
tests/constraints/generic/test_constraint.py
tests/version/test_markers.py
```

The direct constraint test should add the missing negative case:

```python
(
    Constraint("a", "!="),
    Constraint("b", "not in"),
    True,   # allows_any
    False,  # allows_all
)
```

The marker-level regression should verify that:

1. `parse_marker('os_name != "a" and "b" not in os_name')` retains both clauses;
2. validation with `{"os_name": "a"}` returns `False`;
3. validation still behaves correctly for values that satisfy both clauses;
4. existing substring implications such as `"tegra" not in x => x != "1.2.3-tegra"` remain simplified correctly.

## Risk notes

The fix should stay within the generic string-constraint algebra. It should not alter version constraints or reinterpret PEP 508 substring operators as equality operators.

A one-case regression is not enough: implication code is directional, so tests should cover both containment directions and both true and false implication outcomes.

## Draft upstream diagnostic comment

I traced this to the implication direction in `poetry-core` rather than the marker parser itself.

`Constraint.allows_all()` is asked whether every value satisfying `W not in x` also satisfies `x != V`. In the `self.operator == "!="` / `other.operator == "not in"` branch, the current code returns:

```python
self.value not in other.value
```

That checks the containment relationship in the wrong direction. The implication is sound only when `other.value in self.value`.

A counterexample makes the boundary clear. For `V="a"` and `W="b"`, the value `x="a"` satisfies `"b" not in x` but violates `x != "a"`, so `allows_all()` must return false and the inequality must not be dropped.

The focused patch appears to be:

```python
if self._operator == "!=":
    return other.value in self.value
```

I would add both a direct constraint-algebra case and the reported marker-level regression. Existing cases where the forbidden substring is contained in the excluded exact value, such as `"tegra" not in x` implying `x != "1.2.3-tegra"`, should continue to return true.

## Commercial relevance

This is representative of dependency-resolution debugging where the visible symptom is an incorrect environment decision, but the defect is a reversed logical implication inside a simplifier. These bugs are high impact because the program returns a plausible result instead of crashing, allowing an invalid dependency branch to survive into lock or install behavior.