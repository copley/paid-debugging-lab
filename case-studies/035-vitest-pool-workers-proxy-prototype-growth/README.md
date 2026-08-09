# Case Study 035 — Vitest Pool Workers proxy prototype growth

## Problem

`@cloudflare/vitest-pool-workers` dynamically wraps Worker and Durable Object entrypoint classes so unknown prototype properties can be forwarded through RPC.

The wrapper currently installs a new `Proxy` around `Class.prototype` from inside the generated constructor. Because the prototype belongs to the class rather than to an individual instance, every construction wraps the previous proxy. Repeated Durable Object construction therefore creates an increasingly deep proxy chain, making property access progressively slower and eventually producing `RangeError: Maximum call stack size exceeded`.

Upstream issue: `cloudflare/workers-sdk#15092`.

## Source-level diagnosis

The important implementation detail is not just that the proxy assignment is inside the constructor. The source explains *why* it was put there:

```ts
function Class(...args) {
  // Delay proxying prototype until construction, so workerd sees this as a
  // regular class when introspecting it.
  Class.prototype = new Proxy(Class.prototype, { ... });
  return Reflect.construct(superClass, args, Class);
}
```

That comment changes the safe fix boundary.

A tempting patch is to hoist `Class.prototype = new Proxy(...)` into the factory so it executes once. That would stop the proxy growth, but it also removes the deliberate pre-construction window in which workerd must see an ordinary class prototype during entrypoint introspection.

The safer invariant is:

> The prototype must remain unproxied until the first construction, then be proxied exactly once for the lifetime of the generated wrapper class.

## Likely fix

Use a one-time installation guard while keeping the installation inside the constructor:

```ts
let prototypeProxied = false;

function Class(...args) {
  if (!prototypeProxied) {
    Class.prototype = new Proxy(Class.prototype, {
      get(target, key, receiver) {
        // existing trap body unchanged
      },
    });
    prototypeProxied = true;
  }

  return Reflect.construct(superClass, args, Class);
}
```

This preserves the workerd introspection requirement while preventing the second and later constructions from adding proxy layers.

An alternative is to retain the original unproxied prototype and cached proxy in closure state, but the same lifecycle rule must hold: no proxy before workerd's initial introspection, exactly one proxy afterwards.

## Regression coverage

A focused upstream regression should verify all of these properties:

1. `Class.prototype` changes at most once across many constructions.
2. Constructing the same wrapper thousands of times does not throw `RangeError`.
3. A normal prototype method continues to resolve correctly after repeated construction.
4. An unknown string key still invokes `getUnknownPrototypeKey()`.
5. ignored keys and symbol keys retain the existing behavior.
6. Worker/Durable Object entrypoint introspection still succeeds, proving the proxy was not installed too early.

The test should avoid wall-clock performance assertions. Stable prototype identity after the first construction is a deterministic proxy for the performance bug and will fail immediately if per-construction wrapping returns.

## PR scope

Expected files:

```text
packages/vitest-pool-workers/src/worker/entrypoints.ts
packages/vitest-pool-workers/... relevant wrapper tests
```

The implementation should be a very small lifecycle change. No public API, serialization format, or user configuration needs to change.

## Diagnostic comment draft

The reported proxy-chain diagnosis is reproducible in the current source, but I would not hoist the proxy assignment all the way into the factory. `entrypoints.ts` explicitly delays proxying until construction because workerd's entrypoint introspection expects a regular class prototype before that point.

The narrower fix is to keep the proxy installation in the constructor but guard it so it runs exactly once per generated wrapper class. That preserves the pre-construction introspection window and prevents every later Durable Object construction from wrapping the same shared prototype again.

For regression coverage, I would assert that `Class.prototype` changes only on the first construction, remains identical across thousands of subsequent constructions, and still forwards an unknown RPC key correctly. That is more deterministic than a timing benchmark and also protects the workerd-introspection constraint that motivated the original placement.
