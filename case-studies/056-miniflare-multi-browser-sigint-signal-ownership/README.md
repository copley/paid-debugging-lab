# Case Study 056 — Miniflare multi-browser SIGINT signal ownership

## Problem

A local Cloudflare Browser Rendering worker can hold multiple Chrome sessions. When `SIGINT` is sent directly to the Wrangler Node process, Wrangler and the first Chrome tree exit, but later Chrome trees can survive and become orphaned processes.

Upstream issue: https://github.com/cloudflare/workers-sdk/issues/15419

## Source-level diagnosis

Browser Rendering launches Chrome through `@puppeteer/browsers` and explicitly disables `handleSIGTERM`, but does not disable `handleSIGINT`.

`@puppeteer/browsers` defaults `handleSIGINT` to `true`. Each launched `Process` registers a SIGINT handler. The library's shared SIGINT dispatcher iterates those per-process handlers, but each handler does two things immediately:

1. kills its own browser process tree;
2. calls `process.exit(130)`.

With one browser, that behavior looks correct. With multiple browsers, the first registered handler terminates the Wrangler process while the shared dispatcher is still iterating. Later browser handlers never run, and Miniflare's centralized cleanup does not get the opportunity to dispose the full tracked set.

This is therefore a signal-ownership bug rather than a generic Chrome teardown bug: both the child-process library and the application believe they own process termination.

## Likely fix boundary

The narrow fix is to make Wrangler/Miniflare the owner of SIGINT for Browser Rendering sessions by launching Chrome with `handleSIGINT: false` and allowing the existing application-level shutdown path to dispose every tracked browser.

`handleSIGTERM` is already disabled in this launch call, which is evidence that Browser Rendering intentionally delegates at least part of signal handling to the surrounding application. I would keep the first patch scoped to the reproduced SIGINT path rather than changing SIGHUP semantics without a corresponding failure.

## Regression strategy

A useful regression should exercise more than one browser process:

1. start a Browser Rendering worker;
2. create at least two browser sessions;
3. send SIGINT to the Wrangler/Miniflare Node process itself;
4. wait for shutdown;
5. assert that every launched Chrome root/process group is gone.

A unit test that only asserts `handleSIGINT: false` is useful but insufficient on its own, because the actual contract is end-to-end ownership and cleanup of multiple child process trees.

## Verification criteria

- Wrangler still exits with the expected SIGINT status/behavior.
- Every Chrome process tree created by the worker is terminated.
- No Chrome process is reparented to PID 1 after shutdown.
- Normal Miniflare disposal still closes browser sessions.
- The change does not alter browser launch/retry behavior during normal operation.

## Prevention rule

When an application owns lifecycle management for a collection of child processes, dependency-level signal handlers should not independently terminate the parent process. Signal ownership should exist at one orchestration layer, and tests should cover multiple children because single-child teardown can hide premature-parent-exit bugs.
