# Case Study 050: setup-python reuses OS-incompatible tool-cache installations

## Target

- Repository: `actions/setup-python`
- Issue: `#1087` — self-hosted runners can reuse a Python build installed by a different Linux distribution

## Symptom

A self-hosted runner executes jobs inside different Linux containers, for example Ubuntu 20.04 and Ubuntu 24.04. `setup-python` downloads a distribution-specific Python archive, but subsequent jobs can reuse the existing tool-cache entry from the other distribution.

The reused interpreter can fail immediately with ABI/runtime errors such as incompatible GLIBC or OpenSSL versions.

## Source-level diagnosis

Current `find-python.ts` looks in the local tool cache before consulting the manifest:

```ts
let installDir: string | null = tc.find(
  'Python',
  semanticVersionSpec,
  architecture
);
```

That lookup identity contains Python version and architecture, but no Linux distribution/version.

By contrast, `install-python.ts` selects the downloadable artifact from the manifest using platform information. The download can therefore be OS-specific while the installed cache namespace is not.

This creates an identity mismatch:

1. artifact selection understands the current operating system;
2. the resulting interpreter is stored under the generic tool-cache identity;
3. another container on the same self-hosted runner asks for the same Python version/architecture;
4. `tc.find()` returns the existing interpreter before OS compatibility is checked.

Hosted runners hide the problem because their tool cache is normally tied to one runner image. Long-lived self-hosted runners that switch container distributions expose it.

## Root cause

The cache key is weaker than the artifact compatibility key.

A native runtime compiled for a particular libc/OpenSSL environment must not be treated as interchangeable merely because its language version and CPU architecture match.

## Focused fix direction

Do not blindly reuse a cached CPython installation until its installation metadata is proven compatible with the current platform release.

A durable design would record the selected manifest artifact identity alongside the installed tool and validate it before accepting `tc.find()` as a hit. That identity should include at least the platform and platform-version dimensions used to select the release.

If changing the global `@actions/tool-cache` directory layout is too disruptive, setup-python can keep the existing physical directory convention while adding compatibility metadata and treating a mismatched cache entry as a miss.

## Regression matrix

1. Install a Python release selected for Ubuntu 20.04 into a shared self-hosted tool cache.
2. Run setup-python from Ubuntu 24.04 against the same cache and same Python version/architecture.
3. Assert the 20.04 installation is rejected rather than reused.
4. Assert a compatible second 20.04 job still receives a cache hit.
5. Repeat in the reverse order (24.04 then 20.04).
6. Verify current hosted-runner behavior and Windows/macOS cache lookup remain unchanged.

## Why this is high signal

The bug looks like a Python/OpenSSL or GLIBC installation failure, but the actual defect is cache identity. The selected binary is correct at install time; the wrong binary is introduced later by an over-broad cache hit.

## General lesson

For native artifacts, cache identity must be at least as specific as artifact selection identity. If the download resolver distinguishes OS variants but the cache does not, an apparently successful cache hit can be more dangerous than a cache miss.
