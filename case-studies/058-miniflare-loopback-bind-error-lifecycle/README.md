# Case Study 058 — Miniflare loopback bind error lifecycle

## Incident summary

A Miniflare instance can become permanently half-started when its internal loopback HTTP server cannot bind to the requested host. The underlying Node/Bun server emits a bind error, but Miniflare does not connect that error event to the promise representing startup. The result is worse than a normal startup failure: the process can see an uncaught exception while both `ready` and subsequent disposal remain pending.

- Upstream: `cloudflare/workers-sdk#15466`
- Area: TypeScript / Node.js service lifecycle, event-to-promise error propagation, deterministic shutdown
- Failure class: resource acquisition fails without transitioning the owning lifecycle state

## Failure contract

The reported deterministic reproduction constructs Miniflare with a deliberately unavailable local bind address (`192.0.2.1`). Instead of rejecting `Miniflare.ready`, current behaviour is:

```text
UNCAUGHT EADDRNOTAVAIL ...
READY pending
DISPOSE pending
```

Bun exposes a different bind code/message in the same reproduction, but the lifecycle failure is identical. This is important: the contract should be based on “listener failed to bind”, not on one runtime-specific error code.

## Source-level diagnosis

`packages/miniflare/src/index.ts` currently implements `#startLoopbackServer()` with a promise that has only a resolve path:

```ts
return new Promise((resolve) => {
  const server = stoppable(http.createServer(this.#handleLoopback), 0);
  server.keepAliveTimeout = 0;
  server.on("upgrade", this.#handleLoopbackUpgrade);
  server.listen(0, hostname, () => resolve(server));
});
```

A Node `http.Server` reports asynchronous bind failures through its `error` event. Because this server has no startup error listener, the event is not translated into rejection of the lifecycle promise. Code awaiting the loopback server therefore never receives either success or failure.

The same subsystem already contains the correct general pattern elsewhere: listener startup should install a one-shot error handler that rejects the startup promise.

## Correct ownership boundary

The loopback server is a resource acquired as part of Miniflare startup. Its bind error therefore belongs to the startup promise.

The invariant should be:

> Every asynchronous resource acquisition started by Miniflare must settle its owning startup operation exactly once: resolve after successful acquisition, or reject with the original acquisition error.

A console-level uncaught-exception handler is not the fix. Neither is matching `EADDRNOTAVAIL` specifically. The lifecycle owner must receive the server error.

## Small PR path

A focused fix should change `#startLoopbackServer()` to accept both resolve and reject, register the error handler before calling `listen()`, and remove the startup-only error handler after the listening callback wins.

Conceptually:

```ts
return new Promise((resolve, reject) => {
  const server = stoppable(http.createServer(this.#handleLoopback), 0);

  const onError = (error: Error) => reject(error);
  server.once("error", onError);

  server.listen(0, hostname, () => {
    server.off("error", onError);
    resolve(server);
  });
});
```

Removing the startup-only handler after success matters. Leaving a `once("error", reject)` listener attached to an already-resolved promise would consume the first later runtime server error even though rejection can no longer affect that promise.

The implementation should then verify that the existing Miniflare update/disposal path settles cleanly after startup rejection. If disposal still waits on the failed update, that is a second lifecycle edge that should be fixed in the same regression-backed change rather than hidden with a timeout.

## Regression matrix

Add the regression in the Miniflare lifecycle/index test area and use a deterministic unavailable bind address rather than racing for an occupied port.

1. `mf.ready` rejects promptly when the loopback server cannot bind.
2. The rejection preserves the original bind error instead of replacing it with a generic Miniflare error.
3. No `uncaughtException` is emitted for the handled startup failure.
4. `mf.dispose()` settles after the failed startup.
5. A normal bind still resolves `ready` and remains usable.
6. The test must not assert one runtime-specific error code; Node and Bun can describe the same bind failure differently.

## Diagnostic comment draft

> I checked current `#startLoopbackServer()` and the unresolved lifecycle comes from the event/promise boundary. The method creates a promise with only a resolve path, then calls `server.listen(...)`; asynchronous bind failures arrive through the server's `error` event, so they never settle the startup promise.
>
> I would register a one-shot startup error handler before `listen()` and reject with that original error. One small detail is worth preserving in the patch: remove that startup-only error listener once the listening callback succeeds. Otherwise a later runtime server error can be consumed by a stale listener whose `reject()` no longer changes the already-settled promise.
>
> The regression should use the deterministic unavailable-address reproduction, assert `ready` rejects without an uncaught exception, and then assert `dispose()` also settles. I would avoid keying the test to `EADDRNOTAVAIL`, because Bun reports the same bind failure differently; the lifecycle contract is that acquisition failure rejects startup.

## Enterprise Java analogue

This is the same defect class seen in Spring Boot and JVM services when asynchronous resource acquisition is detached from application lifecycle state: an HTTP listener, database pool, Kafka consumer, scheduled executor, or Netty channel fails during startup but the owning `CompletableFuture`, bean lifecycle, or shutdown coordinator never transitions.

A senior fix is not “catch the exception”. It identifies which lifecycle operation owns the resource, propagates failure through that boundary, makes cleanup deterministic after partial initialization, and regression-tests both successful and failed acquisition paths.

## Evidence status

This case study is a source-grounded investigation and proposed fix path. It is not presented as a merged upstream contribution. At the time of analysis, the upstream issue was open and no matching open PR was found.