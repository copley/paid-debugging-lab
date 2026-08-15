# Case Study 041: Cloudflare Vite plugin drops `access.dev` before Miniflare

## Problem

Cloudflare's new `access.dev` configuration can simulate `ctx.access` correctly under `wrangler dev`, but the same Wrangler configuration produces `ctx.access === undefined` when the application is started through `@cloudflare/vite-plugin`.

Upstream issue: `cloudflare/workers-sdk#15204`.

The upstream automated reproduction confirmed the split behavior: the Wrangler dev path returns the configured audience and identity, while the Vite dev path returns no Access context.

## Root cause

Miniflare itself already implements the feature. The change that introduced local Access simulation added an `access` worker option and wires that option into the Access blob and identity service used by `ctx.access`.

The loss happens in Wrangler's platform-integration helper used by the Vite plugin.

`packages/vite-plugin-cloudflare/src/miniflare-options.ts` passes each normalized Wrangler config into:

```ts
wrangler.unstable_getMiniflareWorkerOptions(...)
```

That helper lives in:

```text
packages/wrangler/src/api/integrations/platform/index.ts
```

It builds `SourcelessWorkerOptions` from the normalized config, including compatibility settings, module rules, zone, bindings, sites and asset options. It does not currently copy the new top-level Wrangler `access.dev` value into Miniflare's V4 `access` worker option.

The result is a clean configuration-plumbing gap:

```text
wrangler.jsonc
  access.dev
      |
      v
normalized Wrangler Config
      |
      v
unstable_getMiniflareWorkerOptions()
      |
      X  access.dev omitted
      |
      v
Vite Miniflare worker options
      |
      v
ctx.access === undefined
```

`wrangler dev` works because its own Miniflare path already forwards the Access development configuration.

## Small PR candidate

The contained fix should be in the Wrangler integration helper, not a Vite-only special case. The worker options should forward the normalized local Access configuration into the Miniflare worker option, conceptually:

```ts
const workerOptions: SourcelessWorkerOptions = {
  rootPath: projectRoot,
  compatibilityDate: config.compatibility_date,
  compatibilityFlags: config.compatibility_flags,
  modulesRules,
  zone: getZoneFromConfig(config),
  access: config.access?.dev,
  ...bindingOptions,
  ...sitesOptions,
  ...assetOptions,
};
```

The exact property should follow the normalized `Config` type rather than re-parsing raw configuration in the Vite plugin.

The parallel `getPlatformProxy()` option builder should also be reviewed for the same omission so that Wrangler's public integration APIs do not diverge from each other.

## Regression coverage

1. Add a unit test for `unstable_getMiniflareWorkerOptions()` with `access.dev` configured.
2. Assert the returned Miniflare worker options contain the expected `access.aud` and mock identity.
3. Add a Vite-plugin integration test whose Worker reads `ctx.access`.
4. Start it through the Vite path and assert the configured audience and identity are returned.
5. Keep a control with no `access.dev` to ensure `ctx.access` is not synthesized unexpectedly.
6. If `getPlatformProxy()` is intended to expose the same local runtime semantics, add equivalent coverage there.

## Debugging lesson

When a feature works in one frontend but not another while both share the same runtime, trace the configuration at every adapter boundary before changing runtime behavior. Here, Miniflare already knows how to provide `ctx.access`; one integration adapter simply fails to carry the newly added option across its translation layer.

That is a lower-risk fix than duplicating feature-specific logic in the Vite plugin.

## Suggested upstream diagnostic comment

I traced the missing wiring one layer below the Vite plugin. `getDevMiniflareOptions()` already passes the normalized Wrangler config into `wrangler.unstable_getMiniflareWorkerOptions()`, but that helper builds `SourcelessWorkerOptions` without copying `config.access?.dev` into Miniflare's V4 `access` worker option. Miniflare already implements the `ctx.access` simulation once that option is present, so the Vite path is dropping the data before Miniflare sees it.

I would fix this in the shared Wrangler integration helper rather than adding Vite-specific Access plumbing: forward the normalized `access.dev` value into `workerOptions.access`, add a direct helper regression, then exercise the Vite path with a Worker that reads `ctx.access`. I would also check the parallel `getPlatformProxy()` option builder for the same omission so the integration APIs stay consistent.