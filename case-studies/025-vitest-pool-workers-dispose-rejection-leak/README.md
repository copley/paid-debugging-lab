# Case Study 025 — Swallowed Miniflare disposal rejection leaves `workerd` alive

## Incident

`@cloudflare/vitest-pool-workers@0.18.8` can finish two parallel test files successfully but never return control to the shell. The remaining active handle is a `workerd` child process. Running either file alone, or disabling Vitest file parallelism, avoids the hang.

Upstream issue: `cloudflare/workers-sdk#14903`.

## High-signal clues

- The regression is reported specifically against `0.18.8`.
- The `0.18.8` changelog contains a teardown-policy change: Miniflare and remote-proxy disposal rejections are logged rather than propagated.
- Each `CloudflarePoolWorker` constructs its own Miniflare runtime.
- `CloudflarePoolWorker.stop()` closes the WebSocket, awaits `mf.dispose().catch(...)`, clears the instance reference, and reports the pool worker stopped.
- The failure appears only with parallel files, where multiple runtimes are disposed concurrently.

## Root-cause boundary

The change that protects a green test run from a teardown exception also removes the guarantee that runtime teardown actually completed.

Conceptually, the current path is:

```text
tests pass
  -> close runner socket
  -> Miniflare.dispose() rejects
  -> catch and debug-log only
  -> discard Miniflare reference
  -> stop() resolves
  -> live workerd child keeps Node's event loop open
```

A rejected disposal is not equivalent to a completed disposal. Swallowing the error is safe only when the child process is already confirmed dead. Under parallel teardown, a rejection can leave an active runtime without any owner capable of performing a second cleanup attempt.

## Safe fix direction

Preserve the intent of `0.18.8`—a known post-test teardown fault should not automatically turn passing tests red—but make cleanup outcome explicit:

1. Keep the Miniflare reference until runtime termination is confirmed.
2. On `dispose()` rejection, determine whether the underlying `workerd` process has already exited.
3. If it is still alive, perform a bounded forced shutdown or use a Miniflare termination primitive.
4. Resolve `stop()` only after the runtime is gone; otherwise surface a structured teardown error rather than hanging indefinitely.
5. Put pool-worker accounting and ancillary session cleanup in `finally` so failure paths cannot leak global state.

Simply rethrowing the original disposal error would restore the pre-0.18.8 false-negative test result and would not address the lifecycle invariant.

## Regression tests

A focused integration test should:

- run two Cloudflare test files with Vitest file parallelism enabled;
- force one Miniflare disposal path to reject while its runtime remains alive;
- assert all test assertions pass;
- assert the Vitest process exits within a bounded time;
- assert no `workerd` child remains;
- retain a control where disposal rejects after the child has already exited and the successful test result is preserved.

A lower-level unit test should also verify that `CloudflarePoolWorker.stop()` does not clear `this.mf` or resolve before the fallback termination path completes.

## Draft upstream diagnostic comment

> The version boundary points directly at the `0.18.8` disposal-policy change. `CloudflarePoolWorker.stop()` now catches `Miniflare.dispose()` rejection, logs it under `NODE_DEBUG`, clears the Miniflare reference, and resolves. That prevents a teardown rejection from overriding passing tests, but it also treats “dispose rejected” as “runtime terminated.”
>
> With parallel files, two Miniflare instances are torn down concurrently. If one disposal rejects while its `workerd` child is still alive, the pool loses its last reference to that runtime and Vitest remains open on the child-process handle.
>
> I would preserve the green-test policy but add a lifecycle fallback: after a disposal rejection, confirm whether the child exited; if not, perform a bounded forced termination before `stop()` resolves. A regression should run two files in parallel, induce a disposal rejection with a live child, and assert both a zero test exit status and no remaining `workerd` process.

## Status

Source-level diagnosis and PR plan only. No public comment or upstream patch was posted without first executing the parallel teardown regression against the repository test suite.
