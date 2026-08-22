# Case Study 048 — Wrangler `secret-tool` presence probe false-negative

## Upstream issue

- Cloudflare Workers SDK: https://github.com/cloudflare/workers-sdk/issues/15306

## Symptom

On Linux, `wrangler login --use-keyring` can report that `secret-tool` is not installed even when the executable is present on `PATH`.

A normal libsecret `secret-tool` binary does not necessarily implement `--version`. It prints usage and exits non-zero, so a probe that equates `secret-tool --version` exit code 0 with “installed” produces a false negative.

## Source diagnosis

`packages/workers-auth/src/credential-store/key-providers/linux-secret-tool.ts` currently runs:

```ts
const r = runner(["--version"]);
result = r.status === 0;
```

The probe is trying to answer an existence question, but it uses command-success semantics for an option that the target program does not guarantee to support.

The existing test suite encodes the same incorrect contract: a non-zero `--version` exit is treated as absence.

There is a second detail worth pinning in the regression: the production runner delegates to Node's synchronous child-process API. A missing executable is represented by a spawn error on the returned result (typically `ENOENT`, with no normal exit status), so the production check should inspect the spawn error state rather than rely only on a thrown exception from the test seam.

## Root cause

The probe conflates two different states:

1. the executable cannot be launched (for example a returned `ENOENT` spawn error), and
2. the executable launches successfully but rejects the supplied option.

Only the first proves that `secret-tool` is unavailable.

## Likely fix

Make the presence check depend on process-launch success rather than `--version` success.

A robust implementation should treat a child process that was successfully spawned as evidence that the executable exists, even when its exit code is non-zero. A spawn error such as `ENOENT` remains the negative case.

The narrow patch surface is:

- `linux-secret-tool.ts`: change `probeSecretTool()` to distinguish a spawn error from a normal non-zero exit;
- `linux-secret-tool.test.ts`: replace the “non-zero means missing” assertion with coverage for a real `secret-tool`-style usage exit (for example status 2) returning `true`;
- add a returned `ENOENT`/spawn-error result test returning `false` (the existing throwing-runner test may remain as defensive coverage);
- retain memoization behavior.

## Verification

Useful regression matrix:

| Probe result | Meaning | Expected |
| --- | --- | --- |
| returned spawn error `ENOENT`, status `null` | executable absent | `false` |
| runner throws | defensive failure case | `false` |
| process exits `0` | executable present | `true` |
| process exits `2` with usage text | executable present, option unsupported | `true` |
| repeated probe | memoized | one spawn only |

Then run the focused `workers-auth` credential-store tests and, on Linux with libsecret installed, confirm `wrangler login --use-keyring` proceeds past the availability check.

## Engineering lesson

Capability probes should test the capability actually required. If the requirement is “can I launch this executable?”, probing an optional CLI flag and requiring success creates version- and implementation-specific false negatives.
