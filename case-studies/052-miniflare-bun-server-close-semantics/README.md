# Case Study 052 — Miniflare inspector shutdown crosses incompatible Node/Bun server-close semantics

## Problem

A Miniflare inspector proxy can fail during teardown under Bun with:

```text
Error: Server is not running.
```

The failure occurs after `InspectorProxyController.dispose()` force-closes active connections and then attempts to close the HTTP server listener.

## Root cause

The shutdown sequence assumes Node's `http.Server` semantics:

```ts
server.closeAllConnections();
server.close(...);
```

Under Node, `closeAllConnections()` destroys active HTTP connections but does not itself stop the listening socket, so the subsequent `close()` is still required.

Bun currently behaves differently: `closeAllConnections()` can transition the server out of the listening state immediately. The following `server.close()` then reports that the server is not running.

The controller also has two subtly different implementations of the same lifecycle operation. The restart path uses a private `#closeServer()` helper that logs a `close()` error and resolves, while `dispose()` duplicates the sequence and rejects on the same error. The duplicated teardown logic therefore has different error semantics depending on which lifecycle path calls it.

## Why this matters

This is a runtime-compatibility bug at a resource-lifecycle boundary, not an application failure. A successful build or development session can be reported as failed solely because cleanup is not idempotent across compatible JavaScript runtimes.

Simply removing `server.close()` is not a portable fix: that would leave the listener open under Node.

## Fix direction

Centralize listener shutdown in one idempotent helper and use it from both restart and final disposal.

A safe sequence is:

1. force-close active connections;
2. if the server is already no longer listening, consider listener shutdown complete;
3. otherwise call `server.close()`;
4. tolerate only the specific already-closed / not-running condition;
5. preserve unrelated close errors rather than swallowing every teardown failure.

This retains Node's required explicit listener close while accepting Bun's stronger `closeAllConnections()` behavior.

## Regression tests

Cover both runtime contracts at the helper boundary:

1. **Node-style:** `closeAllConnections()` leaves `listening === true`; `close()` is invoked and disposal succeeds.
2. **Bun-style:** `closeAllConnections()` transitions to `listening === false`; disposal succeeds without treating an already-stopped listener as fatal.
3. **Real close failure:** an unrelated `close()` error is still surfaced.
4. **Restart:** closing followed by `listen()` still re-establishes the inspector server.
5. **Repeated disposal:** teardown remains safe if the shutdown path is reached more than once.

## Verification

Run the Miniflare inspector-proxy tests under Node and the Bun reproduction from the upstream report. Both should complete teardown without `Server is not running`, while the Node test should prove the listening socket is actually closed.

## Prevention

When a library targets multiple JavaScript runtimes, treat cleanup methods as semantic contracts rather than assuming every runtime implements Node's state transitions identically. Centralize resource ownership and make terminal cleanup idempotent.

## Upstream reference

- cloudflare/workers-sdk#15366
