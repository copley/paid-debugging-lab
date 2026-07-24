# Case Study 019: Poetry Misclassifies a Symlinked System Interpreter as a Virtualenv

## Upstream issue

`python-poetry/poetry#10991`

## Risk

A Python version manager can expose one interpreter through a version alias symlink such as:

```text
.../python/3.14 -> .../python/3.14.6
```

Poetry can compare those two path strings, decide they represent different environments, and incorrectly classify the plain shared interpreter as an active virtualenv. `poetry install` or `poetry sync` may then modify the shared interpreter instead of creating a project-specific environment.

This is a correctness and isolation failure rather than a cosmetic environment-reporting bug.

## Failure path

`EnvManager.get_system_env()` constructs a `GenericEnv` from Poetry's base Python prefix.

`VirtualEnv.__init__()` asks that interpreter for its own `sys.base_prefix`. A version-manager alias can therefore produce:

```text
GenericEnv._path = .../python/3.14
GenericEnv._base = .../python/3.14.6
```

`GenericEnv.is_venv()` currently uses a lexical comparison:

```python
return self._path != self._base
```

The paths are different strings but resolve to the same directory.

`EnvManager.create_venv()` trusts the result and returns early when `env.is_venv()` is true, bypassing normal project virtualenv creation.

## Root-cause hypothesis

Virtual-environment identity is being inferred from uncanonicalized path spellings.

A symlink alias is not evidence of environment isolation. The comparison should operate on filesystem identity or canonical paths.

## Focused fix direction

The smallest candidate change is to canonicalize both paths before comparison:

```python
def is_venv(self) -> bool:
    return self._path.resolve() != self._base.resolve()
```

An alternative is `os.path.samefile()` with a conservative fallback when either path cannot be statted. `Path.resolve()` is simpler, but the regression matrix should cover platform and missing-path behavior before choosing it.

## Regression tests

Add focused tests for `GenericEnv.is_venv()`:

1. two identical paths return false;
2. a symlink and its target return false;
3. a real virtualenv path and its base interpreter path return true;
4. relative path components do not create a false virtualenv result;
5. unresolved or missing paths retain deterministic behavior;
6. Windows junction/case-normalization behavior is preserved where supported.

Add an `EnvManager.create_venv()` regression proving that a symlinked base interpreter no longer triggers the early `Already inside a virtualenv` return.

## Verification

A reproduction should demonstrate:

```text
virtualenvs.create = true
no VIRTUAL_ENV is active
base interpreter exposed through a version alias symlink
```

Expected result: Poetry creates or selects a project-specific virtualenv and does not install packages into the shared version-manager interpreter.

## Draft diagnostic comment

I traced the early return to `GenericEnv.is_venv()` rather than to Poetry's virtualenv configuration.

`get_system_env()` constructs `GenericEnv(base_prefix, child_env=...)`. `VirtualEnv.__init__()` then queries the selected interpreter for its own `sys.base_prefix`. With a version-manager alias, `_path` can be the symlink path while `_base` is its resolved target.

`GenericEnv.is_venv()` currently compares those paths lexically, so two names for the same directory are treated as an isolated environment. `EnvManager.create_venv()` then returns early because it believes Poetry is already inside a virtualenv.

A focused fix is to compare canonical filesystem paths, or use `samefile()` with a fallback. The regression test should use a real symlink and also prove that a genuine virtualenv still returns true.

## Engineering lesson

Environment isolation is a filesystem-identity property. Comparing unresolved path strings is unsafe anywhere symlinks, junctions, version aliases, or case normalization are possible.
