# Case Study 055 — setup-python GraalPy four-part release version resolution

## Problem

`actions/setup-python@v7` can resolve `graalpy-25` to an older 25.x release even when a newer stable GraalPy release is available.

The concrete report is `actions/setup-python#1352`: GraalPy 25.3.4.1 was published, but `graalpy-25` selected 25.2.4.

## Evidence

The current resolver converts a GraalPy release tag to a version string and then filters releases with `semver.satisfies()`.

`graalPyTagToVersion()` explicitly accepts a fourth numeric component:

```text
25.3.4.1
```

but Node's `semver` model is three-component SemVer (`major.minor.patch`). The release filter therefore feeds a GraalPy four-component version into a SemVer comparison boundary that is not designed to represent it.

This is more likely to explain the fallback to 25.2.4 than the changed archive prefix. `findAsset()` already accepts any asset whose filename starts with `graalpy`, so both `graalpy-...` and `graalpy3.13-...` satisfy that prefix test.

The upstream 25.3.4.1 release uses assets such as:

```text
graalpy3.13-25.3.4.1-linux-amd64.tar.gz
```

## Root cause

The release-version domain and the comparison domain are mismatched.

GraalPy publishes a four-component release version, while setup-python feeds that value directly to the `semver` package for range matching. The code's parser permits four components, but the downstream matcher assumes standard SemVer.

## Likely fix

Normalize GraalPy release versions into a comparison representation before calling `semver.satisfies()` and `semver.compare()`, while preserving the original release version for download URLs, cache identity, logs, and outputs.

For example, the resolver can use a dedicated comparison helper that maps GraalPy's fourth numeric component into a SemVer-compatible ordering representation rather than silently dropping it.

The important invariant is:

```text
25.3.4.1 > 25.3.4 > 25.2.4
```

and a request for `25` must consider all of those releases eligible.

## Regression coverage

Add resolver tests containing at least:

- `graal-25.2.4`
- `graal-25.3.4`
- `graal-25.3.4.1`
- matching `graalpy3.13-...` assets

Then assert:

1. `graalpy-25` selects 25.3.4.1.
2. `graalpy-25.3` selects 25.3.4.1.
3. asset selection accepts the Python-version-prefixed archive name.
4. the exact original GraalPy release version is retained in the tool-cache/output identity.
5. prerelease ordering remains unchanged.

## Prevention

When an upstream project uses a version grammar that is not strict SemVer, do not pass that version directly into a generic SemVer library. Parse the upstream version once, define an explicit ordering representation, and test real release-name examples from the upstream distribution.
