# Case Study 008 — Pytest turns a valid doctest skip into an INTERNALERROR

## Problem

A test suite using `jaraco.test` fails inside pytest report generation instead of recording a skipped test. The failure occurs on Python 3.12 with pytest 8.4 and newer when the package is tested outside a Git checkout:

```text
INTERNALERROR> File "src/_pytest/reports.py", line 420, in from_item_and_call
INTERNALERROR>   assert line is not None
INTERNALERROR> AssertionError
```

Upstream issue inspected: `pytest-dev/pytest#14700`.

## Reported reproduction

```bash
git clone https://github.com/jaraco/jaraco.test
cd jaraco.test
uv venv -p 3.12
uv pip install -e . pytest
rm -rf .git
pytest -vv jaraco/test/git.py
```

Removing `.git` is important because the affected fixture intentionally skips when the working tree has no `origin` remote.

## Source trace

### 1. `jaraco.test.git` is loaded as a pytest plugin

The package registers the module under the `pytest11` entry-point group:

```toml
[project.entry-points."pytest11"]
"jaraco.test.http" = "jaraco.test.http"
"jaraco.test.git" = "jaraco.test.git"
```

The fixture is therefore available while pytest is collecting doctests from that same plugin module.

### 2. The fixture performs a legitimate runtime skip

```python
@pytest.fixture
def ensure_checkout() -> None:
    """Skip the test unless a git checkout with an 'origin' remote is present."""
    _has_origin() or pytest.skip("requires a git checkout with an 'origin' remote")
```

The skip itself is expected behavior when the source tree came from an sdist or when `.git` has been removed.

### 3. A doctest item may have no numeric source line

Pytest's doctest item reports its location directly from the standard-library `DocTest` object:

```python
def reportinfo(self) -> tuple[os.PathLike[str] | str, int | None, str]:
    return self.path, self.dtest.lineno, f"[doctest] {self.name}"
```

The return type explicitly permits `None` for the line number.

### 4. Report generation assumes the line is always present

For skip exceptions marked to use the item's location, `TestReport.from_item_and_call()` currently does this:

```python
if excinfo.value._use_item_location:
    path, line = item.reportinfo()[:2]
    assert line is not None
    longrepr = (os.fspath(path), line + 1, r.message)
```

That assertion is incompatible with the valid `DoctestItem.reportinfo()` contract. A normal skip is consequently converted into a pytest INTERNALERROR.

## Root cause

The bug is not in `pytest.skip()` and not in the `jaraco.test` fixture. It is a report-formatting invariant that is too strong:

```text
valid doctest item
    + runtime skip
    + item location requested
    + reportinfo() returns line=None
                         |
                         v
             assert line is not None
                         |
                         v
                 pytest INTERNALERROR
```

Pytest already has a usable fallback location in `excinfo._getreprcrash()`. Report generation should use that fallback when an item cannot provide a numeric line.

## Small upstream PR candidate

A narrow fix can remain inside `src/_pytest/reports.py`:

```python
if excinfo.value._use_item_location:
    path, line = item.reportinfo()[:2]
    if line is not None:
        longrepr = (os.fspath(path), line + 1, r.message)
    else:
        longrepr = (str(r.path), r.lineno, r.message)
else:
    longrepr = (str(r.path), r.lineno, r.message)
```

The duplicated fallback could be factored into a small local branch, but the behavioral change should stay limited to the `line is None` case.

This preserves the preferred item location for normal Python test items while preventing an unknown doctest line from crashing the entire session.

## Regression tests

A focused test should create or collect a doctest item whose `reportinfo()` returns a path with `line=None`, then trigger a skip that requests the item location.

Assertions:

1. pytest exits normally rather than with an INTERNALERROR;
2. the item outcome is `skipped`;
3. the skip reason is retained;
4. the long representation is a valid `(path, line, message)` tuple;
5. ordinary skip-marker reports with a numeric item line still use that item line.

A higher-level regression can mirror the reported integration case by loading a tiny plugin module containing a fixture doctest and making the fixture skip.

## Draft public diagnostic comment

```markdown
I traced this to a mismatch between two valid pytest contracts rather than to the `jaraco.test` fixture itself.

`jaraco.test.git` is loaded as a `pytest11` plugin, and its `ensure_checkout` fixture legitimately calls `pytest.skip()` when the source tree has no Git `origin`. The collected item is a doctest item. `DoctestItem.reportinfo()` is explicitly allowed to return `line=None`, because it forwards `self.dtest.lineno`.

In `TestReport.from_item_and_call()`, the skipped-exception branch currently assumes that any item-location skip has a numeric line:

```python
path, line = item.reportinfo()[:2]
assert line is not None
```

That assertion converts a normal skip into the reported INTERNALERROR.

A narrow fix would retain the item location when `line` is present, but fall back to the already-computed crash representation when it is not:

```python
if line is not None:
    longrepr = (os.fspath(path), line + 1, r.message)
else:
    longrepr = (str(r.path), r.lineno, r.message)
```

I would add a regression test with a doctest item whose `reportinfo()` returns `None` for the line, asserting that it produces a normal skipped report and does not abort the session. A second assertion should preserve the current numeric item-location behavior for ordinary skip markers.
```

## Why this is a strong portfolio case

This issue demonstrates commercially useful debugging skills:

- reducing a package-level failure to a report-generation invariant;
- following pytest plugin entry points into doctest collection;
- distinguishing a valid skip from the later formatting crash;
- reconciling incompatible type contracts across subsystems;
- proposing a minimal fallback rather than weakening doctest behavior;
- defining regression coverage for both the edge case and existing behavior.
