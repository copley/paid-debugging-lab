# Case Study 030 — PyPy pip overlay breaks later downgrades on Windows

## Target

- Repository: `actions/setup-python`
- Issue: [#1348 — pip is broken after downgrade on Windows pypy-3.11](https://github.com/actions/setup-python/issues/1348)
- Area: GitHub Actions, Windows, PyPy, Python packaging

## Symptom

A Windows job installs `pypy-3.11` through `actions/setup-python`, downgrades pip, and then finds that pip can no longer import its own internals:

```text
ImportError: cannot import name 'get_runnable_pip'
from 'pip._internal.utils.misc'
```

The equivalent workflow does not reproduce on Ubuntu.

## Reproduction boundary

The setup action currently runs two installation steps for PyPy:

```text
python -m ensurepip
python -m pip install --ignore-installed pip
```

The observed log shows `ensurepip` installing pip 24.0, the second command downloading pip 26.2, and the final status still reporting pip 24.0. A subsequent downgrade to pip 26.1.2 leaves a pip 26.2-only module on disk, producing a mixed installation.

## Root cause

`--ignore-installed` is the critical flag.

It tells pip to install the new payload without uninstalling the distribution already supplied by `ensurepip`. That bypasses the normal uninstall/RECORD cleanup path and can leave package files and metadata describing different pip releases in the same `site-packages` directory.

The later downgrade is then operating from inconsistent metadata. It cannot reliably remove every file introduced by the overlaid release, so newer-only modules survive beside the downgraded code. The traceback is therefore not an ordinary pip downgrade incompatibility; it is evidence of a file-level overlay created during the action's bootstrap step.

## Small PR candidate

### Source change

File:

```text
src/install-pypy.ts
```

Replace the overlay install with a normal pip upgrade that permits uninstall and metadata replacement:

```text
python -m ensurepip
python -m pip install --upgrade pip
```

If a clean reinstall is needed for a specific PyPy release, use a tested reinstall path rather than `--ignore-installed`; the important invariant is that the previous pip distribution is removed using its installed metadata before the replacement files are committed.

### Unit coverage

File:

```text
__tests__/install-pypy.test.ts
```

The current installer test checks only that the command runner was called. Add assertions for the exact sequence and verify that the pip upgrade command does not contain `--ignore-installed`.

### Windows regression workflow

A meaningful regression test must execute a real Windows PyPy installation:

1. install `pypy-3.11` through the action;
2. record `python -m pip --version` and `importlib.metadata.version("pip")`;
3. install a known earlier pip release;
4. run `python -m pip --version` again;
5. install a normal package such as `requests`;
6. assert that no files from the later release remain in a module path that should not exist in the downgraded version.

This protects the package-state invariant rather than merely checking that the bootstrap command exited successfully.

## Verification

```text
npm test -- --runTestsByPath __tests__/install-pypy.test.ts
npm run build
npm run format-check
npm run lint
```

Then run the Windows PyPy workflow and confirm that both the downgrade and a subsequent package installation succeed.

Because GitHub Actions repositories commit their executable bundle, the generated distribution files must be rebuilt and committed if this repository's contribution rules require them.

## Diagnostic comment draft

> I confirmed that the failure is consistent with a mixed pip installation rather than a normal downgrade bug. `ensurepip` installs pip 24.0, then setup-python runs `pip install --ignore-installed pip`. That flag bypasses uninstall and RECORD cleanup, so the newer payload can be copied over the existing distribution while the installed metadata still describes the earlier version. The log downloading 26.2 but reporting 24.0 is the visible sign of that mismatch.
>
> When the workflow later installs 26.1.2, pip removes files according to inconsistent metadata and leaves a 26.2-only `pip/_internal/build_env/__init__.py` behind. That stale module then imports an API that does not exist in 26.1.2.
>
> A focused fix is to use the normal upgrade path after `ensurepip` so pip can uninstall and replace its own distribution cleanly. I would pair the source change with a Windows PyPy regression that downgrades pip, verifies `importlib.metadata.version("pip")`, and then successfully installs another package. The existing unit test should also assert the exact bootstrap command so `--ignore-installed` cannot return unnoticed.

## Scope

This case study does not claim that every pip self-upgrade on every interpreter is safe. It identifies a narrower invariant: the action must not intentionally overlay one pip release on another while suppressing the uninstall path, because later package-management operations depend on coherent files and metadata.
