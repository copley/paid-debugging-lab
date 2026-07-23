# Case Study 018: setup-python pip cache discovery depends on ambient PATH

## Target

- Repository: `actions/setup-python`
- Issue: `#1050` — self-hosted Windows runner fails when pip is not already in `PATH`

## Symptom

The action successfully resolves and installs the requested Python version, but enabling the built-in pip cache with `update-environment: false` fails inside setup-python:

```text
Command failed: pip cache dir
'pip' is not recognized as an internal or external command
```

The user workflow has not invoked pip. The failure occurs in the action's internal cache-discovery step.

## Source-level diagnosis

`src/cache-distributions/pip-cache.ts` discovers the cache directory through ambient command lookup:

```ts
if (IS_WINDOWS) {
  ({stdout, stderr} = await execPromisify('pip cache dir'));
} else {
  ({stdout, stderr, exitCode} = await exec.getExecOutput('pip cache dir'));
}
```

Meanwhile, `src/find-python.ts` already computes the absolute selected interpreter path and installs pip through that installation. When `update-environment` is false, it intentionally does not add the interpreter or scripts directory to `PATH`.

The two behaviors conflict:

1. setup-python knows exactly which interpreter it installed;
2. environment mutation is intentionally disabled;
3. cache discovery ignores the known interpreter and searches ambient `PATH` for pip.

Hosted runners can hide this defect because a separate global pip often already exists. A clean self-hosted runner exposes it.

## Root cause

An internal setup operation is incorrectly coupled to the user-facing environment mutation option.

`update-environment: false` should mean that later workflow steps do not receive PATH and related environment changes. It should not prevent setup-python itself from using the interpreter it selected.

## Focused fix path

Thread the selected Python executable path into the cache distributor and invoke pip as a module:

```text
<absolute-python> -m pip cache dir
```

Likely implementation boundaries:

```text
src/find-python.ts
- Return the selected executable/install path as part of InstalledVersion.

src/setup-python.ts
- Retain the selected interpreter path after setup.
- Pass it into cacheDependencies().

src/cache-distributions/cache-factory.ts
- Forward the interpreter path to PipCache.

src/cache-distributions/pip-cache.ts
- Replace shell and ambient-PATH lookup with @actions/exec using:
  pythonPath, ['-m', 'pip', 'cache', 'dir']
```

PyPy and GraalPy paths should follow the same contract so cache behavior is consistent across supported runtimes.

## Regression tests

1. `update-environment: false`, pip cache enabled, no global pip in PATH: cache discovery succeeds.
2. The command uses the absolute selected Python executable.
3. Windows execution does not require `child_process.exec` or shell lookup.
4. A pip failure still produces a clear cache-discovery error.
5. `update-environment: true` preserves existing behavior.
6. CPython, PyPy and GraalPy pass the correct selected interpreter into caching.

## Why this is a useful PR

- It fixes the action's own internal failure rather than weakening the user's environment policy.
- It removes platform-specific shell behavior from pip cache discovery.
- It makes self-hosted and hosted runners behave consistently.
- It keeps the patch scoped to interpreter-path plumbing and cache discovery.

## Draft diagnostic comment

I inspected the current cache path, and the failure is inside setup-python rather than in a user workflow command.

`PipCache.getCacheGlobalDirectories()` invokes `pip cache dir` through ambient `PATH`; on Windows it uses `child_process.exec`. At the same time, setup-python already computes the absolute executable for the selected interpreter. With `update-environment: false`, the action intentionally does not add that installation to `PATH`, so its own cache-discovery implementation loses access to the pip it just installed.

A focused fix is to pass the selected interpreter path into the cache distributor and execute `<absolute-python> -m pip cache dir`. That keeps `update-environment: false` meaningful for subsequent workflow steps while allowing the action to use its own selected tool internally.

The regression should remove global pip from PATH, enable pip caching with `update-environment: false`, and assert that cache discovery succeeds through the absolute interpreter path.
